package com.hklab.airuler

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.hklab.airuler.calibration.GridOnlineOffsetCalibrationStore
import com.hklab.airuler.autoreturn.ReturnWatcherService
import com.hklab.airuler.databinding.ActivityMainBinding
import com.hklab.airuler.gallery.AirulerGalleryFoldersActivity
import com.hklab.airuler.film.FilmModelConfigLoader
import com.hklab.airuler.film.FilmTotalMeasureGridProcessor
import com.hklab.airuler.model.ModelSelectActivity
import com.hklab.airuler.model.ModelFileStore
import com.hklab.airuler.model.HawkModelDownloader
import com.hklab.airuler.model.ModelStore
import com.hklab.airuler.pipeline.autoreturn.AutoReturnManager
import com.hklab.airuler.pipeline.camera.CameraPipeline
import com.hklab.airuler.pipeline.inference.InferencePipeline
import com.hklab.airuler.pipeline.measurement.MeasurementPipeline
import com.hklab.airuler.pipeline.MeasurementMethod
import com.hklab.airuler.pipeline.state.AppRuntimeState
import com.hklab.airuler.pipeline.state.AppSessionSettings
import com.hklab.airuler.sound.AirulerSoundPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MainActivity는 “UI + 파이프라인 오케스트레이션”만 담당하도록 역할을 줄였습니다.
 *
 * - CameraPipeline      : CameraX 바인딩/해상도/프레임 콜백
 * - InferencePipeline   : Analyzer 프레임 처리 (YOLO/BadBox/Motion/Direction)
 * - MeasurementPipeline : Samsung 캡처 이미지 기반 FilmTotalMeasureProcessor + 결과 저장/표시
 * - AutoReturnManager   : Samsung 카메라 호출 + 복귀(Watcher/Accessibility) + URI 전달
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val state = AppRuntimeState()

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var backgroundExecutor: ExecutorService

    private lateinit var soundPlayer: AirulerSoundPlayer

    private lateinit var cameraPipeline: CameraPipeline
    private lateinit var inferencePipeline: InferencePipeline
    private lateinit var measurementPipeline: MeasurementPipeline
    private lateinit var autoReturnManager: AutoReturnManager

    private var selectedModelName: String? = null

    // ✅ Settings long-press "Model Update" 중복 실행 방지
    @Volatile
    private var modelUpdateInProgress: Boolean = false

    // ✅ Preview Pause/ALERT 시 Camera를 완전히 멈추기 위해 마지막 프레임을 저장
    private var frozenPauseFrameBitmap: Bitmap? = null
    private var frozenAlertFrameBitmap: Bitmap? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        val granted = res.values.all { it }
        if (granted) {
            cameraPipeline.start()
        } else {
            showToast("권한이 필요합니다")
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult

        // 1) Inference 설정 적용
        inferencePipeline.applySettingsFromResult(data)

        // ✅ DEBUG/LOG_FILE_SAVE (GlobalParams)
        GlobalParams.DEBUG = data.getBooleanExtra(SettingsActivity.RESULT_DEBUG_ENABLED, GlobalParams.DEBUG)
        GlobalParams.LOG_FILE_SAVE = data.getBooleanExtra(SettingsActivity.RESULT_LOG_FILE_SAVE_ENABLED, GlobalParams.LOG_FILE_SAVE)

        // 0) Capture MP(50/200) 적용 + GlobalParams(px 파라미터 스케일)
        val captureMp = data.getIntExtra(
            SettingsActivity.RESULT_CAPTURE_MP,
            AppSessionSettings.captureMegapixel
        )
        AppSessionSettings.captureMegapixel = captureMp
        GlobalParams.applyCaptureMegapixel(captureMp)

        // 200MP 선택 시 Measurement Method는 항상 RULER(안전장치)
        if (captureMp >= 200) {
            AppSessionSettings.measurementMethod = com.hklab.airuler.pipeline.MeasurementMethod.RULER
        }

        // 2) 카메라 프리뷰 해상도 적용/재바인딩
        val previewSpec = data.getStringExtra(SettingsActivity.RESULT_PREVIEW)
        val parsed = parseSizeSpec(previewSpec)
        if (parsed != null) {
            cameraPipeline.setSelectedPreviewSize(parsed)
            // ✅ 세션 동안 선택 해상도 유지(모델 바꿔도 유지)
            AppSessionSettings.selectedPreviewSize = parsed
        }
        // ✅ Preview Pause/ALERT/캡처 오버레이 중에는 CameraPipeline을 재시작하지 않음(절전/상태 유지)
        if (!state.previewPaused && !state.alertStop && !state.capturedOverlayVisible) {
            cameraPipeline.start()
        }

        updatePreviewPauseUi()

        // 3) Online Offset Update(=Calibration) ON/OFF
        val beforeCalib = AppSessionSettings.gridCalibrationEnabled
        val afterCalib = data.getBooleanExtra(SettingsActivity.RESULT_OFFSET_UPDATE_ENABLED, GlobalParams.Defaults.OFFSET_CALIBRATION_ENABLED)
        AppSessionSettings.gridCalibrationEnabled = afterCalib
        AppSessionSettings.offsetCalibrationUserOverridden = true

        // ON -> OFF 로 전환되는 경우: 현재까지의 offset을 모델 JSON에 저장
        if (beforeCalib && !afterCalib) {
            selectedModelName?.let { model ->
                val base = model.trim().substringBefore("_FO")
                GridOnlineOffsetCalibrationStore.persistOffsetsToModelJson(this, base)
            }
        }


        // 4) Upload to Server ON/OFF
        AppSessionSettings.uploadToServerEnabled = data.getBooleanExtra(
            SettingsActivity.RESULT_UPLOAD_TO_SERVER_ENABLED,
            AppSessionSettings.uploadToServerEnabled
        )

        // 5) Save Result(DCIM/Result) ON/OFF
        AppSessionSettings.saveResultEnabled = data.getBooleanExtra(
            SettingsActivity.RESULT_SAVE_RESULT_ENABLED,
            AppSessionSettings.saveResultEnabled
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


        // ✅ Capture MP 기본값(50MP) 반영(앱 실행 시점)
        // - Settings에서 변경되면 settingsLauncher에서 다시 apply 됩니다.
        GlobalParams.applyCaptureMegapixel(AppSessionSettings.captureMegapixel)

        // ✅ Offset Calibration Mode 기본값 보장(앱 첫 실행/재실행 시)
        // - 사용자가 Settings에서 값을 적용한 적이 없으면 GlobalParams.Defaults 값을 사용합니다.
        if (!AppSessionSettings.offsetCalibrationUserOverridden) {
            AppSessionSettings.gridCalibrationEnabled = GlobalParams.Defaults.OFFSET_CALIBRATION_ENABLED
        }


        // ✅ 시스템 Back(내비게이션 바 화살표) 동작을 앱 내부 'Home'과 동일하게 처리
        //    - ModelSelectActivity에서 MainActivity로 올 때 finish()를 호출하고 있어
        //      기본 back 동작은 앱 종료(홈 화면)로 이어집니다.
        //    - 요구사항: Back을 누르면 앱 첫 화면(ModelSelectActivity)로 이동
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    goToModelSelectHome()
                }
            }
        )

        // OpenCV (필요 시)
        com.hklab.airuler.cv.OpenCvProcessor.initIfNeeded(applicationContext)

        cameraExecutor = Executors.newSingleThreadExecutor()
        backgroundExecutor = Executors.newSingleThreadExecutor()

        soundPlayer = AirulerSoundPlayer(this)

        if (!ensureModelSelectedOrGoHome()) return

        // ✅ 파이프라인 구성 (서로를 참조해야 하므로 “lambda + property”로 느슨하게 연결)
        // (1) Inference
        inferencePipeline = InferencePipeline(
            activity = this,
            binding = binding,
            state = state,
            cameraExecutor = cameraExecutor,
            ioExecutor = backgroundExecutor,
            appendStatus = ::appendStatus,
            appendStatusStyled = ::appendStatusStyled,
            showToast = ::showToast,
            isGridModelSelected = ::isGridModelSelected,
            getSelectedModelName = { selectedModelName },
            requestSamsungCapture = { autoReturnManager.launchSamsungCamera() },
            hideCapturedOverlay = { measurementPipeline.hideCapturedOverlay() },
            updatePreviewPauseUi = ::updatePreviewPauseUi,
            onAlertStop = ::showAlertAndStop,
            soundPlayer = soundPlayer
        )

        // (2) Measurement
        measurementPipeline = MeasurementPipeline(
            activity = this,
            binding = binding,
            state = state,
            backgroundExecutor = backgroundExecutor,
            showToast = ::showToast,
            appendStatus = ::appendStatus,
            updatePreviewPauseUi = ::updatePreviewPauseUi,
            setAnalyzerPaused = ::setAnalyzerPaused,
            updateMeasureButtonsForCurrentState = { inferencePipeline.updateMeasureButtonsForCurrentState() },
            onFinalDecision = { decision -> inferencePipeline.onFinalDecision(decision) },
            getSelectedModel = { selectedModelName ?: com.hklab.airuler.model.ModelStore.get(this) },
            getSelectedPreviewSize = { cameraPipeline.selectedPreviewSize }
        )

        // (3) AutoReturn
        autoReturnManager = AutoReturnManager(
            activity = this,
            state = state,
            appendStatus = ::appendStatus,
            onCaptured = { uri -> measurementPipeline.onSamsungCaptured(uri) }
        )

        // (4) Camera
        cameraPipeline = CameraPipeline(
            activity = this,
            previewView = binding.previewView,
            cameraExecutor = cameraExecutor,
            analyzer = { image -> inferencePipeline.processFrame(image) },
            onStatus = ::appendStatus,
            onToast = ::showToast
        )

        // ✅ Settings(해상도) 선택값을 세션 동안 유지
        //    - 홈에서 다른 모델을 선택해 MainActivity가 재생성되어도 동일한 preview size를 적용
        AppSessionSettings.selectedPreviewSize?.let { cameraPipeline.setSelectedPreviewSize(it) }

        // 초기 모델 기반 Ref 로딩
        
        initUi()

        // ✅ (성능) 첫 측정에서 dt가 튀는 현상(모델 init/warm-up)을 줄이기 위해
        //    백그라운드에서 1회 warm-up을 수행합니다.
        // - 실패해도 앱 동작에 영향이 없어야 하므로 runCatching으로 감쌉니다.
        runCatching {
            // ModelStore.get(...)가 nullable(String?)일 수 있어, warm-up 호출 전에 non-null을 보장합니다.
            val model: String = selectedModelName ?: ModelStore.get(this) ?: return@runCatching
            if (AppSessionSettings.captureMegapixel < 200 && AppSessionSettings.measurementMethod == MeasurementMethod.GRID) {
                backgroundExecutor.execute {
                    FilmTotalMeasureGridProcessor.prewarm(applicationContext, model)
                }
            }
        }

        updatePreviewPauseUi()
        requestPermissionsIfNeeded()

        // 인텐트로 들어온 Samsung 캡처 URI 처리
        autoReturnManager.consumePendingSamsungCapture(intent)
    }

    /**
     * ✅ (요구사항)
     * activity_main(MainActivity) 화면에서는 외부 키보드 입력으로 인해
     * 측정이 멈추거나 다른 화면으로 이동하는 문제를 방지하기 위해,
     * "외부 입력 장치(블루투스/USB 키보드 등)"에서 들어오는 KeyEvent를 모두 무시합니다.
     *
     * - MainActivity에만 적용됩니다. (다른 화면은 기존대로 키보드 입력 가능)
     * - 터치/마우스 입력(MotionEvent)은 영향을 받지 않습니다.
     * - 기기 자체(내장) 버튼에서 오는 이벤트는 최소한으로 영향 주기 위해 차단하지 않습니다.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (shouldIgnoreExternalKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Ctrl+조합 등 "키보드 숏컷" 이벤트도 동일하게 차단합니다.
     */
    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
        if (shouldIgnoreExternalKeyEvent(event)) return true
        return super.dispatchKeyShortcutEvent(event)
    }

    private fun shouldIgnoreExternalKeyEvent(event: KeyEvent): Boolean {
        // 외부 키보드/리모컨 등에서 들어오는 키 이벤트만 차단 (내장 버튼/시스템 제스처 영향 최소화)
        val device = event.device ?: return false
        return device.isExternal
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return

        // 1) 모델 변경 처리
        val newModel = intent.getStringExtra(ModelStore.EXTRA_SELECTED_MODEL)
        if (!newModel.isNullOrBlank() && newModel != selectedModelName) {
            selectedModelName = newModel
            ModelStore.save(this, newModel)
            updatePreviewPauseUi()
            inferencePipeline.onModelChanged()

            // ✅ 모델 변경 시에도 warm-up(첫 촬영 지연 완화)
            runCatching {
                if (AppSessionSettings.captureMegapixel < 200 && AppSessionSettings.measurementMethod == MeasurementMethod.GRID) {
                    // newModel은 nullable(String?)이고, 람다(Executor) 캡처 시 smart-cast가 풀릴 수 있어
                    // non-null local val로 고정한 뒤 전달합니다.
                    val modelForWarmup: String = newModel ?: return@runCatching
                    backgroundExecutor.execute {
                        FilmTotalMeasureGridProcessor.prewarm(applicationContext, modelForWarmup)
                    }
                }
            }
        }

        // 2) Samsung 캡처 복귀 처리
        autoReturnManager.consumePendingSamsungCapture(intent)
    }

    override fun onResume() {
        super.onResume()
        updatePreviewPauseUi()

        // Store에 남아있는 캡처 URI가 있으면 처리
        autoReturnManager.consumePendingSamsungCapture(null)
    }

    override fun onStart() {
        super.onStart()
        state.isStopping = false
    }

    override fun onStop() {
        state.isStopping = true

        // ✅ (요구사항) Calibration ON 상태에서 다른 화면으로 이동하면
        //    현재까지의 offset을 모델 JSON에 저장해 두었다가,
        //    다시 돌아왔을 때 이어서 사용할 수 있게 합니다.
        if (AppSessionSettings.gridCalibrationEnabled) {
            selectedModelName?.let { model ->
                val base = model.trim().substringBefore("_FO")
                runCatching { GridOnlineOffsetCalibrationStore.persistOffsetsToModelJson(this, base) }
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        state.isStopping = true

        // Analyzer/Camera 자원
        runCatching { cameraPipeline.stop() }

        // ✅ Freeze frame bitmap 정리
        frozenPauseFrameBitmap?.let { runCatching { if (!it.isRecycled) it.recycle() } }
        frozenPauseFrameBitmap = null
        frozenAlertFrameBitmap?.let { runCatching { if (!it.isRecycled) it.recycle() } }
        frozenAlertFrameBitmap = null

        // 파이프라인 자원
        runCatching { inferencePipeline.release() }
        runCatching { measurementPipeline.shutdown() }

        // ✅ (선택) cached Yolo/warp 리소스 정리
        runCatching { FilmTotalMeasureGridProcessor.releaseCachedResources() }

        // Sound
        runCatching { soundPlayer.release() }

        // Executors
        runCatching { cameraExecutor.shutdown() }
        runCatching { backgroundExecutor.shutdown() }

        super.onDestroy()
    }

    // ---------------- UI wiring ----------------

    private fun initUi() = with(binding) {
        btnHome.setOnClickListener { goToModelSelectHome() }
        // ✅ Home 버튼 길게 누름: 삼성 인터넷으로 지정 서버 접속
        // - 기존 짧게 누름 동작(모델 선택 화면 이동)은 그대로 유지
        btnHome.setOnLongClickListener {
            openSamsungInternetToUrl("http://106.240.235.194:60080")
            true
        }
        btnSettings.setOnClickListener { openSettings() }
        btnSettings.setOnLongClickListener {
            confirmAndUpdateCurrentModelFiles()
            true
        }

        // 캡처/결과 오버레이 닫기
        capturedImageView.setOnClickListener { measurementPipeline.hideCapturedOverlay() }
        capturedOverlay.setOnClickListener { measurementPipeline.hideCapturedOverlay() }

        btnCapturePreview.setOnClickListener { inferencePipeline.requestAnalyzerFrameCapture() }
        btnCapturePreview.setOnLongClickListener {
            confirmAndSaveRefImages()
            true
        }

        btnGallery.setOnClickListener { openAirulerGallery() }
        btnGallery.setOnLongClickListener {
            showToast("삼성 갤러리 앱으로 이동")
            openSystemGalleryApp()
            true
        }

        // ✅ Retry 버튼 (기존 Download 버튼 자리)
        // - 짧게: 50MP 기반으로 처음부터 재시도
        // - 길게: 200MP 기반으로 처음부터 재시도(이번 1회, Method=Ruler)
        btnRetry.setOnClickListener { requestRetryMeasure(forceCaptureMp = 50) }
        btnRetry.setOnLongClickListener {
            requestRetryMeasure(forceCaptureMp = 200)
            true
        }

        // ✅ 결과 오버레이 하단 재측정 버튼
        btnRetryResult50.setOnClickListener { requestRetryMeasure(forceCaptureMp = 50) }
        btnRetryResult200.setOnClickListener { requestRetryMeasure(forceCaptureMp = 200) }

        btnMeasure.setOnClickListener { inferencePipeline.onMeasureButtonClicked() }

        // ✅ Manual Measurement Mode: 50MP / 200MP 버튼
        btnMeasure50.setOnClickListener { inferencePipeline.onMeasureButtonClicked(forceCaptureMp = 50) }
        btnMeasure200.setOnClickListener { inferencePipeline.onMeasureButtonClicked(forceCaptureMp = 200) }

        // ✅ Status 버튼: offset 정보 표시
        // - Calibration ON/OFF는 Settings 화면에서 선택
        btnStatus.setOnClickListener { showGridCalibrationOffsetsDialog() }
        btnStatus.setOnLongClickListener {
            showButtonHelpDialog()
            true
        }

        previewContainer.setOnClickListener {
            if (state.alertStop) {
                clearAlertAndResume(); return@setOnClickListener
            }
            togglePreviewPauseByTap()
        }
        previewView.setOnClickListener {
            if (state.alertStop) {
                clearAlertAndResume(); return@setOnClickListener
            }
            togglePreviewPauseByTap()
        }

        // ✅ 마우스/트랙패드 hover 도움말 (지원되는 환경에서 tooltip으로 표시)
        applyButtonTooltips()

    }

    private fun showGridCalibrationOffsetsDialog() {
        val model = selectedModelName
        if (model.isNullOrBlank()) {
            showToast("모델이 선택되지 않았습니다")
            return
        }

        val base = model.trim().substringBefore("_FO")

        // ✅ 요구사항: "현재 모델명.json 파일"에 저장된 measure별 offset 값만 표시
        val cfg = FilmModelConfigLoader.loadFromInternalModels(this, base)
        if (cfg == null) {
            showToast("모델 JSON을 읽을 수 없습니다: $base")
            return
        }

        // 세션 내 calibrator가 있으면 그 값(=현재 offset / n)을 우선 표시하고,
        // 없으면 모델 JSON(measure_*.offset)에 저장된 값을 fallback으로 사용합니다.
        val snap = GridOnlineOffsetCalibrationStore.snapshotOffsetStates(base)

        // measure 정의 순서대로 출력
        val sb = StringBuilder()
        for (m in cfg.measures) {
            val expr = m.offset.trim()
            val parts = if (expr.isNotBlank() && expr.contains(',')) expr.split(',') else listOf(expr)

            val perFilm = snap[m.name]
            val maxFilmFromSnap = perFilm?.keys?.maxOrNull() ?: 0
            val filmCount = maxOf(parts.size, maxFilmFromSnap, 1)

            sb.append(m.name).append(" : ")
            for (fi in 1..filmCount) {
                val st = perFilm?.get(fi)
                val off = st?.offset ?: run {
                    val tok = parts.getOrNull(fi - 1)?.trim().orEmpty()
                    tok.toDoubleOrNull() ?: 0.0
                }
                val n = st?.sampleN ?: 0

                if (fi > 1) sb.append(", ")
                // ✅ 요구사항: 필름명 옆에 n 값도 표시 (예: F1(n=10)=+0.12345)
                sb.append("F").append(fi)
                    .append("(n=").append(n).append(")=")
                    .append(String.format(Locale.US, "%+.5f", off))
            }
            sb.append('\n')
        }

        AlertDialog.Builder(this)
            .setTitle("Offsets ($base)")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .setNeutralButton("Reset") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Reset Offsets")
                    .setMessage("Offsets 값과 측정 n(누적 샘플 수)을 0으로 초기화합니다.\n\n계속할까요?")
                    .setPositiveButton("Reset") { _, _ ->
                        val ok = GridOnlineOffsetCalibrationStore.resetOffsetsForModel(this, base)
                        showToast(if (ok) "Offsets reset" else "Offsets reset (session only)")
                        // 리셋된 값을 바로 확인할 수 있도록 다시 표시
                        showGridCalibrationOffsetsDialog()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    private fun goToModelSelectHome() {
        // 캡처 오버레이 떠있으면 닫기
        if (state.capturedOverlayVisible) {
            measurementPipeline.hideCapturedOverlay()
        }

        startActivity(
            Intent(this, ModelSelectActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    /**
     * ✅ 삼성 인터넷(패키지: com.sec.android.app.sbrowser)으로 지정 URL을 엽니다.
     * - 삼성 인터넷이 없거나 실행 불가 시, 기본 브라우저로 fallback
     * - 호출 주체가 Activity이므로 불필요한 NEW_TASK 플래그는 사용하지 않습니다.
     */
    private fun openSamsungInternetToUrl(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null) {
            showToast("URL 형식이 올바르지 않습니다: $url")
            return
        }

        val samsungInternetPkg = "com.sec.android.app.sbrowser"

        // 1) 삼성 인터넷으로 우선 시도
        val samsungIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(samsungInternetPkg)
        }
        try {
            startActivity(samsungIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // 2) 폴백: 다른 브라우저/핸들러
        } catch (_: Exception) {
            // 예외는 폴백으로 처리 (앱 크래시 방지)
        }

        // 2) 폴백: 시스템이 처리 가능한 기본 브라우저로 열기
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            showToast("브라우저 실행에 실패했습니다: ${e.localizedMessage}")
        }
    }

    private fun openAirulerGallery() {
        startActivity(Intent(this, AirulerGalleryFoldersActivity::class.java))
    }

    /**
     * 시스템(삼성) 기본 갤러리 앱을 엽니다.
     */
    private fun openSystemGalleryApp() {
        val samsungPkg = "com.sec.android.gallery3d"

        // 1) 삼성 갤러리 패키지가 설치돼 있으면 우선 실행
        try {
            packageManager.getPackageInfo(samsungPkg, 0)

            val launchIntent = packageManager.getLaunchIntentForPackage(samsungPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                return
            }

            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_GALLERY)
                setPackage(samsungPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        } catch (_: Exception) {
            // fallback
        }

        // 2) fallback
        try {
            val fallback = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_GALLERY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallback)
        } catch (_: Exception) {
            // 3) 마지막 fallback
            try {
                val view = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(view)
            } catch (_: Exception) {
                showToast("시스템 갤러리를 열 수 없습니다.")
            }
        }
    }

    // ---------------- Buttons: long-press helpers ----------------

    /**
     * ✅ Capture 버튼 길게 눌렀을 때: Ref 이미지를 저장(기존 Download 버튼 기능)하되,
     *    바로 저장하지 않고 확인/취소 팝업을 먼저 띄웁니다.
     */
    private fun confirmAndSaveRefImages() {
        AlertDialog.Builder(this)
            .setTitle("Save Ref Images")
            .setMessage(
                "현재 화면에서 감지된 ROI 기준으로 Ref 이미지를 저장합니다.\n" +
                    "(정상 동작하려면 모델이 선택되어 있고, 객체가 화면에 감지되어야 합니다.)\n\n" +
                    "계속할까요?"
            )
            .setPositiveButton("확인") { _, _ ->
                inferencePipeline.saveRefCropsByBoxIndex()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * ✅ Settings 버튼 길게 눌렀을 때: ModelSelect 홈의 "Update" 버튼과 동일하게
     *    (tflite/json) 파일을 다운로드(업데이트)합니다.
     *
     *  - 바로 실행하지 않고 확인/취소 팝업을 먼저 띄웁니다.
     *  - 다운로드 완료 후 현재 모델을 리로드하여 앱에 반영합니다.
     */
    private fun confirmAndUpdateCurrentModelFiles() {
        if (modelUpdateInProgress) {
            showToast("모델 업데이트 진행 중...")
            return
        }

        val model = selectedModelName ?: ModelStore.get(this)
        if (model.isNullOrBlank()) {
            showToast("모델이 선택되지 않았습니다")
            return
        }

        val base = model.trim().substringBefore("_FO")

        AlertDialog.Builder(this)
            .setTitle("Update Model")
            .setMessage(
                "현재 모델 파일(tflite/json)을 업데이트(다운로드)합니다.\n\n" +
                    "Model: $base\n\n" +
                    "계속할까요?"
            )
            .setPositiveButton("확인") { _, _ ->
                modelUpdateInProgress = true
                appendStatus("Model update started: $base")

                // ✅ 백그라운드 다운로드 (coroutines)
                lifecycleScope.launch {
                    try {
                        var lastBucket = -1
                        withContext(Dispatchers.IO) {
                            HawkModelDownloader.downloadOrUpdateModel(
                                context = this@MainActivity,
                                modelName = base,
                                onProgress = { p ->
                                    val bucket = p / 10
                                    if (bucket != lastBucket) {
                                        lastBucket = bucket
                                        runOnUiThread {
                                            appendStatus("Model update $base: ${p}%")
                                        }
                                    }
                                }
                            )
                        }

                        // ✅ 다운로드 완료 -> 현재 모델 리로드
                        runOnUiThread {
                            showToast("모델 업데이트 완료")
                            appendStatus("Model update finished: $base")
                            inferencePipeline.onModelChanged()
                        }
                    } catch (t: Throwable) {
                        runOnUiThread {
                            showToast("모델 업데이트 실패")
                            appendStatus("Model update failed: ${t.message}")
                        }
                    } finally {
                        modelUpdateInProgress = false
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * ✅ Retry 버튼(및 결과 오버레이 재측정 버튼) 동작
     *  - 짧게: forceCaptureMp=50
     *  - 길게: forceCaptureMp=200 (이번 1회, measurementMethod=Ruler)
     */
    private fun requestRetryMeasure(forceCaptureMp: Int) {
        // Grid 모델은 측정 파이프라인이 다르므로 안전하게 제한
        if (isGridModelSelected()) {
            if (forceCaptureMp >= 200) {
                showToast("Grid 모델에서는 200MP 재측정이 지원되지 않습니다")
                return
            }

            // Grid에서는 기존 'Grid 분석' 흐름을 그대로 사용
            if (state.capturedOverlayVisible) measurementPipeline.hideCapturedOverlay()
            inferencePipeline.hideArrows()
            inferencePipeline.onMeasureButtonClicked()
            return
        }

        // ✅ ALERT/Pause 상태에서는 먼저 복구
        if (state.alertStop) {
            clearAlertAndResume()
        }
        if (state.previewPaused) {
            exitPreviewPauseMode()
        }

        // ✅ 캡처/결과 오버레이가 떠 있으면 먼저 닫기(카메라 재호출 가드 회피)
        if (state.capturedOverlayVisible) {
            measurementPipeline.hideCapturedOverlay()
        }

        // ✅ 측정 진행 중이면 토큰을 갱신하여 기존 결과 UI 업데이트를 무시
        val mf = state.snapshotMeasureFlags()
        if (mf.measuringNow) {
            state.currentSamsungCaptureToken = SystemClock.elapsedRealtime()
            state.resultShownToken = 0L
            state.finishMeasuring(clearPending = true)
        }

        // ✅ 이전 1회성 오버라이드가 남아있을 수 있으므로 정리 후 다시 설정
        state.clearOneShotOverrides()
        state.nextCaptureMegapixelOverride = forceCaptureMp
        state.nextMeasurementMethodOverride = if (forceCaptureMp >= 200) MeasurementMethod.RULER else null
        state.forceAutoMeasureOnce = true

        // ✅ GlobalParams가 이전 캡처 오버라이드 값으로 남아있을 수 있으므로 세션값으로 정리
        GlobalParams.applyCaptureMegapixel(AppSessionSettings.captureMegapixel)

        // ✅ "처음부터" 다시 시작 (tilt/badBox/방향 등)
        inferencePipeline.resetForUserRetry()

        appendStatus("Retry requested: ${forceCaptureMp}MP (waiting for tilt/badBox → capture)")
    }

    /**
     * ✅ Status 버튼 길게 클릭 시: 6개 버튼(홈/설정/Retry/Capture/Gallery/Status)의
     *    짧게/길게 동작을 요약해주는 도움말 창을 표시합니다.
     */
    private fun showButtonHelpDialog() {
        val msg = buildString {
            append("[Home]\n")
            append(" - 짧게: 모델 선택 화면\n")
            append(" - 길게: YesunAI 서버 접속\n\n")

            append("[Settings]\n")
            append(" - 짧게: 설정 화면\n")
            append(" - 길게: 현재 모델 파일 업데이트(tflite/json)\n\n")

            append("[Retry]\n")
            append(" - 짧게: 50MP로 처음부터 재측정\n")
            append(" - 길게: 200MP로 처음부터 재측정(이번 1회, Method=Ruler)\n\n")

            append("[Capture]\n")
            append(" - 짧게: 프리뷰 저장(DCIM/Capture)\n")
            append(" - 길게: Ref 이미지 저장(확인 후 저장)\n\n")

            append("[Gallery]\n")
            append(" - 짧게: AIRuler 갤러리\n")
            append(" - 길게: 삼성 갤러리 앱으로 이동\n\n")

            append("[Status]\n")
            append(" - 짧게: Offsets/Calibration 상태 표시\n")
            append(" - 길게: 버튼 도움말(현재 창)\n")
        }

        AlertDialog.Builder(this)
            .setTitle("버튼 도움말")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * ✅ hover(마우스/트랙패드) 환경을 위해 Tooltip 텍스트를 코드로도 세팅합니다.
     * - minSdk=26이라 XML tooltipText만으로도 가능하지만, 코드로 한 번 더 보장합니다.
     */
    private fun applyButtonTooltips() {
        TooltipCompat.setTooltipText(binding.btnHome, "짧게: 모델 선택\n길게: 서버 접속")
        TooltipCompat.setTooltipText(binding.btnSettings, "짧게: 설정\n길게: 모델 업데이트")
        TooltipCompat.setTooltipText(binding.btnRetry, "짧게: 50MP 재측정\n길게: 200MP 재측정")
        TooltipCompat.setTooltipText(binding.btnCapturePreview, "짧게: 프리뷰 저장\n길게: Ref 저장")
        TooltipCompat.setTooltipText(binding.btnGallery, "짧게: 앱 갤러리\n길게: 삼성 갤러리 이동")
        TooltipCompat.setTooltipText(binding.btnStatus, "짧게: 상태/오프셋\n길게: 버튼 도움말")

        TooltipCompat.setTooltipText(binding.btnRetryResult50, "50MP 기반으로 처음부터 재시도")
        TooltipCompat.setTooltipText(binding.btnRetryResult200, "200MP 기반으로 처음부터 재시도")
    }

    // ---------------- Settings ----------------

    private fun openSettings() {
        val previewList = ArrayList(cameraPipeline.previewSizes.map { sizeToSpecWithMp(it) })
        val selectedSpec = cameraPipeline.selectedPreviewSize?.let { sizeToSpecWithMp(it) }

        val intent = Intent(this, SettingsActivity::class.java)
        // ✅ 해상도 목록/현재 선택은 MainActivity가 넘기고,
        //    추론/오버레이 옵션은 InferencePipeline이 채웁니다.
        intent.putStringArrayListExtra(SettingsActivity.EXTRA_PREVIEW_LIST, previewList)
        intent.putExtra(SettingsActivity.EXTRA_PREVIEW_SELECTED, selectedSpec)
        // ✅ Online Offset Update(=Calibration) ON/OFF
        intent.putExtra(SettingsActivity.EXTRA_OFFSET_UPDATE_ENABLED, AppSessionSettings.gridCalibrationEnabled)
        // ✅ Upload to Server ON/OFF
        intent.putExtra(SettingsActivity.EXTRA_UPLOAD_TO_SERVER_ENABLED, AppSessionSettings.uploadToServerEnabled)
        // ✅ Save Result(DCIM/Result) ON/OFF
        intent.putExtra(SettingsActivity.EXTRA_SAVE_RESULT_ENABLED, AppSessionSettings.saveResultEnabled)
        // ✅ Capture MP (50/200)
        intent.putExtra(SettingsActivity.EXTRA_CAPTURE_MP, AppSessionSettings.captureMegapixel)
        // ✅ DEBUG/LOG_FILE_SAVE (GlobalParams)
        intent.putExtra(SettingsActivity.EXTRA_DEBUG_ENABLED, GlobalParams.DEBUG)
        intent.putExtra(SettingsActivity.EXTRA_LOG_FILE_SAVE_ENABLED, GlobalParams.LOG_FILE_SAVE)
        inferencePipeline.fillSettingsIntent(intent)
        settingsLauncher.launch(intent)
    }

    // ---------------- Permissions / Camera start ----------------

    private fun requestPermissionsIfNeeded() {
        val perms = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (need.isEmpty()) {
            cameraPipeline.start()
        } else {
            permissionLauncher.launch(need.toTypedArray())
        }
    }

    // ---------------- Model selection ----------------

    private fun ensureModelSelectedOrGoHome(): Boolean {
        val fromIntent = intent.getStringExtra(ModelStore.EXTRA_SELECTED_MODEL)
        if (!fromIntent.isNullOrBlank()) {
            ModelStore.save(this, fromIntent)
            selectedModelName = fromIntent
            return true
        }

        val stored = ModelStore.get(this)
        if (!stored.isNullOrBlank()) {
            selectedModelName = stored
            return true
        }

        // 모델 선택 없이 들어온 경우
        startActivity(Intent(this, ModelSelectActivity::class.java))
        finish()
        return false
    }

    private fun isGridModelSelected(): Boolean {
        return selectedModelName?.contains("grid", ignoreCase = true) == true
    }

        // ---------------- Preview Pause / Alert UI ----------------

    /**
     * Preview Pause/ALERT 시 CameraX를 unbindAll()로 완전히 멈추면 PreviewView가 까맣게 될 수 있어,
     * 마지막 프레임을 Bitmap으로 캡처해서 freezeImageView에 표시합니다.
     */
    private fun capturePreviewFreezeFrame(): Bitmap? {
        return runCatching { binding.previewView.bitmap }.getOrNull()
    }

    private fun enterPreviewPauseMode() {
        // 혹시 남아있을 수 있는 Analyzer 캡처 요청은 무시
        state.pendingLivePreviewCapture = false

        val bmp = capturePreviewFreezeFrame()

        // 이전 pause frame 정리
        frozenPauseFrameBitmap?.let { old ->
            if (old !== bmp) runCatching { if (!old.isRecycled) old.recycle() }
        }
        frozenPauseFrameBitmap = bmp

        binding.freezeImageView.setImageBitmap(bmp)
        binding.freezeImageView.visibility = View.VISIBLE

        // ✅ Preview + Analysis 완전 중단 (발열/배터리 감소)
        cameraPipeline.stop()
    }

    private fun exitPreviewPauseMode() {
        // freeze overlay 해제
        binding.freezeImageView.visibility = View.GONE
        binding.freezeImageView.setImageDrawable(null)

        frozenPauseFrameBitmap?.let { runCatching { if (!it.isRecycled) it.recycle() } }
        frozenPauseFrameBitmap = null

        // ✅ Analyzer가 pause된 상태(측정/오버레이 등)일 수 있으므로, resume 보장
        cameraPipeline.resumeAnalyzer()
        cameraPipeline.start()
    }

    private fun enterAlertStopMode() {
        // 혹시 남아있을 수 있는 Analyzer 캡처 요청은 무시
        state.pendingLivePreviewCapture = false

        val bmp = capturePreviewFreezeFrame()

        frozenAlertFrameBitmap?.let { old ->
            if (old !== bmp) runCatching { if (!old.isRecycled) old.recycle() }
        }
        frozenAlertFrameBitmap = bmp

        binding.freezeImageView.setImageBitmap(bmp)
        binding.freezeImageView.visibility = View.VISIBLE

        // ✅ Preview + Analysis 완전 중단
        cameraPipeline.stop()
    }

    private fun exitAlertStopMode() {
        // freeze overlay 해제
        binding.freezeImageView.visibility = View.GONE
        binding.freezeImageView.setImageDrawable(null)

        frozenAlertFrameBitmap?.let { runCatching { if (!it.isRecycled) it.recycle() } }
        frozenAlertFrameBitmap = null

        // ALERT 해제 시 Preview가 일시정지 상태가 아니면 카메라 재시작
        if (!state.previewPaused && !state.capturedOverlayVisible) {
            cameraPipeline.resumeAnalyzer()
            cameraPipeline.start()
        }
    }

    private fun togglePreviewPauseByTap() {
        if (state.capturedOverlayVisible) {
            // 캡처/결과 화면이 떠 있으면 탭으로 토글하지 않음
            return
        }

        state.previewPaused = !state.previewPaused

        if (state.previewPaused) {
            enterPreviewPauseMode()
        } else {
            exitPreviewPauseMode()
            // Resume하면 상태/결정/가이드 정리
            inferencePipeline.hideArrows()
        }

        updatePreviewPauseUi()
    }

    private fun updatePreviewPauseUi() {
        // ALERT 상태면 오버레이는 ALERT가 우선
        if (state.alertStop) return

        if (state.capturedOverlayVisible) {
            binding.overlayImageView.visibility = View.GONE
            return
        }

        if (state.previewPaused) {
            setOverlayImageDrawable(ContextCompat.getDrawable(this, R.drawable.play))
            binding.overlayImageView.visibility = View.VISIBLE
            return
        }

        // ✅ 혹시 남아있는 freeze 오버레이 정리(안전장치)
        binding.freezeImageView.visibility = View.GONE
        binding.freezeImageView.setImageDrawable(null)

        // 미리보기 진행 중: 모델에 따른 안내 오버레이(선택)
        val model = selectedModelName
        if (model.isNullOrBlank()) {
            binding.overlayImageView.visibility = View.GONE
            return
        }

        // ✅ 모델 안내 오버레이 이미지
        // - 서버(images_overlay)에서 다운로드하지 않고, 앱 내장 assets/overlay/<model>.jpg 를 사용
        // - 모델명에 "_FO"가 붙어있어도 baseModel 로 정규화
        val baseModel = model.trim().substringBefore("_FO")

        val assetUri = ModelFileStore.overlayAssetUriOrNull(this, baseModel)
        if (!assetUri.isNullOrBlank()) {
            binding.overlayImageView.load(assetUri) {
                crossfade(true)
                allowHardware(false)
            }
            binding.overlayImageView.visibility = View.VISIBLE
        } else {
            // 이미지가 없으면 오버레이는 숨김
            binding.overlayImageView.visibility = View.GONE
        }
    }

    private fun showAlertAndStop(reason: String) {
        // 이미 ALERT이면 중복 표시만 방지
        if (state.alertStop) return

        state.alertStop = true
        state.previewPaused = false // ✅ ALERT 우선(안전장치)

        // 측정 버튼 숨김
        binding.btnMeasure.visibility = View.GONE
        binding.layoutManualMeasureButtons.visibility = View.GONE

        // 방향 화살표 숨김/상태 리셋
        inferencePipeline.hideArrows()

        // ALERT 오버레이 표시
        setOverlayImageDrawable(ContextCompat.getDrawable(this, R.drawable.alert))
        binding.overlayImageView.visibility = View.VISIBLE

        // ✅ Preview/분석 완전 중단 + Freeze 프레임 표시
        enterAlertStopMode()

        appendStatus("ALERT: $reason")
    }

    private fun clearAlertAndResume() {
        state.alertStop = false
        appendStatus("ALERT 해제")

        // ✅ Camera 재시작 + Freeze 해제
        exitAlertStopMode()

        updatePreviewPauseUi()

        // ALERT 해제 후 버튼/가이드 상태 반영
        inferencePipeline.updateMeasureButtonsForCurrentState()
    }

    /**
     * MeasurementPipeline이 "loading / 결과 오버레이" 등에서 Analyzer를 끄고 켤 수 있도록 제공.
     * - paused=true  : RGBA 변환/콜백 자체가 줄어 발열/배터리 감소
     * - paused=false : 일반 분석/손감지(오버레이 auto-close) 등을 다시 켬
     */
    private fun setAnalyzerPaused(paused: Boolean) {
        if (paused) cameraPipeline.pauseAnalyzer() else cameraPipeline.resumeAnalyzer()
    }

    private fun setOverlayImageDrawable(drawable: Drawable?) {
        binding.overlayImageView.setImageDrawable(drawable)
    }

    private fun setOverlayImageFile(file: File) {
        binding.overlayImageView.load(file) {
            crossfade(true)
        }
    }

    // ---------------- Status helpers ----------------

    private fun appendStatus(msg: String) {
        binding.txtStatus.text = msg
    }

    private fun appendStatusStyled(msg: CharSequence) {
        binding.txtStatus.text = msg
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------------- Size helpers ----------------

    private fun parseSizeSpec(spec: String?): Size? {
        if (spec.isNullOrBlank()) return null
        val core = spec.substringBefore(" ")
        val parts = core.split("x")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        return Size(w, h)
    }

    private fun sizeToSpecWithMp(size: Size): String {
        val mp = size.width.toDouble() * size.height.toDouble() / 1_000_000.0
        val mpStr = String.format("%.1f", mp)
        return "${size.width}x${size.height} (${mpStr}MP)"
    }
}
