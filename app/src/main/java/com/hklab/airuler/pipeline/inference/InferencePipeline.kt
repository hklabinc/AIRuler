package com.hklab.airuler.pipeline.inference

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import androidx.camera.core.ImageProxy
import androidx.appcompat.app.AppCompatActivity
import com.hklab.airuler.R
import com.hklab.airuler.SettingsActivity
import com.hklab.airuler.autoreturn.ReturnWatcherService
import com.hklab.airuler.cv.OpenCvProcessor
import com.hklab.airuler.databinding.ActivityMainBinding
import com.hklab.airuler.inspection.BadBoxPx
import com.hklab.airuler.inspection.FilmTracker
import com.hklab.airuler.inspection.GoodBadDecisionEngine
import com.hklab.airuler.inspection.GoodBadModelRules
import com.hklab.airuler.inspection.InspectionDecision
import com.hklab.airuler.inspection.MotionHandDetector
import com.hklab.airuler.inspection.MoveDirection
import com.hklab.airuler.media.AirulerMediaStore
import com.hklab.airuler.model.ModelFileStore
import com.hklab.airuler.model.ModelStore
import com.hklab.airuler.pipeline.MeasureMode
import com.hklab.airuler.pipeline.MeasurementMethod
import com.hklab.airuler.pipeline.TiltMode
import com.hklab.airuler.pipeline.state.AppRuntimeState
import com.hklab.airuler.pipeline.state.AppSessionSettings
import com.hklab.airuler.samsungcapture.SamsungCaptureStore
import com.hklab.airuler.sound.AirulerSoundPlayer
import com.hklab.airuler.film.FilmModelConfig
import com.hklab.airuler.film.FilmModelConfigLoader
import com.hklab.airuler.film.FilmPointFinder
import com.hklab.airuler.film.RoiCfg
import com.hklab.airuler.film.ruler.PyMath
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import com.hklab.airuler.view.DetectionOverlayView
import com.hklab.airuler.yolo.AirulerYoloClasses
import com.hklab.airuler.yolo.YoloDetection
import com.hklab.airuler.yolo.YoloDetector
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.hklab.airuler.GlobalParams

/**
 * MainActivity에서 분리된 추론(프레임 처리) 파이프라인.
 *
 * ✅ 목표
 * - 기존 동작 유지
 * - MainActivity 책임을 줄이고(카메라/복귀/측정과 분리),
 *   프레임 처리 로직을 한 곳으로 모읍니다.
 */
class InferencePipeline(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val state: AppRuntimeState,
    private val cameraExecutor: ExecutorService,
    private val ioExecutor: ExecutorService,
    private val soundPlayer: AirulerSoundPlayer,
    private val appendStatus: (String) -> Unit,
    private val appendStatusStyled: (CharSequence) -> Unit,
    private val showToast: (String) -> Unit,
    private val isGridModelSelected: () -> Boolean,
    private val getSelectedModelName: () -> String?,
    private val requestSamsungCapture: () -> Unit,
    private val hideCapturedOverlay: () -> Unit,
    private val updatePreviewPauseUi: () -> Unit,
    private val onAlertStop: (String) -> Unit
) {

    private val tag = "InferencePipeline"

    // ---------------- Settings/Options ----------------
    // ✅ Settings 값은 “세션(AppSessionSettings)”에서 초기화해
    //    홈에서 모델을 다시 선택하더라도 디폴트로 리셋되지 않게 합니다.

    var measureMode: MeasureMode = AppSessionSettings.measureMode
        private set

    var measurementMethod: MeasurementMethod = AppSessionSettings.measurementMethod
        private set

    var directionCheckEnabled: Boolean = AppSessionSettings.directionCheckEnabled
        private set

    var yoloFrameInterval: Int = AppSessionSettings.yoloFrameInterval
        private set

    private var tiltMode: TiltMode = AppSessionSettings.tiltMode

    private var showGuideGrid = AppSessionSettings.showGuideGrid
    private var showFilmBox = AppSessionSettings.showFilmBox
    private var showConfOnBox = AppSessionSettings.showConfOnBox
    private var showAngleOnPreview = AppSessionSettings.showAngleOnPreview
    private var showTilt = AppSessionSettings.showTilt

    // ---------------- Grid ROI gating ----------------
    // Grid.tflite 검출 시: ROI가 충분히 수평(각도≈0°)에 가까워졌을 때만 "Grid 분석" 버튼을 노출
    // (안정성/재현성 우선: 연속 프레임 조건으로 깜빡임을 줄입니다.)
    private var gridAngleOkStreak: Int = 0

    @Volatile private var gridReadyForAnalyze: Boolean = false
    @Volatile private var lastGridAngleDeg: Double = Double.NaN
    @Volatile private var lastGridRoiNorm: RectF? = null

    private val GRID_READY_ANGLE_DEG = 1.0
    private val GRID_READY_STREAK = 3

    // Debug overlay options
    // ✅ View Options: Good 라벨 bbox 표시(디폴트 OFF)
    private var showGoodBoxOverlay = AppSessionSettings.showGoodBoxOverlay
    private var showBadBoxOverlay = AppSessionSettings.showBadBoxOverlay
    private var showHandOverlay = AppSessionSettings.showHandOverlay
    private var showMotionOverlay = AppSessionSettings.showMotionOverlay
    private var showTrackOverlay = AppSessionSettings.showTrackOverlay

    // ---------------- Track debug buffer ----------------

    private val trackDebugLock = Any()
    private val trackDebugPoints = ArrayDeque<PointF>(32)
    @Volatile
    private var lastDebugOverlayUpdateMs: Long = 0L

    // ---------------- Core detectors/engines ----------------

    private val motionHandDetector = MotionHandDetector()


    // BadBox(구멍 막힘) 판정: good/bad 라벨 기반 (ref_img 비교 방식 제거)

    // ✅ HkDetector(main_detector.py) 방식: good/bad 라벨 기반 decision_count 엔진
    private val goodBadDecisionEngine = GoodBadDecisionEngine(threshold = 4)
    private val filmTracker = FilmTracker()

    // ---------------- YOLO ----------------

    private var yoloDetector: YoloDetector? = null
    private var yoloFrameCounter: Int = 0

    // ✅ YOLO는 Analyzer thread(카메라)와 분리된 단일 스레드에서 돌려
    //    프레임 처리(모션/핸드/FPS)가 막히지 않게 합니다.
    //    또한 “KEEP_ONLY_LATEST” 동작을 흉내 내기 위해 최신 프레임만 유지합니다.
    private val yoloExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val yoloQueueLock = Any()

    // double buffer (idx 0/1)
    private var yoloBuf0: Bitmap? = null
    private var yoloBuf1: Bitmap? = null
    private var yoloMeta0: MotionHandDetector.Result? = null
    private var yoloMeta1: MotionHandDetector.Result? = null
    private var yoloWriteIdx: Int = 0
    private var yoloHasPending: Boolean = false
    @Volatile private var yoloRunning: Boolean = false
    @Volatile private var yoloGeneration: Int = 0

    private val yoloCopyCanvas = Canvas()
    private val yoloCopyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // ---------------- Misc state ----------------

    // ---------------- Decision latency (요구사항: 걸린시간 표시) ----------------
    // - 시작: 손/모션이 사라진 뒤 BadBox 분석을 시작하기 직전
    // - 종료: 최종 좌/우 화살표가 표시된 직후(즉, 최종 PASS/FAIL 확정 시점)
    //
    // ⚠️ Status(TextView)는 최신 한 줄만 표시되므로,
    //    최종 판정 직후에는 YOLO Detect Status가 덮어쓰지 않도록(=손이 들어오기 전까지)
    //    Detect 처리/Status 업데이트를 잠시 멈춥니다.
    @Volatile private var decisionLatencyStartMs: Long = 0L
    @Volatile private var lastDecisionLatencyMs: Long = 0L

    private val motionTimeoutMs: Long = 30_000L
    @Volatile
    private var lastMotionAtMs: Long = 0L
    @Volatile
    private var lastIdleState: Boolean = false

    @Volatile
    private var lastMhResult: MotionHandDetector.Result? = null

    @Volatile
    private var lastDetectionsForDirection: List<YoloDetection> = emptyList()

    // -------- Bitmap reuse (성능) --------
    private val frameReuseLock = Any()
    private var reusableRawBitmap: Bitmap? = null
    private var reusableRotatedBitmap: Bitmap? = null
    private val reuseMatrix = Matrix()
    private val reuseCanvas = Canvas()
    private val reusePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // -------- Crop snapshot (Ref 저장용) --------
    private val cropFrameLock = Any()
    private var cropFrameBitmap: Bitmap? = null
    @Volatile
    private var lastYoloDetectionsForCrop: List<YoloDetection> = emptyList()

    // -------- Overlays --------
    @Volatile
    private var lastBadBoxBoxesForOverlay: List<BadBoxPx> = emptyList()

    // ✅ Film CV ROI corner points (frame px) for overlay
    @Volatile
    private var lastCornerPointsForOverlay: List<PointF> = emptyList()
    @Volatile
    // box index(정렬된 YOLO 결과) 기준 결함 여부
    private var lastBadBoxDefectsForOverlay: BooleanArray? = null

    // -------- 이동 중 overlay suppress --------
    @Volatile
    private var suppressTiltAngleDiffBadBoxDuringMove: Boolean = false

    // -------- BadBox 시작 각도 게이트 --------
    private val ANGLE_NEAR_ZERO_DEG = 1.0

    private val FILM_TILT_DY_THRESH = 1.5
    private val FILM_TILT_OK_STREAK = 3
    private var filmTiltOkStreak: Int = 0

    // -------- 비정상(촬영 감지 실패) 측정 상태 복구 --------
    // Samsung 카메라로 전환 후, 촬영 파일 감지가 실패하면(measuringNow=true 상태로 복귀)
    // 이후 측정이 다시 시작되지 않는 문제가 발생할 수 있습니다.
    // 사용자가 필름을 화면 밖으로 뺐다가 다시 넣을 때 자동으로 상태를 초기화해 재시작할 수 있게 합니다.
    private val STUCK_MEASURE_EMPTY_STREAK_TO_RESET = 2
    private var stuckMeasureEmptyStreak: Int = 0


    // -------- UI Status throttle (performance) --------
    // Detect/BadBox 상태 문구는 매 프레임 갱신 시 UI thread 부하가 커질 수 있어,
    // 일정 시간 간격으로만 갱신합니다. (GlobalParams에서 조절 가능)
    @Volatile private var lastDetectStatusUpdateMs: Long = 0L
    @Volatile private var lastBadBoxStatusUpdateMs: Long = 0L
    @Volatile private var lastCornerSkipStatusUpdateMs: Long = 0L
    @Volatile private var lastCornerSkipStatusMsg: String? = null


    // Film tilt(Δy) 계산을 위한 JSON/roi_L, roi_R prerequisite
    @Volatile private var filmConfig: FilmModelConfig? = null
    @Volatile private var filmTiltPrereqOk: Boolean = true
    @Volatile private var filmTiltPrereqToastShown: Boolean = false

    // Film CV ROI(8개 roi_TL/TR/BR/BL_* ) prerequisite
    @Volatile private var filmCornerPrereqOk: Boolean = true
    @Volatile private var filmCornerPrereqToastShown: Boolean = false

    // Ref image missing toast (중복 노출 방지)

    private enum class BadBoxStartAngleGateMode {
        ANY_NEAR_ZERO,
        ALL_NEAR_ZERO
    }

    private var badBoxStartAngleGateMode: BadBoxStartAngleGateMode = BadBoxStartAngleGateMode.ANY_NEAR_ZERO

    // -------- FPS/Hz --------
    @Volatile
    private var analysisFps: Double = 0.0
    private var fpsWindowStartNs: Long = 0L
    private var fpsFramesInWindow: Int = 0

    @Volatile
    private var yoloHz: Double = 0.0
    private var yoloHzWindowStartNs: Long = 0L
    private var yoloRunsInWindow: Int = 0

    // -------- 온도 표시 --------
    private var lastBatteryTempReadMs: Long = 0L
    private var lastBatteryTempC: Float? = null

    init {
        // 기존 코드와 동일하게 초기 해상도는 “대충 1280x720”로 둡니다.
        filmTracker.updateResolution(1280, 720)
        applyOverlayOptions()
    }

    // ---------------- Public API ----------------


    fun onModelChanged() {
        hideArrows()
        clearBadBoxOverlayState()

        // ✅ Film JSON/ROI prerequisite 캐시/토스트 상태 리셋
        filmConfig = null
        filmTiltPrereqOk = true
        filmTiltPrereqToastShown = false
        filmCornerPrereqOk = true
        filmCornerPrereqToastShown = false

        // ✅ Ref 없을 때 토스트 상태 리셋

        // Film tilt streak reset
        filmTiltOkStreak = 0

        // ✅ 타이밍(걸린시간) 측정 상태도 리셋
        resetDecisionLatency()

        // ✅ 모델이 바뀌면 합불 누적 카운트도 리셋
        goodBadDecisionEngine.reset()
        filmTracker.reset()
        lastDetectionsForDirection = emptyList()


        // Grid 게이팅 상태 초기화
        gridAngleOkStreak = 0
        gridReadyForAnalyze = false
        lastGridAngleDeg = Double.NaN
        lastGridRoiNorm = null

        // ✅ YOLO 리셋(동시 close 방지)
        invalidateYoloRunnerAndCloseDetector()
        yoloFrameCounter = 0

        updatePreviewPauseUi()

        appendStatus("Model changed: ${(getSelectedModelName() ?: ModelStore.get(activity)).orEmpty()}")
    }

    fun release() {
        // ✅ YOLO: runner 무효화 + detector close를 executor에서 처리
        invalidateYoloRunnerAndCloseDetector()
        runCatching { yoloExecutor.shutdownNow() }

        synchronized(yoloQueueLock) {
            yoloHasPending = false
            yoloMeta0 = null
            yoloMeta1 = null
            runCatching { yoloBuf0?.recycle() }
            runCatching { yoloBuf1?.recycle() }
            yoloBuf0 = null
            yoloBuf1 = null
        }

        // badBox refs mats

        synchronized(cropFrameLock) {
            runCatching { cropFrameBitmap?.recycle() }
            cropFrameBitmap = null
        }
    }

    fun requestAnalyzerFrameCapture() {
        if (state.alertStop) {
            showToast("ALERT 상태에서는 캡처할 수 없습니다")
            return
        }
        if (state.capturedOverlayVisible) {
            showToast("캡처 화면 표시 중에는 저장할 수 없습니다")
            return
        }
        state.pendingLivePreviewCapture = true
        appendStatus("Capture requested (next analyzer frame)")
    }

    fun onMeasureButtonClicked(forceCaptureMp: Int? = null) {
        if (state.capturedOverlayVisible) {
            appendStatus("이미 캡처 화면이 표시 중입니다.")
            return
        }

        // ✅ Grid 모드: "Grid 분석" (ROI 수평 정렬이 OK일 때만 버튼이 노출되지만, 안전하게 한 번 더 체크)
        if (isGridModelSelected()) {
            if (!gridReadyForAnalyze) {
                val ang = if (lastGridAngleDeg.isNaN()) "?" else String.format("%.2f", lastGridAngleDeg)
                appendStatus("Grid 정렬 필요 (angle=$ang°)")
                return
            }

            // (안전장치) 이전 모델에서 남아있을 수 있는 pendingMeasureAfterBadBoxPass를 제거
            state.setMeasureFlags(pending = false, measuring = false)

            if (!state.tryStartMeasuring()) {
                appendStatus("이미 분석 진행 중입니다")
                return
            }

            setMeasureButtonsVisible(false)
            activity.runOnUiThread { appendStatus("Grid 분석 촬영을 시작합니다…") }
            requestSamsungCapture()
            return
        }

        if (measureMode == MeasureMode.NONE) {
            appendStatus("MeasureMode=NONE")
            return
        }
        val mf = state.snapshotMeasureFlags()
        if (!mf.pendingMeasureAfterBadBoxPass) {
            appendStatus("먼저 BadBox PASS가 필요합니다")
            return
        }
        // ✅ MANUAL 모드에서 버튼 클릭으로 측정 시작(원자적 start)
        if (!state.tryStartMeasuring()) {
            appendStatus("이미 측정 진행 중입니다")
            return
        }
        // ✅ Manual 모드에서 50/200MP 버튼을 눌렀다면, 이번 1회 캡처 MP를 강제합니다.
        if (forceCaptureMp != null) {
            // 다른 1회성 override가 남아있지 않도록 정리
            state.clearOneShotOverrides()
            state.nextCaptureMegapixelOverride = forceCaptureMp
            state.nextMeasurementMethodOverride = if (forceCaptureMp >= 200) {
                com.hklab.airuler.pipeline.MeasurementMethod.RULER
            } else {
                null
            }
        }
        setMeasureButtonsVisible(false)
        requestSamsungCapture()
    }

    fun updateMeasureButtonsForCurrentState() {
        // Grid 모드에서는 "치수 재기" 대신 "Grid 분석" 버튼을 조건부로 노출합니다.
        if (isGridModelSelected()) {
            binding.btnMeasure.text = "Grid 분석"
            val shouldShow =
                gridReadyForAnalyze &&
                        !state.measuringNow &&
                        !state.capturedOverlayVisible &&
                        !state.alertStop

            setMeasureButtonsVisible(shouldShow)
            return
        }

        binding.btnMeasure.text = "치수 재기"

        val mf = state.snapshotMeasureFlags()
        val shouldShow =
            (measureMode == MeasureMode.MANUAL) &&
                    mf.pendingMeasureAfterBadBoxPass &&
                    !mf.measuringNow &&
                    !state.capturedOverlayVisible &&
                    !state.alertStop

        setMeasureButtonsVisible(shouldShow)
    }

    private fun updateGridGatingAfterYolo(
        detections: List<YoloDetection>,
        tiltResults: List<OpenCvProcessor.TiltResult>
    ) {
        if (detections.isEmpty() || tiltResults.isEmpty()) {
            // ROI가 사라짐
            if (lastGridRoiNorm != null || gridReadyForAnalyze) {
                gridAngleOkStreak = 0
                gridReadyForAnalyze = false
                lastGridAngleDeg = Double.NaN
                lastGridRoiNorm = null

                activity.runOnUiThread {
                    updateMeasureButtonsForCurrentState()
                    appendStatus("Grid ROI 미검출")
                }
            }
            return
        }

        val tilt = tiltResults.first()
        lastGridAngleDeg = tilt.angleDeg
        lastGridRoiNorm = detections.first().rect

        val ok = abs(tilt.angleDeg) <= GRID_READY_ANGLE_DEG
        gridAngleOkStreak = if (ok) (gridAngleOkStreak + 1).coerceAtMost(GRID_READY_STREAK) else 0

        val newReady = gridAngleOkStreak >= GRID_READY_STREAK
        val changed = newReady != gridReadyForAnalyze
        gridReadyForAnalyze = newReady

        if (changed) {
            val angStr = String.format("%.2f", tilt.angleDeg)
            activity.runOnUiThread {
                updateMeasureButtonsForCurrentState()
                appendStatus(
                    if (newReady) {
                        "Grid ROI 정렬 OK (angle=${angStr}°) → 'Grid 분석' 버튼을 누르세요"
                    } else {
                        "Grid ROI 각도=${angStr}° (0°로 맞추세요)"
                    }
                )
            }
        }
    }

    // ---------------- Final decision message helpers ----------------

    /** BadBox 분석이 시작되기 직전에 호출해서 타이머를 시작합니다(한 사이클당 1회). */
    private fun markDecisionLatencyStartIfNeeded() {
        if (decisionLatencyStartMs == 0L) {
            decisionLatencyStartMs = SystemClock.elapsedRealtime()
        }
    }

    /** 최종 판정 시점(화살표 표시 직후)에서 호출해 elapsed(ms)를 계산하고 타이머를 리셋합니다. */
    private fun computeAndResetDecisionLatencyMs(nowMs: Long = SystemClock.elapsedRealtime()): Long {
        val start = decisionLatencyStartMs
        val elapsed = if (start > 0L) (nowMs - start).coerceAtLeast(0L) else 0L
        lastDecisionLatencyMs = elapsed
        decisionLatencyStartMs = 0L
        return elapsed
    }

    private fun resetDecisionLatency() {
        decisionLatencyStartMs = 0L
        lastDecisionLatencyMs = 0L
    }

    private fun buildFinalDecisionStatusLine(decision: InspectionDecision, elapsedMs: Long): String {
        return when (decision) {
            InspectionDecision.PASS -> "걸린시간: ${elapsedMs}ms, 정상, 오른쪽으로 빼세요."
            InspectionDecision.FAIL -> "걸린시간: ${elapsedMs}ms, 불량, 왼쪽으로 빼세요."
        }
    }

    /**
     * 최종 판정(화살표 표시) 이후, 손이 들어오기 전까지는 YOLO detect/Status 업데이트를 잠시 멈춥니다.
     * - 목적: 불필요한 연산(전력/발열) 감소 + 최종 안내 문구가 Detect Status로 덮여쓰이지 않게 유지
     */
    private fun shouldPauseYoloUntilHand(mh: MotionHandDetector.Result?): Boolean {
        if (state.decidedInspection == null) return false
        val hand = mh?.handExists == true
        val motion = mh?.motionExists == true
        return !hand && !motion
    }

    /**
     * 최종 PASS/FAIL을 확정해 UI에 표기.
     * - showDecisionGuide + sound
     */
    fun onFinalDecision(decision: InspectionDecision, counts: List<Int>? = null) {
        // 최종 판정이 내려졌으므로, 기존에 시작된 타이머가 있으면 여기서 종료합니다.
        // (BadBox 기반 판정 → 치수측정 → 최종 판정 흐름에서도 동일하게 동작)
        val elapsedMs = computeAndResetDecisionLatencyMs()

        state.decidedInspection = decision
        showDecisionGuide(decision)
        setMeasureButtonsVisible(false)

        when (decision) {
            InspectionDecision.PASS -> soundPlayer.playPass()
            InspectionDecision.FAIL -> soundPlayer.playFail()
        }

        // UI Status는 요구사항 포맷으로 표시
        appendStatus(buildFinalDecisionStatusLine(decision, elapsedMs))

        // 디버그(로그캣)에는 counts를 남깁니다(기존 분석 정보 유지).
        if (counts != null) {
            Log.i(tag, "DECISION=$decision counts=${counts.joinToString(",")}")
        } else {
            Log.i(tag, "DECISION=$decision")
        }
    }

    fun applySettingsFromResult(data: Intent) {
        // 표시 옵션
        showGuideGrid = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_GRID, showGuideGrid)
        showFilmBox = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_FILM_BOX, showFilmBox)
        showConfOnBox = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_CONF, showConfOnBox)
        showAngleOnPreview = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_ANGLE, showAngleOnPreview)
        showTilt = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_TILT, showTilt)
        showGoodBoxOverlay = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_GOOD_BOX, showGoodBoxOverlay)
        showBadBoxOverlay = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_BAD_BOX, showBadBoxOverlay)
        showHandOverlay = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_HAND, showHandOverlay)
        showMotionOverlay = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_MOTION, showMotionOverlay)
        showTrackOverlay = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_TRACK, showTrackOverlay)

        // 모드
        val tiltModeName = data.getStringExtra(SettingsActivity.RESULT_TILT_MODE)
        tiltMode = when (tiltModeName) {
            "NONE" -> TiltMode.NONE
            "HOUGH" -> TiltMode.HOUGH
            else -> tiltMode
        }

        val measureModeName = data.getStringExtra(SettingsActivity.RESULT_MEASURE_MODE)
        measureMode = when (measureModeName) {
            "NONE" -> MeasureMode.NONE
            "MANUAL" -> MeasureMode.MANUAL
            "AUTO" -> MeasureMode.AUTO
            else -> measureMode
        }

        val measurementMethodName = data.getStringExtra(SettingsActivity.RESULT_MEASUREMENT_METHOD)
        measurementMethod = when (measurementMethodName) {
            "RULER" -> MeasurementMethod.RULER
            "GRID" -> MeasurementMethod.GRID
            else -> measurementMethod
        }

        // NONE로 바뀌면 혹시 남아있던 “치수 대기 상태”는 정리
        if (measureMode == MeasureMode.NONE) {
            state.finishMeasuring(clearPending = true)
            setMeasureButtonsVisible(false)
        }

        yoloFrameInterval = data.getIntExtra(SettingsActivity.RESULT_YOLO_INTERVAL, yoloFrameInterval)
            .coerceIn(GlobalParams.Defaults.YOLO_INTERVAL_MIN, GlobalParams.Defaults.YOLO_INTERVAL_MAX)
        directionCheckEnabled = data.getBooleanExtra(SettingsActivity.RESULT_DIRECTION_CHECK, directionCheckEnabled)

        // ✅ 세션 설정 업데이트(앱이 살아있는 동안 모델을 바꿔도 유지)
        AppSessionSettings.showGuideGrid = showGuideGrid
        AppSessionSettings.showFilmBox = showFilmBox
        AppSessionSettings.showConfOnBox = showConfOnBox
        AppSessionSettings.showAngleOnPreview = showAngleOnPreview
        AppSessionSettings.showTilt = showTilt
        AppSessionSettings.showGoodBoxOverlay = showGoodBoxOverlay
        AppSessionSettings.showBadBoxOverlay = showBadBoxOverlay
        AppSessionSettings.showHandOverlay = showHandOverlay
        AppSessionSettings.showMotionOverlay = showMotionOverlay
        AppSessionSettings.showTrackOverlay = showTrackOverlay
        AppSessionSettings.tiltMode = tiltMode
        AppSessionSettings.yoloFrameInterval = yoloFrameInterval
        AppSessionSettings.directionCheckEnabled = directionCheckEnabled
        AppSessionSettings.measureMode = measureMode
        AppSessionSettings.measurementMethod = measurementMethod

        applyOverlayOptions()
        updateMeasureButtonsForCurrentState()

        appendStatus(
            "Settings 적용: tiltMode=$tiltMode, yoloFrameInterval=$yoloFrameInterval, " +
                    "directionCheckEnabled=$directionCheckEnabled, measureMode=$measureMode, measurementMethod=$measurementMethod"
        )
    }

    /** SettingsActivity로 넘길 현재 옵션 스냅샷 */
    fun fillSettingsIntent(intent: Intent) {
        intent.putExtra(SettingsActivity.EXTRA_SHOW_GRID, showGuideGrid)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_FILM_BOX, showFilmBox)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_CONF, showConfOnBox)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_ANGLE, showAngleOnPreview)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_TILT, showTilt)
        // ✅ Settings UI에서 "Diff" 대신 "Good Box" 옵션을 사용합니다.
        intent.putExtra(SettingsActivity.EXTRA_SHOW_GOOD_BOX, showGoodBoxOverlay)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_BAD_BOX, showBadBoxOverlay)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_HAND, showHandOverlay)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_MOTION, showMotionOverlay)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_TRACK, showTrackOverlay)
        intent.putExtra(SettingsActivity.EXTRA_TILT_MODE, tiltMode.name)
        intent.putExtra(SettingsActivity.EXTRA_YOLO_INTERVAL, yoloFrameInterval)
        intent.putExtra(SettingsActivity.EXTRA_DIRECTION_CHECK, directionCheckEnabled)
        intent.putExtra(SettingsActivity.EXTRA_MEASURE_MODE, measureMode.name)
        intent.putExtra(SettingsActivity.EXTRA_MEASUREMENT_METHOD, measurementMethod.name)
    }

    fun saveRefCropsByBoxIndex() {
        if (state.capturedOverlayVisible) {
            showToast("캡처 화면 표시 중에는 Ref 저장을 할 수 없습니다")
            return
        }

        val model = getSelectedModelName() ?: ModelStore.get(activity)
        if (model.isNullOrBlank()) {
            showToast("모델이 선택되지 않았습니다")
            return
        }

        val frameSnapshot = snapshotCropFrame()
        val dets = lastYoloDetectionsForCrop.toList()

        if (frameSnapshot == null || dets.isEmpty()) {
            showToast("저장할 박스가 없습니다")
            return
        }

        // “위치 순서” 안정화
        val sorted = sortDetectionsForBoxIndex(dets)

        // ✅ Film 모델은 반드시 JSON + roi_TL/TR/BR/BL_* 가 있어야 CV ROI 기반 Ref 저장을 수행
        val isGrid = model.contains("grid", ignoreCase = true)
        if (!isGrid) {
            val ok = ensureFilmCornerPrereqOrNotify()
            if (!ok) return
        }

        ioExecutor.execute {
            var saved = 0
            var cornerFailIdx: List<Int> = emptyList()
            var computeFail = false

            try {
                if (isGrid) {
                    // Grid는 기존 방식 유지 (tflite bbox crop)
                    sorted.forEachIndexed { idx, det ->
                        val crop = runCatching { cropFromNormalizedRect(frameSnapshot, det.rect) }.getOrNull()
                            ?: return@forEachIndexed

                        val outFile = ModelFileStore.refImageFile(activity, model, idx)
                        val ok = runCatching {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                val b = crop.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                                fos.flush()
                                b
                            }
                        }.getOrDefault(false)

                        if (ok) saved++
                        runCatching { crop.recycle() }
                    }
                } else {
                    // Film: CV ROI(코너 기반) crop
                    val detRects01 = sorted.map { it.rect }
                    val cv = computeFilmCvRois(frameSnapshot, detRects01)
                    if (cv == null) {
                        computeFail = true
                    } else {
                        cornerFailIdx = cv.cvRoisPx.mapIndexedNotNull { i, r -> if (r == null) i else null }
                        if (cornerFailIdx.isNotEmpty()) {
                            // 요구사항: 꼭지점 4개 추출 실패 시 toast 알림
                            computeFail = true
                        } else {
                            cv.cvRoisPx.forEachIndexed { idx, rectNullable ->
                                val rect = rectNullable!!
                                val w = rect.width().coerceAtLeast(1)
                                val h = rect.height().coerceAtLeast(1)
                                val crop = Bitmap.createBitmap(frameSnapshot, rect.left, rect.top, w, h)

                                val outFile = ModelFileStore.refImageFile(activity, model, idx)
                                val ok = runCatching {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        val b = crop.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                                        fos.flush()
                                        b
                                    }
                                }.getOrDefault(false)

                                if (ok) saved++
                                runCatching { crop.recycle() }
                            }
                        }
                    }
                }
            } finally {
                runCatching { frameSnapshot.recycle() }
            }

            activity.runOnUiThread {
                if (!isGrid) {
                    if (computeFail) {
                        val msg = if (cornerFailIdx.isNotEmpty()) {
                            "Ref 저장 실패: 필름 꼭지점 4개 추출 실패 (idx=${cornerFailIdx.joinToString()})"
                        } else {
                            "Ref 저장 실패: CV ROI 계산 실패"
                        }
                        showToast(msg)
                        appendStatus(msg)
                        return@runOnUiThread
                    }
                }

                appendStatus("Ref saved: $saved/${sorted.size} -> internal ref_imgs/")

                // ✅ Ref 저장 완료 알림(기존 동작 유지)
                // - BadBox(구멍) 검사는 이제 good/bad 라벨 기반으로만 동작하므로
                //   저장 직후 "ref 재로드" 같은 부하는 제거했지만,
                //   사용자에게 저장 완료 toast는 동일하게 제공합니다.
                showToast("레퍼런스 이미지 저장 완료: $saved 개")
            }
        }
    }

    // ---------------- Frame processing (ImageAnalysis.Analyzer) ----------------

    fun processFrame(image: ImageProxy) {
        if (state.isStopping) {
            image.close()
            return
        }

        // ✅ FPS는 프레임이 들어온 시점에 계산(스킵/리턴 전)
        tickAnalysisFps()

        // ✅ 캡처 요청 상태 스냅샷
        val captureRequested = state.pendingLivePreviewCapture

        // ✅ ALERT / Preview Pause 처리
        if (state.alertStop || (state.previewPaused && !captureRequested)) {
            if (captureRequested) state.pendingLivePreviewCapture = false
            image.close()
            return
        }

        // ✅ 캡처/결과 오버레이가 떠 있으면 분석은 멈춤
        if (state.capturedOverlayVisible && !state.dismissCapturedOverlayOnHand) {
            if (captureRequested) state.pendingLivePreviewCapture = false
            image.close()
            return
        }

        try {
            val frameW = image.width
            val frameH = image.height
            val rotationDeg = image.imageInfo.rotationDegrees

            // Bitmap(reuse) 확보
            val raw = synchronized(frameReuseLock) {
                ensureReusableBitmap(reusableRawBitmap, frameW, frameH).also {
                    reusableRawBitmap = it
                }
            }

            // ImageProxy → Bitmap 복사
            val buffer = image.planes.firstOrNull()?.buffer
            if (buffer != null) {
                buffer.rewind()
                raw.copyPixelsFromBuffer(buffer)
            } else {
                if (captureRequested) state.pendingLivePreviewCapture = false
                return
            }

            // 회전 처리 (reuse)
            val rotated: Bitmap = if (rotationDeg == 0) {
                raw
            } else {
                synchronized(frameReuseLock) {
                    val dst = ensureReusableBitmap(
                        reusableRotatedBitmap,
                        if (rotationDeg % 180 == 0) frameW else frameH,
                        if (rotationDeg % 180 == 0) frameH else frameW
                    )
                    reusableRotatedBitmap = dst

                    rotateInto(src = raw, dst = dst, rotationDeg = rotationDeg)
                    dst
                }
            }

            // 방향 트래커는 프리뷰 해상도 기반이므로, 프레임 사이즈가 바뀌면 갱신
            filmTracker.updateResolution(rotated.width, rotated.height)

            // Motion/Hand
            val mh = motionHandDetector.analyze(rotated)
            lastMhResult = mh

            // ✅ 캡처/측정 오버레이가 떠 있고 “손으로 닫기” 옵션이면, 손 감지 시 오버레이 닫기 후 return
            if (state.capturedOverlayVisible && state.dismissCapturedOverlayOnHand) {
                if (mh.handExists) {
                    activity.runOnUiThread {
                        appendStatus("Hand detected → close measure preview")
                        hideCapturedOverlay()
                    }
                }
                return
            }

            // Debug overlays (motion/hand/track)
            pushTrackDebugPointFromMotion(mh)
            maybeUpdateDebugOverlay(rotated.width, rotated.height, mh)

            // ✅ 판정(화살표 표시) 이후 실제 이동이 시작되면 Tilt/Diff/BadBox 등 overlay 숨김 ON
            if (directionCheckEnabled && state.decidedInspection != null) {
                if (!suppressTiltAngleDiffBadBoxDuringMove && (mh.motionExists || mh.handExists)) {
                    suppressTiltAngleDiffBadBoxDuringMove = true
                    (binding.detectionOverlay as DetectionOverlayView).setDecisionMoveSuppression(true)
                }
            }

            // motion 갱신
            val nowMs = SystemClock.elapsedRealtime()
            if (mh.motionExists) {
                lastMotionAtMs = nowMs
            }

            val enablePredict = (nowMs - lastMotionAtMs) <= motionTimeoutMs
            val idle = !enablePredict
            if (idle != lastIdleState) {
                lastIdleState = idle
                if (idle) {
                    activity.runOnUiThread {
                        appendStatus("${motionTimeoutMs / 1000}초 이상 움직임이 없어 detection을 멈춥니다.")
                    }
                    // ✅ YOLO runner가 완전히 쉬는 상태일 때만 detector close
                    if (!yoloRunning) {
                        yoloExecutor.execute {
                            runCatching { yoloDetector?.close() }
                            yoloDetector = null
                        }
                    }
                } else {
                    activity.runOnUiThread { appendStatus("Motion 감지 → Detection 시작") }
                }
            }

            // ---------------- 방향 체크 ----------------
            if (directionCheckEnabled && state.decidedInspection != null) {
                if (mh.motionExists && mh.motionBoxPx != null) {
                    val cx = (mh.motionBoxPx.left + mh.motionBoxPx.right) / 2
                    val cy = (mh.motionBoxPx.top + mh.motionBoxPx.bottom) / 2
                    filmTracker.update(cx to cy)
                } else {
                    if (!mh.handExists && lastDetectionsForDirection.isEmpty()) {
                        filmTracker.update(null)
                    }
                }

                val actual = filmTracker.consumeDirection()
                if (actual != null && actual != MoveDirection.UNKNOWN) {
                    val expected = when (state.decidedInspection) {
                        InspectionDecision.PASS -> MoveDirection.RIGHT
                        InspectionDecision.FAIL -> MoveDirection.LEFT
                        else -> MoveDirection.UNKNOWN
                    }

                    val ok = actual == expected
                    if (ok) soundPlayer.playSuccess() else soundPlayer.playError()

                    activity.runOnUiThread {
                        appendStatus("MOVE: $actual ${if (ok) "✅" else "❌"} (expected=$expected)")
                        onMoveFinished(expected, actual)
                        setMeasureButtonsVisible(false)
                    }

                    state.decidedInspection = null
                    resetDecisionLatency()
                    goodBadDecisionEngine.reset()
                    lastDetectionsForDirection = emptyList()
                    filmTracker.reset()
                    clearBadBoxOverlayState()
                }
            }

            // ✅ 방향 체크 OFF일 때도 다음 필름을 위해 decision을 풀어줘야 함
            if (!directionCheckEnabled && state.decidedInspection != null) {
                if (mh.motionExists || mh.handExists) {
                    state.decidedInspection = null
                    resetDecisionLatency()
                    goodBadDecisionEngine.reset()
                    lastDetectionsForDirection = emptyList()
                    filmTracker.reset()
                    clearBadBoxOverlayState()

                    activity.runOnUiThread {
                        hideArrows()
                        setMeasureButtonsVisible(false)
                        appendStatus("Decision cleared (direction check OFF) due to motion/hand")
                    }
                }
            }

            // ---------------- Live preview capture 요청 처리 (딱 1프레임만) ----------------
            if (captureRequested) {
                state.pendingLivePreviewCapture = false

                val capturedCopy = rotated.copy(Bitmap.Config.ARGB_8888, false)

                ioExecutor.execute {
                    val displayName = AirulerMediaStore.makeFileName(prefix = "Capture")
                    val saved = AirulerMediaStore.saveJpegBitmapToDcim(
                        context = activity,
                        bitmap = capturedCopy,
                        displayName = displayName,
                        folderName = "Capture",
                        overwrite = false,
                        jpegQuality = 95
                    )
                    runCatching { capturedCopy.recycle() }

                    activity.runOnUiThread {
                        if (saved != null) {
                            showToast("저장 완료: DCIM/Capture/$displayName")
                            appendStatus("CapturePreview 저장 완료: $displayName")
                        } else {
                            showToast("저장 실패")
                            appendStatus("CapturePreview 저장 실패")
                        }
                    }
                }
                return
            }

            // ---------------- YOLO Detection (샘플링: N프레임마다 한 번) ----------------
            yoloFrameCounter++
            // ✅ 최종 판정 직후(화살표 표시)에는 손이 들어오기 전까지 YOLO detect를 잠시 멈춰
            //    불필요한 연산을 줄이고, Status가 Detect 메시지로 덮여쓰이지 않게 합니다.
            val pauseUntilHand = shouldPauseYoloUntilHand(mh)

            val shouldRunYolo =
                !pauseUntilHand &&
                        enablePredict && (yoloFrameInterval > 0 && (yoloFrameCounter % yoloFrameInterval == 0))
            if (!shouldRunYolo) return

            // ✅ 최신 프레임만 유지하도록 YOLO 입력을 double buffer에 복사해 enqueue
            enqueueYoloFrame(
                src = rotated,
                mh = mh,
                scoreThresh = GlobalParams.SCORE_THRESH,
                iouThresh = GlobalParams.IOU_THRESH
            )
        } catch (e: Exception) {
            activity.runOnUiThread { appendStatus("processFrame 오류: ${e.message}") }
        } finally {
            image.close()
        }
    }

// ---------------- Helpers ----------------

    private fun applyOverlayOptions() {
        val overlay = binding.detectionOverlay as DetectionOverlayView
        overlay.updateDisplayOptions(
            showGrid = showGuideGrid,
            showFilmBox = showFilmBox,
            showConf = showConfOnBox,
            showAngle = showAngleOnPreview,
            showTilt = showTilt,
            showBadBox = showBadBoxOverlay,
            showHand = showHandOverlay,
            showMotion = showMotionOverlay,
            showTrack = showTrackOverlay
        )

        if (!showHandOverlay) overlay.clearHandDebug()
        if (!showMotionOverlay) overlay.setMotionBoxes(emptyList())
        if (!showTrackOverlay) overlay.clearTrack()
}




    private fun clearBadBoxOverlayState() {
        lastBadBoxBoxesForOverlay = emptyList()
        lastCornerPointsForOverlay = emptyList()
        lastBadBoxDefectsForOverlay = null

        activity.runOnUiThread {
            val overlay = binding.detectionOverlay as DetectionOverlayView
            // ✅ 새 필름/리셋 시에는 badBox 결과를 "완전히" 지워야 하므로 clear 사용
            overlay.clearBadBoxCircles()
            overlay.clearBadBoxBoxes()
            overlay.clearCornerPoints()
        }
    }

    private fun ensureReusableBitmap(existing: Bitmap?, width: Int, height: Int): Bitmap {
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            return existing
        }
        if (existing != null && !existing.isRecycled) {
            runCatching { existing.recycle() }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * YOLO double-buffer 전용 bitmap 확보.
     *
     * ⚠️ 주의: YOLO는 별도 스레드에서 이 bitmap을 읽는 중일 수 있으므로,
     *          사이즈가 바뀔 때 기존 bitmap을 즉시 recycle 하면 크래시 위험이 있습니다.
     *          → 기존 것은 YOLO executor 큐 뒤에서 안전하게 정리하도록 미룹니다.
     */
    private fun ensureYoloBuffer(existing: Bitmap?, width: Int, height: Int): Bitmap {
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            return existing
        }

        val old = existing
        val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        if (old != null && !old.isRecycled) {
            // YOLO thread가 old를 사용 중일 수 있어 즉시 recycle 금지.
            // 같은 executor에 enqueue 하면 현재 실행 중인 detect 이후에 처리됨.
            runCatching {
                yoloExecutor.execute { runCatching { old.recycle() } }
            }
        }

        return created
    }

    private fun rotateInto(src: Bitmap, dst: Bitmap, rotationDeg: Int) {
        // dst를 src 회전한 결과로 덮어쓰기
        reuseMatrix.reset()
        reuseMatrix.postRotate(rotationDeg.toFloat(), src.width / 2f, src.height / 2f)

        reuseCanvas.setBitmap(dst)
        reuseCanvas.drawColor(Color.TRANSPARENT)

        // 회전 후 중앙 정렬
        val cx = dst.width / 2f
        val cy = dst.height / 2f
        val m = Matrix()
        m.postRotate(rotationDeg.toFloat(), src.width / 2f, src.height / 2f)

        // 원본을 회전하여 dst 중심에 맞추기 위해 translate
        val srcRect = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        val outRect = RectF()
        m.mapRect(outRect, srcRect)

        val dx = cx - outRect.centerX()
        val dy = cy - outRect.centerY()
        m.postTranslate(dx, dy)

        reuseCanvas.drawBitmap(src, m, reusePaint)
    }

    private fun updateCropFrame(src: Bitmap) {
        synchronized(cropFrameLock) {
            val w = src.width
            val h = src.height
            val cur = cropFrameBitmap
            val dst = if (cur != null && !cur.isRecycled && cur.width == w && cur.height == h) {
                cur
            } else {
                runCatching { cur?.recycle() }
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
            cropFrameBitmap = dst

            val c = Canvas(dst)
            c.drawBitmap(src, 0f, 0f, null)
        }
    }

    private fun snapshotCropFrame(): Bitmap? {
        synchronized(cropFrameLock) {
            val src = cropFrameBitmap ?: return null
            if (src.isRecycled) return null
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    private fun cropFromNormalizedRect(src: Bitmap, rect01: RectF): Bitmap? {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null

        val left = (rect01.left * w).toInt().coerceIn(0, w - 1)
        val top = (rect01.top * h).toInt().coerceIn(0, h - 1)
        val right = (rect01.right * w).toInt().coerceIn(left + 1, w)
        val bottom = (rect01.bottom * h).toInt().coerceIn(top + 1, h)

        val cw = right - left
        val ch = bottom - top
        if (cw <= 1 || ch <= 1) return null

        return Bitmap.createBitmap(src, left, top, cw, ch)
    }

    private fun sortDetectionsForBoxIndex(list: List<YoloDetection>): List<YoloDetection> {
        if (list.size <= 1) return list

        val ys = list.map { it.rect.centerY() }.sorted()
        val medianY = ys[ys.size / 2]

        val top = list.filter { it.rect.centerY() < medianY }.sortedBy { it.rect.centerX() }
        val bottom = list.filter { it.rect.centerY() >= medianY }.sortedBy { it.rect.centerX() }

        return top + bottom
    }

    /**
     * MainActivity에서도 호출해야 하므로 public.
     * (기존 MainActivity의 hideArrows() 역할)
     */
    fun hideArrows() {
        binding.imgLeftArrow.visibility = View.GONE
        binding.imgRightArrow.visibility = View.GONE

        suppressTiltAngleDiffBadBoxDuringMove = false
        (binding.detectionOverlay as DetectionOverlayView).setDecisionMoveSuppression(false)
    }

    /**
     * ✅ 사용자 Retry(재시도) 요청으로 "현재 필름"에 대한 판정/측정 흐름을 초기화합니다.
     *
     * 목적
     * - 결과 이미지/화살표가 떠 있는 상태에서도 다시 tilt→badBox→(PASS시)measure 까지
     *   처음부터 재시도할 수 있도록 내부 상태를 안전하게 리셋
     *
     * 주의
     * - 기존 자동 리셋(모션/손/방향체크) 로직과 충돌하지 않도록
     *   핵심 엔진/게이팅 상태만 정리합니다.
     */
    fun resetForUserRetry() {
        // 1) 런타임 상태(공유 플래그)
        state.decidedInspection = null
        state.finishMeasuring(clearPending = true)

        // 2) 내부 엔진/게이팅 상태
        resetDecisionLatency()
        goodBadDecisionEngine.reset()
        filmTracker.reset()
        lastDetectionsForDirection = emptyList()

        filmTiltOkStreak = 0

        gridAngleOkStreak = 0
        gridReadyForAnalyze = false
        lastGridAngleDeg = Double.NaN
        lastGridRoiNorm = null

        yoloFrameCounter = 0

        suppressTiltAngleDiffBadBoxDuringMove = false

        // 3) 오버레이/디버그 표시 정리
        clearBadBoxOverlayState()
        synchronized(trackDebugLock) { trackDebugPoints.clear() }

        // 4) UI 정리
        activity.runOnUiThread {
            hideArrows()
            updateMeasureButtonsForCurrentState()
        }
    }

    private fun showDecisionGuide(decision: InspectionDecision) {
        if (!directionCheckEnabled) {
            hideArrows()
            return
        }

        binding.imgLeftArrow.visibility = View.GONE
        binding.imgRightArrow.visibility = View.GONE

        when (decision) {
            InspectionDecision.PASS -> binding.imgRightArrow.visibility = View.VISIBLE
            InspectionDecision.FAIL -> binding.imgLeftArrow.visibility = View.VISIBLE
        }
    }

    private fun onMoveFinished(expected: MoveDirection, actual: MoveDirection) {
        hideArrows()

        // expected/actual unknown은 비교 의미 없음
        if (expected == MoveDirection.UNKNOWN || actual == MoveDirection.UNKNOWN) return

        if (expected != actual) {
            onAlertStop("Move mismatch: expected=$expected actual=$actual")
        }
    }

    // (tilt gate는 YOLO task에서 “표시용 tilt 계산”과 함께 1회만 계산하도록 변경)

    // ---------------- YOLO queue (KEEP_ONLY_LATEST) ----------------

    private fun enqueueYoloFrame(
        src: Bitmap,
        mh: MotionHandDetector.Result,
        scoreThresh: Float,
        iouThresh: Float
    ) {
        val gen = yoloGeneration

        synchronized(yoloQueueLock) {
            // 버퍼 확보
            yoloBuf0 = ensureYoloBuffer(yoloBuf0, src.width, src.height)
            yoloBuf1 = ensureYoloBuffer(yoloBuf1, src.width, src.height)

            val dst = if (yoloWriteIdx == 0) yoloBuf0!! else yoloBuf1!!

            // ✅ src(reuse bitmap)의 내용을 dst로 복사해서 YOLO가 “안전한 스냅샷”을 보게 함
            yoloCopyCanvas.setBitmap(dst)
            yoloCopyCanvas.drawBitmap(src, 0f, 0f, yoloCopyPaint)

            if (yoloWriteIdx == 0) yoloMeta0 = mh else yoloMeta1 = mh

            yoloHasPending = true

            if (!yoloRunning) {
                yoloRunning = true
                yoloExecutor.execute { yoloLoop(gen, scoreThresh, iouThresh) }
            }
        }
    }


    private fun ensureFilmTiltPrereqOrNotify(): Boolean {
        if (filmConfig == null) {
            val model = (getSelectedModelName() ?: ModelStore.get(activity)).orEmpty().trim()
            if (model.isNotBlank()) {
                filmConfig = FilmModelConfigLoader.loadFromInternalModels(activity, model)
            }
        }
        val cfg = filmConfig
        val ok = (cfg != null && cfg.rois.containsKey("roi_L") && cfg.rois.containsKey("roi_R"))
        filmTiltPrereqOk = ok
        if (!ok && !filmTiltPrereqToastShown) {
            filmTiltPrereqToastShown = true
            val model = (getSelectedModelName() ?: ModelStore.get(activity)).orEmpty().trim()
            activity.runOnUiThread {
                showToast("Tilt용 JSON 파일이 없습니다: ${model}.json")
                appendStatus("Tilt prerequisites missing: need ${model}.json with roi_L/roi_R. (YOLO only)")
            }
        }
        return ok
    }

    private val FILM_CORNER_ROI_KEYS = listOf(
        "roi_TL_H", "roi_TL_V",
        "roi_TR_H", "roi_TR_V",
        "roi_BR_H", "roi_BR_V",
        "roi_BL_H", "roi_BL_V"
    )

    /**
     * BadBox/Ref 저장 단계에서 사용할 Film CV ROI(코너 기반) prerequisite 체크.
     * - JSON 파일 + 8개 roi_* 키가 반드시 있어야 동작
     */
    private fun ensureFilmCornerPrereqOrNotify(): Boolean {
        if (filmConfig == null) {
            val model = (getSelectedModelName() ?: ModelStore.get(activity)).orEmpty().trim()
            if (model.isNotBlank()) {
                filmConfig = FilmModelConfigLoader.loadFromInternalModels(activity, model)
            }
        }

        val cfg = filmConfig
        val ok = (cfg != null && FILM_CORNER_ROI_KEYS.all { cfg.rois.containsKey(it) })
        filmCornerPrereqOk = ok
        if (!ok && !filmCornerPrereqToastShown) {
            filmCornerPrereqToastShown = true
            val model = (getSelectedModelName() ?: ModelStore.get(activity)).orEmpty().trim()
            activity.runOnUiThread {
                showToast("JSON에 roi_TL/TR/BR/BL_*가 없습니다: ${model}.json")
                appendStatus(
                    "Corner prerequisites missing: need ${model}.json with " +
                            FILM_CORNER_ROI_KEYS.joinToString()
                )
            }
        }
        return ok
    }

    private data class CvRoiComputation(
        val cornerPointsPx: List<PointF>,
        val cvRoisPx: List<AndroidRect?>
    )

    /**
     * python(main_tflite_json_roi_v2.py) 방식 포팅:
     * - tflite bbox(필름) -> tflite_pad 적용
     * - 8개 roi_xxx(point detection) -> 4 corner 생성
     * - corner들의 bbox에 corner_pad 적용 => cv roi
     */
    private fun computeFilmCvRois(
        frameBitmap: Bitmap,
        detRects01: List<RectF>,
        tflitePadPx: Int = GlobalParams.FILM_TFLITE_PAD_PX,
        cornerPadPx: Int = GlobalParams.FILM_CORNER_PAD_PX
    ): CvRoiComputation? {
        // ✅ computeFilmCvRoisFromBgr()와 로직을 공유해 중복 코드를 제거합니다.
        //    (Bitmap→Mat 변환만 이 함수에서 수행)
        if (filmConfig == null) return null
        if (detRects01.isEmpty()) return CvRoiComputation(emptyList(), emptyList())

        val imgW = frameBitmap.width
        val imgH = frameBitmap.height
        if (imgW <= 0 || imgH <= 0) return null

        val rgba = Mat()
        val bgr = Mat()

        return try {
            Utils.bitmapToMat(frameBitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            computeFilmCvRoisFromBgr(
                frameBgr = bgr,
                imgW = imgW,
                imgH = imgH,
                detRects01 = detRects01,
                tflitePadPx = tflitePadPx,
                cornerPadPx = cornerPadPx
            )
        } catch (e: Exception) {
            Log.w(tag, "computeFilmCvRois failed", e)
            null
        } finally {
            rgba.release()
            bgr.release()
        }
    }


    /**
     * computeFilmCvRois()와 동일 로직이지만,
     * Bitmap→Mat 변환을 외부에서 1회만 수행한 BGR Mat을 재사용합니다. (성능 최적화)
     */
    private fun computeFilmCvRoisFromBgr(
        frameBgr: Mat,
        imgW: Int,
        imgH: Int,
        detRects01: List<RectF>,
        tflitePadPx: Int = GlobalParams.FILM_TFLITE_PAD_PX,
        cornerPadPx: Int = GlobalParams.FILM_CORNER_PAD_PX
    ): CvRoiComputation? {
        val cfg = filmConfig ?: return null
        if (detRects01.isEmpty()) return CvRoiComputation(emptyList(), emptyList())
        if (imgW <= 0 || imgH <= 0) return null

        val cornerPoints = ArrayList<PointF>()
        val cvRois = ArrayList<AndroidRect?>(detRects01.size)

        try {
            for (det01 in detRects01) {
                // tflite bbox (px) + pad (round-half-even like python)
                var l = PyMath.roundHalfEvenInt(det01.left.toDouble() * imgW.toDouble())
                var t = PyMath.roundHalfEvenInt(det01.top.toDouble() * imgH.toDouble())
                var r = PyMath.roundHalfEvenInt(det01.right.toDouble() * imgW.toDouble())
                var b = PyMath.roundHalfEvenInt(det01.bottom.toDouble() * imgH.toDouble())

                l = (l - tflitePadPx).coerceIn(0, imgW - 1)
                t = (t - tflitePadPx).coerceIn(0, imgH - 1)
                r = (r + tflitePadPx).coerceIn(l + 1, imgW)
                b = (b + tflitePadPx).coerceIn(t + 1, imgH)

                val fw = (r - l).coerceAtLeast(1)
                val fh = (b - t).coerceAtLeast(1)

                val filmCrop = frameBgr.submat(Rect(l, t, fw, fh))
                try {
                    val ew = filmCrop.cols()
                    val eh = filmCrop.rows()

                    // ✅ (성능) ROI 좌표 계산에서 ew/eh의 Double 변환을 반복하지 않도록 캐시
                    val ewD = ew.toDouble()
                    val ehD = eh.toDouble()

                    fun roiToAbsPoint(roi: RoiCfg): PointF? {
                        // roi bbox in film coords (round-half-even like python)
                        val x2 = roi.x + roi.w
                        val y2 = roi.y + roi.h
                        val rx1 = PyMath.roundHalfEvenInt(ewD * roi.x).coerceIn(0, ew - 1)
                        val ry1 = PyMath.roundHalfEvenInt(ehD * roi.y).coerceIn(0, eh - 1)
                        val rx2 = PyMath.roundHalfEvenInt(ewD * x2).coerceIn(rx1 + 1, ew)
                        val ry2 = PyMath.roundHalfEvenInt(ehD * y2).coerceIn(ry1 + 1, eh)
                        val rw = (rx2 - rx1).coerceAtLeast(1)
                        val rh = (ry2 - ry1).coerceAtLeast(1)
                        if (rw <= 1 || rh <= 1) return null

                        val sub = filmCrop.submat(Rect(rx1, ry1, rw, rh))
                        try {
                            val pt = FilmPointFinder.find(roi.method, sub, roi.parameter) ?: return null
                            return PointF(
                                (l + rx1 + pt.x).toFloat(),
                                (t + ry1 + pt.y).toFloat()
                            )
                        } finally {
                            sub.release()
                        }
                    }

                    fun roiVal(key: String): Int? {
                        val roi = cfg.rois[key] ?: return null
                        val absPt = roiToAbsPoint(roi) ?: return null
                        val v = if (key.endsWith("_H")) absPt.y.toDouble() else absPt.x.toDouble()
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

                    // corners (x from *_V, y from *_H)
                    val tl = if (tlH != null && tlV != null) PointF(tlV.toFloat(), tlH.toFloat()) else null
                    val tr = if (trH != null && trV != null) PointF(trV.toFloat(), trH.toFloat()) else null
                    val br = if (brH != null && brV != null) PointF(brV.toFloat(), brH.toFloat()) else null
                    val bl = if (blH != null && blV != null) PointF(blV.toFloat(), blH.toFloat()) else null

                    // ✅ (성능) listOfNotNull() allocation 방지
                    if (tl != null) cornerPoints += tl
                    if (tr != null) cornerPoints += tr
                    if (br != null) cornerPoints += br
                    if (bl != null) cornerPoints += bl

                    if (tl != null && tr != null && br != null && bl != null) {
                        // ✅ (성능) min/max 계산 시 list 생성/2-pass(minOrNull/maxOrNull) 제거
                        val minX = min(min(tl.x, tr.x), min(br.x, bl.x))
                        val maxX = max(max(tl.x, tr.x), max(br.x, bl.x))
                        val minY = min(min(tl.y, tr.y), min(br.y, bl.y))
                        val maxY = max(max(tl.y, tr.y), max(br.y, bl.y))

                        var x1 = (minX.toInt() - cornerPadPx)
                        var y1 = (minY.toInt() - cornerPadPx)
                        var x2 = (maxX.toInt() + cornerPadPx + 1)
                        var y2 = (maxY.toInt() + cornerPadPx + 1)

                        x1 = x1.coerceIn(0, imgW - 1)
                        y1 = y1.coerceIn(0, imgH - 1)
                        x2 = x2.coerceIn(x1 + 1, imgW)
                        y2 = y2.coerceIn(y1 + 1, imgH)

                        val rect = AndroidRect(x1, y1, x2, y2)
                        cvRois += rect
                    } else {
                        cvRois += null
                    }
                } finally {
                    filmCrop.release()
                }
            }

            return CvRoiComputation(cornerPoints, cvRois)
        } catch (e: Exception) {
            Log.w(tag, "computeFilmCvRoisFromBgr failed", e)
            return null
        }
    }


    private fun calcFilmTiltByDy(
        frameBgr: Mat,
        imgW: Int,
        imgH: Int,
        detRect01: RectF,
        roiL: RoiCfg,
        roiR: RoiCfg
    ): OpenCvProcessor.TiltResult? {
        if (imgW <= 0 || imgH <= 0) return null

        // detection rect (px)
        val l = (detRect01.left * imgW).toInt().coerceIn(0, imgW - 1)
        val t = (detRect01.top * imgH).toInt().coerceIn(0, imgH - 1)
        val r = (detRect01.right * imgW).toInt().coerceIn(0, imgW)
        val b = (detRect01.bottom * imgH).toInt().coerceIn(0, imgH)
        val w = (r - l).coerceAtLeast(1)
        val h = (b - t).coerceAtLeast(1)
        if (w <= 1 || h <= 1) return null

        // ✅ Bitmap→Mat 변환을 반복하지 않도록, 외부에서 만든 frameBgr에서 바로 crop
        val detCrop = frameBgr.submat(Rect(l, t, w, h))
        try {
            fun roiPointAbs(roi: RoiCfg): PointF? {
                val rx = (roi.x * w).toInt().coerceIn(0, w - 1)
                val ry = (roi.y * h).toInt().coerceIn(0, h - 1)
                val rw = (roi.w * w).toInt().coerceAtLeast(1).coerceAtMost(w - rx)
                val rh = (roi.h * h).toInt().coerceAtLeast(1).coerceAtMost(h - ry)
                if (rw <= 0 || rh <= 0) return null

                val sub = detCrop.submat(Rect(rx, ry, rw, rh))
                try {
                    val pt = FilmPointFinder.find(roi.method, sub, roi.parameter) ?: return null
                    return PointF((l + rx + pt.x).toFloat(), (t + ry + pt.y).toFloat())
                } finally {
                    sub.release()
                }
            }

            val pL = runCatching { roiPointAbs(roiL) }.getOrNull()
            val pR = runCatching { roiPointAbs(roiR) }.getOrNull()
            if (pL == null || pR == null) return null

            val dy = (pL.y - pR.y).toDouble()
            val dyAbs = kotlin.math.abs(dy)
            val good = dyAbs <= FILM_TILT_DY_THRESH
            val level = if (good) OpenCvProcessor.TiltLevel.GOOD else OpenCvProcessor.TiltLevel.BAD

            // ✅ dyAbs가 5 이내일 때만 Overlay에 보이도록 line 생성
            val line: OpenCvProcessor.TiltLine? =
                if (dyAbs <= 5.0) {
                    OpenCvProcessor.TiltLine(
                        x1Norm = pL.x / imgW.toFloat(),
                        y1Norm = pL.y / imgH.toFloat(),
                        x2Norm = pR.x / imgW.toFloat(),
                        y2Norm = pR.y / imgH.toFloat(),
                        angleDeg = dy,                       // 내부 값은 signed 유지
                        isGood = good,                       // 판정은 |dy| 기준
                        label = String.format(java.util.Locale.US, "Δ=%.1f", dy)  // ✅ 표기
                    )
                } else {
                    null
                }

            return OpenCvProcessor.TiltResult(
                angleDeg = dy,
                level = level,
                line = line
            )
        } finally {
            detCrop.release()
        }
    }

    private fun yoloLoop(genAtStart: Int, scoreThresh: Float, iouThresh: Float) {
        while (true) {
            if (state.isStopping) {
                yoloRunning = false
                return
            }

            // 모델 변경/리셋이 발생하면 즉시 탈출
            if (genAtStart != yoloGeneration) {
                yoloRunning = false
                return
            }

            // 최신 pending frame 가져오기(원자적 swap)
            val workBitmap: Bitmap
            val workMh: MotionHandDetector.Result?
            synchronized(yoloQueueLock) {
                if (!yoloHasPending) {
                    yoloRunning = false
                    return
                }

                val idx = yoloWriteIdx
                yoloHasPending = false

                // 다음 write는 반대 버퍼로
                yoloWriteIdx = 1 - idx

                workBitmap = if (idx == 0) yoloBuf0!! else yoloBuf1!!
                workMh = if (idx == 0) yoloMeta0 else yoloMeta1
            }

            // detector 확보/초기화는 YOLO thread에서만 수행
            val detector = ensureYoloDetectorReady() ?: continue

            val t0 = System.nanoTime()

            // ✅ Bitmap→Mat 변환을 YOLO 루프에서 1회만 수행하고(필요한 경우에만),
            //    tilt(Δy) + corner(CV ROI) + badBox 단계에서 공유합니다.
            val sharedFrameRgba = Mat()
            val sharedFrameBgr = Mat()
            var sharedFrameBgrReady = false

            try {
                // ✅ (2026-02) 모델이 flim/film + good + bad 라벨을 함께 출력하므로,
                //    - detect 단계에서는 good/bad도 놓치지 않도록 score thresh를 조금 완화해서 가져오고,
                //    - "필름 ROI"로 사용할 box는 film만 필터링해서 기존 로직(tilt/측정)에 영향이 없게 합니다.
                val goodBadScoreThresh = GlobalParams.GOOD_BAD_SCORE_THRESH  // 고정값(요구사항)

                val allDetectionsRaw = detector.detect(
                    bitmap = workBitmap,
                    scoreThresh = goodBadScoreThresh,
                    iouThresh = iouThresh
                )

                val filmDetectionsRaw = allDetectionsRaw
                    .filter { AirulerYoloClasses.isFilm(it) && it.score >= scoreThresh }

                // good/bad는 badBox 합불 판정에만 사용 (overlay/crop에는 포함하지 않음)
                val goodDetectionsRaw = allDetectionsRaw
                    .filter { AirulerYoloClasses.isGood(it) && it.score >= goodBadScoreThresh }

                val badDetectionsRaw = allDetectionsRaw
                    .filter { AirulerYoloClasses.isBad(it) && it.score >= goodBadScoreThresh }

                // ✅ YOLO 박스 순서를 “위(좌→우) + 아래(좌→우)”로 안정화 (필름 박스 기준)
                val results = sortDetectionsForBoxIndex(filmDetectionsRaw)

                // 방향 판단용(최신 YOLO 결과 유무)
                lastDetectionsForDirection = results

                val isGrid = isGridModelSelected()

                // ---- Tilt 계산 ----
                // Grid: 기존처럼 Hough 기반(angleDeg) 유지
                // Film: JSON(roi_L/roi_R) 기반 Δy(|yR-yL|)로 tilt 계산. |dy|<=1 이면 기존 0.5° 이하와 동일한 GOOD 취급
                val tiltResults: List<OpenCvProcessor.TiltResult>
                var allowAdvancedStages = true
                var forceClearBadBoxOverlay = false

                if (isGrid) {
                    tiltResults = if (results.isNotEmpty()) {
                        listOf(
                            OpenCvProcessor.estimateHorizontalTilt(
                                bitmap = workBitmap,
                                roiNorm = results.first().rect
                            )
                        )
                    } else emptyList()
                } else {
                    // Film tilt prerequisites required
                    val prereqOk = ensureFilmTiltPrereqOrNotify()
                    if (!prereqOk) {
                        // YOLO만 수행하고 tilt/badBox/판정은 하지 않음
                        tiltResults = emptyList()
                        allowAdvancedStages = false
                        filmTiltOkStreak = 0
                        forceClearBadBoxOverlay = true
                        lastBadBoxDefectsForOverlay = null
                        lastBadBoxBoxesForOverlay = emptyList()
                        lastCornerPointsForOverlay = emptyList()
                    } else {
                        // ✅ (성능) Film tilt/Corner/BadBox 단계에서 공유할 BGR Mat를 1회만 생성
                        if (!sharedFrameBgrReady) {
                            Utils.bitmapToMat(workBitmap, sharedFrameRgba)
                            Imgproc.cvtColor(sharedFrameRgba, sharedFrameBgr, Imgproc.COLOR_RGBA2BGR)
                            sharedFrameBgrReady = true
                        }

                        val cfg = filmConfig!!
                        val roiL = cfg.rois.getValue("roi_L")
                        val roiR = cfg.rois.getValue("roi_R")

                        val imgW = workBitmap.width
                        val imgH = workBitmap.height

                        // 각 detection ROI마다 Δy tilt 계산(실패하면 해당 ROI tilt는 BAD 처리)
                        tiltResults = results.map { det ->
                            calcFilmTiltByDy(sharedFrameBgr, imgW, imgH, det.rect, roiL, roiR)
                                ?: OpenCvProcessor.TiltResult(9999.0, OpenCvProcessor.TiltLevel.BAD, null)
                        }
                    }
                }

                // ---- Film tilt OK streak ----
                // 조건: (여러 ROI 중 하나라도 |dy|<=1) AND 손/모션 없음일 때만 연속 카운트. 그 외에는 0으로 리셋.
                // (Grid는 기존 게이팅 로직을 사용하므로 여기서는 streak를 사용하지 않음)


                val measureFlags = state.snapshotMeasureFlags()

                // ---- 측정 시작 후(삼성 카메라) 촬영 결과 URI 미수신 상태 복구 ----
                // ReturnWatcherService가 촬영 파일을 감지하지 못한 경우,
                // measuringNow=true 상태가 남아 다음 측정이 시작되지 않을 수 있습니다.
                // 이때 사용자가 필름을 화면 밖으로 잠시 빼면(= YOLO 결과 empty) 상태를 초기화해 재시작 가능하게 합니다.
                if (measureFlags.measuringNow && !state.capturedOverlayVisible) {
                    if (results.isEmpty()) {
                        stuckMeasureEmptyStreak++
                        if (stuckMeasureEmptyStreak >= STUCK_MEASURE_EMPTY_STREAK_TO_RESET) {
                            if (GlobalParams.DEBUG) {
                                Log.w(tag, "Stuck measuring (no captured URI). Film disappeared → auto reset.")
                            }
                            stuckMeasureEmptyStreak = 0
                            runCatching {
                                // 감지 실패 상태에서 남아있는 watcher service가 이후 사진을 잘못 잡지 않도록 정리
                                activity.stopService(Intent(activity, ReturnWatcherService::class.java))
                            }
                            runCatching { SamsungCaptureStore.clear(activity) }

                            resetForUserRetry()
                            // 현재 프레임은 더 처리하지 않고 다음 프레임으로
                            continue
                        }
                    } else {
                        // 필름이 보이면 streak는 다시 0 (사용자가 다시 위치 조정 중일 수 있음)
                        stuckMeasureEmptyStreak = 0
                    }
                } else {
                    stuckMeasureEmptyStreak = 0
                }


                // ---- Grid ROI 정렬 게이팅 업데이트 ----
                if (isGrid) {
                    updateGridGatingAfterYolo(results, tiltResults)
                } else {
                    // 그리드 모드가 아닐 때는 상태를 정리(모델 전환시 깜빡임 방지)
                    if (gridReadyForAnalyze || gridAngleOkStreak != 0 || !lastGridAngleDeg.isNaN() || lastGridRoiNorm != null) {
                        gridAngleOkStreak = 0
                        gridReadyForAnalyze = false
                        lastGridAngleDeg = Double.NaN
                        lastGridRoiNorm = null
                    }
                }

                val mhForGate = workMh ?: lastMhResult
                val motionNow = mhForGate?.motionExists == true
                val handNow = mhForGate?.handExists == true
                if (!isGrid) {
                    val tiltFrameOk = tiltResults.any { kotlin.math.abs(it.angleDeg) <= FILM_TILT_DY_THRESH }
                    filmTiltOkStreak = if (!motionNow && !handNow && tiltFrameOk) {
                        (filmTiltOkStreak + 1).coerceAtMost(FILM_TILT_OK_STREAK)
                    } else 0
                }


                // ---- BadBox(구멍 막힘) 합불 판정 ----
                // YOLO(tflite)로 검출된 film/good/bad bbox 결과(개수/스코어)를 기반으로 PASS/FAIL 판단
                // (이후 치수 측정 플로우는 기존 AIRuler 로직을 그대로 유지)
                if (!isGrid) {
                    lastCornerPointsForOverlay = emptyList()
                }

                if (!isGrid &&
                    state.decidedInspection == null &&
                    !measureFlags.pendingMeasureAfterBadBoxPass &&
                    !measureFlags.measuringNow &&
                    !motionNow &&
                    !handNow &&
                    results.isNotEmpty() &&
                    allowAdvancedStages &&
                    filmTiltOkStreak >= FILM_TILT_OK_STREAK
                ) {
                    val modelCode = (getSelectedModelName() ?: ModelStore.get(activity)).orEmpty().trim()

                    // ✅ (요구사항) 손/모션이 사라진 뒤 합불 판정을 시작하기 직전부터 시간을 측정
                    markDecisionLatencyStartIfNeeded()

                    // --- HkDetector(main_detector.py)와 동일한 카운팅 규칙 ---
                    val filmCount = results.size
                    val goodCount = goodDetectionsRaw.size
                    val badCount = badDetectionsRaw.size
                    val isFilmOnlyModel = GoodBadModelRules.isFilmOnlyModel(modelCode)

                    // film-only 모델(L2785-02 / L2791-02)은 good/bad 라벨이 없으므로
                    // good/bad 조건을 보지 않고, film 검출만으로 PASS 누적을 진행합니다.
                    val hasBad = if (isFilmOnlyModel) false else (badCount > 0)
                    val hasGood = if (isFilmOnlyModel) {
                        filmCount > 0
                    } else {
                        goodCount > 0
                    }

                    // PASS(all_good) 조건
                    // - 일반 모델: (모델별 expected good 개수 만족) AND (bad 없음)
                    // - film-only 모델: film만 1개 이상 검출되면 good/bad 없이 통과 누적
                    val expectedGoodTotal = GoodBadModelRules.expectedGoodTotal(modelCode, filmCount)
                    val allGood = if (isFilmOnlyModel) {
                        filmCount >= 1
                    } else {
                        (expectedGoodTotal != null) && (filmCount >= 1) && (goodCount == expectedGoodTotal) && !hasBad
                    }

                    // overlay + 최종 박스 색칠용: bad box가 포함된 film만 defect로 표시
                    val filmDefects = BooleanArray(filmCount) { false }
                    val badBoxesPx = ArrayList<BadBoxPx>(badDetectionsRaw.size)

                    fun rect01ToPxBox(r01: RectF): BadBoxPx {
                        val w = workBitmap.width
                        val h = workBitmap.height
                        var l = (r01.left * w).toInt().coerceIn(0, w - 1)
                        var t = (r01.top * h).toInt().coerceIn(0, h - 1)
                        var r = (r01.right * w).toInt().coerceIn(0, w - 1)
                        var b = (r01.bottom * h).toInt().coerceIn(0, h - 1)
                        if (r < l) { val tmp = l; l = r; r = tmp }
                        if (b < t) { val tmp = t; t = b; b = tmp }
                        // DetectionOverlayView는 right/bottom을 exclusive로 취급하지만,
                        // 여기서는 시각화 목적이므로 1px 이상 보정만 수행
                        if (r == l) r = (l + 1).coerceAtMost(w - 1)
                        if (b == t) b = (t + 1).coerceAtMost(h - 1)
                        return BadBoxPx(left = l, top = t, right = r, bottom = b)
                    }

                    // bad box → film 매칭(간단/안정): bad bbox center가 film bbox 내부면 해당 film을 defect로
                    for (bd in badDetectionsRaw) {
                        badBoxesPx += rect01ToPxBox(bd.rect)
                        val cx = (bd.rect.left + bd.rect.right) * 0.5f
                        val cy = (bd.rect.top + bd.rect.bottom) * 0.5f
                        for ((idx, fd) in results.withIndex()) {
                            val fr = fd.rect
                            if (cx >= fr.left && cx <= fr.right && cy >= fr.top && cy <= fr.bottom) {
                                if (idx in filmDefects.indices) filmDefects[idx] = true
                            }
                        }
                    }

                    lastBadBoxDefectsForOverlay = filmDefects
                    lastBadBoxBoxesForOverlay = badBoxesPx

                    val decision = goodBadDecisionEngine.update(hasBad = hasBad, hasGood = hasGood, allGood = allGood)
                    val decCount = goodBadDecisionEngine.snapshotDecisionCount()

                    if (decision != null) {
                        when (decision) {
                            InspectionDecision.FAIL -> {
                                state.finishMeasuring(clearPending = true)
                                state.decidedInspection = InspectionDecision.FAIL

                                // ✅ Retry 등 1회성 오버라이드가 걸린 상태에서 FAIL로 종료되면
                                //    다음 사이클로 값이 새지 않도록 정리합니다.
                                state.clearOneShotOverrides()

                                soundPlayer.playFail()

                                activity.runOnUiThread {
                                    // ✅ 화살표 표시 직후 Status에 "걸린시간: ..." 표시
                                    showDecisionGuide(InspectionDecision.FAIL)
                                    setMeasureButtonsVisible(false)

                                    val elapsedMs = computeAndResetDecisionLatencyMs()
                                    appendStatus(buildFinalDecisionStatusLine(InspectionDecision.FAIL, elapsedMs))
                                }

                                Log.i(
                                    tag,
                                    "DECISION: FAIL | decCount=$decCount film=$filmCount good=$goodCount bad=$badCount expected=$expectedGoodTotal"
                                )
                            }

                            InspectionDecision.PASS -> {
                                // ✅ (요구사항) Measurement mode가 MANUAL/AUTO여도
                                //    구멍 검사(=Good/Bad 판정)가 끝나는 시점에 PASS 로그를 남깁니다.
                                //    - 치수 측정으로 넘어가더라도 "hole decision"은 여기서 확정된 것이므로
                                //      Logcat에서 PASS 케이스도 동일 포맷으로 확인할 수 있습니다.
                                Log.i(
                                    tag,
                                    "DECISION: PASS | decCount=$decCount film=$filmCount good=$goodCount bad=$badCount expected=$expectedGoodTotal"
                                )

                                // ✅ Retry 버튼 등에서 "이번 1회는 자동 촬영"을 강제할 수 있습니다.
                                // - measureMode(NONE/MANUAL/AUTO)와 무관하게 PASS면 바로 삼성 카메라를 띄웁니다.
                                // - 1회성 플래그이므로 여기서 소비(consume)하여 false로 정리합니다.
                                val forceAutoOnce = state.consumeForceAutoMeasureOnce()

                                if (forceAutoOnce) {
                                    state.setMeasureFlags(pending = true, measuring = state.snapshotMeasureFlags().measuringNow)

                                    activity.runOnUiThread {
                                        hideArrows()
                                        setMeasureButtonsVisible(false)
                                        appendStatus("Good/Bad PASS → measure required (RETRY). Launching Samsung camera...")
                                    }

                                    if (state.tryStartMeasuring()) {
                                        activity.runOnUiThread { requestSamsungCapture() }
                                    }
                                } else {
                                    when (measureMode) {
                                        MeasureMode.NONE -> {
                                            state.finishMeasuring(clearPending = true)
                                            state.decidedInspection = InspectionDecision.PASS
                                            soundPlayer.playPass()

                                            activity.runOnUiThread {
                                                // ✅ 화살표 표시 직후 Status에 "걸린시간: ..." 표시
                                                showDecisionGuide(InspectionDecision.PASS)
                                                setMeasureButtonsVisible(false)

                                                val elapsedMs = computeAndResetDecisionLatencyMs()
                                                appendStatus(buildFinalDecisionStatusLine(InspectionDecision.PASS, elapsedMs))
                                            }

                                        }

                                        MeasureMode.MANUAL -> {
                                            state.setMeasureFlags(pending = true, measuring = false)
                                            soundPlayer.playPass()

                                            activity.runOnUiThread {
                                                hideArrows()
                                                updateMeasureButtonsForCurrentState()
                                                appendStatus("Good/Bad PASS → measure required (MANUAL). Press '치수 재기'")
                                            }
                                        }

                                        MeasureMode.AUTO -> {
                                            state.setMeasureFlags(pending = true, measuring = state.snapshotMeasureFlags().measuringNow)

                                            activity.runOnUiThread {
                                                hideArrows()
                                                setMeasureButtonsVisible(false)
                                                appendStatus("Good/Bad PASS → measure required (AUTO). Launching Samsung camera...")
                                            }

                                            if (state.tryStartMeasuring()) {
                                                activity.runOnUiThread { requestSamsungCapture() }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // ✅ (성능) 상태 문구(UI)가 매 프레임 갱신되지 않도록 throttle
                        val msg = buildString {
                            append("Good/Bad check: film=")
                            append(filmCount)
                            append(" good=")
                            append(goodCount)
                            append(" bad=")
                            append(badCount)
                            append(" expected=")
                            append(expectedGoodTotal ?: "?")
                            append(" allGood=")
                            append(allGood)
                            append(" dec=")
                            append(decCount)
                        }

                        val now = SystemClock.elapsedRealtime()
                        val minInterval = GlobalParams.UI_STATUS_THROTTLE_MS
                        if (minInterval <= 0L || now - lastBadBoxStatusUpdateMs >= minInterval) {
                            lastBadBoxStatusUpdateMs = now
                            activity.runOnUiThread { appendStatus(msg) }
                        }
                    }
                }

                val dtMs = (System.nanoTime() - t0) / 1_000_000.0
                tickYoloHz()

                val rw = workBitmap.width
                val rh = workBitmap.height

                val coloredDetections = mutableListOf<YoloDetection>()
                val tiltLinesForOverlay = mutableListOf<OpenCvProcessor.TiltLine>()
                val angleResults = mutableListOf<Pair<Double, Boolean>>()

                val defects = lastBadBoxDefectsForOverlay
                val decisionMade = (state.decidedInspection != null)

                for ((idx, detRes) in results.withIndex()) {
                    // Tilt overlay: Grid(Hough) 또는 Film(Δy) 결과를 표시
                    if (tiltResults.isNotEmpty()) {
                        // Grid는 1개 결과만 있으므로 idx==0에서만 표시
                        if (isGrid) {
                            if (idx == 0) {
                                val tr = tiltResults[0]
                                val nearZero = kotlin.math.abs(tr.angleDeg) <= ANGLE_NEAR_ZERO_DEG
                                angleResults += tr.angleDeg to nearZero
                                tr.line?.let { tiltLinesForOverlay += it }
                            }
                        } else {
                            // Film: det index와 1:1 매칭이 깨질 수 있으므로(점 검출 실패 등)
                            // 가능한 만큼만 표시 (tiltResults는 결과가 있는 ROI만 들어있음)
                            if (idx < tiltResults.size) {
                                val tr = tiltResults[idx]
                                val nearOk = kotlin.math.abs(tr.angleDeg) <= FILM_TILT_DY_THRESH
                                angleResults += tr.angleDeg to nearOk
                                tr.line?.let { tiltLinesForOverlay += it }
                            }
                        }
                    }

                    var color = Color.YELLOW
                    if (decisionMade && defects != null && idx < defects.size) {
                        color = if (defects[idx]) Color.RED else Color.GREEN
                    }

                    coloredDetections += detRes.copy(color = color)
                }

                if (results.isNotEmpty()) {
                    updateCropFrame(workBitmap)
                }

                // ✅ 최종 판정 직후(손이 들어오기 전)에는 Status를 유지해야 하므로
                //    YOLO Detect Status 업데이트를 생략합니다.
                val pauseStatusUpdate = shouldPauseYoloUntilHand(mhForGate)

                // ✅ View Options("Good Box")가 켜져 있으면 good 라벨 bbox도 추가로 표시
                // - 치수 측정/Ref crop에는 절대 영향을 주지 않도록(display 전용 리스트로 분리)
                //   coloredDetections: film bbox(기존 색 로직 유지) -> crop/measure 전용
                //   overlayDetections : 화면 표시 전용 (film + good)
                val overlayDetections: List<YoloDetection> =
                    if (showGoodBoxOverlay && goodDetectionsRaw.isNotEmpty()) {
                        val list = ArrayList<YoloDetection>(coloredDetections.size + goodDetectionsRaw.size)
                        list.addAll(coloredDetections)
                        for (gd in goodDetectionsRaw) {
                            // good bbox는 film bbox 색(초록/빨강/노랑)과 구분되도록 CYAN 사용
                            list.add(gd.copy(color = Color.GREEN))
                        }
                        list
                    } else {
                        coloredDetections
                    }

                activity.runOnUiThread {
                    val overlay = binding.detectionOverlay as DetectionOverlayView
                    overlay.setFrameSize(rw, rh)
                    overlay.setDetections(overlayDetections)
                    overlay.setTiltLines(tiltLinesForOverlay)
                    overlay.setCornerPoints(lastCornerPointsForOverlay)

                    if (forceClearBadBoxOverlay) {
                        overlay.clearBadBoxCircles()
            overlay.clearBadBoxBoxes()
                    }

                    val badBoxValid = allowAdvancedStages && (lastBadBoxDefectsForOverlay != null)
                    overlay.setBadBoxBoxes(lastBadBoxBoxesForOverlay, valid = badBoxValid)

                    lastYoloDetectionsForCrop = coloredDetections

                    if (!pauseStatusUpdate) {
                        // ✅ (성능) Detect 상태 문구(UI) 업데이트 throttle
                        val now = SystemClock.elapsedRealtime()
                        val minInterval = GlobalParams.UI_STATUS_THROTTLE_MS
                        if (minInterval <= 0L || now - lastDetectStatusUpdateMs >= minInterval) {
                            lastDetectStatusUpdateMs = now
                            appendYoloStatus(dtMs, angleResults, lastMhResult)
                        }
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    appendStatus("Detection 오류: ${e.message}")
                }
            } finally {
                // ✅ shared mats release (native memory)
                runCatching { sharedFrameRgba.release() }
                runCatching { sharedFrameBgr.release() }
            }
        }
    }

    private fun ensureYoloDetectorReady(): YoloDetector? {
        // 이미 있으면 그대로
        yoloDetector?.let { return it }

        return try {
            val modelCode = (getSelectedModelName() ?: ModelStore.get(activity)).orEmpty()
            if (modelCode.isBlank()) {
                activity.runOnUiThread { showToast("모델이 선택되지 않았습니다.") }
                null
            } else {
                val local = ModelFileStore.downloadedModelFile(activity, modelCode)
                if (!local.exists()) {
                    activity.runOnUiThread {
                        showToast("모델 파일이 없습니다. ModelSelect에서 Download 하세요: $modelCode")
                        appendStatus("Detection 초기화 실패: missing ${local.name} (internal models/)")
                    }
                    null
                } else {
                    val modelPath = local.absolutePath
                    YoloDetector(
                        context = activity,
                        modelAssetName = modelPath,
                        numThreads = 4,
                        classLabels = arrayOf("film", "good", "bad")
                    ).also {
                        yoloDetector = it
                        activity.runOnUiThread {
                            appendStatus("Detection 초기화 완료 (CPU, threads=4, $modelPath)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to init detector", e)
            activity.runOnUiThread { appendStatus("Detection 초기화 실패: ${e.message}") }
            null
        }
    }

    private fun invalidateYoloRunnerAndCloseDetector() {
        yoloGeneration++
        synchronized(yoloQueueLock) {
            yoloHasPending = false
        }
        yoloExecutor.execute {
            runCatching { yoloDetector?.close() }
            yoloDetector = null
        }
    }

    private fun maybeUpdateDebugOverlay(frameW: Int, frameH: Int, mh: MotionHandDetector.Result) {
        if (!showMotionOverlay && !showHandOverlay && !showTrackOverlay) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastDebugOverlayUpdateMs < 80L) return
        lastDebugOverlayUpdateMs = now

        val motionBoxes = mh.motionBox01?.let { listOf(it) } ?: emptyList()
        val handRoi01 = mh.handRoi01
        val handBox01 = mh.handBox01
        val handScore = mh.handScore
        val handExists = mh.handExists
        val track = if (showTrackOverlay) snapshotTrackDebugPoints() else emptyList()

        activity.runOnUiThread {
            val overlay = binding.detectionOverlay as DetectionOverlayView
            overlay.setFrameSize(frameW, frameH)

            if (showMotionOverlay) overlay.setMotionBoxes(motionBoxes)
            if (showHandOverlay) overlay.setHandDebug(handRoi01, handBox01, handScore, handExists)
            if (showTrackOverlay) overlay.setTrackPoints(track)
        }
    }

    private fun pushTrackDebugPointFromMotion(mh: MotionHandDetector.Result) {
        if (!showTrackOverlay) return
        if (!mh.motionExists) return
        val r = mh.motionBoxPx ?: return
        val cx = (r.left + r.right) * 0.5f
        val cy = (r.top + r.bottom) * 0.5f

        synchronized(trackDebugLock) {
            if (trackDebugPoints.size >= 32) trackDebugPoints.removeFirst()
            trackDebugPoints.addLast(PointF(cx, cy))
        }
    }

    private fun snapshotTrackDebugPoints(): List<PointF> {
        synchronized(trackDebugLock) {
            return trackDebugPoints.toList()
        }
    }

    private fun setMeasureButtonsVisible(show: Boolean) {
        // Grid 모드: 기존 btnMeasure("Grid 분석") 버튼 사용
        if (isGridModelSelected()) {
            binding.btnMeasure.visibility = if (show) View.VISIBLE else View.GONE
            // Manual measure buttons는 숨김
            binding.layoutManualMeasureButtons.visibility = View.GONE
            return
        }

        // Film 모드: MANUAL일 때만 50/200MP 측정 버튼 노출
        if (measureMode == MeasureMode.MANUAL) {
            binding.layoutManualMeasureButtons.visibility = if (show) View.VISIBLE else View.GONE
        } else {
            binding.layoutManualMeasureButtons.visibility = View.GONE
        }

        // 기존 단일 버튼은 Film 모드에서는 사용하지 않으므로 숨김
        binding.btnMeasure.visibility = View.GONE
    }

    private fun tickAnalysisFps() {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (fpsWindowStartNs == 0L) fpsWindowStartNs = nowNs

        fpsFramesInWindow++
        val elapsedNs = nowNs - fpsWindowStartNs
        if (elapsedNs >= 1_000_000_000L) {
            analysisFps = fpsFramesInWindow * 1e9 / elapsedNs
            fpsFramesInWindow = 0
            fpsWindowStartNs = nowNs
        }
    }

    private fun tickYoloHz() {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (yoloHzWindowStartNs == 0L) yoloHzWindowStartNs = nowNs

        yoloRunsInWindow++
        val elapsedNs = nowNs - yoloHzWindowStartNs
        if (elapsedNs >= 1_000_000_000L) {
            yoloHz = yoloRunsInWindow * 1e9 / elapsedNs
            yoloRunsInWindow = 0
            yoloHzWindowStartNs = nowNs
        }
    }

    private fun getBatteryTempC(cooldownMs: Long = 5000L): Float? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBatteryTempReadMs < cooldownMs) return lastBatteryTempC

        lastBatteryTempReadMs = now

        val intent = activity.registerReceiver(
            /* receiver = */ null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val t = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        lastBatteryTempC = if (t != Int.MIN_VALUE) (t / 10f) else null
        return lastBatteryTempC
    }

    private fun getThermalStatusLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "N/A"

        val pm = activity.getSystemService(AppCompatActivity.POWER_SERVICE) as PowerManager
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    }

    private fun appendYoloStatus(
        dtMs: Double,
        angleResults: List<Pair<Double, Boolean>>,
        mh: MotionHandDetector.Result?
    ) {
        val sb = StringBuilder()

        sb.append("Detect: ").append(String.format("%.1f", dtMs)).append(" ms")

        val interval = yoloFrameInterval.coerceAtLeast(1)
        val targetHz = if (analysisFps > 0.0) (analysisFps / interval) else 0.0

        sb.append(" | FPS: ")
            .append(String.format("%.1f", yoloHz)).append("/")
            .append(String.format("%.1f", analysisFps)).append("Hz")

        mh?.let {
            sb.append(" | Motion: ").append(if (it.motionExists) "Yes" else "No")
            sb.append(" | Hand: ").append(if (it.handExists) "Yes" else "No")
        } ?: sb.append(" | MH:-")

        // 온도 표시(버튼 위 오버레이)
        val tempC = getBatteryTempC()
        val tempText = if (tempC != null) String.format("%.1f", tempC) else "N/A"

        appendStatusStyled(sb.toString())

        // appendYoloStatus()는 YOLO UI 업데이트 runOnUiThread 내부에서 호출되므로,
        // 여기서 다시 runOnUiThread를 중첩 호출하지 않습니다. (성능 최적화)
        binding.txtTempOverlay.text = tempText
        binding.txtTempOverlay.setTextColor(
            when {
                tempC == null -> Color.GRAY
                tempC < 30f -> Color.rgb(33, 150, 243)
                tempC < 37f -> Color.rgb(76, 175, 80)
                tempC < 40f -> Color.rgb(205, 220, 57)
                tempC < 45f -> Color.rgb(255, 152, 0)
                else -> Color.rgb(244, 67, 54)
            }
        )
    }

    private fun RectF.centerX(): Float = (left + right) * 0.5f
    private fun RectF.centerY(): Float = (top + bottom) * 0.5f
}