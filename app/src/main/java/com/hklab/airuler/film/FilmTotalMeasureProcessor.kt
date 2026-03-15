package com.hklab.airuler.film

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.hklab.airuler.film.ruler.DistanceUtils
import com.hklab.airuler.film.ruler.PyMath
import com.hklab.airuler.film.ruler.RulerCalibration
import com.hklab.airuler.model.ModelFileStore
import com.hklab.airuler.yolo.AirulerYoloClasses
import com.hklab.airuler.yolo.YoloDetector
import com.hklab.airuler.yolo.YoloDetection
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import com.hklab.airuler.GlobalParams
import com.hklab.airuler.calibration.GridOnlineOffsetCalibrationStore
import com.hklab.airuler.pipeline.state.AppSessionSettings
import com.hklab.airuler.log.AirulerFileLogger
import com.hklab.airuler.model.ModelNameCompat

/**
 * Python(HkRuler) `on_run_film_total_measure_ruler()` 파이프라인을 AIRuler에 이식한 프로세서.
 *
 * - 입력: 삼성 카메라로 촬영한 DCIM/Camera 이미지(Uri)
 * - 처리: YOLO ROI 검출 -> union_bbox -> Top/Left ruler 검출 -> Film json(roi/measure) 기반 길이 측정
 * - 출력: overlay Mat + 로그
 */
object FilmTotalMeasureProcessor {

    private const val TAG = "FilmTotalMeasure"
    private const val DEBUG_LOG = false

    // ✅ (성능) OpenCV 상수 reflection(필드 조회)은 1회만 수행
    // - 대용량 이미지(50/200MP) 처리에서 loadBgrMatFromUri()가 반복 호출될 수 있어,
    //   매번 reflection을 하면 불필요한 오버헤드가 됩니다.
    private val IMREAD_IGNORE_ORIENTATION_FLAG: Int by lazy {
        runCatching {
            Imgcodecs::class.java.getField("IMREAD_IGNORE_ORIENTATION").getInt(null)
        }.getOrDefault(0)
    }

    // ===== HkRuler(film_total_ruler.py) 파라미터 =====
    // - tickLength / px 기반 값들은 GlobalParams(50MP base, 200MP=2배)에서 관리합니다.
    private const val RULER_TOP_START_RATIO = 0.25
    private const val RULER_TOP_END_RATIO = 0.75
    private const val RULER_LEFT_START_RATIO = 0.25
    private const val RULER_LEFT_END_RATIO = 0.75

    // NEW(curve_points): curve_points ROI 지원 (Python: main_ruler_selected_2points_GUI_v17.py)
    private const val CURVE_TARGET_Y_MM_DEFAULT = 10.0

    private data class BestCurvePick(
        val pointAbs: Pair<Double, Double>,
        val dxMm: Double,
        val dyMm: Double,
        val errMm: Double,
    )
    private data class MeasureCalc(
        val m: MeasureCfg,
        val p1: Pair<Double, Double>,
        val p2: Pair<Double, Double>,
        val r1: IntArray,
        val r2: IntArray,
        val rawMm: Double,
    )

    /**
     * (요구사항 - 2026-02)
     * - offset update 여부를 "필름 단위"가 아니라 "이미지(run) 단위"로 결정하기 위해,
     *   각 필름에서 계산된 측정값/판정 정보를 모아 둡니다.
     */
    private data class FilmCalcBundle(
        val filmIndex: Int,
        val calcs: List<MeasureCalc>,
        val hasOutlier: Boolean,
    )

    // -------------------------------------------------------------------------------------
    // ROI Cache (film_total_ruler.py의 "ROI key당 1번만 탐색" 로직)
    // - Film# 단위로 start/end ROI 키를 미리 계산해 Map에 저장
    // - 병렬화/스레드 사용 없음(요구사항)
    // -------------------------------------------------------------------------------------
    private enum class RoiCacheKind { POINT, CURVE }

    private data class RoiCacheItem(
        val kind: RoiCacheKind,
        val point: Pair<Double, Double>?,
        val curvePoints: List<Pair<Double, Double>>?,
        val rect: IntArray,
        val error: Throwable?,
    )



    data class Result(
        val overlay: Mat,
        val logs: List<String>,
        val detectedFilms: Int,
        val pixelsPerMmH: Double,
        val pixelsPerMmV: Double,
        val detectedRectsPx: List<Rect> = emptyList(),
        val calibrationToastMessage: String? = null,
    )

    /**
     * ✅ 측정 실패 시, “실제로 어떤 회전 정책이 적용됐는지” 확인하기 위한 디버그 프리뷰
     * - 원본 전체 이미지를 preview 크기(예: 1280x720)에 맞춰 보여줌
     * - (요청사항) EXIF orientation 기반 회전은 사용하지 않고,
     *   "세로(h>w)면 90° CCW"만 적용한 내역을 문자열로 제공합니다.
     */
    data class RotationDebugPreview(
        val bitmap: Bitmap,
        val debugText: String,
    )

    /**
     * 측정 실패 디버깅용: 삼성 카메라 원본 JPEG를 '측정에 사용된 것과 동일한 회전 정책'으로
     * 가로(landscape)로 맞춘 뒤 preview 해상도(targetW,targetH)에 스케일해 반환.
     *
     * ⚠️ 주의
     * - fixed5까지 있던 “엣지 기반 방향 판정(Canny/edge score)”은 제거했습니다.
     * - 2026-01-08 정책 변경: EXIF orientation 기반 회전은 사용하지 않습니다.
     */
    fun buildRotationDebugPreview(
        context: Context,
        imageUri: Uri,
        targetW: Int = 1280,
        targetH: Int = 720,
        forceLandscape: Boolean = true,
    ): RotationDebugPreview {
        // 1) bounds
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(imageUri)?.use { ins ->
            BitmapFactory.decodeStream(ins, null, bounds)
        }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight

        // 2) decode (downsample)
        val inSample = calculateInSampleSize(srcW, srcH, targetW, targetH)
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = inSample
            // ✅ 실패 디버깅 프리뷰는 품질 우선(1280x720 수준)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(imageUri)?.use { ins ->
            BitmapFactory.decodeStream(ins, null, opts)
        } ?: throw IllegalStateException("decodeStream failed: $imageUri")

        // ✅ EXIF orientation은 무시하고, 파일 픽셀 데이터 기준으로만 처리
        var bmp = decoded

        // 3) forceLandscape (same policy as loadBgrMatFromUri)
        //    - 엣지 기반 판정 제거: portrait이면 CCW(-90)로 1회 회전만 적용
        val forcedLandscapeApplied = forceLandscape && (bmp.height > bmp.width)
        if (forcedLandscapeApplied) {
            bmp = rotateBitmap(bmp, -90f, recycleSrc = true)
        }

        // 4) scale to preview
        val out = if (bmp.width == targetW && bmp.height == targetH) {
            bmp
        } else {
            val scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
            if (scaled !== bmp) runCatching { bmp.recycle() }
            scaled
        }

        // 5) debug text
        val debug = buildString {
            append("src=")
            append(if (srcW > 0 && srcH > 0) "${srcW}x${srcH}" else "?x?")
            append(", inSample=")
            append(inSample)
            append(", exifIgnored=true")
            append(", forceLandscape=")
            append(forceLandscape)
            append(", forcedLandscapeApplied=")
            append(forcedLandscapeApplied)
            append(" | out=")
            append("${out.width}x${out.height}")
        }

        return RotationDebugPreview(bitmap = out, debugText = debug)
    }

    // -----------------------------------------------------------------------------
    // ROI Cache helpers
    // -----------------------------------------------------------------------------

    private fun collectNeededRoiKeys(measures: List<MeasureCfg>): List<String> {
        val keys = LinkedHashSet<String>(measures.size * 2)
        for (m in measures) {
            if (m.startKey.isNotBlank()) keys.add(m.startKey)
            if (m.endKey.isNotBlank()) keys.add(m.endKey)
        }
        return keys.toList()
    }

    private fun roiCfgToAbsRect(filmBBox: IntArray, roiCfg: RoiCfg): IntArray {
        // filmBBox: [x1,y1,x2,y2] in full image coords
        val fx1 = filmBBox[0]
        val fy1 = filmBBox[1]
        val fw = (filmBBox[2] - filmBBox[0]).coerceAtLeast(1)
        val fh = (filmBBox[3] - filmBBox[1]).coerceAtLeast(1)

        val x1 = (fx1 + roiCfg.x * fw).roundToInt()
        val y1 = (fy1 + roiCfg.y * fh).roundToInt()
        val x2 = (fx1 + (roiCfg.x + roiCfg.w) * fw).roundToInt()
        val y2 = (fy1 + (roiCfg.y + roiCfg.h) * fh).roundToInt()

        // clamp min size
        val xx1 = min(x1, x2)
        val yy1 = min(y1, y2)
        val xx2 = max(x1, x2)
        val yy2 = max(y1, y2)
        return intArrayOf(xx1, yy1, xx2, yy2)
    }

    private fun precomputeRoiCacheForFilm(
        filmCrop: Mat,
        filmBBox: IntArray,
        rois: Map<String, RoiCfg>,
        keys: List<String>,
    ): MutableMap<String, RoiCacheItem> {
        val cache = LinkedHashMap<String, RoiCacheItem>(keys.size)

        for (key in keys) {
            if (cache.containsKey(key)) continue
            val cfg = rois[key] ?: continue
            val isCurve = isCurvePointsRoi(cfg)
            val rectAbs = roiCfgToAbsRect(filmBBox, cfg)
            try {
                if (isCurve) {
                    val (pts, rr) = filmRoiCfgToAbsCurvePoints(filmCrop, filmBBox, cfg)
                    cache[key] = RoiCacheItem(
                        kind = RoiCacheKind.CURVE,
                        point = null,
                        curvePoints = pts,
                        rect = rr,
                        error = null
                    )
                } else {
                    val (pt, rr) = filmRoiCfgToAbsPoint(filmCrop, filmBBox, cfg)
                    cache[key] = RoiCacheItem(
                        kind = RoiCacheKind.POINT,
                        point = pt,
                        curvePoints = null,
                        rect = rr,
                        error = null
                    )
                }
            } catch (t: Throwable) {
                // 요구사항: 1회만 탐색하고 결과를 캐시에 저장
                cache[key] = RoiCacheItem(
                    kind = if (isCurve) RoiCacheKind.CURVE else RoiCacheKind.POINT,
                    point = null,
                    curvePoints = null,
                    rect = rectAbs,
                    error = t
                )
            }
        }
        return cache
    }

    // -----------------------------------------------------------------------------
    // BBox/ROI helpers (Python film_total_ruler.py 로직 이식)
    // -----------------------------------------------------------------------------

    private fun padAndClampBBox(bbox: IntArray, padPx: Int, imgW: Int, imgH: Int): IntArray {
        var x1 = bbox[0] - padPx
        var y1 = bbox[1] - padPx
        var x2 = bbox[2] + padPx
        var y2 = bbox[3] + padPx

        // end is exclusive
        x1 = x1.coerceIn(0, (imgW - 1).coerceAtLeast(0))
        y1 = y1.coerceIn(0, (imgH - 1).coerceAtLeast(0))
        x2 = x2.coerceIn(x1 + 1, imgW)
        y2 = y2.coerceIn(y1 + 1, imgH)
        return intArrayOf(x1, y1, x2, y2)
    }

    private fun expandAndClampBBoxAsym(
        bbox: IntArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        imgW: Int,
        imgH: Int,
    ): IntArray {
        var x1 = bbox[0] - left
        var y1 = bbox[1] - top
        var x2 = bbox[2] + right
        var y2 = bbox[3] + bottom

        x1 = x1.coerceIn(0, (imgW - 1).coerceAtLeast(0))
        y1 = y1.coerceIn(0, (imgH - 1).coerceAtLeast(0))
        x2 = x2.coerceIn(x1 + 1, imgW)
        y2 = y2.coerceIn(y1 + 1, imgH)
        return intArrayOf(x1, y1, x2, y2)
    }

    private fun intersectBBox(a: IntArray, b: IntArray): IntArray? {
        if (a.size < 4 || b.size < 4) return null
        val x1 = max(a[0], b[0])
        val y1 = max(a[1], b[1])
        val x2 = min(a[2], b[2])
        val y2 = min(a[3], b[3])
        if (x2 <= x1 || y2 <= y1) return null
        return intArrayOf(x1, y1, x2, y2)
    }

    private fun clampRoiToBounds(roi: IntArray, bounds: IntArray): IntArray {
        if (roi.size < 4 || bounds.size < 4) return intArrayOf(0, 0, 1, 1)

        val bx1 = bounds[0]
        val by1 = bounds[1]
        val bx2 = bounds[2]
        val by2 = bounds[3]

        // guard: invalid bounds
        if (bx2 <= bx1 + 1 || by2 <= by1 + 1) {
            return intArrayOf(bx1, by1, maxOf(bx2, bx1 + 1), maxOf(by2, by1 + 1))
        }

        var x1 = roi[0].coerceIn(bx1, bx2 - 1)
        var y1 = roi[1].coerceIn(by1, by2 - 1)
        var x2 = roi[2].coerceIn(x1 + 1, bx2)
        var y2 = roi[3].coerceIn(y1 + 1, by2)

        return intArrayOf(x1, y1, x2, y2)
    }

    private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        val rw = max(1, reqW)
        val rh = max(1, reqH)
        if (srcW <= 0 || srcH <= 0) return 1

        val wRatio = srcW / rw
        val hRatio = srcH / rh

        // ✅ target 이상 크기로 디코딩되게 하려면 floor(min(ratio))가 유리
        val sample = min(wRatio, hRatio)
        return max(1, sample)
    }

    // NOTE: 2026-01-08 정책 변경으로 EXIF orientation 기반 회전을 사용하지 않습니다.
    //       (삼성 카메라 원본 파일은 항상 세로로 저장된다고 가정하고,
    //        세로(h>w)일 때만 90° CCW 회전으로 정규화합니다.)

    private fun rotateBitmap(src: Bitmap, degrees: Float, recycleSrc: Boolean): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (recycleSrc && out !== src) runCatching { src.recycle() }
        return out
    }

    // (fixed5까지 존재했던 edgeScoreTopLeftBitmap/edgeScoreTopLeft 기반 회전 판정은 제거)

    fun run(
        context: Context,
        imageUri: Uri,
        modelName: String,
        scoreThresh: Float = GlobalParams.SCORE_THRESH,
        iouThresh: Float = GlobalParams.IOU_THRESH,
    ): Result {
        val t0 = SystemClock.elapsedRealtime()
        val logs = ArrayList<String>()

        // (1) 모델 파일 로드
        val base = ModelNameCompat.canonical(modelName)
        val calibrationEnabled = AppSessionSettings.gridCalibrationEnabled

        // ✅ 2026-02-10 요청사항
        // - 50MP 측정: adjust + offset 둘 다 적용
        // - 200MP 측정: offset은 적용하지 않고 adjust만 적용
        val useOffset = (GlobalParams.CAPTURE_MP != 200)

        val tfliteFile = ModelFileStore.downloadedModelFile(context, base)
        val jsonCfg = FilmModelConfigLoader.loadFromInternalModels(context, base)
            ?: throw IllegalStateException("film json 모델이 없습니다: files/models/$base.json")
        if (!tfliteFile.exists()) {
            throw IllegalStateException("tflite 모델이 없습니다: ${tfliteFile.absolutePath}")
        }

        // (2) 이미지 Uri -> OpenCV Mat(BGR)
        //     ✅ 2026-01-08 정책: EXIF orientation 무시 + 세로(h>w)면 90° CCW 1회 회전(가로 정규화)
        val img = loadBgrMatFromUri(context, imageUri, forceLandscape = true)

        try {
            val H = img.rows()
            val W = img.cols()

            if (DEBUG_LOG) {
                Log.w(TAG, "[run] image(after rotate) size=(${W}x${H}) uri=$imageUri")
            }

            // ✅ 50MP(8160x4592) / 200MP(16320x9184; Expert RAW 4:3 이미지를 내부 crop 후) 둘 다 허용
            val isExpected50 = (W == 8160 && H == 4592) || (W == 4592 && H == 8160)
            val isExpected200 = (W == 16320 && H == 9184) || (W == 9184 && H == 16320)
            if (!(isExpected50 || isExpected200)) {
                Log.w(TAG, "Unexpected capture size: ${W}x${H} (expected 8160x4592 or 16320x9184)")
            }

            // (3) YOLO ROI detector (MainActivity와 동일한 com.hklab.airuler.yolo.YoloDetector 사용) :contentReference[oaicite:1]{index=1}
            fun toPixelXYXY(d: YoloDetection): IntArray {
                var xx1 = (d.rect.left * W).roundToInt().coerceIn(0, W - 1)
                var yy1 = (d.rect.top * H).roundToInt().coerceIn(0, H - 1)
                var xx2 = (d.rect.right * W).roundToInt().coerceIn(0, W - 1)
                var yy2 = (d.rect.bottom * H).roundToInt().coerceIn(0, H - 1)
                if (xx2 < xx1) { val t = xx1; xx1 = xx2; xx2 = t }
                if (yy2 < yy1) { val t = yy1; yy1 = yy2; yy2 = t }
                return intArrayOf(xx1, yy1, xx2, yy2)
            }

            // YOLO 입력용 다운스케일 Bitmap 준비 (OpenCV Mat(BGR) -> Bitmap(ARGB))
            val detBmp: Bitmap = run {
                val maxSide = 1280
                val longSide = max(W, H)
                val scale = if (longSide > maxSide) (maxSide.toDouble() / longSide.toDouble()) else 1.0
                val dw = (W * scale).roundToInt().coerceAtLeast(1)
                val dh = (H * scale).roundToInt().coerceAtLeast(1)

                if (DEBUG_LOG) {
                    Log.w(TAG, "[YOLO] downscale for detect: ${W}x${H} -> ${dw}x${dh} (scale=%.6f)".format(scale))
                }

                val small = Mat()
                Imgproc.resize(img, small, org.opencv.core.Size(dw.toDouble(), dh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

                val rgba = Mat()
                Imgproc.cvtColor(small, rgba, Imgproc.COLOR_BGR2RGBA)
                small.release()

                val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(rgba, bmp)
                rgba.release()
                bmp
            }

            val yolo = YoloDetector(context, tfliteFile.absolutePath, numThreads = 4, classLabels = arrayOf("film", "good", "bad"))
            val detsAll: List<YoloDetection> = try {
                yolo.detect(detBmp, scoreThresh = scoreThresh, iouThresh = iouThresh)
            } finally {
                runCatching { yolo.close() }
                runCatching { detBmp.recycle() }
            }

            // (2026-02) YOLO 모델이 good/bad도 함께 출력할 수 있으므로,
            // "필름 ROI"로 사용할 detection은 film/flim class만 필터링합니다.
            val dets: List<YoloDetection> = AirulerYoloClasses.filterFilms(detsAll)

            logs.add("[Ruler 기반 필름 전체 길이 측정] detections(after NMS)=${dets.size}")
            if (DEBUG_LOG) Log.w(TAG, "[YOLO] detections(after NMS)=${dets.size}")

            // overlay: img 복사본에 모든 시각화(박스/union/측정) 그리기
            val out = img.clone()

            // 디버깅: 각 박스 로그 + 박스 그리기
            dets.forEachIndexed { idx, d ->
                val xyxy = toPixelXYXY(d)
                val x1 = xyxy[0]; val y1 = xyxy[1]; val x2 = xyxy[2]; val y2 = xyxy[3]
                if (x2 <= x1 || y2 <= y1) return@forEachIndexed

                if (DEBUG_LOG) {
                    Log.w(
                        TAG,
                        String.format(
                            Locale.US,
                            "  %02d: score=%.3f rect01=(%.4f,%.4f)-(%.4f,%.4f) box=(%d,%d)-(%d,%d)",
                            idx + 1, d.score,
                            d.rect.left, d.rect.top, d.rect.right, d.rect.bottom,
                            x1, y1, x2, y2
                        )
                    )
                }

                Imgproc.rectangle(
                    out,
                    Point(x1.toDouble(), y1.toDouble()),
                    Point(x2.toDouble(), y2.toDouble()),
                    Scalar(0.0, 255.0, 255.0), // yellow (BGR)
                    3
                )
            }

            if (dets.isEmpty()) {
                logs.add("[Ruler 기반 필름 전체 길이 측정] detections=0")
                return Result(out, logs, 0, 0.0, 0.0)
            }

            // (4) union_bbox
            var ux1 = W
            var uy1 = H
            var ux2 = 0
            var uy2 = 0

            for (d in dets) {
                val xyxy = toPixelXYXY(d)
                val fx1 = xyxy[0]
                val fy1 = xyxy[1]
                val fx2 = xyxy[2]
                val fy2 = xyxy[3]
                if (fx2 <= fx1 || fy2 <= fy1) continue
                ux1 = minOf(ux1, fx1)
                uy1 = minOf(uy1, fy1)
                ux2 = maxOf(ux2, fx2)
                uy2 = maxOf(uy2, fy2)
            }

            if (ux2 <= ux1 || uy2 <= uy1) {
                logs.add("[Ruler 기반 필름 전체 길이 측정] 오류: 유효한 ROI bbox가 없습니다")
                return Result(out, logs, dets.size, 0.0, 0.0)
            }

            val union = intArrayOf(ux1, uy1, ux2, uy2)
            logs.add("[Ruler 기반 필름 전체 길이 측정] union_bbox=($ux1,$uy1,$ux2,$uy2)")
            if (DEBUG_LOG) {
                Log.w(TAG, "[Ruler 기반 필름 전체 길이 측정] union_bbox=($ux1,$uy1,$ux2,$uy2)")
            }

            // union_bbox 시각화(RED)
            Imgproc.rectangle(
                out,
                Point(union[0].toDouble(), union[1].toDouble()),
                Point(union[2].toDouble(), union[3].toDouble()),
                Scalar(255.0, 0.0, 0.0),
                4
            )

            // (5) ruler calibration
            // ✅ Python(film_total_ruler.py) 로직 적용:
            //    1) union_bbox + pad
            //    2) corner_box(no pad) 계산 (roi_TL/TR/BR/BL _H/_V)
            //    3) crop_interest(corner_box 기준 비대칭 확장)
            //    4) corner_box_pad로 film bbox clip
            //    5) corner_box 기반으로 top/left ruler search ROI를 만들어서 검색
            val rois = jsonCfg.rois
            val measures = jsonCfg.measures

            val unionPad = padAndClampBBox(
                bbox = union,
                padPx = GlobalParams.MEASURE_TFLITE_PAD_PX,
                imgW = W,
                imgH = H
            )
            logs.add(
                "[Ruler 기반 필름 전체 길이 측정] union_bbox(tflite+pad)=" +
                        "(${unionPad[0]},${unionPad[1]},${unionPad[2]},${unionPad[3]})"
            )

            // corner_box(no pad): corner ROI로 계산 (없으면 union_bbox_pad fallback)
            val cornerBoxNoPad = computeCvRoiBBoxFromCornersOrNull(
                imgBgr = img,
                rawFilmBBox = unionPad,
                rois = rois,
                tflitePadPx = 0,
                cornerPadPx = 0
            )
            val cornerBox = cornerBoxNoPad ?: unionPad
            if (cornerBoxNoPad != null) {
                logs.add(
                    "[Ruler 기반 필름 전체 길이 측정] corner_box(full, no pad)=" +
                            "(${cornerBox[0]},${cornerBox[1]},${cornerBox[2]},${cornerBox[3]})"
                )
            } else {
                logs.add(
                    "[Ruler 기반 필름 전체 길이 측정] corner ROI missing/failed -> " +
                            "fallback corner_box=union_bbox_pad"
                )
            }

            // crop_interest: corner_box 기준 비대칭 확장
            val cropInterest = expandAndClampBBoxAsym(
                bbox = cornerBox,
                left = GlobalParams.CROP_INTEREST_EXPAND_LEFT_PX,
                top = GlobalParams.CROP_INTEREST_EXPAND_UP_PX,
                right = GlobalParams.CROP_INTEREST_EXPAND_RIGHT_PX,
                bottom = GlobalParams.CROP_INTEREST_EXPAND_DOWN_PX,
                imgW = W,
                imgH = H
            )
            logs.add(
                "[Ruler 기반 필름 전체 길이 측정] crop_interest(full)=" +
                        "(${cropInterest[0]},${cropInterest[1]},${cropInterest[2]},${cropInterest[3]})" +
                        " (U${GlobalParams.CROP_INTEREST_EXPAND_UP_PX},D${GlobalParams.CROP_INTEREST_EXPAND_DOWN_PX}," +
                        "L${GlobalParams.CROP_INTEREST_EXPAND_LEFT_PX},R${GlobalParams.CROP_INTEREST_EXPAND_RIGHT_PX})"
            )

            // corner_box_pad: film bbox clip 기준 (ruler 영역 배제)
            val cornerBoxPad = padAndClampBBox(
                bbox = cornerBox,
                padPx = GlobalParams.MEASURE_CORNER_PAD_PX,
                imgW = W,
                imgH = H
            )

            // (debug) crop_interest / corner_box_pad 시각화
            Imgproc.rectangle(
                out,
                Point(cropInterest[0].toDouble(), cropInterest[1].toDouble()),
                Point(cropInterest[2].toDouble(), cropInterest[3].toDouble()),
                Scalar(255.0, 0.0, 255.0),
                2
            )
            Imgproc.rectangle(
                out,
                Point(cornerBoxPad[0].toDouble(), cornerBoxPad[1].toDouble()),
                Point(cornerBoxPad[2].toDouble(), cornerBoxPad[3].toDouble()),
                Scalar(0.0, 255.0, 0.0),
                2
            )

            // ✅ corner_box 기반 ruler search ROI (crop_interest 범위로 clamp)
            // - Python과 동일한 형태
            //   Top  ROI: (corner_x1-PAD, crop_y1) ~ (corner_x2+PAD, corner_y1-PAD)
            //   Left ROI: (crop_x1, corner_y1-PAD) ~ (corner_x1-PAD, corner_y2+PAD)
            val padRuler = GlobalParams.RULER_SEARCH_PAD_PX
            val topRoiRaw = intArrayOf(
                cornerBox[0] - padRuler,
                cropInterest[1],
                cornerBox[2] + padRuler,
                cornerBox[1] - padRuler
            )
            val leftRoiRaw = intArrayOf(
                cropInterest[0],
                cornerBox[1] - padRuler,
                cornerBox[0] - padRuler,
                cornerBox[3] + padRuler
            )

            var topRoi = clampRoiToBounds(topRoiRaw, cropInterest)
            var leftRoi = clampRoiToBounds(leftRoiRaw, cropInterest)

            // ROI 유효성 최소 체크 (너무 작으면 fallback)
            val minRoiSizePx = 10
            if ((topRoi[2] - topRoi[0]) < minRoiSizePx || (topRoi[3] - topRoi[1]) < minRoiSizePx) {
                // fallback: crop_interest 상단 영역 (Python: top_search_up_px)
                val topH = min(cropInterest[3], cropInterest[1] + GlobalParams.CROP_INTEREST_EXPAND_UP_PX)
                topRoi = intArrayOf(cropInterest[0], cropInterest[1], cropInterest[2], topH)
                logs.add("[Ruler 기반 필름 전체 길이 측정] (fallback) top_ruler_search_roi=${topRoi.contentToString()}")
            }
            if ((leftRoi[2] - leftRoi[0]) < minRoiSizePx || (leftRoi[3] - leftRoi[1]) < minRoiSizePx) {
                // fallback: crop_interest 좌측 영역 (Python: left_search_left_px)
                val leftW = min(cropInterest[2], cropInterest[0] + GlobalParams.CROP_INTEREST_EXPAND_LEFT_PX)
                leftRoi = intArrayOf(cropInterest[0], cropInterest[1], leftW, cropInterest[3])
                logs.add("[Ruler 기반 필름 전체 길이 측정] (fallback) left_ruler_search_roi=${leftRoi.contentToString()}")
            }

            logs.add("[Ruler 기반 필름 전체 길이 측정] PAD_RULER=$padRuler px")
            logs.add("[Ruler 기반 필름 전체 길이 측정] top_ruler_search_roi=${topRoi.contentToString()}")
            logs.add("[Ruler 기반 필름 전체 길이 측정] left_ruler_search_roi=${leftRoi.contentToString()}")

            // ✅ (요구사항) ruler tick/스케일 검증 실패 디버깅용 Logcat 출력
            // - GlobalParams.DEBUG==true 일 때만 출력
            if (GlobalParams.DEBUG) {
                Log.i(
                    TAG,
                    "[DBG][ruler] capture=${W}x${H}, CAPTURE_MP=${GlobalParams.CAPTURE_MP}, scale=${GlobalParams.CAPTURE_SCALE}"
                )
                Log.i(
                    TAG,
                    "[DBG][ruler] union=${union.contentToString()} unionPad=${unionPad.contentToString()} cornerBox=${cornerBox.contentToString()} cornerBoxPad=${cornerBoxPad.contentToString()}"
                )
                Log.i(
                    TAG,
                    "[DBG][ruler] cropInterest=${cropInterest.contentToString()} padRuler=$padRuler tickLength=${GlobalParams.RULER_TICK_LENGTH_PX}"
                )
                Log.i(
                    TAG,
                    "[DBG][ruler] topRoiRaw=${topRoiRaw.contentToString()} leftRoiRaw=${leftRoiRaw.contentToString()}"
                )
                Log.i(
                    TAG,
                    "[DBG][ruler] topRoi=${topRoi.contentToString()} leftRoi=${leftRoi.contentToString()}"
                )
            }

            val grayFull = Mat()
            Imgproc.cvtColor(img, grayFull, Imgproc.COLOR_BGR2GRAY)

            val top = RulerCalibration.detectTopRulerBySearchRoi(
                grayFull = grayFull,
                searchRoi = topRoi,
                tickLength = GlobalParams.RULER_TICK_LENGTH_PX,
                // Python: top tick detection uses LEFT start/end ratio
                startRatio = RULER_LEFT_START_RATIO,
                endRatio = RULER_LEFT_END_RATIO,
                overlay = out,
            )

            val left = RulerCalibration.detectLeftRulerBySearchRoi(
                grayFull = grayFull,
                searchRoi = leftRoi,
                tickLength = GlobalParams.RULER_TICK_LENGTH_PX,
                // Python: left tick detection uses TOP start/end ratio
                startRatio = RULER_TOP_START_RATIO,
                endRatio = RULER_TOP_END_RATIO,
                overlay = out,
            )

            grayFull.release()

            val pxPerMmH = top.pixelsPerMm
            val pxPerMmV = left.pixelsPerMm
            logs.add(String.format(Locale.US, "[Ruler 기반 필름 전체 길이 측정] pixels_per_mm: H=%.6f, V=%.6f", pxPerMmH, pxPerMmV))

            // (6) Film JSON 모델 기반 측정

            // 보기 좋게 정렬 (위->아래, 좌->우)
            val detsSorted = dets.sortedWith(compareBy<YoloDetection> { it.rect.top }.thenBy { it.rect.left })

            val (fontScale, thick) = when {
                W >= 16000 -> 2.5 to 3   // 200MP (16320 폭)
                W >= 8000  -> 1.2 to 2   // 50MP (8160 폭)
                else       -> 0.8 to 1
            }

            // ✅ ROI key당 1번만 탐색하도록, Film 단위 ROI 결과 캐시를 만듭니다.
            // - 병렬화/스레드 사용 없음(요구사항)
            val neededRoiKeys: List<String> = collectNeededRoiKeys(measures)

            // ✅ (요구사항 - 2026-02)
            // 기존: 필름 단위로 PASS/FAIL을 판정하여, PASS인 필름만 offset update 수행
            // 변경: "한 이미지(run)에서 모든 필름이 PASS"일 때만 offset update 수행
            //      (즉, 한 필름이라도 FAIL이면 전체 필름의 offset update를 하지 않음)
            //
            // 구현 방식)
            // 1) 1st pass: 모든 필름에 대해 measure별 raw(mm) 및 ROI/point 결과를 먼저 계산해 저장
            // 2) 1st pass에서 "필름별 outlier 여부"를 계산해 저장
            // 3) 2nd pass: 한 필름이라도 outlier이면(imageUpdateBlocked=true)
            //    applyAndUpdate()에 동일 플래그를 전달하여 "모든 필름"의 offset update를 막음
            //    (단, sampleN은 기존 로직처럼 계속 증가)
            val filmBundles = mutableListOf<FilmCalcBundle>()

            var filmIndex = 0
            for (d in detsSorted) {
                filmIndex++
                val xyxy = toPixelXYXY(d)
                val fx1 = xyxy[0]
                val fy1 = xyxy[1]
                val fx2 = xyxy[2]
                val fy2 = xyxy[3]
                if (fx2 <= fx1 || fy2 <= fy1) continue

                // ✅ Python(film_total_ruler.py) 로직: corner_box_pad 로 film bbox를 한 번 clip
                //   - Top/Left ruler가 포함되는 경우를 줄여 ROI 탐색 실패/오검출을 완화
                val rawFilmBBox0 = intArrayOf(fx1, fy1, fx2, fy2)
                val rawFilmBBox = intersectBBox(rawFilmBBox0, cornerBoxPad)
                    ?: continue

                // ✅ tflite bbox를 한 번 더 보정하여(CV ROI: 8개 roi_xxx -> 4 corner -> bbox)
                //    더 정교한 crop 영역에서 치수 측정을 수행합니다.
                // - corner pad 기본값은 요구사항대로 10px (GlobalParams.MEASURE_CORNER_PAD_PX)
                val cvFilmBBox = computeCvRoiBBoxFromCornersOrNull(
                    imgBgr = img,
                    rawFilmBBox = rawFilmBBox,
                    rois = rois,
                    tflitePadPx = GlobalParams.MEASURE_TFLITE_PAD_PX,
                    cornerPadPx = GlobalParams.MEASURE_CORNER_PAD_PX
                )

                // ✅ STRICT: 반드시 CV ROI(corner box)가 성공해야만 측정을 진행합니다.
                // - 실패(roi 키 누락/포인트 검출 실패 등) 시 즉시 예외를 던져 측정 파이프라인을 중단합니다.
                // - 상위(MeasurementPipeline)에서 예외를 받아 Toast/알림으로 사용자에게 안내하게 됩니다.
                val usedFilmBBox = cvFilmBBox
                    ?: throw IllegalStateException(
                        "[Measure] CV ROI(corners) failed for Film#${filmIndex}. " +
                            "Check film json has 8 roi keys (roi_TL_H/V, roi_TR_H/V, roi_BR_H/V, roi_BL_H/V) " +
                            "and point detection works."
                    )

                val filmCrop = img.submat(
                    usedFilmBBox[1],
                    usedFilmBBox[3],
                    usedFilmBBox[0],
                    usedFilmBBox[2]
                ).clone()

                // ✅ ROI Cache (film 단위): start/end ROI 키를 모두 미리 탐색해서 캐시
                // - 같은 key는 1번만 탐색
                // - thread/parallel 없음
                val roiCache = precomputeRoiCacheForFilm(
                    filmCrop = filmCrop,
                    filmBBox = usedFilmBBox,
                    rois = rois,
                    keys = neededRoiKeys
                )

                fun getPointCached(key: String, roiCfg: RoiCfg): Pair<Pair<Double, Double>, IntArray> {
                    val cached = roiCache[key]
                    if (cached != null) {
                        cached.error?.let { throw it }
                        if (cached.kind != RoiCacheKind.POINT) {
                            throw IllegalStateException("ROI kind mismatch for key=$key (need POINT)")
                        }
                        return (cached.point ?: throw IllegalStateException("ROI cache missing point: $key")) to cached.rect
                    }
                    // on-demand(그래도 1회) - 예외 발생 시 상위로 전파
                    val (p, r) = filmRoiCfgToAbsPoint(filmCrop, usedFilmBBox, roiCfg)
                    roiCache[key] = RoiCacheItem(
                        kind = RoiCacheKind.POINT,
                        point = p,
                        curvePoints = null,
                        rect = r,
                        error = null
                    )
                    return p to r
                }

                fun getCurveCached(key: String, roiCfg: RoiCfg): Pair<List<Pair<Double, Double>>, IntArray> {
                    val cached = roiCache[key]
                    if (cached != null) {
                        cached.error?.let { throw it }
                        if (cached.kind != RoiCacheKind.CURVE) {
                            throw IllegalStateException("ROI kind mismatch for key=$key (need CURVE)")
                        }
                        return (cached.curvePoints ?: throw IllegalStateException("ROI cache missing curvePoints: $key")) to cached.rect
                    }
                    val (pts, r) = filmRoiCfgToAbsCurvePoints(filmCrop, usedFilmBBox, roiCfg)
                    roiCache[key] = RoiCacheItem(
                        kind = RoiCacheKind.CURVE,
                        point = null,
                        curvePoints = pts,
                        rect = r,
                        error = null
                    )
                    return pts to r
                }

                Imgproc.putText(
                    out,
                    "Film#${filmIndex}",
                    Point((fx1 + 5).toDouble(), (fy1 + 25).toDouble()),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    fontScale,
                    Scalar(0.0, 255.0, 255.0),
                    thick,
                    Imgproc.LINE_AA
                )

                // 1st pass: measure별 raw(mm) 및 포인트/ROI를 계산해 저장
                val calcs = mutableListOf<MeasureCalc>()

                for (m in measures) {
                    val startRoi = rois[m.startKey] ?: continue
                    val endRoi = rois[m.endKey] ?: continue

                    // --- NEW: curve_points ROI 지원 (Python main_ruler_selected_2points_GUI_v17.py) ---
                    val startIsCurve = isCurvePointsRoi(startRoi)
                    val endIsCurve = isCurvePointsRoi(endRoi)

                    var p1: Pair<Double, Double> = 0.0 to 0.0
                    var p2: Pair<Double, Double> = 0.0 to 0.0
                    var r1: IntArray = intArrayOf(0, 0, 0, 0)
                    var r2: IntArray = intArrayOf(0, 0, 0, 0)
                    var distMmRaw = 0.0

                    if (startIsCurve || endIsCurve) {
                        if (startIsCurve && endIsCurve) {
                            logs.add("[Film#${filmIndex}][M${m.name}] curve_points: start/end 둘 다 curve_points 입니다. (skip)")
                            continue
                        }

                        val targetYMm = m.yTargetMm ?: CURVE_TARGET_Y_MM_DEFAULT

                        try {
                            if (startIsCurve) {
                                val (curvePts, rr1) = getCurveCached(m.startKey, startRoi)
                                val (fixedPt, rr2) = getPointCached(m.endKey, endRoi)

                                val best = selectBestCurvePointByTargetY_Ruler(
                                    curvePointsAbs = curvePts,
                                    fixedPointAbs = fixedPt,
                                    tickStructsH = top.tickStructs,
                                    pixelsPerMmH = pxPerMmH,
                                    tickStructsV = left.tickStructs,
                                    pixelsPerMmV = pxPerMmV,
                                    targetYMm = targetYMm,
                                )

                                p1 = best.pointAbs
                                p2 = fixedPt
                                r1 = rr1
                                r2 = rr2

                                distMmRaw = when (m.direction.lowercase(Locale.US)) {
                                    "x" -> best.dxMm
                                    "y" -> best.dyMm
                                    else -> hypot(best.dxMm, best.dyMm)
                                }

                                logs.add(
                                    String.format(
                                        Locale.US,
                                        "[Film#%d][M%s] curve_points: N=%d, targetY=%.3fmm, bestY=%.3fmm(|Δ|=%.3f) => X=%.3fmm",
                                        filmIndex,
                                        m.name,
                                        curvePts.size,
                                        targetYMm,
                                        best.dyMm,
                                        best.errMm,
                                        best.dxMm
                                    )
                                )
                            } else {
                                val (fixedPt, rr1) = getPointCached(m.startKey, startRoi)
                                val (curvePts, rr2) = getCurveCached(m.endKey, endRoi)

                                val best = selectBestCurvePointByTargetY_Ruler(
                                    curvePointsAbs = curvePts,
                                    fixedPointAbs = fixedPt,
                                    tickStructsH = top.tickStructs,
                                    pixelsPerMmH = pxPerMmH,
                                    tickStructsV = left.tickStructs,
                                    pixelsPerMmV = pxPerMmV,
                                    targetYMm = targetYMm,
                                )

                                p1 = fixedPt
                                p2 = best.pointAbs
                                r1 = rr1
                                r2 = rr2

                                distMmRaw = when (m.direction.lowercase(Locale.US)) {
                                    "x" -> best.dxMm
                                    "y" -> best.dyMm
                                    else -> hypot(best.dxMm, best.dyMm)
                                }

                                logs.add(
                                    String.format(
                                        Locale.US,
                                        "[Film#%d][M%s] curve_points: N=%d, targetY=%.3fmm, bestY=%.3fmm(|Δ|=%.3f) => X=%.3fmm",
                                        filmIndex,
                                        m.name,
                                        curvePts.size,
                                        targetYMm,
                                        best.dyMm,
                                        best.errMm,
                                        best.dxMm
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            logs.add("[Film#${filmIndex}][M${m.name}] curve_points failed: ${e.message}")
                            continue
                        }
                    } else {
                        val (pp1, rr1) = getPointCached(m.startKey, startRoi)
                        val (pp2, rr2) = getPointCached(m.endKey, endRoi)
                        p1 = pp1
                        p2 = pp2
                        r1 = rr1
                        r2 = rr2

                        distMmRaw = when (m.direction.lowercase(Locale.US)) {
                            "x" -> DistanceUtils.computeHorizontalDistanceMm(
                                p1.first, p2.first, top.tickStructs, pxPerMmH
                            )
                            "y" -> DistanceUtils.computeVerticalDistanceMm(
                                p1.second, p2.second, left.tickStructs, pxPerMmV
                            )
                            else -> DistanceUtils.computeEuclideanDistanceMm(
                                p1, p2, top.tickStructs, pxPerMmH, left.tickStructs, pxPerMmV
                            )
                        }
                    }

                    calcs.add(
                        MeasureCalc(
                            m = m,
                            p1 = p1,
                            p2 = p2,
                            r1 = r1,
                            r2 = r2,
                            rawMm = distMmRaw,
                        )
                    )
                }

                // 1st pass: 이 필름의 outlier 여부를 계산해 저장
                val filmHasOutlier = if (calibrationEnabled && useOffset && calcs.isNotEmpty()) {
                    val eps = 1e-6
                    val extra = com.hklab.airuler.calibration.OffsetCalibratorConfig.DEFAULT_MARGIN_EXTRA
                    var hasOutlier = false
                    for (c in calcs) {
                        val gt = c.m.gt
                        val margin = c.m.margin
                        if (gt == null || margin == null || margin.isNaN()) continue

                        val off0 = GridOnlineOffsetCalibrationStore.getCurrentOffset(
                            modelBase = base,
                            measureName = c.m.name,
                            filmIndex = filmIndex,
                            offsetFieldInJson = c.m.offset,
                        )
                        // ✅ (요구사항) adjust + offset 둘 다 적용한 값으로 outlier 판정
                        val adj0 = c.m.adjustForFilm(filmIndex)
                        val comp0 = c.rawMm + adj0 + off0
                        // inlier 판정 완화: |comp0-GT| <= margin + extra
                        val inlier = kotlin.math.abs(comp0 - gt) <= (margin + extra + eps)
                        if (!inlier) {
                            hasOutlier = true
                            break
                        }
                    }
                    hasOutlier
                } else {
                    false
                }

                filmBundles.add(
                    FilmCalcBundle(
                        filmIndex = filmIndex,
                        calcs = calcs,
                        hasOutlier = filmHasOutlier,
                    )
                )

                filmCrop.release()
            }

            // 2nd pass: "이미지(run) 단위"로 update 허용/차단 플래그 결정
            val imageUpdateBlocked = calibrationEnabled && useOffset && filmBundles.any { it.hasOutlier }
            if (imageUpdateBlocked) {
                val badFilms = filmBundles.filter { it.hasOutlier }
                    .joinToString(",") { "#${it.filmIndex}" }
                logs.add(
                    "[Image] outlier detected in Film${badFilms} => " +
                        "skip offset update for ALL films in this image (after warmup only)"
                )
            }

            // ✅ warm-up(첫 3회)은 "성공한 측정"만 반영해야 하므로,
            //    이번 run이 warm-up 구간이면 offset 상태를 스냅샷으로 잡아 두었다가
            //    최종 결과가 FAIL이면 sampleN/offset/window를 모두 rollback 합니다.
            val warmupSampleNBeforeRun = if (calibrationEnabled && useOffset) {
                GridOnlineOffsetCalibrationStore.getMaxSampleN(base)
            } else 0
            val warmupPhaseActive = calibrationEnabled && useOffset &&
                warmupSampleNBeforeRun < GridOnlineOffsetCalibrationStore.WARMUP_SAMPLES
            val warmupSnapshot = if (warmupPhaseActive) {
                GridOnlineOffsetCalibrationStore.snapshotModel(base)
            } else null

            // 2nd pass: 보정(applyAndUpdate) + 시각화/로그
            for (fb in filmBundles) {
                val filmIdx = fb.filmIndex

                for (c in fb.calcs) {
                    val m = c.m
                    val distMmRaw = c.rawMm
                    val p1 = c.p1
                    val p2 = c.p2
                    val r1 = c.r1
                    val r2 = c.r2

                    // ✅ (요구사항 - 2026-02) Grid/Ruler 공통
                    // - measure별/film별 adjust + offset을 적용합니다.
                    // - compensated = (raw + adjust) + offset
                    // - Calibration ON
                    //   - n=1..3 : outlier 판별 없이 alpha=1.0로 업데이트 (median 기반)
                    //   - n>=4  : outlier(=GT±margin 범위 밖) 샘플은 업데이트에서 제외
                    //              ✨ n>=4부터는 EMA 방식으로 업데이트 (offset += alpha*(GT - (raw + offset)))
                    //              + (변경) 이미지 단위 outlier(어느 필름이라도 outlier)이면 전체 업데이트 금지
                    // - offset 저장소: 모델 JSON의 measure_*.offset

                    // ✅ (요구사항) model json의 adjust(mm) 적용 (film별로 ',' 구분 가능)
                    val adjustMm = m.adjustForFilm(filmIdx)
                    val rawWithAdjust = distMmRaw + adjustMm

                    val distMm = if (useOffset) {
                        val calibRes = GridOnlineOffsetCalibrationStore.applyAndUpdate(
                            modelBase = base,
                            measureName = m.name,
                            filmIndex = filmIdx,
                            rawMm = rawWithAdjust,
                            gtMm = m.gt,
                            marginMm = m.margin,
                            offsetFieldInJson = m.offset,
                            updateEnabled = calibrationEnabled,
                            // ✅ (변경) film 단위가 아니라 image 단위 차단 플래그를 전달
                            filmUpdateBlocked = imageUpdateBlocked,
                        )
                        calibRes.compensated
                    } else {
                        rawWithAdjust
                    }

                    val gt = m.gt
                    val margin = m.margin
                    val hasJudge = (gt != null && margin != null)

                    val visColor: Scalar
                    val visText: String
                    val logSuffix: String

                    if (hasJudge) {
                        val err = distMm - gt!!
                        val pass = kotlin.math.abs(err) <= margin!!
                        visColor = if (pass) Scalar(0.0, 255.0, 0.0) else Scalar(0.0, 0.0, 255.0)
                        visText = String.format(Locale.US, "%.3f(%+.3f)", distMm, err)
                        logSuffix = String.format(
                            Locale.US,
                            ", gt=%.3f, err=%+.3f, margin=±%.3f, %s",
                            gt,
                            err,
                            margin,
                            if (pass) "PASS" else "FAIL"
                        )
                    } else {
                        visColor = Scalar(0.0, 0.0, 255.0)
                        visText = String.format(Locale.US, "%.3f", distMm)
                        logSuffix = ""
                    }

                    logs.add(
                        "[Film#${filmIdx}][M${m.name}] ${m.direction.uppercase(Locale.US)} = " +
                            "${String.format(Locale.US, "%.3f", distMm)} mm${logSuffix}"
                    )

                    // 시각화
                    if (GlobalParams.DEBUG) {
                        Imgproc.rectangle(
                            out,
                            Point(r1[0].toDouble(), r1[1].toDouble()),
                            Point(r1[2].toDouble(), r1[3].toDouble()),
                            visColor,
                            1
                        )
                        Imgproc.rectangle(
                            out,
                            Point(r2[0].toDouble(), r2[1].toDouble()),
                            Point(r2[2].toDouble(), r2[3].toDouble()),
                            visColor,
                            1
                        )
                    }
                    Imgproc.circle(out, Point(p1.first, p1.second), 3, visColor, -1)
                    Imgproc.circle(out, Point(p2.first, p2.second), 3, visColor, -1)
                    Imgproc.line(out, Point(p1.first, p1.second), Point(p2.first, p2.second), visColor, 2)

                    val mx = PyMath.roundHalfEvenInt((p1.first + p2.first) / 2.0)
                    val my = PyMath.roundHalfEvenInt((p1.second + p2.second) / 2.0)
                    Imgproc.putText(
                        out,
                        visText,
                        Point(mx.toDouble(), my.toDouble()),
                        Imgproc.FONT_HERSHEY_SIMPLEX,
                        fontScale,
                        visColor,
                        thick,
                        Imgproc.LINE_AA
                    )
                }
            }

            logs.add("[Ruler 기반 필름 전체 길이 측정] 완료 (${SystemClock.elapsedRealtime() - t0} ms)")

            val allPass = logs.none { line ->
                line.contains("FAIL", ignoreCase = true) || line.contains("NG", ignoreCase = true)
            }
            var calibrationToastMessage: String? = null
            if (warmupPhaseActive) {
                if (allPass) {
                    val warmupSampleNAfterRun = GridOnlineOffsetCalibrationStore.getMaxSampleN(base)
                    if (warmupSampleNAfterRun > warmupSampleNBeforeRun) {
                        calibrationToastMessage =
                            "Calibration ${warmupSampleNAfterRun.coerceAtMost(GridOnlineOffsetCalibrationStore.WARMUP_SAMPLES)}/${GridOnlineOffsetCalibrationStore.WARMUP_SAMPLES} 완료"
                    }
                } else {
                    warmupSnapshot?.let { GridOnlineOffsetCalibrationStore.restoreModel(it) }
                    calibrationToastMessage = "실패: Offset에 반영되지 않습니다."
                    logs.add("[Calibration] warm-up FAIL => rollback offset update / sample count")
                }
            }

            // ✅ (요구사항) Calibration ON일 때는 online으로 업데이트된 offset을
            //    모델 JSON(measure_*.offset)에 즉시 저장하여,
            //    앱 재시작/화면 전환 등에서도 값이 유지되게 합니다.
            if (calibrationEnabled && useOffset) {
                // ✅ 필름이 여러 개이면, measure_*.offset 이 "F1,F2,..." 형태로 저장되도록
                //    이번 run에서 검출된 필름 개수를 힌트로 전달합니다.
                GridOnlineOffsetCalibrationStore.persistOffsetsToModelJson(
                    context,
                    base,
                    filmCountHint = detsSorted.size
                )
            }

            // ✅ Preview용 ROI: YOLO 박스를 px 좌표로 변환해서 전달 (out == 원본 해상도 overlay)
            val imgW = out.cols().coerceAtLeast(1)
            val imgH = out.rows().coerceAtLeast(1)
            val detectedRectsPx: List<Rect> = dets.mapNotNull { d ->
                val r01 = d.rect

                val left = (r01.left * imgW).toInt().coerceIn(0, imgW - 1)
                val top = (r01.top * imgH).toInt().coerceIn(0, imgH - 1)
                val right = (r01.right * imgW).toInt().coerceIn(left + 1, imgW)
                val bottom = (r01.bottom * imgH).toInt().coerceIn(top + 1, imgH)

                val w = right - left
                val h = bottom - top
                if (w <= 1 || h <= 1) null else Rect(left, top, w, h)
            }

            return Result(
                overlay = out,
                logs = logs,
                detectedFilms = dets.size,
                pixelsPerMmH = pxPerMmH,
                pixelsPerMmV = pxPerMmV,
                detectedRectsPx = detectedRectsPx,
                calibrationToastMessage = calibrationToastMessage,
            )


        } finally {
            // out(Mat)은 반환하므로 release하면 안 됨
            runCatching { img.release() }
        }
    }


    private val MEASURE_CORNER_ROI_KEYS: List<String> = listOf(
        "roi_TL_H", "roi_TL_V",
        "roi_TR_H", "roi_TR_V",
        "roi_BR_H", "roi_BR_V",
        "roi_BL_H", "roi_BL_V"
    )

    /**
     * 치수 측정용 CV ROI 계산 (python: main_tflite_json_roi_v2.py 의 corner box 로직과 동일)
     *
     * - 입력: tflite로 검출된 raw film bbox
     * - 처리:
     *   1) raw bbox에 tflitePadPx 적용
     *   2) 8개 roi_xxx(roi_TL/TR/BR/BL_H/V)에서 점 검출
     *      - H=Horizontal=y, V=Vertical=x 규칙
     *   3) 코너 4점의 min/max bbox + cornerPadPx => 최종 CV ROI bbox
     *
     * @return CV ROI bbox(intArrayOf(x1,y1,x2,y2)) or null(코너 검출 실패/키 누락)
     */
    private fun computeCvRoiBBoxFromCornersOrNull(
        imgBgr: Mat,
        rawFilmBBox: IntArray,
        rois: Map<String, RoiCfg>,
        tflitePadPx: Int,
        cornerPadPx: Int
    ): IntArray? {
        if (rawFilmBBox.size < 4) return null

        val imgW = imgBgr.cols()
        val imgH = imgBgr.rows()
        if (imgW <= 0 || imgH <= 0) return null

        var x1 = rawFilmBBox[0]
        var y1 = rawFilmBBox[1]
        var x2 = rawFilmBBox[2]
        var y2 = rawFilmBBox[3]
        if (x2 <= x1 || y2 <= y1) return null

        val tpad = tflitePadPx.coerceAtLeast(0)
        val cpad = cornerPadPx.coerceAtLeast(0)

        // (1) tflite pad
        x1 = (x1 - tpad).coerceIn(0, imgW - 1)
        y1 = (y1 - tpad).coerceIn(0, imgH - 1)
        x2 = (x2 + tpad).coerceIn(x1 + 1, imgW)
        y2 = (y2 + tpad).coerceIn(y1 + 1, imgH)

        val paddedBBox = intArrayOf(x1, y1, x2, y2)
        val fw = x2 - x1
        val fh = y2 - y1
        if (fw <= 1 || fh <= 1) return null

        // (2) prerequisite
        if (!MEASURE_CORNER_ROI_KEYS.all { rois.containsKey(it) }) return null

        val filmCrop = imgBgr.submat(y1, y2, x1, x2)
        try {
            fun roiVal(key: String): Int? {
                val roiCfg = rois[key] ?: return null

                // filmRoiCfgToAbsPoint()가 throw할 수 있으므로 안전하게 감쌉니다.
                val p = runCatching { filmRoiCfgToAbsPoint(filmCrop, paddedBBox, roiCfg) }
                    .getOrNull()
                    ?.first
                    ?: return null

                val v = if (key.endsWith("_H")) p.second else p.first
                return PyMath.roundHalfEvenInt(v)
            }

            val tlH = roiVal("roi_TL_H")
            val tlV = roiVal("roi_TL_V")
            val trH = roiVal("roi_TR_H")
            val trV = roiVal("roi_TR_V")
            val brH = roiVal("roi_BR_H")
            val brV = roiVal("roi_BR_V")
            val blH = roiVal("roi_BL_H")
            val blV = roiVal("roi_BL_V")

            if (
                tlH == null || tlV == null ||
                trH == null || trV == null ||
                brH == null || brV == null ||
                blH == null || blV == null
            ) {
                return null
            }

            // corners: (x,y)=(*_V,*_H)
            val xs = listOf(tlV, trV, brV, blV)
            val ys = listOf(tlH, trH, brH, blH)

            var cx1 = xs.minOrNull()!! - cpad
            var cy1 = ys.minOrNull()!! - cpad
            // x2/y2 is end(exclusive)
            var cx2 = xs.maxOrNull()!! + cpad + 1
            var cy2 = ys.maxOrNull()!! + cpad + 1

            cx1 = cx1.coerceIn(0, imgW - 1)
            cy1 = cy1.coerceIn(0, imgH - 1)
            cx2 = cx2.coerceIn(cx1 + 1, imgW)
            cy2 = cy2.coerceIn(cy1 + 1, imgH)
            if (cx2 <= cx1 || cy2 <= cy1) return null

            return intArrayOf(cx1, cy1, cx2, cy2)
        } finally {
            filmCrop.release()
        }
    }

    private fun filmRoiCfgToAbsPoint(
        filmCrop: Mat,
        filmBBox: IntArray,
        roiCfg: RoiCfg,
    ): Pair<Pair<Double, Double>, IntArray> {
        val fx1 = filmBBox[0]
        val fy1 = filmBBox[1]
        val eh = filmCrop.rows()
        val ew = filmCrop.cols()

        var x = PyMath.roundHalfEvenInt(ew.toDouble() * roiCfg.x)
        var y = PyMath.roundHalfEvenInt(eh.toDouble() * roiCfg.y)
        var w = PyMath.roundHalfEvenInt(ew.toDouble() * roiCfg.w)
        var h = PyMath.roundHalfEvenInt(eh.toDouble() * roiCfg.h)

        x = x.coerceIn(0, ew - 1)
        y = y.coerceIn(0, eh - 1)
        w = w.coerceIn(1, ew - x)
        h = h.coerceIn(1, eh - y)

        val crop = filmCrop.submat(y, y + h, x, x + w).clone()
        val pt = FilmPointFinder.find(roiCfg.method, crop, roiCfg.parameter)
            ?: throw IllegalStateException("point detect failed: method=${roiCfg.method}")
        crop.release()

        // ✅ FilmPointFinder가 subpixel(float) 좌표를 반환하도록 변경됨
        //    -> ROI 절대 좌표도 정수 반올림 없이 그대로 누적하여 precision 유지
        val gx = fx1.toDouble() + x.toDouble() + pt.x
        val gy = fy1.toDouble() + y.toDouble() + pt.y

        val rx1 = fx1 + x
        val ry1 = fy1 + y
        val rx2 = fx1 + x + w
        val ry2 = fy1 + y + h

        return (gx to gy) to intArrayOf(rx1, ry1, rx2, ry2)
    }

    // --------------------------------------------------------------------------------------------
    // NEW(curve_points): ROI로부터 curve line 전체 points를 찾고, target y(mm)에 맞는 point를 선택
    // --------------------------------------------------------------------------------------------
    private fun isCurvePointsRoi(roiCfg: RoiCfg): Boolean {
        return roiCfg.method.trim().equals("curve_points", ignoreCase = true)
    }

    private fun filmRoiCfgToAbsCurvePoints(
        filmCrop: Mat,
        filmBBox: IntArray,
        roiCfg: RoiCfg,
    ): Pair<List<Pair<Double, Double>>, IntArray> {
        val fx1 = filmBBox[0]
        val fy1 = filmBBox[1]
        val eh = filmCrop.rows()
        val ew = filmCrop.cols()

        var x = PyMath.roundHalfEvenInt(ew.toDouble() * roiCfg.x)
        var y = PyMath.roundHalfEvenInt(eh.toDouble() * roiCfg.y)
        var w = PyMath.roundHalfEvenInt(ew.toDouble() * roiCfg.w)
        var h = PyMath.roundHalfEvenInt(eh.toDouble() * roiCfg.h)

        x = x.coerceIn(0, ew - 1)
        y = y.coerceIn(0, eh - 1)
        w = w.coerceIn(1, ew - x)
        h = h.coerceIn(1, eh - y)

        val crop = filmCrop.submat(y, y + h, x, x + w).clone()
        if (crop.empty()) {
            crop.release()
            throw IllegalStateException("curve_points ROI crop is empty")
        }

        val pts = try {
            FilmCurvePointFinder.findCurvePoints(crop, roiCfg.parameter)
        } finally {
            crop.release()
        }

        if (pts.isEmpty()) {
            throw IllegalStateException("curve_points: edge points not found")
        }

        val rx1 = fx1 + x
        val ry1 = fy1 + y
        val rx2 = fx1 + x + w
        val ry2 = fy1 + y + h

        val outPts = ArrayList<Pair<Double, Double>>(pts.size)
        for ((px, py) in pts) {
            val gx = fx1.toDouble() + x.toDouble() + px.toDouble()
            val gy = fy1.toDouble() + y.toDouble() + py.toDouble()
            if (gx.isFinite() && gy.isFinite()) {
                outPts.add(gx to gy)
            }
        }

        if (outPts.isEmpty()) {
            throw IllegalStateException("curve_points: all points became invalid")
        }

        return outPts to intArrayOf(rx1, ry1, rx2, ry2)
    }

    private fun selectBestCurvePointByTargetY_Ruler(
        curvePointsAbs: List<Pair<Double, Double>>,
        fixedPointAbs: Pair<Double, Double>,
        tickStructsH: DistanceUtils.TickStructs,
        pixelsPerMmH: Double,
        tickStructsV: DistanceUtils.TickStructs,
        pixelsPerMmV: Double,
        targetYMm: Double,
    ): BestCurvePick {
        if (curvePointsAbs.isEmpty()) throw IllegalStateException("curve_points_abs is empty")

        var bestPt: Pair<Double, Double>? = null
        var bestDx = 0.0
        var bestDy = 0.0
        var bestErr = Double.POSITIVE_INFINITY

        for ((gx, gy) in curvePointsAbs) {
            val dyMm = try {
                DistanceUtils.computeVerticalDistanceMm(gy, fixedPointAbs.second, tickStructsV, pixelsPerMmV)
            } catch (_: Exception) {
                continue
            }

            val err = abs(dyMm - targetYMm)
            if (err >= bestErr) continue

            val dxMm = try {
                DistanceUtils.computeHorizontalDistanceMm(gx, fixedPointAbs.first, tickStructsH, pixelsPerMmH)
            } catch (_: Exception) {
                continue
            }

            bestPt = gx to gy
            bestDx = dxMm
            bestDy = dyMm
            bestErr = err
        }

        val outPt = bestPt ?: throw IllegalStateException("curve_points: no valid candidate (ruler)")
        return BestCurvePick(outPt, bestDx, bestDy, bestErr)
    }

    /**
     * ✅ 이미지 회전 정책(안정성/재현성 우선):
     * 1) OpenCV imread 단계에서는 (가능하면) EXIF 자동 회전을 무시
     * 2) EXIF ORIENTATION 값을 읽어 적용하지 않습니다.
     * 3) 세로(h>w)인 경우에만 "항상 90° CCW"로 1회 회전하여 landscape로 만듭니다.
     */
    private fun loadBgrMatFromUri(
        context: Context,
        uri: Uri,
        forceLandscape: Boolean = true
    ): Mat {
        val t0 = SystemClock.elapsedRealtime()
        AirulerFileLogger.i(TAG, "loadBgrMatFromUri start uri=$uri")
        val tmp = File.createTempFile("airuler_capture_", ".jpg", context.cacheDir)

        val tCopy0 = SystemClock.elapsedRealtime()
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out ->
                // ✅ (성능) 기본 copyTo(8KB) 대신 큰 버퍼로 복사
                input.copyTo(out, bufferSize = 1024 * 1024)
                out.flush()
            }
        } ?: throw IllegalStateException("Cannot open uri: $uri")

        val tCopy1 = SystemClock.elapsedRealtime()
        val copiedBytes = runCatching { tmp.length() }.getOrDefault(-1L)
        AirulerFileLogger.i(TAG, "loadBgrMatFromUri copy done dtMs=${tCopy1 - tCopy0} bytes=$copiedBytes")

        try {
            // ✅ OpenCV imread가 EXIF를 자동 적용하는 빌드가 있어, 가능하면 무시 플래그를 켠다.
            val ignoreOriFlag = IMREAD_IGNORE_ORIENTATION_FLAG
            val flags = Imgcodecs.IMREAD_COLOR or ignoreOriFlag

            val tRead0 = SystemClock.elapsedRealtime()
            var img = Imgcodecs.imread(tmp.absolutePath, flags)
            val tRead1 = SystemClock.elapsedRealtime()
            if (img.empty()) throw IllegalStateException("OpenCV imread failed: $uri")

            AirulerFileLogger.i(
                TAG,
                "loadBgrMatFromUri imread done dtMs=${tRead1 - tRead0} size=${img.cols()}x${img.rows()} totalMs=${tRead1 - t0}"
            )

            val rawW = img.cols()
            val rawH = img.rows()

            if (DEBUG_LOG) {
                Log.w(
                    TAG,
                    "[loadBgrMatFromUri] imread=${rawW}x${rawH}, flags=$flags(ignoreFlag=$ignoreOriFlag), exifIgnored=${ignoreOriFlag != 0}"
                )
            }

            // 2) forceLandscape: portrait이면 90° CCW로 1회 회전만 적용
            //    (EXIF/엣지 기반 판정 제거)
            if (forceLandscape && img.rows() > img.cols()) {
                val rotated = Mat()
                Core.rotate(img, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                img.release()
                img = rotated
                if (DEBUG_LOG) {
                    Log.w(TAG, "[loadBgrMatFromUri] after forceLandscape(CCW-90) -> ${img.cols()}x${img.rows()}")
                }
            }

            // -----------------------------------------------------------------
            // ✅ 200MP Expert RAW (4:3) -> 16:9 pre-crop
            //
            // - Expert RAW는 200MP를 4:3 해상도(예: 16320x12240)로 저장합니다.
            // - AIRuler의 tflite/측정 파이프라인은 16:9(50MP: 8160x4592) 기준으로
            //   모델링/파라미터가 잡혀 있으므로, 200MP 모드에서는 Expert RAW 이미지를
            //   내부적으로 먼저 위/아래 동일 크기로 crop 하여 16:9로 맞춘 뒤
            //   이후 추론/치수 측정을 진행합니다.
            //
            // 요구사항(고정 수치):
            // - 원본: 16320 x 12240 (4:3)
            // - 목표: 16320 x 9184  (16:9 기준, 50MP(4592) x 2)
            // - crop: (12240-9184)=3056 => top 1528, bottom 1528
            //
            // 안전장치:
            // - 200MP 모드일 때만 적용
            // - 폭이 50MP 기준(8160) * scale(2)와 동일한 경우에만 crop
            // - 이미 16:9(또는 목표 높이 이하)면 그대로 둠
            // -----------------------------------------------------------------
            if (GlobalParams.CAPTURE_MP >= 200) {
                val w = img.cols()
                val h = img.rows()

                // 50MP(8160x4592) 기준을 scale(200MP=2)로 확장
                val targetW = 8160 * GlobalParams.CAPTURE_SCALE
                val targetH = 4592 * GlobalParams.CAPTURE_SCALE

                if (w == targetW && h > targetH) {
                    val dy = h - targetH
                    val top = (dy / 2).coerceAtLeast(0)
                    val bottom = (dy - top).coerceAtLeast(0)
                    val y1 = top
                    val y2 = (h - bottom).coerceAtMost(h)

                    if (y2 > y1 && (y2 - y1) == targetH) {
                        val roi = img.submat(y1, y2, 0, w)
                        val cropped = roi.clone()
                        roi.release()
                        img.release()
                        img = cropped

                        if (DEBUG_LOG) {
                            Log.w(
                                TAG,
                                "[loadBgrMatFromUri] Expert RAW crop: ${w}x${h} -> ${img.cols()}x${img.rows()} (top=$top, bottom=$bottom)"
                            )
                        }
                    } else if (DEBUG_LOG) {
                        Log.w(TAG, "[loadBgrMatFromUri] Expert RAW crop skipped: w=$w, h=$h, target=${targetW}x${targetH}, roiH=${y2 - y1}")
                    }
                }
            }

            return img
        } finally {
            runCatching { tmp.delete() }
        }
    }



// NOTE: EXIF 기반 회전/반전 로직은 정책 변경으로 더 이상 사용하지 않습니다.
    //       필요하면 과거 커밋(또는 patch)에서 복구할 수 있습니다.
}
