package com.hklab.airuler.grid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.hklab.airuler.film.ruler.PyMath
import com.hklab.airuler.GlobalParams
import com.hklab.airuler.model.ModelFileStore
import com.hklab.airuler.yolo.YoloDetector
import com.hklab.airuler.grid.GridWarpCache
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.android.Utils
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * Grid 분석(파이썬 main_ruler_selected_2points_GUI_v15의 "Grid 검출" 버튼 동작과 동일 로직 지향)
 *
 * Flow:
 * 1) Samsung captured 이미지(full-res) 로드(BGR) + landscape 정규화
 * 2) Grid.tflite(YOLO) 로 Grid ROI 검출
 * 3) ROI crop 후 GridDetector(OpenCV)로 교차점 grid 추출
 * 4) internal/models/Grid.json 저장
 */
object GridAnalysisProcessor {

    // ✅ (성능/안정성) OpenCV 상수 reflection(필드 조회)은 1회만 수행
    // - Grid 분석은 50/200MP 캡처 이미지를 다루므로, 불필요한 reflection/메모리 사용을 피합니다.
    private val IMREAD_IGNORE_ORIENTATION_FLAG: Int by lazy {
        runCatching {
            Imgcodecs::class.java.getField("IMREAD_IGNORE_ORIENTATION").getInt(null)
        }.getOrDefault(0)
    }

    data class Result(
        val overlay: Mat, // ROI overlay (python과 동일하게 ROI 이미지에 오버레이)
        val rows: Int,
        val cols: Int,
        val roiX1: Int,
        val roiY1: Int,
        val roiX2: Int,
        val roiY2: Int,
        val savedJsonFile: File,
        val cellTypeCounts: Map<String, Int>,
        val neighborStats: GridDetector.NeighborStats?,
        val logs: List<String>
    )

    @Throws(Exception::class)
    fun run(
        context: Context,
        imageUri: Uri,
        scoreThresh: Float = GlobalParams.SCORE_THRESH,
        iouThresh: Float = GlobalParams.IOU_THRESH,
        gridPitchMm: Double = 5.0,
        yoloMaxSide: Int = 1920,
        // ✅ Python(HkRuler)에서는 pad_ratio=0.00을 기본으로 사용합니다.
        //    ROI padding을 주면(예: 0.02) raw grid 크기가 +2씩 늘어나며
        //    통계(hist/std)도 파이썬과 달라지므로 기본값은 0.0으로 둡니다.
        roiPaddingRatio: Double = 0.0
    ): Result {
        val logs = ArrayList<String>()

        val gridModel = ModelFileStore.downloadedModelFile(context, "Grid")
        if (!gridModel.exists()) {
            throw IllegalStateException(
                "Grid.tflite가 internal/models에 없습니다. " +
                    "Model 선택 화면에서 Update Grid(길게 클릭)로 다운로드하세요."
            )
        }

        // 1) Full-res BGR 로드 + landscape 정규화
        //    ✅ 2026-01-08 정책 변경(사용자 요청): EXIF orientation 기반 회전은 사용하지 않고,
        //       "세로(h>w)면 항상 90° CCW"만 적용합니다.
        val fullBgr = loadBgrMatFromUri(context, imageUri, forceLandscape = true)
        val H = fullBgr.rows()
        val W = fullBgr.cols()

        // 2) Grid.tflite(YOLO) ROI 검출
        //    - 파이썬은 (cv2.imread로 로드한 원본) -> cv2.resize(input_w,input_h) 로 바로 전처리합니다.
        //    - Android도 OpenCV(Mat) 기준으로 동일한 입력을 만들기 위해,
        //      모델 입력 크기(WH)로 **직접 리사이즈한 Bitmap**을 만들어 넣습니다.
        val detector = YoloDetector(context, gridModel.absolutePath, classLabels = arrayOf("film", "good", "bad"))
        val (inW, inH) = detector.inputSizeWh()

        val yoloBmp = createYoloInputBitmapFromBgr(fullBgr, targetW = inW, targetH = inH)
            ?: createYoloBitmapFromBgr(fullBgr, maxSide = yoloMaxSide)
            ?: decodeOrientedBitmap(context = context, uri = imageUri, maxSide = yoloMaxSide, forceLandscape = true)
            ?: run {
                detector.close()
                fullBgr.release()
                throw IllegalStateException("이미지 디코딩 실패")
            }
        // YoloDetector.detect() 시그니처에 맞춰 named argument 사용
        // (scoreThresh / iouThresh)
        val dets = try {
            detector.detect(
                bitmap = yoloBmp,
                scoreThresh = scoreThresh,
                iouThresh = iouThresh
            )
        } catch (e: Exception) {
            fullBgr.release()
            throw e
        } finally {
            detector.close()
            yoloBmp.recycle()
        }

        if (dets.isEmpty()) {
            fullBgr.release()
            throw IllegalStateException("Grid ROI 검출 실패 (0 detections)")
        }

        // 3) ROI 선택 로직: Python(HkRuler)와 동일하게 "best(area) vs union" 규칙 적용
        data class PxBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val score: Float) {
            val area: Long get() = (x2 - x1).toLong() * (y2 - y1).toLong()
        }

        val pxBoxes = dets.mapNotNull { d ->
            var x1 = PyMath.roundHalfEvenInt(d.rect.left.toDouble() * W.toDouble())
            var y1 = PyMath.roundHalfEvenInt(d.rect.top.toDouble() * H.toDouble())
            var x2 = PyMath.roundHalfEvenInt(d.rect.right.toDouble() * W.toDouble())
            var y2 = PyMath.roundHalfEvenInt(d.rect.bottom.toDouble() * H.toDouble())

            // Python: x1/y1는 [0, W-1/H-1], x2/y2는 [0, W/H] 쪽으로 클램프(슬라이스용)
            x1 = x1.coerceIn(0, W - 1)
            y1 = y1.coerceIn(0, H - 1)
            x2 = x2.coerceIn(0, W)
            y2 = y2.coerceIn(0, H)
            if (x2 <= x1 || y2 <= y1) return@mapNotNull null
            PxBox(x1, y1, x2, y2, d.score)
        }

        if (pxBoxes.isEmpty()) {
            fullBgr.release()
            throw IllegalStateException("Grid ROI 검출 실패 (유효 ROI 0개)")
        }

        val bestByArea = pxBoxes.maxBy { it.area }
        val union = run {
            val ux1 = pxBoxes.minOf { it.x1 }
            val uy1 = pxBoxes.minOf { it.y1 }
            val ux2 = pxBoxes.maxOf { it.x2 }
            val uy2 = pxBoxes.maxOf { it.y2 }
            PxBox(ux1, uy1, ux2, uy2, bestByArea.score)
        }
        val useUnion = (union.area.toDouble() / bestByArea.area.toDouble()) <= 1.8
        val chosen0 = if (useUnion) union else bestByArea

        logs.add("Grid ROI detection: dets=${dets.size}, bestScore=${String.format("%.6f", bestByArea.score)}, chosen=${if (useUnion) "union" else "best"}")
        logs.add("Grid ROI px(raw): (${chosen0.x1},${chosen0.y1})-(${chosen0.x2},${chosen0.y2}) / full=($W,$H)")

        // 4) padding (기본 0.0) - Python에서는 기본적으로 padding을 주지 않음
        var x1 = chosen0.x1
        var y1 = chosen0.y1
        var x2 = chosen0.x2
        var y2 = chosen0.y2
        if (roiPaddingRatio > 0.0) {
            val padX = ((x2 - x1) * roiPaddingRatio).toInt()
            val padY = ((y2 - y1) * roiPaddingRatio).toInt()
            x1 = max(0, x1 - padX)
            y1 = max(0, y1 - padY)
            x2 = min(W, x2 + padX)
            y2 = min(H, y2 + padY)
        }
        logs.add("Grid ROI px(final): ($x1,$y1)-($x2,$y2)")

        // 4) ROI crop → GridDetector
        val roiRect = Rect(x1, y1, x2 - x1, y2 - y1)
        val roiBgr = fullBgr.submat(roiRect).clone()
        fullBgr.release()

        val gridDetector = GridDetector()
        val gridRes = gridDetector.run(roiBgr, drawOverlay = true)
        roiBgr.release()

        logs.addAll(gridRes.logs)

        val rows = gridRes.pointGrid.size
        val cols = if (rows > 0) gridRes.pointGrid[0].size else 0
        if (rows == 0 || cols == 0) {
            gridRes.overlay.release()
            throw IllegalStateException("Grid 교차점 검출 실패 (valid grid = 0x0)")
        }



        // ✅ 추가 실패 조건(사용자 요청):
        // - CENTERED 비율 > 3% 또는 neighbor std > 0.9 이면 Grid 분석 실패로 처리
        //   (기존 실패 조건은 그대로 유지)
        val totalCells = gridRes.cellTypeCounts.values.sum().coerceAtLeast(0)
        val centeredCount = gridRes.cellTypeCounts["CENTERED"] ?: 0
        val centeredRatio = if (totalCells > 0) centeredCount.toDouble() / totalCells.toDouble() else 0.0
        val std = gridRes.neighborStats?.std ?: Double.NaN

        val qcFails = ArrayList<String>(2)
        if (centeredRatio > 0.03) {
            val pct = centeredRatio * 100.0
            qcFails.add(
                "C${centeredCount}/T${totalCells}=${String.format(Locale.US, "%.2f", pct)}%>3%"
            )
        }
        if (!std.isNaN() && std > 0.9) {
            qcFails.add("std${String.format(Locale.US, "%.2f", std)}>0.90")
        }
        if (qcFails.isNotEmpty()) {
            runCatching { gridRes.overlay.release() }
            throw IllegalStateException("QC fail: ${qcFails.joinToString(" " )}")
        }
        // 5) Grid.json 저장 (Python GUI의 on_save_grid_points 포맷과 최대한 동일)
        val jsonFile = File(ModelFileStore.modelsDir(context), "Grid.json")
        val root = JSONObject()
        // ✅ (요구사항) 테이블(JSON 컬럼) 표시를 위해 updated 필드 + 사람이 읽기 쉬운 포맷 사용
        //   예: "2026-03-04 07:44:29"
        root.put("updated", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        root.put("image_path", imageUri.toString())
        root.put("rows", rows)
        root.put("cols", cols)
        root.put("pitch_mm", gridPitchMm)
        val roiObj = JSONObject()
        roiObj.put("x1", x1)
        roiObj.put("y1", y1)
        roiObj.put("x2", x2)
        roiObj.put("y2", y2)
        root.put("roi", roiObj)

        // points: 2D 배열 (rows x cols)
        val pts2d = JSONArray()
        for (r in 0 until rows) {
            val rowArr = JSONArray()
            for (c in 0 until cols) {
                val p = gridRes.pointGrid[r][c]
                val obj = JSONObject()
                obj.put("gx", p.gx + x1.toDouble())
                obj.put("gy", p.gy + y1.toDouble())
                obj.put("mx", c.toDouble() * gridPitchMm)
                obj.put("my", r.toDouble() * gridPitchMm)
                rowArr.put(obj)
            }
            pts2d.put(rowArr)
        }
        root.put("points", pts2d)
        jsonFile.writeText(root.toString(2))
        logs.add("Saved Grid.json: ${jsonFile.absolutePath}")

        // Grid.json이 갱신되었으니 warp 캐시를 비웁니다(즉시 반영)
        GridWarpCache.clear()

        return Result(
            overlay = gridRes.overlay,
            rows = rows,
            cols = cols,
            roiX1 = x1,
            roiY1 = y1,
            roiX2 = x2,
            roiY2 = y2,
            savedJsonFile = jsonFile,
            cellTypeCounts = gridRes.cellTypeCounts,
            neighborStats = gridRes.neighborStats,
            logs = logs
        )
    }

    // ---------------- Image IO helpers (FilmTotalMeasureProcessor와 동일한 정책을 복사) ----------------

    private fun loadBgrMatFromUri(context: Context, imageUri: Uri, forceLandscape: Boolean): Mat {
        // ✅ (성능/안정성) 대용량(50/200MP) 이미지를 byte[]로 통째로 읽으면 메모리 스파이크가 큽니다.
        //    FilmTotalMeasureProcessor와 동일하게 temp file로 스트리밍 복사 후 OpenCV imread를 사용합니다.
        // 원본 포맷이 JPEG가 아닐 수도 있어(mime type/uri 기준), 가능한 한 확장자를 맞춥니다.
        // (OpenCV 빌드/플러그인에 따라 확장자 기반 디코더 선택이 영향을 줄 수 있음)
        val suffix = runCatching {
            val mime = context.contentResolver.getType(imageUri)?.lowercase(Locale.US) ?: ""
            when (mime) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "image/bmp" -> ".bmp"
                "image/jpeg", "image/jpg" -> ".jpg"
                else -> {
                    val name = imageUri.lastPathSegment ?: ""
                    val dot = name.lastIndexOf('.')
                    if (dot >= 0 && dot < name.length - 1) name.substring(dot) else ".jpg"
                }
            }
        }.getOrDefault(".jpg")

        val tmp = File.createTempFile("airuler_grid_", suffix, context.cacheDir)

        context.contentResolver.openInputStream(imageUri)?.use { input ->
            FileOutputStream(tmp).use { out ->
                input.copyTo(out)
            }
        } ?: throw IllegalArgumentException("Cannot open Uri: $imageUri")

        try {
            // ✅ OpenCV imread가 EXIF 방향을 자동 적용하는 빌드가 있어, 가능하면 무시 플래그를 켭니다.
            val ignoreOriFlag = IMREAD_IGNORE_ORIENTATION_FLAG
            val flags = Imgcodecs.IMREAD_COLOR or ignoreOriFlag
            val bgr = Imgcodecs.imread(tmp.absolutePath, flags)
            if (bgr.empty()) throw IllegalStateException("이미지 로드 실패: $imageUri")

            // ✅ EXIF orientation은 무시하고, 픽셀 데이터 기준으로만 처리
            if (forceLandscape && bgr.rows() > bgr.cols()) {
                Core.rotate(bgr, bgr, Core.ROTATE_90_COUNTERCLOCKWISE)
            }
            return bgr
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun decodeOrientedBitmap(
        context: Context,
        uri: Uri,
        maxSide: Int,
        forceLandscape: Boolean
    ): Bitmap? {
        // bounds
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val ow = bounds.outWidth
        val oh = bounds.outHeight
        if (ow <= 0 || oh <= 0) return null

        var sample = 1
        val maxOrigSide = max(ow, oh)
        while (maxOrigSide / sample > maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp0 = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // ✅ EXIF orientation은 무시하고, 픽셀 데이터 기준으로만 처리
        var out = bmp0
        if (forceLandscape && out.height > out.width) {
            // 90° CCW (Python cv2.ROTATE_90_COUNTERCLOCKWISE와 동일)
            val m = Matrix()
            m.postRotate(-90f)
            val rotated = Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true)
            if (rotated !== out) out.recycle()
            out = rotated
        }
        return out
    }

    /**
     * OpenCV Mat(BGR) -> Bitmap(ARGB_8888)
     * - Grid ROI 검출 입력을 OpenCV 기준으로 통일해 Python 결과와의 차이를 최소화합니다.
     */
    private fun createYoloInputBitmapFromBgr(fullBgr: Mat, targetW: Int, targetH: Int): Bitmap? {
        if (fullBgr.empty()) return null
        if (targetW <= 0 || targetH <= 0) return null

        val resized = Mat()
        val rgba = Mat()
        return try {
            // Python: cv2.resize(..., interpolation=cv2.INTER_LINEAR)
            Imgproc.resize(
                fullBgr,
                resized,
                Size(targetW.toDouble(), targetH.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR
            )
            Imgproc.cvtColor(resized, rgba, Imgproc.COLOR_BGR2RGBA)
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            bmp
        } catch (e: Exception) {
            null
        } finally {
            resized.release()
            rgba.release()
        }
    }

    private fun createYoloBitmapFromBgr(fullBgr: Mat, maxSide: Int): Bitmap? {
        if (fullBgr.empty()) return null
        val H = fullBgr.rows()
        val W = fullBgr.cols()

        val maxLen = max(W, H)
        val scale = if (maxSide > 0 && maxLen > maxSide) maxSide.toDouble() / maxLen.toDouble() else 1.0
        val newW = max(1, PyMath.roundHalfEvenInt(W.toDouble() * scale))
        val newH = max(1, PyMath.roundHalfEvenInt(H.toDouble() * scale))

        val bgrSmall = if (scale < 0.999) {
            val tmp = Mat()
            // downscale는 INTER_AREA가 안정적(노이즈↓), 업스케일은 INTER_LINEAR
            val interp = if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
            Imgproc.resize(fullBgr, tmp, Size(newW.toDouble(), newH.toDouble()), 0.0, 0.0, interp)
            tmp
        } else {
            fullBgr
        }

        val rgba = Mat()
        return try {
            Imgproc.cvtColor(bgrSmall, rgba, Imgproc.COLOR_BGR2RGBA)
            val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            bmp
        } catch (e: Exception) {
            null
        } finally {
            rgba.release()
            if (bgrSmall !== fullBgr) {
                bgrSmall.release()
            }
        }
    }
}