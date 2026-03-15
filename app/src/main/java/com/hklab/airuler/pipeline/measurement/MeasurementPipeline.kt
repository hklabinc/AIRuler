package com.hklab.airuler.pipeline.measurement

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.hklab.airuler.R
import com.hklab.airuler.calibration.GridOnlineOffsetCalibrationStore
import com.hklab.airuler.databinding.ActivityMainBinding
import com.hklab.airuler.film.ExternalMediaStoreUtils
import com.hklab.airuler.film.FilmTotalMeasureGridProcessor
import com.hklab.airuler.film.FilmTotalMeasureProcessor
import com.hklab.airuler.grid.GridAnalysisProcessor
import com.hklab.airuler.model.ModelFileStore
import com.hklab.airuler.pipeline.MeasurementMethod
import com.hklab.airuler.pipeline.state.AppSessionSettings
import com.hklab.airuler.pipeline.state.AppRuntimeState
import com.hklab.airuler.samsungcapture.SamsungPhotoPreviewLoader
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Size as CvSize
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.lifecycle.lifecycleScope
import com.hklab.airuler.net.AirulerResultsUploadClient
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import com.hklab.airuler.GlobalParams
import com.hklab.airuler.log.AirulerFileLogger
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * MeasurementPipeline
 * - 삼성 카메라로 촬영한 사진 Uri를 받아 필름 치수 측정 수행
 * - 측정 결과 프리뷰/오버레이 표시 및 결과 저장
 */
class MeasurementPipeline(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val state: AppRuntimeState,
    private val backgroundExecutor: ExecutorService,
    private val showToast: (String) -> Unit,
    private val appendStatus: (String) -> Unit,
    private val updatePreviewPauseUi: () -> Unit,
    private val setAnalyzerPaused: (Boolean) -> Unit,
    private val updateMeasureButtonsForCurrentState: () -> Unit,
    private val onFinalDecision: (com.hklab.airuler.inspection.InspectionDecision) -> Unit,
    private val getSelectedModel: () -> String?,
    private val getSelectedPreviewSize: () -> Size?,
) {

    /**
     * ✅ “측정 전용” executor
     * - 기존 backgroundExecutor는 InferencePipeline의 IO(Ref 저장 등)와도 공유됩니다.
     * - 만약 특정 이미지에서 OpenCV decode/IO가 block(교착)되면 single-thread executor 전체가 멈추어
     *   이후 모든 측정이 영구적으로 대기 상태가 될 수 있습니다.
     * - 따라서 측정 파이프라인은 별도 executor로 격리하고,
     *   타임아웃(Watchdog) 발생 시 executor를 재생성하여 복구 경로를 확보합니다.
     */
    private val measureExecutorLock = Any()
    @Volatile private var measureExecutor: ExecutorService = newMeasureExecutor()

    private fun newMeasureExecutor(): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "airuler-measure").apply {
            // 디버깅 편의: thread name 고정
            priority = Thread.NORM_PRIORITY
        }
    }

    /** Activity.onDestroy()에서 호출(선택). 호출하지 않아도 동작에는 영향 없음. */
    fun shutdown() {
        runCatching { synchronized(measureExecutorLock) { measureExecutor.shutdownNow() } }
    }

    private fun resetMeasureExecutor(reason: String) {
        synchronized(measureExecutorLock) {
            AirulerFileLogger.w("Measurement", "Reset measure executor. reason=$reason")
            runCatching { measureExecutor.shutdownNow() }
            measureExecutor = newMeasureExecutor()
        }
    }

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var watchdogRunnable: Runnable? = null

    // ✅ 측정 watchdog: 일정 시간 내 결과가 표시되지 않으면(교착/무한 대기) UI/상태를 복구
    // - 정상 케이스에서는 거의 발동하지 않지만, 문제 발생 시 "복구까지 기다리는 시간"을 줄이기 위해
    //   MP에 따라 값을 분리합니다(200MP가 50MP보다 저장/후처리/IO가 느릴 수 있음).
    private val MEASURE_WATCHDOG_MS_50MP = 20_000L
    private val MEASURE_WATCHDOG_MS_200MP = 35_000L
    private fun measureWatchdogMsFor(captureMp: Int): Long =
        if (captureMp >= 200) MEASURE_WATCHDOG_MS_200MP else MEASURE_WATCHDOG_MS_50MP

    // ✅ MediaStore 이미지(특히 50MP/200MP)의 “저장 완료” 안정화 대기 파라미터
    // - 안정화가 끝나면 즉시 진행되므로, 이 값은 "이상 상황에서 기다리는 최대치"입니다.
    private val MEDIA_READY_MAX_WAIT_MS_50MP = 15_000L
    private val MEDIA_READY_MAX_WAIT_MS_200MP = 40_000L
    private fun mediaReadyMaxWaitMsFor(captureMp: Int): Long =
        if (captureMp >= 200) MEDIA_READY_MAX_WAIT_MS_200MP else MEDIA_READY_MAX_WAIT_MS_50MP
    // Polling interval is intentionally small so that “already-ready” images do not incur ~0.7s latency.
    // We still protect against half-written files by requiring a minimum stable-duration below.
    private val MEDIA_READY_POLL_MS = 100L
    private val MEDIA_READY_POLL_BACKOFF_MULT = 1.25
    private val MEDIA_READY_POLL_MAX_MS = 800L
    private val MEDIA_READY_STABLE_COUNT = 2

    // Minimum “stable size” duration before we consider the MediaStore item safe to read.
    // Samsung Camera 50MP can expose the row early; waiting a short stable window avoids rare IO blocks.
    private val MEDIA_READY_MIN_STABLE_MS_50MP = 220L
    private val MEDIA_READY_MIN_STABLE_MS_200MP = 400L

    // ✅ 캡처 오버레이에 마지막으로 표시한 Bitmap을 추적해
    //    교체/닫기 시점에 안전하게 recycle 하여 GC/메모리 스파이크를 줄입니다.
    private var lastCapturedOverlayBitmap: Bitmap? = null

    // ✅ Calibration warm-up 진행 배너
    @Volatile private var calibrationBannerCompletedCount: Int = 0
    @Volatile private var calibrationBannerVisible: Boolean = false
    @Volatile private var calibrationBannerHideOnNextFilmDetected: Boolean = false
    @Volatile private var calibrationBannerAwaitingPreviewClear: Boolean = false
    private val calibrationProgressRegex =
        Regex("""Calibration\s*(\d+)\s*/\s*(\d+)\s*완료""", RegexOption.IGNORE_CASE)

    private data class MediaReadySnapshot(
        val sizeBytes: Long,
        val isPending: Int?
    )

    /**
     * ✅ MediaStore 이미지가 “완전히 저장되어 읽기 가능한 상태”인지 안정화 대기
     * - Samsung 카메라 50MP에서 간헐적으로 파일이 완전히 쓰이기 전에 복귀 Uri가 전달되는 경우가 있어
     *   바로 openInputStream/copy/OpenCV imread 를 수행하면 장시간 block(교착)될 수 있습니다.
     * - 이 함수는 contentResolver.query(SIZE/IS_PENDING)만 사용하므로 UI thread를 block하지 않습니다.
     *
     * @return true: 안정화 완료 / false: timeout 또는 token mismatch
     */
    private fun waitForMediaStoreImageReady(uri: Uri, token: Long, purpose: String): Boolean {
        val start = SystemClock.elapsedRealtime()

        var lastSize = -1L
        var stableSamples = 0
        var stableSinceElapsed = -1L
        var sleep = MEDIA_READY_POLL_MS

        val minStableMs =
            if (GlobalParams.CAPTURE_MP >= 200) MEDIA_READY_MIN_STABLE_MS_200MP else MEDIA_READY_MIN_STABLE_MS_50MP

        val captureMp = GlobalParams.CAPTURE_MP
        val maxWaitMs = mediaReadyMaxWaitMsFor(captureMp)

        AirulerFileLogger.i(
            "Measurement",
            "waitMediaReady start purpose=$purpose token=$token uri=$uri minStableMs=$minStableMs maxWaitMs=$maxWaitMs pollMs=$MEDIA_READY_POLL_MS"
        )

        while (SystemClock.elapsedRealtime() - start < maxWaitMs) {
            if (state.currentSamsungCaptureToken != token) {
                AirulerFileLogger.w("Measurement", "waitMediaReady abort(token mismatch) purpose=$purpose token=$token")
                return false
            }

            val snap = queryMediaReadySnapshot(uri)
            val size = snap?.sizeBytes ?: -1L
            val pending = snap?.isPending
            val now = SystemClock.elapsedRealtime()

            val ready = (size > 0L) && (pending == null || pending == 0)
            if (ready) {
                if (size == lastSize) {
                    stableSamples += 1
                } else {
                    stableSamples = 1
                    stableSinceElapsed = now
                }
                lastSize = size

                val stableForMs = if (stableSinceElapsed >= 0) (now - stableSinceElapsed) else 0L
                if (stableSamples >= MEDIA_READY_STABLE_COUNT && stableForMs >= minStableMs) {
                    val waited = now - start
                    AirulerFileLogger.i(
                        "Measurement",
                        "waitMediaReady OK purpose=$purpose waitedMs=$waited sizeBytes=$size pending=$pending stableSamples=$stableSamples stableForMs=$stableForMs"
                    )
                    return true
                }
            } else {
                stableSamples = 0
                stableSinceElapsed = -1L
                lastSize = size
            }

            SystemClock.sleep(sleep)
            sleep = (sleep * MEDIA_READY_POLL_BACKOFF_MULT).toLong().coerceAtMost(MEDIA_READY_POLL_MAX_MS)
        }


        val waited = SystemClock.elapsedRealtime() - start
        AirulerFileLogger.e(
            "Measurement",
            "waitMediaReady TIMEOUT purpose=$purpose waitedMs=$waited maxWaitMs=$maxWaitMs token=$token uri=$uri"
        )

        // UI/상태 복구(사용자가 Retry 가능)
        activity.runOnUiThread {
            // 더 최신 캡처가 시작되었으면 무시
            if (state.currentSamsungCaptureToken != token) return@runOnUiThread
            handleCalibrationBannerAfterMeasurement(null)
            showToast("사진 저장이 아직 완료되지 않았습니다. 잠시 후 Retry 해주세요.")
            appendStatus("사진 저장 지연/대기 시간 초과(> ${maxWaitMs}ms). Retry 권장")
            state.finishMeasuring(clearPending = false)
            hideCapturedOverlay()
        }
        return false
    }

    private fun queryMediaReadySnapshot(uri: Uri): MediaReadySnapshot? {
        val proj = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                android.provider.MediaStore.Images.Media.SIZE,
                android.provider.MediaStore.Images.Media.IS_PENDING
            )
        } else {
            arrayOf(android.provider.MediaStore.Images.Media.SIZE)
        }
        return try {
            activity.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val sizeIdx = c.getColumnIndex(android.provider.MediaStore.Images.Media.SIZE)
                val pendingIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(android.provider.MediaStore.Images.Media.IS_PENDING)
                } else {
                    -1
                }
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else -1L
                val pending = if (pendingIdx >= 0) c.getInt(pendingIdx) else null
                MediaReadySnapshot(sizeBytes = size, isPending = pending)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun scheduleMeasureWatchdog(
        token: Long,
        uri: Uri,
        capturedAtElapsedMs: Long,
        finalizeAfterMeasure: Boolean,
        watchdogMs: Long,
    ) {
        // 기존 watchdog가 있다면 제거(누적 방지)
        watchdogRunnable?.let { r -> runCatching { mainHandler.removeCallbacks(r) } }

        val scheduledAt = SystemClock.elapsedRealtime()
        val r = Runnable {
            // 다른 캡처가 시작되었으면 무시
            if (state.currentSamsungCaptureToken != token) return@Runnable
            // 이미 결과가 화면에 떠 있으면 무시
            if (state.resultShownToken == token) return@Runnable

            val flags = state.snapshotMeasureFlags()
            if (!flags.measuringNow) return@Runnable

            val elapsed = SystemClock.elapsedRealtime() - capturedAtElapsedMs
            AirulerFileLogger.e(
                "Measurement",
                "WATCHDOG TIMEOUT token=$token elapsedMs=$elapsed watchdogMs=$watchdogMs finalizeAfterMeasure=$finalizeAfterMeasure uri=$uri"
            )

            // ✅ 토큰 무효화: 진행 중이던(또는 향후 늦게 끝나는) 백그라운드 작업이
            //    UI를 덮어쓰지 못하도록 차단
            state.currentSamsungCaptureToken = 0L
            state.resultShownToken = 0L

            // ✅ 상태/UI 복구
            // - pendingMeasureAfterBadBoxPass는 유지(clearPending=false)하여 사용자가 Retry로 다시 측정 가능
            state.finishMeasuring(clearPending = false)

            // GlobalParams는 Settings 값으로 복구(오버라이드가 남아 live preview에 영향 주는 것 방지)
            GlobalParams.applyCaptureMegapixel(AppSessionSettings.captureMegapixel)

            handleCalibrationBannerAfterMeasurement(null)
            showToast("치수 분석이 오래 걸립니다(시간 초과). Retry 해주세요.")
            appendStatus("치수 분석 시간 초과(> ${watchdogMs}ms). Retry 권장")

            // 오버레이 닫기 + Analyzer 재개
            hideCapturedOverlay()
            updateMeasureButtonsForCurrentState()

            // ✅ 다음 측정을 위해 executor를 재생성(교착으로 single thread가 영구 block 되는 경우 대비)
            resetMeasureExecutor("watchdog timeout token=$token")

            // (디버그) watchdog 발동 시점 기록
            val dtFromSchedule = SystemClock.elapsedRealtime() - scheduledAt
            AirulerFileLogger.w("Measurement", "watchdog fired. watchdogMs=$watchdogMs dtFromScheduleMs=$dtFromSchedule")
        }

        watchdogRunnable = r
        mainHandler.postDelayed(r, watchdogMs)
    }

    fun showCapturedOverlay(bmp: Bitmap, dismissOnHand: Boolean = false) {
        state.pendingLivePreviewCapture = false
        state.capturedOverlayVisible = true
        state.dismissCapturedOverlayOnHand = dismissOnHand

        // ✅ 기존 동작: 캡처/결과 오버레이가 떠 있으면 측정 버튼은 항상 숨김
        binding.btnMeasure.visibility = View.GONE
        binding.layoutManualMeasureButtons.visibility = View.GONE

        // ✅ 오버레이가 떠 있는 동안에는 불필요한 Analyzer 콜백을 줄여 발열/배터리 소모를 낮춥니다.
        //  - dismissOnHand=true 인 경우에는 '손 감지로 자동 닫기'가 필요하므로 Analyzer를 켭니다.
        setAnalyzerPaused(!dismissOnHand)

        // 이전 Bitmap은 이미지 교체 후 recycle
        val prev = lastCapturedOverlayBitmap
        lastCapturedOverlayBitmap = bmp
        binding.capturedImageView.setImageBitmap(bmp)
        if (prev != null && prev !== bmp) {
            runCatching { if (!prev.isRecycled) prev.recycle() }
        }

        // ✅ 요청사항(2026-03): 결과 화면의 "50MP로 재측정 / 200MP로 재측정" 버튼은
        //    기능은 남겨두되 UI에서는 항상 숨깁니다.
        binding.layoutRetryResultButtons.visibility = View.GONE

        binding.capturedOverlay.visibility = View.VISIBLE
    }

    fun hideCapturedOverlay() {
        state.dismissCapturedOverlayOnHand = false
        state.pendingLivePreviewCapture = false // ✅ 안전장치
        state.capturedOverlayVisible = false
        // ✅ 오버레이를 닫으면 Analyzer를 정상 동작으로 복구
        setAnalyzerPaused(false)
        updateMeasureButtonsForCurrentState()

        binding.detectionOverlay.visibility = View.VISIBLE
        // ✅ 오버레이 닫을 때 재측정 버튼도 함께 숨김
        binding.layoutRetryResultButtons.visibility = View.GONE
        binding.capturedOverlay.visibility = View.GONE
        binding.capturedImageView.setImageDrawable(null)

        // ✅ ImageView가 Bitmap 참조를 끊은 다음 recycle
        lastCapturedOverlayBitmap?.let { b ->
            runCatching { if (!b.isRecycled) b.recycle() }
        }
        lastCapturedOverlayBitmap = null

        updatePreviewPauseUi()
    }

    fun clearCalibrationBanner() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearCalibrationBannerInternal()
        } else {
            activity.runOnUiThread { clearCalibrationBannerInternal() }
        }
    }

    private fun clearCalibrationBannerInternal() {
        calibrationBannerCompletedCount = 0
        calibrationBannerVisible = false
        calibrationBannerHideOnNextFilmDetected = false
        calibrationBannerAwaitingPreviewClear = false
        binding.txtCalibrationBanner.text = ""
        binding.txtCalibrationBanner.visibility = View.GONE
        binding.txtCalibrationBanner.alpha = 1f
        binding.txtCalibrationBanner.setTextColor(Color.parseColor("#E53935"))
    }

    fun refreshCalibrationBannerForCurrentState() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshCalibrationBannerForCurrentStateInternal()
        } else {
            activity.runOnUiThread { refreshCalibrationBannerForCurrentStateInternal() }
        }
    }

    fun onPreviewFilmDetected(hasFilm: Boolean) {
        if (!calibrationBannerHideOnNextFilmDetected || !calibrationBannerVisible) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handlePreviewFilmDetectedInternal(hasFilm)
        } else {
            activity.runOnUiThread { handlePreviewFilmDetectedInternal(hasFilm) }
        }
    }

    private fun refreshCalibrationBannerForCurrentStateInternal() {
        if (!AppSessionSettings.gridCalibrationEnabled) {
            clearCalibrationBannerInternal()
            return
        }

        val totalCount = GridOnlineOffsetCalibrationStore.WARMUP_SAMPLES
        val completedCount = currentCalibrationCompletedCount().coerceIn(0, totalCount)

        if (completedCount >= totalCount) {
            if (calibrationBannerHideOnNextFilmDetected && calibrationBannerVisible) {
                showCalibrationBanner(completedCount = completedCount, totalCount = totalCount, failed = false)
            } else {
                clearCalibrationBannerInternal()
            }
            return
        }

        calibrationBannerHideOnNextFilmDetected = false
        calibrationBannerAwaitingPreviewClear = false
        showCalibrationBanner(completedCount = completedCount, totalCount = totalCount, failed = false)
    }

    private fun handlePreviewFilmDetectedInternal(hasFilm: Boolean) {
        if (!AppSessionSettings.gridCalibrationEnabled) return
        if (state.capturedOverlayVisible) return
        if (state.measuringNow) return
        if (!calibrationBannerHideOnNextFilmDetected || !calibrationBannerVisible) return

        if (calibrationBannerAwaitingPreviewClear) {
            if (!hasFilm) {
                calibrationBannerAwaitingPreviewClear = false
            }
            return
        }

        if (hasFilm) {
            clearCalibrationBannerInternal()
        }
    }

    private fun handleCalibrationBannerAfterMeasurement(calibrationToastMessage: String?) {
        if (!AppSessionSettings.gridCalibrationEnabled) {
            clearCalibrationBannerInternal()
            return
        }

        val totalCount = GridOnlineOffsetCalibrationStore.WARMUP_SAMPLES
        val progressMatch = calibrationToastMessage
            ?.let { calibrationProgressRegex.find(it) }

        when {
            progressMatch != null -> {
                val completedCount = progressMatch.groupValues[1]
                    .toIntOrNull()
                    ?.coerceIn(0, totalCount)
                    ?: 0
                showCalibrationBanner(completedCount = completedCount, totalCount = totalCount, failed = false)
                calibrationBannerHideOnNextFilmDetected = completedCount >= totalCount
                calibrationBannerAwaitingPreviewClear = completedCount >= totalCount
            }

            !calibrationToastMessage.isNullOrBlank() &&
                calibrationToastMessage.contains("실패", ignoreCase = true) -> {
                val completedCount = currentCalibrationCompletedCount().coerceIn(0, totalCount)
                showCalibrationBanner(completedCount = completedCount, totalCount = totalCount, failed = true)
                calibrationBannerHideOnNextFilmDetected = false
                calibrationBannerAwaitingPreviewClear = false
            }

            !calibrationBannerVisible -> {
                refreshCalibrationBannerForCurrentStateInternal()
            }
        }
    }

    private fun currentCalibrationCompletedCount(): Int {
        val base = getSelectedModel()
            ?.trim()
            ?.substringBefore("_FO")
            ?.takeIf { it.isNotBlank() }
            ?: return calibrationBannerCompletedCount

        return GridOnlineOffsetCalibrationStore.getMaxSampleN(base)
            .coerceAtMost(GridOnlineOffsetCalibrationStore.WARMUP_SAMPLES)
    }

    private fun showCalibrationBanner(
        completedCount: Int,
        totalCount: Int,
        failed: Boolean,
    ) {
        calibrationBannerCompletedCount = completedCount
        calibrationBannerVisible = true

        binding.txtCalibrationBanner.text = buildString {
            append(if (failed) "보정 실패 " else "보정중 ")
            append("(${completedCount}/${totalCount})")
        }
        binding.txtCalibrationBanner.alpha = 1f
        binding.txtCalibrationBanner.setTextColor(Color.parseColor("#E53935"))
        binding.txtCalibrationBanner.visibility = View.VISIBLE
    }

    /** 삼성 카메라 복귀 Uri 처리(프리뷰 표시 + 측정 + 저장) */
    fun onSamsungCaptured(uri: Uri) {
        state.lastSamsungCapturedUri = uri

        val onCapturedAt = SystemClock.elapsedRealtime()
        AirulerFileLogger.i("Measurement", "onSamsungCaptured() uri=$uri")

        // ✅ Retry(재시도) 등에서 "이번 캡처 1회"만 적용되는 오버라이드(MP/Method)를 지원합니다.
        // - MeasurementPipeline이 캡처 Uri를 받는 시점에 오버라이드를 소비(consumed)하여 1회성으로 만듭니다.
        // - Capture MP는 GlobalParams의 px 기반 파라미터(200MP=2배) 및 Expert RAW 4:3→16:9 crop에 영향을 줍니다.
        val (mpOverride, methodOverride) = state.consumeNextCaptureOverrides()
        val effectiveCaptureMp = mpOverride ?: AppSessionSettings.captureMegapixel
        val effectiveMethodRaw = methodOverride ?: AppSessionSettings.measurementMethod
        val effectiveMethod = if (effectiveCaptureMp >= 200) MeasurementMethod.RULER else effectiveMethodRaw

        AirulerFileLogger.i(
            "Measurement",
            "captureOverrides mpOverride=$mpOverride methodOverride=$methodOverride | effectiveMp=$effectiveCaptureMp effectiveMethod=$effectiveMethod"
        )

        // ✅ 측정 파라미터 스케일/Expert RAW crop은 GlobalParams.CAPTURE_MP 기준으로 동작하므로,
        //    실제 캡처 MP(override 포함)를 먼저 반영합니다.
        GlobalParams.applyCaptureMegapixel(effectiveCaptureMp)

        // ✅ 측정 플래그 경쟁 방지: "이번 측정"이 최종 판정에 포함되는지 여부는 시작 시점 스냅샷으로 고정
        //    (측정 중 다른 스레드에서 pending/measuring 값이 바뀌어도 결과 처리/정리가 흔들리지 않게)
        val mf0 = state.snapshotMeasureFlags()
        val finalizeAfterMeasure = mf0.pendingMeasureAfterBadBoxPass

        // 측정이 시작된 캡처라면 measuringNow는 true여야 합니다(안전장치)
        state.setMeasureFlags(pending = mf0.pendingMeasureAfterBadBoxPass, measuring = true)

        val model = getSelectedModel().orEmpty()

        // ✅ 이번 캡처 세션 토큰(프리뷰/분석 콜백이 뒤섞여도 1번만 적용하기 위한 장치)
        val token = SystemClock.elapsedRealtime()
        state.currentSamsungCaptureToken = token
        state.resultShownToken = 0L

        AirulerFileLogger.i(
            "Measurement",
            "captureSession token=$token finalizeAfterMeasure=$finalizeAfterMeasure model='${model}' previewSize=${getSelectedPreviewSize()?.width}x${getSelectedPreviewSize()?.height}"
        )

        // ✅ helper: 최신 캡처(token)에서만 GlobalParams를 Settings 값으로 복구
        fun restoreGlobalParamsIfCurrent() {
            if (state.currentSamsungCaptureToken == token) {
                GlobalParams.applyCaptureMegapixel(AppSessionSettings.captureMegapixel)
            }
        }

        // 1) (절전/발열 저감) 측정 대기 동안에는 캡처 이미지 디코딩/리사이즈를 하지 않고,
        //    "현재 Preview의 마지막 프레임"을 캡처해 오버레이에 고정 표시합니다.
        showSamsungCapturedLoading(token)

        // ✅ 교착/무한 대기 대비 Watchdog
        // - 결과가 일정 시간 내 표시되지 않으면 UI/상태를 복구하고 측정 executor를 재생성
        val watchdogMs = measureWatchdogMsFor(effectiveCaptureMp)
        scheduleMeasureWatchdog(
            token = token,
            uri = uri,
            capturedAtElapsedMs = onCapturedAt,
            finalizeAfterMeasure = finalizeAfterMeasure,
            watchdogMs = watchdogMs,
        )

        // 모델이 없으면 오버레이를 닫고 종료
        if (model.isBlank()) {
            appendStatus("모델이 선택되지 않았습니다")
            state.finishMeasuring(clearPending = false)
            hideCapturedOverlay()
            // 오버라이드 적용 후 조기 종료 시에도 GlobalParams를 복구
            restoreGlobalParamsIfCurrent()
            return
        }

        // ✅ Grid 모드: Grid ROI(TFLite) → Grid 분석(OpenCV) → Grid.json 저장
        if (model.contains("grid", ignoreCase = true)) {
            appendStatus("Grid 분석 시작…")

            val target = getSelectedPreviewSize() ?: Size(1280, 720)
            val tw = target.width
            val th = target.height

            measureExecutor.execute {
                try {
                    // ✅ 파일이 완전히 저장되기 전에 Uri를 읽으면 블로킹/교착이 발생할 수 있어,
                    //    MediaStore SIZE 안정화를 먼저 기다립니다.
                    if (!waitForMediaStoreImageReady(uri, token, purpose = "GridAnalysis")) {
                        restoreGlobalParamsIfCurrent()
                        return@execute
                    }

                    val tProc0 = SystemClock.elapsedRealtime()
                    AirulerFileLogger.i("Measurement", "GridAnalysis start token=$token uri=$uri")

                    val res = GridAnalysisProcessor.run(
                        context = activity,
                        imageUri = uri,
                        scoreThresh = GlobalParams.SCORE_THRESH,
                        iouThresh = GlobalParams.IOU_THRESH,
                        gridPitchMm = 5.0
                    )

                    val tProc1 = SystemClock.elapsedRealtime()
                    AirulerFileLogger.i(
                        "Measurement",
                        "GridAnalysis done token=$token dtMs=${tProc1 - tProc0} rows=${res.rows} cols=${res.cols}"
                    )

                    // 다른 캡처가 시작되었다면(토큰 불일치) 이번 결과는 버림
                    if (state.currentSamsungCaptureToken != token) {
                        runCatching { res.overlay.release() }
                        return@execute
                    }

                    val previewBmp = buildMeasurePreviewBitmapRoiLetterbox(
                        overlayBgr = res.overlay,
                        targetW = tw,
                        targetH = th,
                        detectedRectsPx = listOf(Rect(0, 0, res.overlay.cols(), res.overlay.rows()))
                    )

                    // ✅ Bitmap 생성 후 OpenCV Mat 해제(메모리 스파이크 방지)
                    runCatching { res.overlay.release() }

                    val savedPath = res.savedJsonFile.absolutePath

                    // Logcat: 기존 로그(ROI 포함)는 그대로 유지
                    val logSummary = "Grid 분석 완료: ${res.rows}x${res.cols} points, ROI=${res.roiX1},${res.roiY1},${res.roiX2},${res.roiY2}"
                    Log.i("GridAnalysis", logSummary)
                    for (line in res.logs) {
                        Log.i("GridAnalysis", line)
                    }

                    // Status: 한 줄 요약(숫자 위주, 압축)
                    val centered = res.cellTypeCounts["CENTERED"] ?: 0
                    val original = res.cellTypeCounts["ORIGINAL"] ?: 0
                    val meanStr = res.neighborStats?.mean?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                    val stdStr = res.neighborStats?.std?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                    val histStr = res.neighborStats?.histogram?.entries
                        ?.sortedBy { it.key }
                        ?.joinToString(",") { "${it.key}=${it.value}" }
                        ?: "-"
                    val statusSummary = "Grid완료:${res.rows}x${res.cols},C=${centered},O=${original},m=${meanStr},s=${stdStr},H{${histStr}}"

                    activity.runOnUiThread {
                        showCapturedOverlay(previewBmp, dismissOnHand = false)

                        // ✅ Status는 한 줄 요약만 표시 (요청사항)
                        appendStatus(statusSummary)
                        android.widget.Toast.makeText(activity, "Grid 분석 완료\nGrid.json 저장: $savedPath", android.widget.Toast.LENGTH_LONG).show()

                        // grid 분석은 최종 PASS/FAIL과 연결하지 않습니다.
                        state.finishMeasuring(clearPending = false)
                        updateMeasureButtonsForCurrentState()
                    }
                } catch (e: Exception) {
                    Log.e("GridAnalysis", "Grid 분석 실패", e)
                    AirulerFileLogger.e("Measurement", "GridAnalysis failed token=$token uri=$uri", e)
                    activity.runOnUiThread {
                        appendStatus("Grid 분석 실패: ${e.message}")
                        android.widget.Toast.makeText(activity, "Grid 분석 실패: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        state.finishMeasuring(clearPending = false)
                        updateMeasureButtonsForCurrentState()
                    }
                } finally {
                    // ✅ 최신 캡처에서만 GlobalParams 복구(토큰 mismatch면 새 캡처가 처리)
                    restoreGlobalParamsIfCurrent()
                }
            }
            return
        }

        val target = getSelectedPreviewSize() ?: Size(1280, 720)
        val tw = target.width
        val th = target.height

        val method = effectiveMethod

        if (method == MeasurementMethod.GRID) {
            appendStatus("치수 분석 시작… (Grid 기반)")

            measureExecutor.execute {
                try {
                    if (!waitForMediaStoreImageReady(uri, token, purpose = "MeasureGrid")) {
                        restoreGlobalParamsIfCurrent()
                        return@execute
                    }

                    val tProc0 = SystemClock.elapsedRealtime()
                    AirulerFileLogger.i("Measurement", "MeasureGrid start token=$token uri=$uri")

                    val res = FilmTotalMeasureGridProcessor.run(
                        context = activity,
                        imageUri = uri,
                        modelName = model,
                        scoreThresh = GlobalParams.SCORE_THRESH,
                        iouThresh = GlobalParams.IOU_THRESH
                    )

                    val tProc1 = SystemClock.elapsedRealtime()
                    AirulerFileLogger.i(
                        "Measurement",
                        "MeasureGrid processor returned token=$token dtMs=${tProc1 - tProc0} detectedFilms=${res.detectedFilms}"
                    )

                    // 다른 캡처가 시작되었다면(토큰 불일치) 이번 결과는 버림
                    if (state.currentSamsungCaptureToken != token) {
                        runCatching { res.overlay.release() }
                        return@execute
                    }

                    // ---- 1) 화면 표시용 Bitmap 생성 ----
                    val previewBmp = buildMeasurePreviewBitmapRoiLetterbox(
                        overlayBgr = res.overlay,
                        targetW = tw,
                        targetH = th,
                        detectedRectsPx = res.detectedRectsPx
                    )

                    // ---- 1.5) 저장용 crop 영역(원본 해상도) 계산 ----
                    // - 프리뷰 ROI(필름+주변)와 동일한 union+pad 규칙을 사용
                    val saveCropRect = computeUnionRectWithPad(
                        rects = res.detectedRectsPx,
                        imgW = res.overlay.cols().coerceAtLeast(1),
                        imgH = res.overlay.rows().coerceAtLeast(1)
                    )
                    val saveW = saveCropRect?.width ?: res.overlay.cols()
                    val saveH = saveCropRect?.height ?: res.overlay.rows()

                    // ---- 2) EXIF(UserComment) JSON ----
                    val exifJson = buildFilmMeasureExifJsonGrid(
                        capturedUri = uri,
                        modelName = model,
                        imageW = saveW,
                        imageH = saveH,
                        detectedFilms = res.detectedFilms,
                        gridJsonPath = res.gridJsonPath,
                        gridPitchMm = res.gridPitchMm,
                        logs = res.logs
                    )

                    // ---- 3) UI 업데이트(결과 표시) ----
                    val allPass = isAllMeasurePass(res.logs)
                    val finalDecision = if (allPass) {
                        com.hklab.airuler.inspection.InspectionDecision.PASS
                    } else {
                        com.hklab.airuler.inspection.InspectionDecision.FAIL
                    }

                    // badBox-pass 후 측정 흐름인지 여부
                    val shouldFinalize = finalizeAfterMeasure

                    activity.runOnUiThread {
                        if (state.currentSamsungCaptureToken != token) {
                            runCatching { previewBmp.recycle() }
                            return@runOnUiThread
                        }

                        state.resultShownToken = token

                        // ✅ 결과 오버레이는 손 감지 시 자동으로 닫히게(최종 흐름일 때만)
                        showCapturedOverlay(previewBmp, dismissOnHand = shouldFinalize)
                        handleCalibrationBannerAfterMeasurement(res.calibrationToastMessage)

                        appendStatus(
                            "치수 분석 완료(Grid): films=${res.detectedFilms}, " +
                                    "pitch=${String.format(Locale.US, "%.3f", res.gridPitchMm)}mm " +
                                    "| RESULT=${if (allPass) "PASS" else "FAIL"}"
                        )

                        // ✅ “수동/자동”에서 badBox-pass 후 측정까지 끝났으면 이제 최종 판정 확정
                        if (shouldFinalize) {
                            state.finishMeasuring(clearPending = true)
                            onFinalDecision(finalDecision)
                        } else {
                            state.finishMeasuring(clearPending = false)
                        }
                    }

                    // ---- 4) 결과 저장/업로드 (서로 완전 분리) ----
                    // - Save Result = ON  : DCIM/Result 저장
                    // - Upload to Server = ON : 서버 업로드
                    //   * 저장이 OFF(또는 저장 실패)여도, cache 임시 파일(EXIF 포함) 생성 → 업로드 → 삭제
                    val saveEnabled = AppSessionSettings.saveResultEnabled
                    val uploadEnabled = AppSessionSettings.uploadToServerEnabled

                    // 저장 또는 업로드 중 하나라도 켜져 있으면 결과 JPEG bytes를 한 번만 생성해 재사용
                    val resultJpegBytes: ByteArray? = if (saveEnabled || uploadEnabled) {
                        encodeResultJpegBytes(
                            overlayBgr = res.overlay,
                            cropRect = saveCropRect,
                            jpegQuality = 95
                        )
                    } else {
                        null
                    }

                    // (A) 저장: DCIM/Result
                    val saveResult: ExternalMediaStoreUtils.SaveResult? =
                        if (saveEnabled && resultJpegBytes != null) {
                            runCatching {
                                ExternalMediaStoreUtils.saveJpegBytesToDcimAirulerResultWithExif(
                                    context = activity,
                                    jpegBytes = resultJpegBytes,
                                    exifUserCommentJson = exifJson
                                )
                            }.getOrNull()
                        } else {
                            null
                        }

                    // (B) 업로드: 저장된 파일이 없으면 cache 임시 파일로 업로드
                    val tempUpload: TempUploadFile? =
                        if (uploadEnabled && saveResult == null && resultJpegBytes != null) {
                            createTempUploadJpegWithExif(
                                jpegBytes = resultJpegBytes,
                                exifUserCommentJson = exifJson
                            )
                        } else {
                            null
                        }

                    runCatching { res.overlay.release() }

                    activity.runOnUiThread {
                        if (state.currentSamsungCaptureToken != token) {
                            return@runOnUiThread
                        }

                        // ---- (A) Save Result 안내 ----
                        if (saveEnabled) {
                            if (saveResult != null) {
                                // ✅ 최종 판정(걸린시간/정상·불량 안내) Status가 유지되어야 하므로,
                                //    저장 메시지는 Status에 쓰지 않고 Toast(Short)로만 표시합니다.
                                //    (단, 최종 판정이 아닌 흐름에서는 기존 Status 출력 유지)
                                val savedPath = "${saveResult.relativePath}${saveResult.displayName}"

                                if (!finalizeAfterMeasure) {
                                    appendStatus("저장 완료: $savedPath")
                                }
                            } else {
                                // 최종 판정 Status를 덮어쓰지 않도록 실패도 Toast 위주로 처리
                                showToast("결과 저장 실패")
                                if (!finalizeAfterMeasure) {
                                    appendStatus("결과 저장 실패")
                                }
                            }
                        }

                        // ---- (B) Upload to Server (Save 여부와 독립) ----
                        if (uploadEnabled) {
                            when {
                                saveResult != null -> {
                                    // 저장 성공 → 저장된 파일을 그대로 업로드
                                    startAutoUploadResultImage(saveResult)
                                }
                                tempUpload != null -> {
                                    // 저장 OFF/실패 → 임시 파일 업로드 후 삭제
                                    startAutoUploadResultImage(
                                        uri = tempUpload.uri,
                                        displayName = tempUpload.displayName,
                                        deleteFileAfterUpload = tempUpload.file
                                    )
                                }
                                else -> {
                                    // 업로드를 원하지만 파일 준비 실패(결과 JPEG 생성/EXIF 쓰기 실패 등)
                                    showToast("결과 업로드 실패")
                                    if (!finalizeAfterMeasure) {
                                        appendStatus("결과 업로드 실패")
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("AIRuler", "치수 분석 실패(Grid)", t)
                    AirulerFileLogger.e("Measurement", "MeasureGrid failed token=$token uri=$uri", t)

                    // ✅ 실패 원인 디버깅을 위해: '측정에 사용되는 회전 정책'으로 원본 전체 프리뷰(1280x720) 구성
                    val debugPreview = runCatching {
                        FilmTotalMeasureProcessor.buildRotationDebugPreview(
                            context = activity,
                            imageUri = uri,
                            targetW = tw,
                            targetH = th,
                            forceLandscape = true
                        )
                    }.getOrNull()

                    val failMsg = "치수 분석 실패: ${t.message ?: t.javaClass.simpleName}"
                    val hint = run {
                        val m = (t.message ?: "").lowercase()
                        when {
                            m.contains("grid.json") || m.contains("grid json") ->
                                "원인 추정: Grid.json이 없거나 읽기 실패(먼저 'Grid 분석'을 실행하세요)"
                            m.contains("outside convex hull") || m.contains("convex") || m.contains("warp") ->
                                "원인 추정: Grid warp 범위 밖 좌표(ROI/포인트가 Grid 범위를 벗어남)"
                            m.contains("yolo") || m.contains("detection") || m.contains("roi") ->
                                "원인 추정: ROI(필름) 탐지 실패(프레임/노출/가림)"
                            m.contains("model") || m.contains("tflite") ->
                                "원인 추정: 모델/추론 초기화 문제"
                            else -> "원인 추정: ${t.javaClass.simpleName}"
                        }
                    }

                    activity.runOnUiThread {
                        if (state.currentSamsungCaptureToken != token) {
                            // token mismatch: UI에 표시하지 않을 것이므로 bitmap 메모리 정리
                            debugPreview?.let { runCatching { it.bitmap.recycle() } }
                            return@runOnUiThread
                        }

                        debugPreview?.let {
                            // ✅ raw preview가 뒤늦게 덮어쓰지 않도록 '결과 표시됨'으로 마킹
                            state.resultShownToken = token
                            showCapturedOverlay(it.bitmap, dismissOnHand = true)
                            appendStatus("Rotation(debug): ${it.debugText}")
                        }

                        state.dismissCapturedOverlayOnHand = true

                        if (finalizeAfterMeasure) {
                            state.finishMeasuring(clearPending = true)
                            onFinalDecision(com.hklab.airuler.inspection.InspectionDecision.FAIL)
                        } else {
                            state.finishMeasuring(clearPending = false)
                        }

                        handleCalibrationBannerAfterMeasurement(null)
                        showToast(failMsg)
                        appendStatus(failMsg)
                        appendStatus(hint)
                    }
                } finally {
                    // ✅ 최신 캡처에서만 GlobalParams 복구(토큰 mismatch면 새 캡처가 처리)
                    restoreGlobalParamsIfCurrent()
                }
            }
        } else {
            // ---- 기존 Ruler 기반 측정 (변동 없음) ----
            appendStatus("치수 분석 시작… (Ruler 기반)")

            measureExecutor.execute {
                try {
                    if (!waitForMediaStoreImageReady(uri, token, purpose = "MeasureRuler")) {
                        restoreGlobalParamsIfCurrent()
                        return@execute
                    }

                    val tProc0 = SystemClock.elapsedRealtime()
                    AirulerFileLogger.i("Measurement", "MeasureRuler start token=$token uri=$uri")

                    val res = FilmTotalMeasureProcessor.run(
                        context = activity,
                        imageUri = uri,
                        modelName = model,
                        scoreThresh = GlobalParams.SCORE_THRESH,
                        iouThresh = GlobalParams.IOU_THRESH
                    )

                    val tProc1 = SystemClock.elapsedRealtime()
                    AirulerFileLogger.i(
                        "Measurement",
                        "MeasureRuler processor returned token=$token dtMs=${tProc1 - tProc0} films=${res.detectedFilms}"
                    )

                    // 다른 캡처가 시작되었다면(토큰 불일치) 이번 결과는 버림
                    if (state.currentSamsungCaptureToken != token) {
                        runCatching { res.overlay.release() }
                        return@execute
                    }

                    // ---- 1) 화면 표시용 Bitmap 생성 ----
                    val previewBmp = buildMeasurePreviewBitmapRoiLetterbox(
                        overlayBgr = res.overlay,
                        targetW = tw,
                        targetH = th,
                        detectedRectsPx = res.detectedRectsPx
                    )

                    // ---- 1.5) 저장용 crop 영역(원본 해상도) 계산 ----
                    // - 프리뷰 ROI(필름+주변)와 동일한 union+pad 규칙을 사용
                    val saveCropRect = computeUnionRectWithPad(
                        rects = res.detectedRectsPx,
                        imgW = res.overlay.cols().coerceAtLeast(1),
                        imgH = res.overlay.rows().coerceAtLeast(1)
                    )
                    val saveW = saveCropRect?.width ?: res.overlay.cols()
                    val saveH = saveCropRect?.height ?: res.overlay.rows()

                    // ---- 2) EXIF(UserComment) JSON ----
                    val exifJson = buildFilmMeasureExifJson(
                        capturedUri = uri,
                        modelName = model,
                        imageW = saveW,
                        imageH = saveH,
                        detectedFilms = res.detectedFilms,
                        pixelsPerMmH = res.pixelsPerMmH,
                        pixelsPerMmV = res.pixelsPerMmV,
                        logs = res.logs
                    )

                    // ---- 3) UI 업데이트(결과 표시) ----
                    val allPass = isAllMeasurePass(res.logs)
                    val finalDecision = if (allPass) {
                        com.hklab.airuler.inspection.InspectionDecision.PASS
                    } else {
                        com.hklab.airuler.inspection.InspectionDecision.FAIL
                    }

                    // badBox-pass 후 측정 흐름인지 여부
                    val shouldFinalize = finalizeAfterMeasure

                    activity.runOnUiThread {
                        if (state.currentSamsungCaptureToken != token) {
                            runCatching { previewBmp.recycle() }
                            return@runOnUiThread
                        }

                        state.resultShownToken = token

                        // ✅ 결과 오버레이는 손 감지 시 자동으로 닫히게(최종 흐름일 때만)
                        showCapturedOverlay(previewBmp, dismissOnHand = shouldFinalize)
                        handleCalibrationBannerAfterMeasurement(res.calibrationToastMessage)

                        appendStatus(
                            "치수 분석 완료: films=${res.detectedFilms}, " +
                                    "H=${"%.3f".format(res.pixelsPerMmH)}px/mm, " +
                                    "V=${"%.3f".format(res.pixelsPerMmV)}px/mm " +
                                    "| RESULT=${if (allPass) "PASS" else "FAIL"}"
                        )

                        // ✅ “수동/자동”에서 badBox-pass 후 측정까지 끝났으면 이제 최종 판정 확정
                        if (shouldFinalize) {
                            state.finishMeasuring(clearPending = true)

                            onFinalDecision(finalDecision)
                        } else {
                            // 측정은 끝났지만(예: 향후 확장), pending을 유지해야 하는 경우
                            state.finishMeasuring(clearPending = false)
                        }
                    }

                    // ---- 4) 결과 저장/업로드 (서로 완전 분리) ----
                    // - Save Result = ON  : DCIM/Result 저장
                    // - Upload to Server = ON : 서버 업로드
                    //   * 저장이 OFF(또는 저장 실패)여도, cache 임시 파일(EXIF 포함) 생성 → 업로드 → 삭제
                    val saveEnabled = AppSessionSettings.saveResultEnabled
                    val uploadEnabled = AppSessionSettings.uploadToServerEnabled

                    // 저장 또는 업로드 중 하나라도 켜져 있으면 결과 JPEG bytes를 한 번만 생성해 재사용
                    val resultJpegBytes: ByteArray? = if (saveEnabled || uploadEnabled) {
                        encodeResultJpegBytes(
                            overlayBgr = res.overlay,
                            cropRect = saveCropRect,
                            jpegQuality = 95
                        )
                    } else {
                        null
                    }

                    // (A) 저장: DCIM/Result
                    val saveResult: ExternalMediaStoreUtils.SaveResult? =
                        if (saveEnabled && resultJpegBytes != null) {
                            runCatching {
                                ExternalMediaStoreUtils.saveJpegBytesToDcimAirulerResultWithExif(
                                    context = activity,
                                    jpegBytes = resultJpegBytes,
                                    exifUserCommentJson = exifJson
                                )
                            }.getOrNull()
                        } else {
                            null
                        }

                    // (B) 업로드: 저장된 파일이 없으면 cache 임시 파일로 업로드
                    val tempUpload: TempUploadFile? =
                        if (uploadEnabled && saveResult == null && resultJpegBytes != null) {
                            createTempUploadJpegWithExif(
                                jpegBytes = resultJpegBytes,
                                exifUserCommentJson = exifJson
                            )
                        } else {
                            null
                        }

                    runCatching { res.overlay.release() }

                    activity.runOnUiThread {
                        if (state.currentSamsungCaptureToken != token) {
                            return@runOnUiThread
                        }

                        // ---- (A) Save Result 안내 ----
                        if (saveEnabled) {
                            if (saveResult != null) {
                                val savedPath = "${saveResult.relativePath}${saveResult.displayName}"
                                if (!finalizeAfterMeasure) {
                                    appendStatus("저장 완료: $savedPath")
                                }
                            } else {
                                showToast("결과 저장 실패")
                                if (!finalizeAfterMeasure) {
                                    appendStatus("결과 저장 실패")
                                }
                            }
                        }

                        // ---- (B) Upload to Server (Save 여부와 독립) ----
                        if (uploadEnabled) {
                            when {
                                saveResult != null -> {
                                    // 저장 성공 → 저장된 파일을 그대로 업로드
                                    startAutoUploadResultImage(saveResult)
                                }
                                tempUpload != null -> {
                                    // 저장 OFF/실패 → 임시 파일 업로드 후 삭제
                                    startAutoUploadResultImage(
                                        uri = tempUpload.uri,
                                        displayName = tempUpload.displayName,
                                        deleteFileAfterUpload = tempUpload.file
                                    )
                                }
                                else -> {
                                    showToast("결과 업로드 실패")
                                    if (!finalizeAfterMeasure) {
                                        appendStatus("결과 업로드 실패")
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("AIRuler", "치수 분석 실패", t)
                    AirulerFileLogger.e("Measurement", "MeasureRuler failed token=$token uri=$uri", t)

                    // ✅ 실패 원인 디버깅을 위해: '측정에 사용되는 회전 정책'으로 원본 전체 프리뷰(1280x720) 구성
                    val debugPreview = runCatching {
                        FilmTotalMeasureProcessor.buildRotationDebugPreview(
                            context = activity,
                            imageUri = uri,
                            targetW = tw,
                            targetH = th,
                            forceLandscape = true
                        )
                    }.getOrNull()

                    val failMsg = "치수 분석 실패: ${t.message ?: t.javaClass.simpleName}"
                    val hint = run {
                        val m = (t.message ?: "").lowercase()
                        when {
                            m.contains("tick") || m.contains("가로") || m.contains("세로") || m.contains("ruler") ->
                                "원인 추정: ruler tick/스케일 검증 실패(회전/흐림/노이즈/배경 엣지 영향 가능)"
                            m.contains("yolo") || m.contains("detection") || m.contains("roi") ->
                                "원인 추정: ROI(필름) 탐지 실패(프레임/노출/가림)"
                            m.contains("model") || m.contains("tflite") ->
                                "원인 추정: 모델/추론 초기화 문제"
                            else -> "원인 추정: ${t.javaClass.simpleName}"
                        }
                    }

                    activity.runOnUiThread {
                        if (state.currentSamsungCaptureToken != token) {
                            // token mismatch: UI에 표시하지 않을 것이므로 bitmap 메모리 정리
                            debugPreview?.let { runCatching { it.bitmap.recycle() } }
                            return@runOnUiThread
                        }

                        // ✅ 실패 시에도: '측정에 사용된 회전 정책' 프리뷰를 보여줘서
                        //    회전 문제인지/다른 문제인지 사용자가 육안으로 확인 가능
                        debugPreview?.let {
                            // ✅ raw preview가 뒤늦게 덮어쓰지 않도록 '결과 표시됨'으로 마킹
                            state.resultShownToken = token
                            showCapturedOverlay(it.bitmap, dismissOnHand = true)
                            appendStatus("Rotation(debug): ${it.debugText}")
                        }

                        // ✅ 실패 시에도 "손 감지"로 오버레이가 자동 닫히게 (성공 결과와 동일 UX)
                        //    - 현재 화면을 탭하지 않아도 손이 감지되면 preview로 복귀
                        state.dismissCapturedOverlayOnHand = true

                        if (finalizeAfterMeasure) {
                            state.finishMeasuring(clearPending = true)
                            onFinalDecision(com.hklab.airuler.inspection.InspectionDecision.FAIL)
                        } else {
                            state.finishMeasuring(clearPending = false)
                        }

                        handleCalibrationBannerAfterMeasurement(null)
                        showToast(failMsg)
                        appendStatus(failMsg)
                        appendStatus(hint)
                    }
                } finally {
                    // ✅ 최신 캡처에서만 GlobalParams 복구(토큰 mismatch면 새 캡처가 처리)
                    restoreGlobalParamsIfCurrent()
                }
            }
        }
    }


    /**
     * ✅ 측정 대기 UI
     * - 치수 측정이 진행되는 동안(=사용자가 기다리는 동안)에는 별도의 drawable/gif를 띄우지 않고,
     *   "현재 Preview의 마지막 프레임"을 1회 캡처해서 오버레이에 고정 표시합니다.
     * - 이때 Analyzer도 pause하여 CameraX의 RGBA 변환/콜백 자체를 줄여 발열/배터리를 낮춥니다.
     */
    private fun showSamsungCapturedLoading(captureToken: Long) {
        state.pendingLivePreviewCapture = false
        state.capturedOverlayVisible = true
        state.dismissCapturedOverlayOnHand = false

        // ✅ 오버레이가 떠 있으면 측정 버튼은 숨김(기존 UX)
        binding.btnMeasure.visibility = View.GONE
        binding.layoutManualMeasureButtons.visibility = View.GONE

        // ✅ 로딩(분석 중)에는 "재측정" 버튼을 숨깁니다.
        binding.layoutRetryResultButtons.visibility = View.GONE

        // 기존 라이브 오버레이 뷰는 숨김
        binding.overlayImageView.visibility = View.GONE
        binding.detectionOverlay.visibility = View.GONE

        // 캡처 오버레이 표시
        binding.capturedOverlay.visibility = View.VISIBLE
        binding.capturedImageView.visibility = View.VISIBLE

        // Bitmap 참조 해제 + recycle
        binding.capturedImageView.setImageDrawable(null)
        lastCapturedOverlayBitmap?.let { b ->
            runCatching { if (!b.isRecycled) b.recycle() }
        }
        lastCapturedOverlayBitmap = null

        // ✅ "현재 Preview 마지막 프레임"을 캡처해서 표시
        // - PreviewView.bitmap 은 null 일 수 있으므로 방어적으로 처리
        val bmp = runCatching { binding.previewView.bitmap }.getOrNull()
        if (bmp != null) {
            lastCapturedOverlayBitmap = bmp
            binding.capturedImageView.setImageBitmap(bmp)
        } else {
            // 캡처 실패 시에는(드문 케이스) 검정 배경만 보이게 둠
            binding.capturedImageView.setImageDrawable(null)
        }

        // ✅ dismissOnHand=false 이므로 Analyzer는 pause
        setAnalyzerPaused(true)
    }


    private fun showSamsungCapturedPreview(uri: Uri, captureToken: Long) {
        state.capturedOverlayVisible = true

        // 기존 라이브 오버레이 뷰는 잠깐 숨김(원하면 유지 가능)
        binding.overlayImageView.visibility = View.GONE
        binding.detectionOverlay.visibility = View.GONE
        binding.capturedImageView.visibility = View.VISIBLE
        binding.capturedImageView.setImageDrawable(null)

        // ✅ fixed5에서 'post{}' 안에서 프리뷰 로드를 시작해서,
        //    같은 executor에서 측정이 먼저 시작되면 프리뷰가 뒤늦게 뜨는 문제가 있었습니다.
        //    → post 제거: 프리뷰 로드 태스크를 즉시 enqueue 하여 “프리뷰가 먼저” 뜨게 합니다.
        val target = getSelectedPreviewSize() ?: Size(1280, 720)
        val tw = target.width
        val th = target.height

        backgroundExecutor.execute {
            // 파일이 막 저장/후처리되는 타이밍이면 아직 읽기 불가일 수 있어, 시간 예산으로 재시도
            val start = SystemClock.elapsedRealtime()
            val maxWaitMs = 15_000L

            var result: SamsungPhotoPreviewLoader.Result? = null
            var sleepMs = 150L

            while (SystemClock.elapsedRealtime() - start < maxWaitMs) {
                result = runCatching {
                    SamsungPhotoPreviewLoader.loadPreviewBitmap(
                        context = activity,
                        uri = uri,
                        targetWidth = tw,
                        targetHeight = th,
                        forceLandscape = true
                    )
                }.getOrNull()

                if (result != null) break

                SystemClock.sleep(sleepMs)
                sleepMs = (sleepMs * 1.4).toLong().coerceAtMost(900L)
            }

            val finalRes = result
            activity.runOnUiThread {
                // ✅ 이미 결과(annotated)가 화면에 떠 있으면 raw preview가 덮어쓰지 않도록 차단
                if (state.resultShownToken == captureToken) return@runOnUiThread

                // ✅ 더 최신 캡처가 들어온 경우(토큰 불일치) 이 프리뷰는 무시
                if (state.currentSamsungCaptureToken != captureToken) return@runOnUiThread

                if (finalRes == null) {
                    showToast("프리뷰 로드 실패(저장 지연/권한/형식 확인)")
                    hideCapturedOverlay()
                    return@runOnUiThread
                }

                val w0 = finalRes.originalWidth
                val h0 = finalRes.originalHeight

                // ✅ 50MP 모드에서는 기존대로 8160x4592(16:9) 권장 경고를 유지
                // ✅ 200MP(Expert RAW) 모드에서는 해당 경고를 띄우지 않음(의도한 해상도이므로)
                if (AppSessionSettings.captureMegapixel < 200) {
                    val is50mp169 = (w0 == 8160 && h0 == 4592) || (w0 == 4592 && h0 == 8160)
                    if (!is50mp169) {
                        showToast("권장 해상도 아님: ${w0}x${h0} (권장 8160x4592)")
                    }
                }

                showCapturedOverlay(finalRes.bitmap)

                val tag = if (AppSessionSettings.captureMegapixel >= 200) "Expert RAW JPEG" else "Samsung JPEG"
                appendStatus("${tag}: ${w0}x${h0} (Uri 기반 표시 완료)")
            }
        }
    }

    private fun isAllMeasurePass(logs: List<String>): Boolean {
        return logs.none { line ->
            line.contains("FAIL", ignoreCase = true) || line.contains("NG", ignoreCase = true)
        }
    }

    // 업로드 전용 임시 파일(저장 OFF 시 사용)
    private data class TempUploadFile(
        val file: File,
        val uri: Uri,
        val displayName: String
    )

    /**
     * 결과 JPEG bytes + EXIF(UserComment) JSON을 포함하는 임시 파일을 cache에 생성합니다.
     * - Save Result = OFF 이지만 Upload to Server = ON 인 경우에 사용됩니다.
     * - 업로드 완료 후 즉시 삭제할 수 있도록 File 객체도 함께 반환합니다.
     */
    private fun createTempUploadJpegWithExif(
        jpegBytes: ByteArray,
        exifUserCommentJson: String
    ): TempUploadFile? {
        var outFile: File? = null
        return try {
            // externalCacheDir가 있으면(대용량 이미지) 우선 사용
            val dir = activity.externalCacheDir ?: activity.cacheDir
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val displayName = "Result_${ts}.jpg"
            outFile = File(dir, displayName)

            FileOutputStream(outFile!!).use { fos ->
                fos.write(jpegBytes)
                fos.flush()
            }

            // EXIF(UserComment) 쓰기
            val exif = ExifInterface(outFile!!.absolutePath)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, exifUserCommentJson)
            exif.saveAttributes()

            TempUploadFile(
                file = outFile!!,
                uri = Uri.fromFile(outFile!!),
                displayName = displayName
            )
        } catch (e: Exception) {
            android.util.Log.w("AIRuler", "createTempUploadJpegWithExif failed", e)
            outFile?.let { runCatching { it.delete() } }
            null
        }
    }

    /**
     * 측정 결과 이미지(DCIM/Result에 저장된 파일)를 서버로 자동 업로드합니다.
     * - Gallery 화면에서 Result 선택 후 Upload 버튼을 누르는 동작과 동일하게
     *   AirulerResultsUploadClient를 호출합니다.
     */
    private fun startAutoUploadResultImage(save: ExternalMediaStoreUtils.SaveResult) {
        startAutoUploadResultImage(
            uri = save.uri,
            displayName = save.displayName,
            deleteFileAfterUpload = null
        )
    }

    /**
     * 측정 결과 이미지(저장 파일/임시 파일 모두)를 서버로 자동 업로드합니다.
     * - Upload to Server = ON 일 때만 수행
     * - deleteFileAfterUpload 가 지정되면 업로드 완료(성공/실패 무관) 후 삭제
     */
    private fun startAutoUploadResultImage(
        uri: Uri,
        displayName: String,
        deleteFileAfterUpload: File? = null
    ) {
        // ✅ Settings: Upload to Server가 OFF이면 자동 업로드를 수행하지 않습니다.
        if (!AppSessionSettings.uploadToServerEnabled) {
            // 업로드 OFF인데 임시 파일이 넘어왔다면 즉시 삭제(누수 방지)
            deleteFileAfterUpload?.let { runCatching { it.delete() } }
            return
        }

        // Activity가 이미 종료되는 상황이면 업로드를 시작하지 않음
        if (activity.isFinishing || activity.isDestroyed) {
            deleteFileAfterUpload?.let { runCatching { it.delete() } }
            return
        }

        activity.lifecycleScope.launch {
            try {
                // ✅ 최종 판정 Status(걸린시간/정상·불량 안내)를 덮어쓰지 않기 위해,
                //    업로드 결과 메시지는 Toast(Short)로만 노출합니다.
                //    (단, 최종 판정과 무관한 흐름에서는 기존처럼 Status에도 표시)
                val res = AirulerResultsUploadClient.uploadResults(activity, listOf(uri))
                val msg = "${res.message}"
                //val msg = "$displayName ${res.message}"

                if (state.decidedInspection != null) {
                    // 최종 판정이 떠 있는 동안에는 Status를 건드리지 않음
                    showToast(msg)
                } else {
                    // Grid 분석 등: 기존 UX 유지
                    appendStatus(msg)
                    showToast(msg)
                }
            } finally {
                // ✅ 임시 업로드 파일은 업로드 후 삭제
                deleteFileAfterUpload?.let { f ->
                    runCatching { f.delete() }
                }
            }
        }
    }

    // ---------------- 유틸 ----------------
    private fun computeUnionRectWithPad(
        rects: List<Rect>,
        imgW: Int,
        imgH: Int,
        padRatio: Double = 0.03,
        minPadPx: Int = 20
    ): Rect? {
        if (rects.isEmpty()) return null

        var x1 = Int.MAX_VALUE
        var y1 = Int.MAX_VALUE
        var x2 = 0
        var y2 = 0

        for (r in rects) {
            x1 = kotlin.math.min(x1, r.x)
            y1 = kotlin.math.min(y1, r.y)
            x2 = kotlin.math.max(x2, r.x + r.width)
            y2 = kotlin.math.max(y2, r.y + r.height)
        }

        if (x1 >= x2 || y1 >= y2) return null

        val unionW = x2 - x1
        val unionH = y2 - y1
        val pad = kotlin.math.max(minPadPx, (kotlin.math.max(unionW, unionH) * padRatio).toInt())

        val nx1 = (x1 - pad).coerceIn(0, imgW - 1)
        val ny1 = (y1 - pad).coerceIn(0, imgH - 1)
        val nx2 = (x2 + pad).coerceIn(nx1 + 1, imgW)
        val ny2 = (y2 + pad).coerceIn(ny1 + 1, imgH)

        return Rect(nx1, ny1, nx2 - nx1, ny2 - ny1)
    }


    /**
     * 저장용 결과 이미지를 "프리뷰에서 보여준 ROI 영역"으로만 crop하여 JPEG bytes로 인코딩합니다.
     * - 중요: 해상도는 낮추지 않고, 원본(res.overlay)에서 crop만 합니다.
     * - cropRect가 null이면 전체 overlay를 그대로 저장합니다.
     */
    private fun encodeResultJpegBytes(
        overlayBgr: Mat,
        cropRect: Rect?,
        jpegQuality: Int = 95
    ): ByteArray? {
        val matToEncode: Mat = if (cropRect != null) overlayBgr.submat(cropRect) else overlayBgr
        return try {
            val mob = MatOfByte()
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality)
            val ok = Imgcodecs.imencode(".jpg", matToEncode, mob, params)
            params.release()
            if (!ok) {
                mob.release()
                null
            } else {
                val bytes = mob.toArray()
                mob.release()
                bytes
            }
        } finally {
            if (matToEncode !== overlayBgr) {
                runCatching { matToEncode.release() }
            }
        }
    }

    private fun buildMeasurePreviewBitmapRoiLetterbox(
        overlayBgr: Mat,
        targetW: Int,
        targetH: Int,
        detectedRectsPx: List<Rect>
    ): Bitmap {
        val imgW = overlayBgr.cols().coerceAtLeast(1)
        val imgH = overlayBgr.rows().coerceAtLeast(1)

        val union = computeUnionRectWithPad(
            rects = detectedRectsPx,
            imgW = imgW,
            imgH = imgH
        )

        // ROI(BGR)
        val roiMat: Mat = if (union != null) overlayBgr.submat(union) else overlayBgr

        val roiW = roiMat.cols().coerceAtLeast(1)
        val roiH = roiMat.rows().coerceAtLeast(1)

        // target(1280x720) 안에 "비율 유지"로 fit
        val scale = kotlin.math.min(targetW.toDouble() / roiW, targetH.toDouble() / roiH)
        val resizedW = kotlin.math.max(1, (roiW * scale).toInt()).coerceAtMost(targetW)
        val resizedH = kotlin.math.max(1, (roiH * scale).toInt()).coerceAtMost(targetH)

        val resized = Mat()
        // ✅ 업스케일(zoom-in)일 때 INTER_AREA는 “화소가 깨져 보이는” 경우가 있어
        //    스케일 방향에 따라 보간법을 분기합니다.
        val interp = if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
        Imgproc.resize(
            roiMat,
            resized,
            CvSize(resizedW.toDouble(), resizedH.toDouble()),
            0.0,
            0.0,
            interp
        )

        if (roiMat !== overlayBgr) roiMat.release()

        // letterbox canvas (BGR)
        val canvas = Mat.zeros(targetH, targetW, overlayBgr.type())
        val x = (targetW - resizedW) / 2
        val y = (targetH - resizedH) / 2

        val dstRoi = canvas.submat(Rect(x, y, resizedW, resizedH))
        resized.copyTo(dstRoi)
        dstRoi.release()
        resized.release()

        // RGBA -> Bitmap
        val rgba = Mat()
        Imgproc.cvtColor(canvas, rgba, Imgproc.COLOR_BGR2RGBA)
        canvas.release()

        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()

        return bmp
    }

    private fun buildFilmMeasureExifJson(
        capturedUri: Uri,
        modelName: String,
        imageW: Int,
        imageH: Int,
        detectedFilms: Int,
        pixelsPerMmH: Double,
        pixelsPerMmV: Double,
        logs: List<String>
    ): String {
        return buildFilmMeasureExifJsonV2(
            capturedUri = capturedUri,
            modelName = modelName,
            measureMethod = "RULER",
            imageW = imageW,
            imageH = imageH,
            detectedFilms = detectedFilms,
            methodDetail = JSONObject().apply {
                put("pixelsPerMm", JSONObject().apply {
                    put("h", pixelsPerMmH)
                    put("v", pixelsPerMmV)
                })
            },
            logs = logs
        )
    }

    private fun buildFilmMeasureExifJsonGrid(
        capturedUri: Uri,
        modelName: String,
        imageW: Int,
        imageH: Int,
        detectedFilms: Int,
        gridJsonPath: String,
        gridPitchMm: Double,
        logs: List<String>
    ): String {
        return buildFilmMeasureExifJsonV2(
            capturedUri = capturedUri,
            modelName = modelName,
            measureMethod = "GRID",
            imageW = imageW,
            imageH = imageH,
            detectedFilms = detectedFilms,
            // ✅ 요청사항: 결과 JSON에는 grid{gridJsonPath, pitchMm} 를 저장하지 않음
            methodDetail = null,
            logs = logs
        )
    }

    private data class ParsedMeasureLine(
        val filmIndex: Int,
        val index: String,
        val direction: String,
        val valueMm: Double,
        val gtMm: Double?,
        val errMm: Double?,
        val marginMm: Double?,
        val result: String?,
        /**
         * ✅ EXIF(UserComment) JSON 저장 시 사용할 offset 조회용 원본 키.
         *
         * - 일반 모델: index == offsetMeasureIndex
         * - L1824-03/L1825-03 (double-film split):
         *   - index: "CP1" 처럼 suffix(-1/-2) 제거된 값
         *   - offsetMeasureIndex: "CP1-1" 또는 "CP1-2" 처럼 원본 measure 키
         */
        val offsetMeasureIndex: String = index,
        /**
         * ✅ offset 조회용 원본 film index.
         *
         * - 일반 모델: filmIndex == offsetFilmIndex
         * - L1824-03/L1825-03 (double-film split):
         *   - filmIndex: split 후 film 번호(1..N)
         *   - offsetFilmIndex: YOLO 검출 film 번호(원본 Film#)
         */
        val offsetFilmIndex: Int = filmIndex,
    )

    /**
     * EXIF(UserComment)에 넣을 결과 JSON (v2)
     * - 기존 logs 중심 포맷 대신, Film/Measure별 구조화된 결과를 저장합니다.
     * - Measure Index는 로그에 있는 "M..." 접두를 제거하여 저장합니다. (예: MCP1 -> CP1)
     */
    private fun buildFilmMeasureExifJsonV2(
        capturedUri: Uri,
        modelName: String,
        measureMethod: String, // "RULER" | "GRID"
        imageW: Int,
        imageH: Int,
        detectedFilms: Int,
        methodDetail: JSONObject?,
        logs: List<String>
    ): String {
        val nowMs = System.currentTimeMillis()
        val nowStr = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs)) }
            .getOrDefault(nowMs.toString())

        val androidId = runCatching {
            Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        // ✅ EXIF(UserComment) 결과 JSON에는 'adjust' 대신, 실제 적용된 'offset' 값을 기록합니다.
        // - offset은 measure별 + film별로 달라질 수 있으므로 (measureIndex, filmIndex)로 조회합니다.
        val modelBase = modelName.trim().substringBefore("_FO")
        val offsetSnapshot = GridOnlineOffsetCalibrationStore.snapshotOffsets(modelBase)
        val offsetMapFallback = loadMeasureOffsetMap(modelName)

        // ✅ (요구사항)
        // L1824-03 / L1825-03 모델은 1개의 "검출 film"(YOLO bbox) 안에 실제 film이 2장(-1/-2) 포함됩니다.
        // - measure index의 접미사 "-1" / "-2" 는 "필름 번호"를 의미
        // - 결과 JSON에는 이를 split 하여 film 번호를 재부여하여 저장
        //   (검출 Film#1: -1 => Film#1, -2 => Film#2)
        //   (검출 Film#2: -1 => Film#3, -2 => Film#4) ...
        val isDoubleFilmSplitModel: Boolean = run {
            val b = modelBase.trim()
            b.equals("L1824-03", ignoreCase = true) || b.equals("L1825-03", ignoreCase = true)
        }

        val reDoubleFilmSuffix = Regex("^(.*)-(1|2)$")

        fun stripDoubleFilmSuffix(idx: String): String {
            val t = idx.trim()
            val m = reDoubleFilmSuffix.find(t)
            return if (m != null) m.groupValues[1].trim() else t
        }

        fun normalizeMeasureOrderForDoubleFilm(order: List<String>): List<String> {
            val ordered = LinkedHashSet<String>()
            for (x in order) {
                val baseIdx = stripDoubleFilmSuffix(x)
                if (baseIdx.isNotBlank()) ordered.add(baseIdx)
            }
            return ordered.toList()
        }

        fun splitParsedMeasuresForDoubleFilm(
            original: Map<Int, List<ParsedMeasureLine>>
        ): Map<Int, List<ParsedMeasureLine>> {
            val out = LinkedHashMap<Int, MutableList<ParsedMeasureLine>>()

            // 원본(검출) film index 기준으로 안정적으로 순회
            val entries = original.entries.sortedBy { it.key }
            for ((origFilmIdx, measures) in entries) {
                for (m in measures) {
                    val rawIndex = m.index.trim()
                    val mo = reDoubleFilmSuffix.find(rawIndex)

                    if (mo != null) {
                        val baseIdx = mo.groupValues[1].trim()
                        val suffix = mo.groupValues[2].toIntOrNull() ?: 1

                        // (검출 Film#k) 의 -1 => Film#(2k-1), -2 => Film#(2k)
                        val newFilmIdx = (origFilmIdx - 1) * 2 + suffix

                        val nm = m.copy(
                            filmIndex = newFilmIdx,
                            index = baseIdx,
                            offsetMeasureIndex = rawIndex,
                            offsetFilmIndex = origFilmIdx,
                        )
                        out.getOrPut(newFilmIdx) { mutableListOf() }.add(nm)
                    } else {
                        // 방어적 fallback: suffix가 없으면 첫 번째 film(-1)로 귀속
                        val newFilmIdx = (origFilmIdx - 1) * 2 + 1
                        val nm = m.copy(
                            filmIndex = newFilmIdx,
                            offsetMeasureIndex = rawIndex,
                            offsetFilmIndex = origFilmIdx,
                        )
                        out.getOrPut(newFilmIdx) { mutableListOf() }.add(nm)
                    }
                }
            }

            // key 오름차순으로 정렬된 Map 반환
            val sorted = LinkedHashMap<Int, List<ParsedMeasureLine>>()
            out.keys.sorted().forEach { k ->
                sorted[k] = out[k]?.toList().orEmpty()
            }
            return sorted
        }

        // ✅ 모델명.json에 적힌 measure 정의 순서를 EXIF 결과에도 그대로 반영
        val measureOrderRaw = loadMeasureIndexOrder(modelName)
        val measureOrder = if (isDoubleFilmSplitModel) {
            normalizeMeasureOrderForDoubleFilm(measureOrderRaw)
        } else {
            measureOrderRaw
        }
        val measureOrderRank: Map<String, Int> = if (measureOrder.isNotEmpty()) {
            measureOrder.withIndex().associate { (i, name) -> name to i }
        } else {
            emptyMap()
        }

        val parsedRawMap = parseMeasureLinesFromLogs(logs)
        val parsedMap: Map<Int, List<ParsedMeasureLine>> = if (isDoubleFilmSplitModel) {
            splitParsedMeasuresForDoubleFilm(parsedRawMap)
        } else {
            parsedRawMap
        }
        val parsedEntries = parsedMap.entries.sortedBy { it.key }

        // ✅ 결과 JSON 기준 detectedFilms (double-film split 모델은 *2)
        val detectedFilmsForJson = if (isDoubleFilmSplitModel) detectedFilms * 2 else detectedFilms

        // --- decisionPerFilm / decision (PASS/FAIL)
        // decisionPerFilm: detectedFilms 개수만큼 순서대로 저장
        // - 각 film: measures 의 result 가 모두 PASS 이면 PASS, 하나라도 FAIL(또는 result 누락) 이면 FAIL
        // decision: 모든 film 이 PASS 인 경우에만 PASS, 하나라도 FAIL 이면 FAIL
        fun isFilmPass(filmIndex: Int): Boolean {
            val measures = parsedMap[filmIndex]
            if (measures.isNullOrEmpty()) return false
            return measures.all { it.result == "PASS" }
        }

        val decisionPerFilm = JSONArray().apply {
            for (filmIdx in 1..detectedFilmsForJson) {
                put(if (isFilmPass(filmIdx)) "PASS" else "FAIL")
            }
        }
        val decision = if (
            detectedFilmsForJson > 0 && (1..detectedFilmsForJson).all { isFilmPass(it) }
        ) "PASS" else "FAIL"

        val root = JSONObject().apply {
            put("schema", "AIRulerFilmMeasureResult/v2")
            put("appName", "AIRuler")

            // 시간은 사람이 읽기 쉬운 문자열 + 원본 ms 둘 다 저장
            put("timestamp", nowStr)
            put("timestampMs", nowMs)

            // 요청: 디바이스를 구분할 수 있는 ID값
            // - ANDROID_ID를 우선 저장 (없으면 빈 문자열)
            put("deviceId", androidId)

            // ✅ 요청사항: 결과 JSON에는 아래 항목들을 저장하지 않습니다.
            // - device{...}
            // - capturedUri
            put("modelName", modelName)
            put("measureMethod", measureMethod)
            put("detectedFilms", detectedFilmsForJson)

            put("image", JSONObject().apply {
                put("width", imageW)
                put("height", imageH)
            })

            // method-specific detail (pixelsPerMm, grid pitch 등)
            if (methodDetail != null) {
                // 그대로 통째로 붙임
                val itKeys = methodDetail.keys()
                while (itKeys.hasNext()) {
                    val k = itKeys.next()
                    // ✅ 요청사항: GRID 관련 grid{gridJsonPath, pitchMm} 는 결과 JSON에 저장하지 않습니다.
                    // - buildFilmMeasureExifJsonGrid()에서 methodDetail로 전달되더라도 방어적으로 차단
                    if (k == "grid") continue
                    put(k, methodDetail.get(k))
                }
            }

            // ✅ 요청사항: results 위에 decision / decisionPerFilm 추가
            put("decision", decision)
            put("decisionPerFilm", decisionPerFilm)

            // Film별 측정 결과
            put("results", JSONArray().apply {
                parsedEntries.forEach { (filmIdx, measures) ->
                    put(JSONObject().apply {
                        put("film", filmIdx)
                        put("measures", JSONArray().apply {
                            // ✅ EXIF JSON의 measures 배열은 모델 JSON에 정의된 순서대로 정렬
                            val orderedMeasures = if (measureOrderRank.isNotEmpty()) {
                                measures.sortedWith(compareBy<ParsedMeasureLine> {
                                    measureOrderRank[it.index] ?: Int.MAX_VALUE
                                })
                            } else {
                                measures
                            }

                            orderedMeasures.forEach { m ->
                                put(JSONObject().apply {
                                    put("index", m.index)
                                    put("direction", m.direction)
                                    put("value", m.valueMm)

                                    m.gtMm?.let { put("gt", it) }
                                    m.errMm?.let { put("err", it) }
                                    m.marginMm?.let { put("margin", it) }

                                    // ✅ offset: film별 적용값
                                    // - 우선: 이번 측정 세션에서 실제 사용된 값(Store snapshot)
                                    // - fallback: 모델 JSON(measure_*.offset)에서 filmIndex에 해당하는 토큰
                                    val off = offsetSnapshot[m.offsetMeasureIndex]?.get(m.offsetFilmIndex)
                                        ?: parseOffsetFromField(offsetMapFallback[m.offsetMeasureIndex], m.offsetFilmIndex)
                                    // ✅ 요청사항: offset 은 소수점 6자리까지만 저장
                                    // - JSON 숫자 길이를 줄이되, 기존 로직/값의 의미는 유지(반올림)
                                    val offRounded = BigDecimal.valueOf(off)
                                        .setScale(6, RoundingMode.HALF_UP)
                                        .stripTrailingZeros()
                                    put("offset", offRounded)

                                    m.result?.let { put("result", it) }
                                })
                            }
                        })
                    })
                }
            })
        }

        return root.toString()
    }

    /**
     * 모델 JSON에서 measure_* 항목의 offset 값을 읽어 맵으로 반환
     * - key: measure 이름 (measure_ 접두 제외)
     * - value: offset 문자열 (없으면 "")
     */
    private fun loadMeasureOffsetMap(modelName: String): Map<String, String> {
        val base = modelName.trim().substringBefore("_FO")
        val jsonFile = ModelFileStore.downloadedModelJsonFile(activity, base)
        if (!jsonFile.exists()) return emptyMap()

        return runCatching {
            val root = JSONObject(jsonFile.readText(Charsets.UTF_8))
            val out = HashMap<String, String>()
            val it = root.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (!k.startsWith("measure_")) continue
                val name = k.removePrefix("measure_")
                val o = root.optJSONObject(k) ?: continue
                out[name] = o.optString("offset", "")
            }
            out
        }.getOrElse { emptyMap() }
    }

    /**
     * 모델 JSON 파일에 정의된 measure_* 키의 **등장 순서**를 반환합니다.
     *
     * 목적)
     * - 결과(EXIF UserComment JSON)의 measures 배열이
     *   모델명.json에 적힌 measure 정의 순서와 동일하게 저장되도록 하기 위함.
     *
     * 구현)
     * - `JSONObject.keys()`의 순서는 보장되지 않을 수 있어,
     *   원문 텍스트에서 "measure_...": 패턴을 정규식으로 추출하여 순서를 보존합니다.
     * - 반환 값은 "measure_" 접두를 제거한 measure index 리스트입니다.
     *   (예: ["CP1", "CP2", "No.3-1", ...])
     */
    private fun loadMeasureIndexOrder(modelName: String): List<String> {
        val base = modelName.trim().substringBefore("_FO")
        val jsonFile = ModelFileStore.downloadedModelJsonFile(activity, base)
        if (!jsonFile.exists()) return emptyList()

        return runCatching {
            val text = jsonFile.readText(Charsets.UTF_8)
            val re = Regex("\"(measure_[^\"]+)\"\\s*:")
            val ordered = LinkedHashSet<String>()
            re.findAll(text).forEach { m ->
                val k = m.groupValues.getOrNull(1).orEmpty()
                if (k.startsWith("measure_")) ordered.add(k)
            }
            ordered.map { it.removePrefix("measure_") }
        }.getOrElse { emptyList() }
    }

    /**
     * 기존 logs의 Film 측정 라인들을 파싱해 Film별 결과 리스트로 변환
     * - 라인 예시:
     *   [Film#1][MCP1] X = 316.225 mm, gt=319.110, err=-2.885, margin=±0.200, FAIL
     */
    private fun parseMeasureLinesFromLogs(
        logs: List<String>
    ): Map<Int, List<ParsedMeasureLine>> {
        val reGt = Regex("gt=([+-]?\\d+(?:\\.\\d+)?)")
        val reErr = Regex("err=([+-]?\\d+(?:\\.\\d+)?)")
        val reMargin = Regex("margin=[^0-9]*([0-9]+(?:\\.\\d+)?)")

        val byFilm = LinkedHashMap<Int, MutableList<ParsedMeasureLine>>()

        for (line in logs) {
            if (!line.startsWith("[Film#")) continue

            // [Film#N]
            val filmNumStr = line.substringAfter("[Film#", "").substringBefore("]", "")
            val filmIdx = filmNumStr.toIntOrNull() ?: continue

            // 두 번째 [ ... ] (measure index)
            val firstClose = line.indexOf(']')
            if (firstClose < 0) continue
            val secondOpen = line.indexOf('[', firstClose + 1)
            val secondClose = if (secondOpen >= 0) line.indexOf(']', secondOpen + 1) else -1
            if (secondOpen < 0 || secondClose < 0) continue

            val rawMeasure = line.substring(secondOpen + 1, secondClose).trim()
            // 요청: Measure Index는 앞의 'M' 제거
            val measureIndex = rawMeasure.removePrefix("M").trim()
            if (measureIndex.isBlank()) continue

            // direction/value 파싱: " X = 316.225 mm ..."
            val rest = line.substring(secondClose + 1).trim()
            val dirToken = rest.substringBefore("=", "").trim() // X / Y / XY
            if (dirToken.isBlank()) continue
            val direction = dirToken.lowercase(Locale.US)

            val afterEq = rest.substringAfter("=", "").trim()
            val valueStr = afterEq.substringBefore("mm", "").trim()
            val valueMm = valueStr.toDoubleOrNull() ?: continue

            val gtMm = reGt.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val errMm = reErr.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val marginMm = reMargin.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

            val result = when {
                line.contains("PASS") -> "PASS"
                line.contains("FAIL") -> "FAIL"
                else -> null
            }

            val pm = ParsedMeasureLine(
                filmIndex = filmIdx,
                index = measureIndex,
                direction = direction,
                valueMm = valueMm,
                gtMm = gtMm,
                errMm = errMm,
                marginMm = marginMm,
                result = result,
            )
            byFilm.getOrPut(filmIdx) { mutableListOf() }.add(pm)
        }

        // stable order: measure index 그대로 등장 순서 유지
        return byFilm
    }

    /**
     * 모델 JSON의 measure_*.offset 형식("+0.01,-0.02" 등)에서
     * filmIndex(1-based)에 해당하는 값을 파싱합니다.
     */
    private fun parseOffsetFromField(offsetExpr: String?, filmIndex: Int): Double {
        val raw = offsetExpr?.trim().orEmpty()
        if (raw.isBlank()) return 0.0

        val token = if (raw.contains(',')) {
            val parts = raw.split(',').map { it.trim() }
            parts.getOrNull(filmIndex - 1).orEmpty().trim()
        } else {
            raw
        }
        if (token.isBlank()) return 0.0
        return token.toDoubleOrNull() ?: 0.0
    }
}