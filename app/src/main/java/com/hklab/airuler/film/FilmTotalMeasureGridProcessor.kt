package com.hklab.airuler.film

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.hklab.airuler.log.AirulerFileLogger
import com.hklab.airuler.film.ruler.PyMath
import com.hklab.airuler.grid.GridWarpCache
import com.hklab.airuler.grid.GridWarpPiecewiseAffine
import com.hklab.airuler.model.ModelFileStore
import com.hklab.airuler.yolo.AirulerYoloClasses
import com.hklab.airuler.yolo.YoloDetection
import com.hklab.airuler.yolo.YoloDetector
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.hklab.airuler.GlobalParams
import com.hklab.airuler.calibration.GridOnlineOffsetCalibrationStore
import com.hklab.airuler.pipeline.state.AppSessionSettings
import com.hklab.airuler.model.ModelNameCompat

/**
 * Grid 기반 "필름 전체 길이 측정" (Python: main_ruler_selected_2points_GUI_v15 의
 * "Grid 기반 필름 전체 길이 측정" 버튼 동작을 최대한 동일하게 포팅)
 *
 * 전제:
 * - internal/models/Grid.json 이 존재해야 함 (Grid 분석 기능에서 생성)
 * - 필름 모델(.tflite/.json)은 기존과 동일
 */
object FilmTotalMeasureGridProcessor {

    private const val TAG = "FilmTotalMeasureGrid"

    // ✅ (성능) Grid 측정은 “촬영→복귀→측정” 흐름에서 연속 호출될 수 있습니다.
    // 매 run마다 YoloDetector(=TFLite Interpreter)를 생성/close 하면
    //  - 모델 로딩/Interpreter 초기화 비용
    //  - detect() 내부 reusable buffer 재할당
    // 이 반복되어 첫 run이 특히 느려지고, GC 압박도 증가합니다.
    //
    // 측정은 MeasurementPipeline의 단일 스레드(executor)에서 순차 실행되는 구조이므로,
    // 여기서는 “단일 인스턴스 캐시”가 안전하고 효과적입니다.
    private val yoloCacheLock = Any()
    @Volatile private var cachedYolo: YoloDetector? = null
    @Volatile private var cachedYoloKey: String? = null

    private fun getOrCreateYolo(context: Context, modelPath: String): YoloDetector {
        val key = "$modelPath|threads=4|labels=film,good,bad"
        synchronized(yoloCacheLock) {
            val cur = cachedYolo
            if (cur != null && cachedYoloKey == key) return cur

            // 모델이 바뀌었거나, 캐시가 없다면 교체
            runCatching { cur?.close() }

            val y = YoloDetector(
                context.applicationContext,
                modelPath,
                4,
                classLabels = arrayOf("film", "good", "bad")
            )
            cachedYolo = y
            cachedYoloKey = key
            return y
        }
    }

    /**
     * ✅ (성능) 1회 warm-up
     * - 첫 측정에서 tflite init/JIT/페이지 로드로 인해 dt가 튀는 현상을 줄이기 위함
     * - 결과/로직에는 영향을 주지 않으며, 실패해도 앱 동작에 영향이 없어야 합니다.
     */
    fun prewarm(context: Context, modelName: String) {
        val appCtx = context.applicationContext
        runCatching {
            val t0 = SystemClock.elapsedRealtime()

            // Grid warp도 미리 로드(없어도 실패로 치지 않음)
            runCatching { GridWarpCache.getOrLoad(appCtx) }

            val baseModel = ModelNameCompat.canonical(modelName)
            val tfliteFile = ModelFileStore.downloadedModelFile(appCtx, baseModel)
            if (!tfliteFile.exists()) return

            val yolo = getOrCreateYolo(appCtx, tfliteFile.absolutePath)
            val (inW, inH) = yolo.inputSizeWh()

            val dummy = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
            try {
                // 높은 threshold로 후보를 최소화(의미 없는 warmup이지만 런타임 초기화는 유도)
                yolo.detect(dummy, scoreThresh = 0.99f, iouThresh = 0.5f)
            } finally {
                runCatching { dummy.recycle() }
            }

            val t1 = SystemClock.elapsedRealtime()
            AirulerFileLogger.i(TAG, "prewarm done model='$baseModel' dtMs=${t1 - t0} yoloIn=${inW}x${inH}")
        }.onFailure { e ->
            AirulerFileLogger.w(TAG, "prewarm skipped: ${e.javaClass.simpleName}:${e.message}")
        }
    }

    /** 앱 종료/파이프라인 해제 시 캐시 정리(선택) */
    fun releaseCachedResources() {
        synchronized(yoloCacheLock) {
            runCatching { cachedYolo?.close() }
            cachedYolo = null
            cachedYoloKey = null
        }
    }

    // ✅ (성능) OpenCV 상수 reflection(필드 조회)은 1회만 수행
    // - Grid 측정에서도 대용량 이미지 로딩이 반복되므로, 매번 reflection을 하면 불필요한 오버헤드가 됩니다.
    private val IMREAD_IGNORE_ORIENTATION_FLAG: Int by lazy {
        runCatching {
            Imgcodecs::class.java.getField("IMREAD_IGNORE_ORIENTATION").getInt(null)
        }.getOrDefault(0)
    }

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
        val rawFilmBBox: IntArray,
        val usedFilmBBox: IntArray,
        val calcs: List<MeasureCalc>,
        val hasOutlier: Boolean,
    )

    private data class CornerRoiDebugItem(
        val key: String,
        val roiRectAbs: IntArray,
        val pointAbs: Pair<Double, Double>?,
    )

    private data class CornerCompositeDebugPoint(
        val name: String,
        val pointAbs: Pair<Double, Double>,
    )

    private data class CornerDebugOverlayData(
        val roiItems: List<CornerRoiDebugItem>,
        val cornerPoints: List<CornerCompositeDebugPoint>,
    )



    data class Result(
        val overlay: Mat,
        val logs: List<String>,
        val detectedFilms: Int,
        val detectedRectsPx: List<Rect>,
        val gridJsonPath: String,
        val gridPitchMm: Double,
        val calibrationToastMessage: String? = null,
    )

    fun run(
        context: Context,
        imageUri: Uri,
        modelName: String,
        scoreThresh: Float = GlobalParams.SCORE_THRESH,
        iouThresh: Float = GlobalParams.IOU_THRESH
    ): Result {
        val t0 = SystemClock.elapsedRealtime()

        val logs = mutableListOf<String>()
        logs.add("[Grid 기반 필름 전체 길이 측정] 시작")

        // 1) Grid.json 로드/warp 구축
        val gridJsonFile = ModelFileStore.downloadedModelJsonFile(context, "Grid")
        if (!gridJsonFile.exists()) {
            throw IllegalStateException("Grid.json not found in internal/models. Run 'Grid 분석' first.")
        }

        val warp = GridWarpCache.getOrLoad(context)
            ?: throw IllegalStateException("Failed to load Grid warp (Grid.json parse/build error).")

        logs.add(
            String.format(
                Locale.US,
                "[Grid 기반 필름 전체 길이 측정] Grid.json=%s, pitch=%.3fmm",
                gridJsonFile.absolutePath,
                warp.pitchMm
            )
        )

        if (GlobalParams.DEBUG) {
            Log.i(
                TAG,
                "[DBG][grid] Grid.json=${gridJsonFile.absolutePath}, pitch=${String.format(Locale.US, "%.3f", warp.pitchMm)}mm, points=${warp.numPoints}, tris=${warp.numTriangles}"
            )
        }

        // 2) 모델 파일/설정 로드
        val baseModel = ModelNameCompat.canonical(modelName)

        // ✅ calibration ON/OFF는 한 번의 측정(run) 동안에는 고정된 스냅샷으로 사용
        //    (측정 중 토글이 바뀌어도 이 run은 일관된 동작을 유지)
        val calibrationEnabled = AppSessionSettings.gridCalibrationEnabled

        // ✅ 2026-02-10 요청사항
        // - 50MP 측정: adjust + offset 둘 다 적용
        // - 200MP 측정: offset은 적용하지 않고 adjust만 적용
        val useOffset = (GlobalParams.CAPTURE_MP != 200)

        if (GlobalParams.DEBUG) {
            Log.i(
                TAG,
                "[DBG][start] modelBase=$baseModel, CAPTURE_MP=${GlobalParams.CAPTURE_MP}, CAPTURE_SCALE=${GlobalParams.CAPTURE_SCALE}, calibrationEnabled=$calibrationEnabled, useOffset=$useOffset, scoreThresh=$scoreThresh, iouThresh=$iouThresh"
            )
        }

        val tfliteFile = ModelFileStore.downloadedModelFile(context, baseModel)
        if (!tfliteFile.exists()) {
            throw IllegalStateException("Model not found: ${tfliteFile.absolutePath}")
        }

        val jsonCfg = FilmModelConfigLoader.loadFromInternalModels(context, baseModel)
            ?: throw IllegalStateException("Film model json not found: $baseModel.json")

        // 3) 이미지 로드 (Samsung 원본: 세로가 더 길면 항상 CCW 90 회전)
        val img = loadBgrMatFromUri(context, imageUri, forceLandscape = true)

        try {
            if (img.empty()) throw IllegalStateException("Image decode failed")

            val H = img.rows()
            val W = img.cols()

            logs.add(String.format(Locale.US, "[Grid 기반 필름 전체 길이 측정] image=%dx%d", W, H))

            // 4) YOLO(필름 ROI) 검출
            val tYolo0 = SystemClock.elapsedRealtime()
            val yolo = getOrCreateYolo(context, tfliteFile.absolutePath)
            val (inW, inH) = yolo.inputSizeWh()
            val tYolo1 = SystemClock.elapsedRealtime()

            val yoloBmp = createYoloInputBitmapFromBgr(img, targetW = inW, targetH = inH)
                ?: throw IllegalStateException("Failed to build yolo input bitmap")

            val tYolo2 = SystemClock.elapsedRealtime()
            val dets = try {
                yolo.detect(
                    bitmap = yoloBmp,
                    scoreThresh = scoreThresh,
                    iouThresh = iouThresh
                )
            } catch (e: Throwable) {
                // ✅ 드물게 TFLite 내부 오류/상태 꼬임이 발생하면 캐시를 버리고 재생성하도록 유도
                AirulerFileLogger.e(TAG, "yolo.detect failed -> drop cache", e)
                releaseCachedResources()
                throw e
            } finally {
                // ✅ 예외가 나더라도 입력 Bitmap은 반드시 정리
                runCatching { yoloBmp.recycle() }
            }

            val tYolo3 = SystemClock.elapsedRealtime()
            AirulerFileLogger.d(
                TAG,
                "yolo stage dtMs getOrCreate=${tYolo1 - tYolo0} buildBmp=${tYolo2 - tYolo1} detect=${tYolo3 - tYolo2}"
            )

            // (2026-02) YOLO 모델이 good/bad도 함께 출력할 수 있으므로,
            // "필름 ROI"로 사용할 detection은 film/flim class만 필터링합니다.
            val filmDets = AirulerYoloClasses.filterFilms(dets)

            // ✅ YOLO 입력 Bitmap은 detect() finally에서 정리됨

            if (filmDets.isEmpty()) {
                logs.add("[Grid 기반 필름 전체 길이 측정] 필름 ROI 검출 실패 (FAIL)")

                if (GlobalParams.DEBUG) {
                    Log.w(TAG, "[DBG][yolo] no detections. image=${W}x${H}, in=${inW}x${inH}")
                }

                val out = img.clone()
                Imgproc.putText(
                    out,
                    "NO FILM DETECTIONS",
                    Point(30.0, 60.0),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    1.5,
                    Scalar(0.0, 0.0, 255.0),
                    3,
                    Imgproc.LINE_AA
                )

                return Result(
                    overlay = out,
                    logs = logs,
                    detectedFilms = 0,
                    detectedRectsPx = emptyList(),
                    gridJsonPath = gridJsonFile.absolutePath,
                    gridPitchMm = warp.pitchMm
                )
            }

            logs.add(String.format(Locale.US, "[Grid 기반 필름 전체 길이 측정] detections=%d", filmDets.size))

            // 출력 오버레이는 원본 해상도 (img clone)
            val out = img.clone()

            val rois = jsonCfg.rois
            val measures = jsonCfg.measures

            // 보기 좋게 정렬 (위->아래, 좌->우)
            val detsSorted = filmDets.sortedWith(compareBy<YoloDetection> { it.rect.top }.thenBy { it.rect.left })

            val fontScale = 1.2
            val thick = 2

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

                val xyxy = toPixelXYXYHalfEvenClampWMinus1(d, W, H)
                var fx1 = xyxy[0]
                var fy1 = xyxy[1]
                var fx2 = xyxy[2]
                var fy2 = xyxy[3]

                // python과 동일하게: slicing end 는 exclusive
                // 안전 clamp (0..W/H)
                fx1 = fx1.coerceIn(0, W - 1)
                fy1 = fy1.coerceIn(0, H - 1)
                fx2 = fx2.coerceIn(0, W)
                fy2 = fy2.coerceIn(0, H)

                if (fx2 <= fx1 || fy2 <= fy1) continue

                val rawFilmBBox = intArrayOf(fx1, fy1, fx2, fy2)

                // ✅ 치수 측정시: tflite film bbox -> 8개 roi_xxx 기반 CV ROI로 한 번 더 정교하게 crop
                //    (corner pad 기본값 10px: GlobalParams.MEASURE_CORNER_PAD_PX)
                val cvFilmBBox = computeCvRoiBBoxFromCornersOrNull(
                    imgBgr = img,
                    rawFilmBBox = rawFilmBBox,
                    rois = rois,
                    tflitePadPx = GlobalParams.MEASURE_TFLITE_PAD_PX,
                    cornerPadPx = GlobalParams.MEASURE_CORNER_PAD_PX
                )

                // ✅ (버그 방어)
                // Grid 기반 측정에서는 필름이 Grid.json의 convex hull 범위 밖에 있거나
                // 코너 ROI 포인트 검출이 실패하면 예외로 인해 결과 이미지/저장이 누락될 수 있습니다.
                // - 기존: 예외 throw → MeasurementPipeline catch → 결과 이미지 저장/전송 없음
                // - 변경: 가능한 범위 내에서 계속 진행하되, FAIL 로그를 남기고 결과 이미지는 생성합니다.
                //         (정상 케이스 결과/로직에는 영향 없음)
                var filmHardError = false

                val usedFilmBBox = if (cvFilmBBox != null) {
                    cvFilmBBox
                } else {
                    filmHardError = true
                    logs.add(
                        "[Film#${filmIndex}] CV ROI(corners) failed → fallback to padded tflite bbox (FAIL)"
                    )
                    if (GlobalParams.DEBUG) {
                        Log.w(TAG, "[DBG][Film#${filmIndex}] CV ROI(corners) failed. rawBBox=${rawFilmBBox.contentToString()}")
                    }

                    // fallback: tflite bbox + pad (python corner_box_pad 유사)
                    val pad = GlobalParams.MEASURE_TFLITE_PAD_PX.coerceAtLeast(0)
                    var ux1 = (rawFilmBBox[0] - pad).coerceIn(0, W - 1)
                    var uy1 = (rawFilmBBox[1] - pad).coerceIn(0, H - 1)
                    var ux2 = (rawFilmBBox[2] + pad).coerceIn(ux1 + 1, W)
                    var uy2 = (rawFilmBBox[3] + pad).coerceIn(uy1 + 1, H)
                    intArrayOf(ux1, uy1, ux2, uy2)
                }

                if (GlobalParams.DEBUG) {
                    Log.i(
                        TAG,
                        "[DBG][Film#${filmIndex}] rawBBox=${rawFilmBBox.contentToString()}, usedBBox=${usedFilmBBox.contentToString()}, cornerOK=${cvFilmBBox != null}"
                    )
                }

                val filmCrop = img.submat(
                    usedFilmBBox[1],
                    usedFilmBBox[3],
                    usedFilmBBox[0],
                    usedFilmBBox[2]
                ).clone()

                try {
                    Imgproc.putText(
                        out,
                        if (cvFilmBBox == null) "Film#${filmIndex}(CV?)" else "Film#${filmIndex}",
                        Point((fx1 + 5).toDouble(), (fy1 + 25).toDouble()),
                        Imgproc.FONT_HERSHEY_SIMPLEX,
                        fontScale,
                        Scalar(0.0, 255.0, 255.0),
                        thick,
                        Imgproc.LINE_AA
                    )

                    // 1st pass: measure별 raw(mm) 및 포인트/ROI를 먼저 계산해 저장
                    // - 이후 filmHasOutlier를 계산해 filmBundles에 저장 (이미지(run) 단위 update gating용)
                    val calcs = mutableListOf<MeasureCalc>()

                    var measureErrorCount = 0

                    for (m in measures) {
                        val startRoi = rois[m.startKey]
                        val endRoi = rois[m.endKey]
                        if (startRoi == null || endRoi == null) {
                            if (GlobalParams.DEBUG) {
                                Log.w(TAG, "[DBG][Film#${filmIndex}][M${m.name}] missing ROI key: start=${m.startKey}, end=${m.endKey}")
                            }
                            continue
                        }

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
                                val (curvePts, rr1) = filmRoiCfgToAbsCurvePoints(filmCrop, usedFilmBBox, startRoi)
                                val (fixedPt, rr2) = filmRoiCfgToAbsPoint(filmCrop, usedFilmBBox, endRoi)

                                val best = selectBestCurvePointByTargetY_Grid(
                                    curvePointsAbs = curvePts,
                                    fixedPointAbs = fixedPt,
                                    warp = warp,
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
                                val (fixedPt, rr1) = filmRoiCfgToAbsPoint(filmCrop, usedFilmBBox, startRoi)
                                val (curvePts, rr2) = filmRoiCfgToAbsCurvePoints(filmCrop, usedFilmBBox, endRoi)

                                val best = selectBestCurvePointByTargetY_Grid(
                                    curvePointsAbs = curvePts,
                                    fixedPointAbs = fixedPt,
                                    warp = warp,
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
                                measureErrorCount++
                                filmHardError = true
                                logs.add("[Film#${filmIndex}][M${m.name}] curve_points failed: ${e.message}")
                                if (GlobalParams.DEBUG) {
                                    Log.e(TAG, "[DBG][Film#${filmIndex}][M${m.name}] curve_points failed", e)
                                }
                                continue
                            }
                        } else {
                            try {
                                val (pp1, rr1) = filmRoiCfgToAbsPoint(filmCrop, usedFilmBBox, startRoi)
                                val (pp2, rr2) = filmRoiCfgToAbsPoint(filmCrop, usedFilmBBox, endRoi)
                                p1 = pp1
                                p2 = pp2
                                r1 = rr1
                                r2 = rr2

                                val mm1 = safePixelToMmOrNull(warp, p1.first, p1.second)
                                val mm2 = safePixelToMmOrNull(warp, p2.first, p2.second)
                                if (mm1 == null || mm2 == null) {
                                    measureErrorCount++
                                    filmHardError = true
                                    logs.add(
                                        "[Film#${filmIndex}][M${m.name}] warp(pixelToMm) failed: outside convex hull (p1=${String.format(Locale.US, "%.1f,%.1f", p1.first, p1.second)}, p2=${String.format(Locale.US, "%.1f,%.1f", p2.first, p2.second)})"
                                    )
                                    continue
                                }

                                val dx = mm2.first - mm1.first
                                val dy = mm2.second - mm1.second

                                distMmRaw = when (m.direction.lowercase(Locale.US)) {
                                    "x" -> abs(dx)
                                    "y" -> abs(dy)
                                    else -> hypot(dx, dy)
                                }

                                if (GlobalParams.DEBUG) {
                                    Log.i(
                                        TAG,
                                        String.format(
                                            Locale.US,
                                            "[DBG][Film#%d][M%s] p1=(%.1f,%.1f) p2=(%.1f,%.1f) mm1=(%.3f,%.3f) mm2=(%.3f,%.3f) raw=%.4f dir=%s",
                                            filmIndex,
                                            m.name,
                                            p1.first,
                                            p1.second,
                                            p2.first,
                                            p2.second,
                                            mm1.first,
                                            mm1.second,
                                            mm2.first,
                                            mm2.second,
                                            distMmRaw,
                                            m.direction
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                measureErrorCount++
                                filmHardError = true
                                logs.add("[Film#${filmIndex}][M${m.name}] point/warp failed: ${e.message}")
                                if (GlobalParams.DEBUG) {
                                    Log.e(TAG, "[DBG][Film#${filmIndex}][M${m.name}] point/warp failed", e)
                                }
                                continue
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

                    if (calcs.isEmpty()) {
                        filmHardError = true
                        logs.add("[Film#${filmIndex}] no valid measures computed (FAIL)")
                    }

                    // 1st pass: 이 필름의 outlier 여부를 계산해 저장
                    val filmHasOutlier = if (filmHardError) {
                        true
                    } else if (calibrationEnabled && useOffset && calcs.isNotEmpty()) {
                    val eps = 1e-6
                    val extra = com.hklab.airuler.calibration.OffsetCalibratorConfig.DEFAULT_MARGIN_EXTRA
                    var hasOutlier = false
                    for (c in calcs) {
                        val gt = c.m.gt
                        val margin = c.m.margin
                        if (gt == null || margin == null || margin.isNaN()) continue

                        val off0 = GridOnlineOffsetCalibrationStore.getCurrentOffset(
                            modelBase = baseModel,
                            measureName = c.m.name,
                            filmIndex = filmIndex,
                            offsetFieldInJson = c.m.offset,
                        )
                        // ✅ (요구사항) adjust + offset 둘 다 적용한 값으로 outlier 판정
                        val adj0 = c.m.adjustForFilm(filmIndex)
                        val comp0 = c.rawMm + adj0 + off0
                        // inlier 판정 완화: |comp0-GT| <= margin + extra
                        val inlier = abs(comp0 - gt) <= (margin + extra + eps)
                        if (!inlier) {
                            hasOutlier = true
                            break
                        }
                    }
                    hasOutlier
                } else {
                    false
                }

                    if (GlobalParams.DEBUG) {
                        Log.i(
                            TAG,
                            "[DBG][Film#${filmIndex}] measures=${calcs.size}, measureErrors=$measureErrorCount, hardError=$filmHardError, outlier=$filmHasOutlier"
                        )
                    }

                    filmBundles.add(
                        FilmCalcBundle(
                            filmIndex = filmIndex,
                            rawFilmBBox = rawFilmBBox.copyOf(),
                            usedFilmBBox = usedFilmBBox.copyOf(),
                            calcs = calcs,
                            hasOutlier = filmHasOutlier,
                        )
                    )
                } finally {
                    filmCrop.release()
                }
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
                GridOnlineOffsetCalibrationStore.getMaxSampleN(baseModel)
            } else 0
            val warmupPhaseActive = calibrationEnabled && useOffset &&
                warmupSampleNBeforeRun < GridOnlineOffsetCalibrationStore.WARMUP_SAMPLES
            val warmupSnapshot = if (warmupPhaseActive) {
                GridOnlineOffsetCalibrationStore.snapshotModel(baseModel)
            } else null

            // 2nd pass: 보정(applyAndUpdate) + 시각화/로그
            for (fb in filmBundles) {
                val filmIdx = fb.filmIndex
                val rawFilmBBox = fb.rawFilmBBox
                val usedFilmBBox = fb.usedFilmBBox

                for (c in fb.calcs) {
                    val m = c.m
                    val distMmRaw = c.rawMm
                    val p1 = c.p1
                    val p2 = c.p2
                    val r1 = c.r1
                    val r2 = c.r2

                    // ✅ (요구사항 - 2026-02)
                    // - measure별/film별 adjust + offset을 적용합니다.
                    // - compensated = (raw + adjust) + offset
                    //
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
                            modelBase = baseModel,
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
                        val eps = 1e-6
                        val err = distMm - gt!!
                        val pass = abs(err) <= margin!! + eps
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
                        "[Film#${filmIdx}][M${m.name}] " +
                                "${m.direction.uppercase(Locale.US)} = ${String.format(Locale.US, "%.3f", distMm)} mm" +
                                logSuffix
                    )

                    if (GlobalParams.DEBUG) {
                        Log.i(
                            TAG,
                            "[DBG][Film#${filmIdx}][M${m.name}] ${m.direction.uppercase(Locale.US)}=${String.format(Locale.US, "%.3f", distMm)}mm (raw=${String.format(Locale.US, "%.3f", distMmRaw)}, adj=${String.format(Locale.US, "%.3f", adjustMm)})" +
                                    if (useOffset) " + offset" else ""
                        )
                    }

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

                if (GlobalParams.DEBUG) {
                    runCatching {
                        val cornerData = buildCornerDebugOverlayDataOrNull(
                            imgBgr = img,
                            rawFilmBBox = rawFilmBBox,
                            rois = rois,
                            tflitePadPx = GlobalParams.MEASURE_TFLITE_PAD_PX,
                        )
                        if (cornerData != null) {
                            drawMeasureCornerDebugOverlay(
                                overlay = out,
                                filmIndex = filmIdx,
                                rawFilmBBox = rawFilmBBox,
                                usedFilmBBox = usedFilmBBox,
                                cornerData = cornerData,
                            )
                        }
                    }.onFailure { e ->
                        if (GlobalParams.DEBUG) {
                            Log.w(TAG, "[DBG][Film#${filmIdx}] measure corner debug overlay failed: ${e.message}", e)
                        }
                    }
                }
            }

            logs.add("[Grid 기반 필름 전체 길이 측정] 완료 (${SystemClock.elapsedRealtime() - t0} ms)")

            val allPass = logs.none { line ->
                line.contains("FAIL", ignoreCase = true) || line.contains("NG", ignoreCase = true)
            }
            var calibrationToastMessage: String? = null
            if (warmupPhaseActive) {
                if (allPass) {
                    val warmupSampleNAfterRun = GridOnlineOffsetCalibrationStore.getMaxSampleN(baseModel)
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
            //    화면 전환/Activity 재생성 등에서도 값이 날아가지 않게 합니다.
            if (calibrationEnabled && useOffset) {
                // ✅ 필름이 여러 개이면, measure_*.offset 이 "F1,F2,..." 형태로 저장되도록
                //    이번 run에서 검출된 필름 개수를 힌트로 전달합니다.
                runCatching {
                    GridOnlineOffsetCalibrationStore.persistOffsetsToModelJson(
                        context,
                        baseModel,
                        filmCountHint = detsSorted.size
                    )
                }.onFailure { e ->
                    // 결과 판정(PASS/FAIL)에는 영향 주지 않도록 logs에는 FAIL 문자열을 넣지 않음
                    logs.add("[Grid] offset persist error: ${e.message}")
                    if (GlobalParams.DEBUG) {
                        Log.e(TAG, "[DBG] persistOffsetsToModelJson error", e)
                    }
                }
            }

            // Preview용 ROI 영역(박스)
            val detectedRectsPx: List<Rect> = detsSorted.mapNotNull { d ->
                val xyxy = toPixelXYXYHalfEvenClampWMinus1(d, W, H)
                val x1 = xyxy[0].coerceIn(0, W - 1)
                val y1 = xyxy[1].coerceIn(0, H - 1)
                val x2 = xyxy[2].coerceIn(x1 + 1, W)
                val y2 = xyxy[3].coerceIn(y1 + 1, H)
                val w = x2 - x1
                val h = y2 - y1
                if (w <= 1 || h <= 1) null else Rect(x1, y1, w, h)
            }

            return Result(
                overlay = out,
                logs = logs,
                detectedFilms = detsSorted.size,
                detectedRectsPx = detectedRectsPx,
                gridJsonPath = gridJsonFile.absolutePath,
                gridPitchMm = warp.pitchMm,
                calibrationToastMessage = calibrationToastMessage,
            )
        } finally {
            // out(Mat)은 반환하므로 release하면 안 됨
            runCatching { img.release() }
        }
    }

    /**
     * Python의 to_pixel_xyxy(int(round(...))) + clamp(0..W-1/H-1) 동작을 최대한 동일하게.
     */
    private fun toPixelXYXYHalfEvenClampWMinus1(d: YoloDetection, imgW: Int, imgH: Int): IntArray {
        var x1 = PyMath.roundHalfEvenInt(d.rect.left.toDouble() * imgW.toDouble())
        var y1 = PyMath.roundHalfEvenInt(d.rect.top.toDouble() * imgH.toDouble())
        var x2 = PyMath.roundHalfEvenInt(d.rect.right.toDouble() * imgW.toDouble())
        var y2 = PyMath.roundHalfEvenInt(d.rect.bottom.toDouble() * imgH.toDouble())

        x1 = x1.coerceIn(0, imgW - 1)
        y1 = y1.coerceIn(0, imgH - 1)
        x2 = x2.coerceIn(0, imgW - 1)
        y2 = y2.coerceIn(0, imgH - 1)

        return intArrayOf(x1, y1, x2, y2)
    }

    /**
     * Grid warp 변환은 Grid.json의 convex hull 밖의 점에 대해 IllegalArgumentException을 던집니다.
     * Grid 기반 측정에서 필름이 가이드 위치 밖에 있을 때 종종 발생하므로,
     * "측정 전체를 중단"하지 않고 "해당 measure만 실패 처리"할 수 있도록 safe wrapper를 둡니다.
     */
    private fun safePixelToMmOrNull(
        warp: GridWarpPiecewiseAffine,
        px: Double,
        py: Double,
    ): Pair<Double, Double>? {
        return try {
            warp.pixelToMm(px, py)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

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

    /**
     * Samsung 원본 이미지 처리 정책:
     * - "세로가 가로보다 긴" 경우에는 EXIF 대신 무조건 CCW 90 회전하여 landscape 로 맞춥니다.
     */
    /**
     * ✅ Grid 측정용 이미지 로딩
     *
     * 기존 구현은 openInputStream(uri).readBytes() → imdecode(MatOfByte) 방식이었는데,
     * - 대용량 이미지에서 ByteArray/MatOfByte 메모리 복사/할당이 커지고
     * - GC/메모리 압박으로 측정 시간이 늘거나(드물게) 교착으로 보이는 현상이 발생할 수 있어
     * FilmTotalMeasureProcessor와 동일하게 "temp file → OpenCV imread" 방식으로 통일합니다.
     *
     * 동작/로직은 동일(최종 Mat는 BGR)하며,
     * - (가능하면) EXIF 자동 회전은 무시
     * - portrait(h>w)인 경우에만 90° CCW 회전으로 landscape 정규화
     */
    private fun loadBgrMatFromUri(context: Context, uri: Uri, forceLandscape: Boolean): Mat {
        val t0 = SystemClock.elapsedRealtime()
        val tmp = File.createTempFile("airuler_capture_grid_", ".jpg", context.cacheDir)

        try {
            val tCopy0 = SystemClock.elapsedRealtime()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out ->
                    // ✅ (성능) 기본 copyTo(8KB) 대신 큰 버퍼로 복사
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        total += n
                    }
                    out.flush()

                    val tCopy1 = SystemClock.elapsedRealtime()
                    AirulerFileLogger.d(TAG, "loadBgrMatFromUri copy done dtMs=${tCopy1 - tCopy0} bytes=$total")
                }
            } ?: throw IllegalStateException("cannot open: $uri")

            val ignoreOriFlag = IMREAD_IGNORE_ORIENTATION_FLAG
            val flags = Imgcodecs.IMREAD_COLOR or ignoreOriFlag

            val tRead0 = SystemClock.elapsedRealtime()
            var img = Imgcodecs.imread(tmp.absolutePath, flags)
            val tRead1 = SystemClock.elapsedRealtime()

            if (img.empty()) return img
            AirulerFileLogger.d(
                TAG,
                "loadBgrMatFromUri imread done dtMs=${tRead1 - tRead0} size=${img.cols()}x${img.rows()} totalMs=${tRead1 - t0}"
            )

            if (forceLandscape && img.rows() > img.cols()) {
                val rotated = Mat()
                Core.rotate(img, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                img.release()
                img = rotated
            }

            return img
        } finally {
            // ✅ cacheDir temp 파일 누적 방지
            runCatching { tmp.delete() }
        }
    }



    private val MEASURE_CORNER_ROI_KEYS: List<String> = listOf(
        "roi_TL_H", "roi_TL_V",
        "roi_TR_H", "roi_TR_V",
        "roi_BR_H", "roi_BR_V",
        "roi_BL_H", "roi_BL_V"
    )

    private fun computeFilmRoiRectAbs(
        filmCrop: Mat,
        filmBBox: IntArray,
        roiCfg: RoiCfg,
    ): IntArray {
        val fx1 = filmBBox[0]
        val fy1 = filmBBox[1]
        val eh = filmCrop.rows().coerceAtLeast(1)
        val ew = filmCrop.cols().coerceAtLeast(1)

        var x = PyMath.roundHalfEvenInt(ew.toDouble() * roiCfg.x)
        var y = PyMath.roundHalfEvenInt(eh.toDouble() * roiCfg.y)
        var w = PyMath.roundHalfEvenInt(ew.toDouble() * roiCfg.w)
        var h = PyMath.roundHalfEvenInt(eh.toDouble() * roiCfg.h)

        x = x.coerceIn(0, ew - 1)
        y = y.coerceIn(0, eh - 1)
        w = w.coerceIn(1, ew - x)
        h = h.coerceIn(1, eh - y)

        return intArrayOf(fx1 + x, fy1 + y, fx1 + x + w, fy1 + y + h)
    }

    private fun buildCornerDebugOverlayDataOrNull(
        imgBgr: Mat,
        rawFilmBBox: IntArray,
        rois: Map<String, RoiCfg>,
        tflitePadPx: Int,
    ): CornerDebugOverlayData? {
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
        x1 = (x1 - tpad).coerceIn(0, imgW - 1)
        y1 = (y1 - tpad).coerceIn(0, imgH - 1)
        x2 = (x2 + tpad).coerceIn(x1 + 1, imgW)
        y2 = (y2 + tpad).coerceIn(y1 + 1, imgH)

        val paddedBBox = intArrayOf(x1, y1, x2, y2)
        val filmCrop = imgBgr.submat(y1, y2, x1, x2)
        try {
            val roiItems = ArrayList<CornerRoiDebugItem>(MEASURE_CORNER_ROI_KEYS.size)
            val pointMap = LinkedHashMap<String, Pair<Double, Double>?>(MEASURE_CORNER_ROI_KEYS.size)

            for (key in MEASURE_CORNER_ROI_KEYS) {
                val roiCfg = rois[key] ?: continue
                val roiRectAbs = computeFilmRoiRectAbs(filmCrop, paddedBBox, roiCfg)
                val pointAbs = runCatching {
                    filmRoiCfgToAbsPoint(filmCrop, paddedBBox, roiCfg).first
                }.getOrNull()
                roiItems.add(
                    CornerRoiDebugItem(
                        key = key,
                        roiRectAbs = roiRectAbs,
                        pointAbs = pointAbs,
                    )
                )
                pointMap[key] = pointAbs
            }

            fun composeCorner(name: String, hKey: String, vKey: String): CornerCompositeDebugPoint? {
                val hPt = pointMap[hKey] ?: return null
                val vPt = pointMap[vKey] ?: return null
                return CornerCompositeDebugPoint(
                    name = name,
                    pointAbs = Pair(vPt.first, hPt.second)
                )
            }

            val corners = listOfNotNull(
                composeCorner("TL", "roi_TL_H", "roi_TL_V"),
                composeCorner("TR", "roi_TR_H", "roi_TR_V"),
                composeCorner("BR", "roi_BR_H", "roi_BR_V"),
                composeCorner("BL", "roi_BL_H", "roi_BL_V"),
            )

            return CornerDebugOverlayData(
                roiItems = roiItems,
                cornerPoints = corners,
            )
        } finally {
            filmCrop.release()
        }
    }

    private fun cornerGroupColor(keyOrName: String): Scalar {
        val upper = keyOrName.uppercase(Locale.US)
        return when {
            upper.contains("TL") -> Scalar(255.0, 0.0, 255.0)
            upper.contains("TR") -> Scalar(255.0, 0.0, 255.0)
            upper.contains("BR") -> Scalar(255.0, 0.0, 255.0)
            upper.contains("BL") -> Scalar(255.0, 0.0, 255.0)
            else -> Scalar(255.0, 255.0, 255.0)
        }
    }

    private fun drawDebugMarker(
        overlay: Mat,
        x: Double,
        y: Double,
        color: Scalar,
        radius: Int,
        thickness: Int,
    ) {
        val r = radius.toDouble()
        Imgproc.circle(overlay, Point(x, y), radius, color, thickness, Imgproc.LINE_AA)
        Imgproc.line(overlay, Point(x - r, y), Point(x + r, y), color, thickness, Imgproc.LINE_AA)
        Imgproc.line(overlay, Point(x, y - r), Point(x, y + r), color, thickness, Imgproc.LINE_AA)
    }

    private fun drawLabeledRect(
        overlay: Mat,
        rect: IntArray,
        color: Scalar,
        label: String,
        thickness: Int,
        labelYOffset: Int = 0,
    ) {
        if (rect.size < 4) return
        Imgproc.rectangle(
            overlay,
            Point(rect[0].toDouble(), rect[1].toDouble()),
            Point(rect[2].toDouble(), rect[3].toDouble()),
            color,
            thickness,
            Imgproc.LINE_AA
        )
        val textX = (rect[0] + 4).toDouble()
        val textY = max(16.0, rect[1].toDouble() - 6.0 + labelYOffset.toDouble())
        Imgproc.putText(
            overlay,
            label,
            Point(textX, textY),
            Imgproc.FONT_HERSHEY_SIMPLEX,
            0.5,
            color,
            1,
            Imgproc.LINE_AA
        )
    }

    private fun drawMeasureCornerDebugOverlay(
        overlay: Mat,
        filmIndex: Int,
        rawFilmBBox: IntArray,
        usedFilmBBox: IntArray,
        cornerData: CornerDebugOverlayData,
    ) {
        drawLabeledRect(
            overlay = overlay,
            rect = rawFilmBBox,
            color = Scalar(255.0, 255.0, 255.0),
            label = "F#${filmIndex} rawBBox",
            thickness = 2,
            labelYOffset = 0,
        )
        drawLabeledRect(
            overlay = overlay,
            rect = usedFilmBBox,
            color = Scalar(0.0, 255.0, 255.0),
            label = "F#${filmIndex} usedBBox",
            thickness = 2,
            labelYOffset = 14,
        )

        for (item in cornerData.roiItems) {
            val color = cornerGroupColor(item.key)
            val shortKey = item.key.removePrefix("roi_")
            drawLabeledRect(
                overlay = overlay,
                rect = item.roiRectAbs,
                color = color,
                label = shortKey,
                thickness = 1,
            )

            val pt = item.pointAbs
            if (pt != null) {
                drawDebugMarker(
                    overlay = overlay,
                    x = pt.first,
                    y = pt.second,
                    color = color,
                    radius = 5,
                    thickness = 1,
                )
            } else {
                Imgproc.putText(
                    overlay,
                    "MISS",
                    Point(item.roiRectAbs[0].toDouble() + 4.0, item.roiRectAbs[3].toDouble() - 4.0),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.45,
                    Scalar(0.0, 0.0, 255.0),
                    1,
                    Imgproc.LINE_AA
                )
            }
        }

        for (corner in cornerData.cornerPoints) {
            val color = cornerGroupColor(corner.name)
            drawDebugMarker(
                overlay = overlay,
                x = corner.pointAbs.first,
                y = corner.pointAbs.second,
                color = color,
                radius = 8,
                thickness = 2,
            )
            Imgproc.putText(
                overlay,
                corner.name,
                Point(corner.pointAbs.first + 6.0, corner.pointAbs.second - 6.0),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                0.55,
                color,
                2,
                Imgproc.LINE_AA
            )
        }
    }

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

        val px = fx1 + x + pt.x
        val py = fy1 + y + pt.y
        val rect = intArrayOf(fx1 + x, fy1 + y, fx1 + x + w, fy1 + y + h)
        return Pair(Pair(px, py), rect)
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

    private fun selectBestCurvePointByTargetY_Grid(
        curvePointsAbs: List<Pair<Double, Double>>,
        fixedPointAbs: Pair<Double, Double>,
        warp: com.hklab.airuler.grid.GridWarpPiecewiseAffine,
        targetYMm: Double,
    ): BestCurvePick {
        if (curvePointsAbs.isEmpty()) throw IllegalStateException("curve_points_abs is empty")

        val (xf, yf) = warp.pixelToMm(fixedPointAbs.first, fixedPointAbs.second)

        var bestPt: Pair<Double, Double>? = null
        var bestDx = 0.0
        var bestDy = 0.0
        var bestErr = Double.POSITIVE_INFINITY

        for ((gx, gy) in curvePointsAbs) {
            val (xi, yi) = try {
                warp.pixelToMm(gx, gy)
            } catch (_: Exception) {
                continue
            }

            val dx = abs(xi - xf)
            val dy = abs(yi - yf)
            val err = abs(dy - targetYMm)

            if (err < bestErr) {
                bestErr = err
                bestPt = gx to gy
                bestDx = dx
                bestDy = dy
            }
        }

        val outPt = bestPt ?: throw IllegalStateException("curve_points: no valid candidate (grid)")
        return BestCurvePick(outPt, bestDx, bestDy, bestErr)
    }
}


