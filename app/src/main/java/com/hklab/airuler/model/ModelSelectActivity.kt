package com.hklab.airuler.model

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.hklab.airuler.GlobalParams
import com.hklab.airuler.MainActivity
import com.hklab.airuler.SettingsActivity
import com.hklab.airuler.calibration.GridOnlineOffsetCalibrationStore
import com.hklab.airuler.databinding.ActivityModelSelectBinding
import com.hklab.airuler.log.AirulerFileLogger
import com.hklab.airuler.pipeline.MeasureMode
import com.hklab.airuler.pipeline.MeasurementMethod
import com.hklab.airuler.pipeline.TiltMode
import com.hklab.airuler.pipeline.state.AppSessionSettings
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

class ModelSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelSelectBinding
    private lateinit var adapter: ModelSelectAdapter

    // ===== Settings (open from ModelSelect) =====

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        applySettingsFromResult(data)
    }

    private val unknownAppSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
            launchPendingDownloadedApkInstaller()
        } else if (pendingSwInstallUri != null) {
            toast("앱 설치 권한이 허용되지 않아 업데이트 설치를 진행할 수 없습니다")
        }
    }

    // ===== SW Update (APK) download =====
    private companion object {
        private const val TAG = "ModelSelectActivity"

        // ✅ AIRuler.apk 다운로드 위치(YesunAI)
        // - 서버 주소는 GlobalParams.YESUNAI_BASE_URL 에서만 관리
        private val SW_UPDATE_URL: String = GlobalParams.yesunaiUrl("/ruler/releases/AIRuler.apk")
        private const val SW_UPDATE_FILE_NAME = "AIRuler.apk"

        // ✅ SW 버전(표시용) 저장
        private const val PREF_SW_UPDATE = "sw_update"
        private const val KEY_SW_LAST_DOWNLOADED_AT = "last_downloaded_at_ms"

        private val DT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    private var swUpdateDownloadId: Long = -1L
    private val swUpdateHandler = Handler(Looper.getMainLooper())
    private var pendingSwInstallUri: Uri? = null

    // (Dialog UI refs) - 다이얼로그가 닫히면 null 로 정리
    private var swDialog: AlertDialog? = null
    private var swDlgInfo: TextView? = null
    private var swDlgStatus: TextView? = null
    private var swDlgPct: TextView? = null
    private var swDlgProgress: ProgressBar? = null

    private val swUpdateCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id <= 0L || id != swUpdateDownloadId) return
            handleSwUpdateCompleted(id)
        }
    }

    private val swUpdateProgressPoll = object : Runnable {
        override fun run() {
            val id = swUpdateDownloadId
            if (id <= 0L) return

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val q = DownloadManager.Query().setFilterById(id)

            dm.query(q)?.use { c ->
                if (!c.moveToFirst()) return@use

                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val sofar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))

                when (status) {
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_PAUSED,
                    DownloadManager.STATUS_RUNNING -> {
                        if (total > 0) {
                            val pct = ((sofar * 100.0) / total).toInt().coerceIn(0, 100)
                            updateSwDialogProgress(pct, "Downloading…")
                        } else {
                            updateSwDialogProgress(null, "Downloading…")
                        }
                        // 다이얼로그가 열려있는 동안에만 폴링(오버헤드 최소화)
                        if (swDialog?.isShowing == true) {
                            swUpdateHandler.postDelayed(this, 600L)
                        }
                    }

                    DownloadManager.STATUS_SUCCESSFUL,
                    DownloadManager.STATUS_FAILED -> {
                        // ✅ 일부 기기/상황에서 ACTION_DOWNLOAD_COMPLETE 브로드캐스트가 누락되거나
                        //    total/sofar가 끝까지 안 맞아 %가 100까지 도달하지 않는 경우가 있어,
                        //    폴링에서도 완료 처리를 보강합니다.
                        handleSwUpdateCompleted(id)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityModelSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ 요청사항: 상단 타이틀은 간단히
        binding.txtTitle.text = "모델을 선택해 주세요"

        // ✅ (요구사항) ModelSelectActivity 진입 시마다 새 로그 파일로 세션 시작
        AirulerFileLogger.startNewSession(this, reason = "ModelSelectActivity.onCreate")
        AirulerFileLogger.i(TAG, "ModelSelectActivity created. YESUNAI_BASE_URL=${GlobalParams.YESUNAI_BASE_URL}")

        // ✅ Settings (ModelSelect에서도 바로 진입 가능)
        binding.btnSettings.setOnClickListener {
            openSettingsFromModelSelect()
        }

        // ✅ Update Model: 새 창(테이블)로 이동
        binding.btnUpdateAllModels.setOnClickListener {
            startActivity(Intent(this, ModelUpdateActivity::class.java))
        }

        // ✅ Update SW: 짧게 눌러 다이얼로그(확인/진행률 표시)
        binding.btnSwUpdate.setOnClickListener {
            showSwUpdateDialog()
        }

        // ✅ DownloadManager 완료 브로드캐스트 등록
        registerSwUpdateReceiver()

        adapter = ModelSelectAdapter(
            onSelect = { model -> tryOpenMain(model) }
        )

        val span = calcSpanCountFor60Percent()
        binding.recyclerModels.layoutManager = GridLayoutManager(this, span)
        binding.recyclerModels.adapter = adapter

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()

        // ✅ polling 중단
        swUpdateHandler.removeCallbacks(swUpdateProgressPoll)

        // ✅ receiver 해제
        runCatching {
            unregisterReceiver(swUpdateCompleteReceiver)
        }
    }

    private fun refresh() {
        val enabled = ModelRegistry.getEnabledModels(this)

        // ✅ Grid는 항상 표시하되, 모델 목록의 '맨 마지막'에 배치
        val list = ArrayList<String>(enabled.size + 1)
        enabled.forEach { m ->
            if (!m.equals("Grid", ignoreCase = true)) list.add(m)
        }
        list.add("Grid")

        val items = list.distinct().map { ModelItem.Model(name = it) }
        adapter.submit(items)
    }

    /** "지금보다 60% 정도"로 보이게: 타일 목표 dp를 작게 잡고 spanCount를 자동 증가 */
    private fun calcSpanCountFor60Percent(): Int {
        val dm: DisplayMetrics = resources.displayMetrics
        val targetTileDp = 90f
        val tilePx = (targetTileDp * dm.density).toInt().coerceAtLeast(1)
        return max(3, dm.widthPixels / tilePx)
    }

    // ---------------- Settings (launch from ModelSelect) ----------------

    private fun openSettingsFromModelSelect() {
        val previewSizes = queryPreviewSizesSafely()
        val previewList = ArrayList(previewSizes.map { sizeToSpecWithMp(it) })

        val selectedSize = AppSessionSettings.selectedPreviewSize ?: pickDefaultPreviewSize(previewSizes)
        val selectedSpec = selectedSize?.let { sizeToSpecWithMp(it) }

        val intent = Intent(this, SettingsActivity::class.java)

        // 해상도 목록/현재 선택
        intent.putStringArrayListExtra(SettingsActivity.EXTRA_PREVIEW_LIST, previewList)
        intent.putExtra(SettingsActivity.EXTRA_PREVIEW_SELECTED, selectedSpec)

        // View Options
        intent.putExtra(SettingsActivity.EXTRA_SHOW_GRID, AppSessionSettings.showGuideGrid)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_FILM_BOX, AppSessionSettings.showFilmBox)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_CONF, AppSessionSettings.showConfOnBox)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_ANGLE, AppSessionSettings.showAngleOnPreview)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_TILT, AppSessionSettings.showTilt)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_GOOD_BOX, AppSessionSettings.showGoodBoxOverlay)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_BAD_BOX, AppSessionSettings.showBadBoxOverlay)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_HAND, AppSessionSettings.showHandOverlay)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_MOTION, AppSessionSettings.showMotionOverlay)
        intent.putExtra(SettingsActivity.EXTRA_SHOW_TRACK, AppSessionSettings.showTrackOverlay)

        // Mode / pipeline
        intent.putExtra(SettingsActivity.EXTRA_TILT_MODE, AppSessionSettings.tiltMode.name)
        intent.putExtra(SettingsActivity.EXTRA_YOLO_INTERVAL, AppSessionSettings.yoloFrameInterval)
        intent.putExtra(SettingsActivity.EXTRA_DIRECTION_CHECK, AppSessionSettings.directionCheckEnabled)
        intent.putExtra(SettingsActivity.EXTRA_MEASURE_MODE, AppSessionSettings.measureMode.name)
        intent.putExtra(SettingsActivity.EXTRA_MEASUREMENT_METHOD, AppSessionSettings.measurementMethod.name)

        // Calibration / upload / save result
        intent.putExtra(SettingsActivity.EXTRA_OFFSET_UPDATE_ENABLED, AppSessionSettings.gridCalibrationEnabled)
        intent.putExtra(SettingsActivity.EXTRA_UPLOAD_TO_SERVER_ENABLED, AppSessionSettings.uploadToServerEnabled)
        intent.putExtra(SettingsActivity.EXTRA_SAVE_RESULT_ENABLED, AppSessionSettings.saveResultEnabled)

        // Capture MP
        intent.putExtra(SettingsActivity.EXTRA_CAPTURE_MP, AppSessionSettings.captureMegapixel)

        // Debug / log
        intent.putExtra(SettingsActivity.EXTRA_DEBUG_ENABLED, GlobalParams.DEBUG)
        intent.putExtra(SettingsActivity.EXTRA_LOG_FILE_SAVE_ENABLED, GlobalParams.LOG_FILE_SAVE)

        settingsLauncher.launch(intent)
    }

    private fun applySettingsFromResult(data: Intent) {
        // ✅ DEBUG/LOG_FILE_SAVE (GlobalParams)
        GlobalParams.DEBUG = data.getBooleanExtra(SettingsActivity.RESULT_DEBUG_ENABLED, GlobalParams.DEBUG)
        GlobalParams.LOG_FILE_SAVE = data.getBooleanExtra(SettingsActivity.RESULT_LOG_FILE_SAVE_ENABLED, GlobalParams.LOG_FILE_SAVE)

        // ✅ Capture MP(50/200)
        val captureMp = data.getIntExtra(
            SettingsActivity.RESULT_CAPTURE_MP,
            AppSessionSettings.captureMegapixel
        )
        AppSessionSettings.captureMegapixel = captureMp
        GlobalParams.applyCaptureMegapixel(captureMp)

        // ✅ Preview 선택(세션 유지)
        val previewSpec = data.getStringExtra(SettingsActivity.RESULT_PREVIEW)
        parseSizeSpec(previewSpec)?.let { AppSessionSettings.selectedPreviewSize = it }

        // ✅ View Options (세션 유지)
        AppSessionSettings.showGuideGrid = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_GRID, AppSessionSettings.showGuideGrid)
        AppSessionSettings.showFilmBox = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_FILM_BOX, AppSessionSettings.showFilmBox)
        AppSessionSettings.showConfOnBox = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_CONF, AppSessionSettings.showConfOnBox)
        AppSessionSettings.showAngleOnPreview = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_ANGLE, AppSessionSettings.showAngleOnPreview)
        AppSessionSettings.showTilt = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_TILT, AppSessionSettings.showTilt)
        AppSessionSettings.showGoodBoxOverlay = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_GOOD_BOX, AppSessionSettings.showGoodBoxOverlay)
        AppSessionSettings.showBadBoxOverlay = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_BAD_BOX, AppSessionSettings.showBadBoxOverlay)
        AppSessionSettings.showHandOverlay = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_HAND, AppSessionSettings.showHandOverlay)
        AppSessionSettings.showMotionOverlay = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_MOTION, AppSessionSettings.showMotionOverlay)
        AppSessionSettings.showTrackOverlay = data.getBooleanExtra(SettingsActivity.RESULT_SHOW_TRACK, AppSessionSettings.showTrackOverlay)

        // ✅ Tilt Mode
        val tiltModeName = data.getStringExtra(SettingsActivity.RESULT_TILT_MODE)
        AppSessionSettings.tiltMode = when (tiltModeName) {
            "NONE" -> TiltMode.NONE
            "HOUGH" -> TiltMode.HOUGH
            else -> AppSessionSettings.tiltMode
        }

        // ✅ YOLO interval
        AppSessionSettings.yoloFrameInterval = data.getIntExtra(
            SettingsActivity.RESULT_YOLO_INTERVAL,
            AppSessionSettings.yoloFrameInterval
        ).coerceIn(GlobalParams.Defaults.YOLO_INTERVAL_MIN, GlobalParams.Defaults.YOLO_INTERVAL_MAX)

        // ✅ Direction check
        AppSessionSettings.directionCheckEnabled = data.getBooleanExtra(
            SettingsActivity.RESULT_DIRECTION_CHECK,
            AppSessionSettings.directionCheckEnabled
        )

        // ✅ Measure mode
        val measureModeName = data.getStringExtra(SettingsActivity.RESULT_MEASURE_MODE)
        AppSessionSettings.measureMode = when (measureModeName) {
            "NONE" -> MeasureMode.NONE
            "MANUAL" -> MeasureMode.MANUAL
            "AUTO" -> MeasureMode.AUTO
            else -> AppSessionSettings.measureMode
        }

        // ✅ Measurement method
        val measurementMethodName = data.getStringExtra(SettingsActivity.RESULT_MEASUREMENT_METHOD)
        val method = when (measurementMethodName) {
            "RULER" -> MeasurementMethod.RULER
            "GRID" -> MeasurementMethod.GRID
            else -> AppSessionSettings.measurementMethod
        }
        // 200MP 선택 시에는 RULER로 강제(안전장치)
        AppSessionSettings.measurementMethod = if (captureMp >= 200) MeasurementMethod.RULER else method

        // ✅ Online Offset Update(=Calibration) ON/OFF
        val beforeCalib = AppSessionSettings.gridCalibrationEnabled
        val afterCalib = data.getBooleanExtra(
            SettingsActivity.RESULT_OFFSET_UPDATE_ENABLED,
            GlobalParams.Defaults.OFFSET_CALIBRATION_ENABLED
        )
        AppSessionSettings.gridCalibrationEnabled = afterCalib
        AppSessionSettings.offsetCalibrationUserOverridden = true

        // ON -> OFF 로 전환되는 경우: 현재까지의 offset을 (가능하면) 마지막 선택 모델 JSON에 저장
        if (beforeCalib && !afterCalib) {
            runCatching {
                val stored = ModelStore.get(this)
                val base = stored?.trim()?.substringBefore("_FO")
                if (!base.isNullOrBlank()) {
                    GridOnlineOffsetCalibrationStore.persistOffsetsToModelJson(this, base)
                }
            }
        }

        // ✅ Upload / Save Result
        AppSessionSettings.uploadToServerEnabled = data.getBooleanExtra(
            SettingsActivity.RESULT_UPLOAD_TO_SERVER_ENABLED,
            AppSessionSettings.uploadToServerEnabled
        )
        AppSessionSettings.saveResultEnabled = data.getBooleanExtra(
            SettingsActivity.RESULT_SAVE_RESULT_ENABLED,
            AppSessionSettings.saveResultEnabled
        )

        // (ModelSelect에서는 토스트/상태 표시를 최소화)
        AirulerFileLogger.i(
            TAG,
            "Settings applied from ModelSelect: captureMp=$captureMp, preview=${AppSessionSettings.selectedPreviewSize}, " +
                "measureMode=${AppSessionSettings.measureMode}, method=${AppSessionSettings.measurementMethod}"
        )
    }

    private fun queryPreviewSizesSafely(): List<Size> {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return emptyList()

            val chars = cm.getCameraCharacteristics(backId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
            val arr = map.getOutputSizes(SurfaceTexture::class.java) ?: return emptyList()

            arr.toList().sortedByDescending { it.width.toLong() * it.height.toLong() }
        } catch (e: Throwable) {
            // 권한/기기 특이 케이스: 해상도 목록은 비워두고 Settings는 여전히 열리게
            AirulerFileLogger.w(TAG, "queryPreviewSizesSafely failed: ${e.message}")
            emptyList()
        }
    }

    private fun pickDefaultPreviewSize(previewSizes: List<Size>): Size? {
        if (previewSizes.isEmpty()) return null
        val targetAspect = 16f / 9f

        fun aspectDiff(size: Size): Float {
            val a = size.width.toFloat() / size.height.toFloat()
            return abs(a - targetAspect)
        }

        val candidates = previewSizes
            .filter { it.width > it.height }
            .sortedWith(
                compareBy<Size> { aspectDiff(it) }
                    .thenBy { abs(it.width - 1280) }
            )

        return candidates.firstOrNull() ?: previewSizes.firstOrNull()
    }

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

    // ---------------- Open Main (with file guards) ----------------

    private fun tryOpenMain(model: String) {
        val base = model.trim().substringBefore("_FO")
        if (!isModelReadyToEnter(base)) return
        openMain(base)
    }

    /**
     * ✅ 요구사항
     * - (일반 모델) tflite + <model>.json + Grid.json 이 없으면 입장 금지
     * - (Grid 모델) tflite만 있으면 입장 허용( Grid.json 없어도 OK )
     */
    private fun isModelReadyToEnter(baseModel: String): Boolean {
        val isGrid = baseModel.equals("Grid", ignoreCase = true)

        val tflite = ModelFileStore.downloadedModelFile(this, baseModel)
        if (!tflite.exists()) {
            toast("${tflite.name} 없음. Update Model에서 다운로드하세요.")
            return false
        }

        if (isGrid) {
            // Grid는 Grid.json이 없어도 Main에서 Grid 분석을 수행할 수 있으므로 허용
            return true
        }

        val json = ModelFileStore.downloadedModelJsonFile(this, baseModel)
        if (!json.exists()) {
            toast("${baseModel}.json 없음. Update Model에서 다운로드하세요.")
            return false
        }

        // Grid.json 존재 확인 (case mismatch 방어)
        val gridJson = ModelFileStore.downloadedModelJsonFile(this, "Grid")
        val gridJsonAlt = runCatching { java.io.File(ModelFileStore.modelsDir(this), "grid.json") }.getOrNull()
        val hasGridJson = gridJson.exists() || (gridJsonAlt?.exists() == true)
        if (!hasGridJson) {
            // ✅ 요청사항: 일반 모델 진입 시 Grid.json이 없으면 '경고'만 하고 입장은 허용
            toast("Grid.json 없음. 치수를 재려면 'Grid 분석'을 먼저 수행하세요.")
        }

        return true
    }

    private fun openMain(model: String) {
        AirulerFileLogger.i(TAG, "Model selected -> open MainActivity. model=$model")

        // ✅ 요구사항: 모델 선택 후 "처음부터 offset 업데이트"를 위해 offsets/n을 초기화
        runCatching {
            val base = model.trim().substringBefore("_FO")
            GridOnlineOffsetCalibrationStore.resetOffsetsForModel(this, base)
        }

        ModelStore.save(this, model)

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(ModelStore.EXTRA_SELECTED_MODEL, model)
            }
        )
        finish()
    }

    // ---------------- SW Update (dialog) ----------------

    private fun registerSwUpdateReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(
                this,
                swUpdateCompleteReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(swUpdateCompleteReceiver, filter)
        }
    }

    private fun showSwUpdateDialog() {
        val v = layoutInflater.inflate(com.hklab.airuler.R.layout.dialog_sw_update, null)

        val txtInfo = v.findViewById<TextView>(com.hklab.airuler.R.id.txtSwInfo)
        val txtStatus = v.findViewById<TextView>(com.hklab.airuler.R.id.txtSwStatus)
        val txtPct = v.findViewById<TextView>(com.hklab.airuler.R.id.txtSwPct)
        val pb = v.findViewById<ProgressBar>(com.hklab.airuler.R.id.progressSw)

        txtInfo.text = buildSwInfoText()
        txtStatus.text = "${SW_UPDATE_FILE_NAME}를 다운로드하여 업데이트할까요?"
        txtPct.text = ""
        pb.max = 100
        pb.progress = 0
        pb.visibility = View.GONE
        txtPct.visibility = View.GONE

        val dialog = AlertDialog.Builder(this)
            .setTitle("Update SW")
            .setView(v)
            .setNegativeButton("CLOSE", null)
            .setPositiveButton("UPDATE", null)
            .create()

        dialog.setOnShowListener {
            // UI refs 연결
            swDialog = dialog
            swDlgInfo = txtInfo
            swDlgStatus = txtStatus
            swDlgPct = txtPct
            swDlgProgress = pb

            val btnUpdate = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btnUpdate.setOnClickListener {
                startSwUpdateDownload()
            }

            // ✅ 버튼 영역(CLOSE/UPDATE)을 가운데로
            runCatching {
                val btnClose = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                val parent = btnClose?.parent
                if (parent is android.widget.LinearLayout) {
                    parent.gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
            }

            // ✅ 창 폭이 너무 넓지 않도록 약간 축소
            runCatching {
                val dm = resources.displayMetrics
                val desired = (330f * dm.density).toInt()
                val max = (dm.widthPixels * 0.92f).toInt()
                val wPx = kotlin.math.min(desired, max)
                dialog.window?.setLayout(wPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            // 이미 다운로드 중이면(예: 다이얼로그를 닫았다가 다시 열었을 때) 진행 표시
            if (swUpdateDownloadId > 0L) {
                btnUpdate.isEnabled = false
                pb.visibility = View.VISIBLE
                txtPct.visibility = View.VISIBLE
                txtStatus.text = "Downloading…"
                swUpdateHandler.removeCallbacks(swUpdateProgressPoll)
                swUpdateHandler.post(swUpdateProgressPoll)
            }
        }

        dialog.setOnDismissListener {
            // 다이얼로그가 닫히면 UI ref 정리 + 진행률 폴링 중단(오버헤드 최소화)
            clearSwDialogUiRefs()
            swUpdateHandler.removeCallbacks(swUpdateProgressPoll)
        }

        dialog.show()
    }

    private fun buildSwInfoText(): String {
        val install = runCatching {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            formatEpochMs(pi.firstInstallTime)
        }.getOrNull()

        return buildString {
            append("Version: ${GlobalParams.SW_VERSION}\n")
            if (install != null) {
                append("App installed: $install\n")
            } else {
                append("App installed: \n")
            }
            append("Provider: HK Lab Inc.\n")
        }
    }

    private fun startSwUpdateDownload() {
        // 이미 다운로드 중이면 중복 방지
        if (swUpdateDownloadId > 0L) {
            updateSwDialogProgress(null, "Already downloading…")
            return
        }

        runCatching {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(SW_UPDATE_URL)).apply {
                setTitle("AIRuler SW Update")
                setDescription("${SW_UPDATE_FILE_NAME} 다운로드")
                setMimeType("application/vnd.android.package-archive")

                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)

                // 시스템 알림은 유지(사용자 편의). 다이얼로그에서도 진행률 표시.
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                // ✅ Download 폴더에 저장
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, SW_UPDATE_FILE_NAME)
            }

            swUpdateDownloadId = dm.enqueue(req)

            // dialog progress UI
            updateSwDialogProgress(0, "Downloading…")
            swUpdateHandler.removeCallbacks(swUpdateProgressPoll)
            swUpdateHandler.post(swUpdateProgressPoll)

            // 버튼 비활성화
            swDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false

        }.onFailure { e ->
            Log.w(TAG, "SW Update start failed", e)
            swUpdateDownloadId = -1L
            updateSwDialogProgress(null, "Download start failed: ${e.message ?: e.javaClass.simpleName}")
            swDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
        }
    }

    private fun promptInstallDownloadedApk(downloadId: Long): Boolean {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val apkUri = runCatching { dm.getUriForDownloadedFile(downloadId) }.getOrNull()
        if (apkUri == null) {
            updateSwDialogProgress(null, "다운로드는 완료되었지만 APK 파일을 열 수 없습니다")
            return false
        }

        pendingSwInstallUri = apkUri

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }

            return runCatching {
                unknownAppSourcesLauncher.launch(intent)
                true
            }.getOrElse { e ->
                Log.w(TAG, "Unknown-app-sources settings launch failed", e)
                toast("앱 설치 권한 화면을 열 수 없습니다: ${e.message ?: e.javaClass.simpleName}")
                false
            }
        }

        return launchPendingDownloadedApkInstaller()
    }

    private fun launchPendingDownloadedApkInstaller(): Boolean {
        val apkUri = pendingSwInstallUri ?: return false

        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            when {
                installIntent.resolveActivity(packageManager) != null -> startActivity(installIntent)
                viewIntent.resolveActivity(packageManager) != null -> startActivity(viewIntent)
                else -> {
                    toast("APK 설치 화면을 열 수 없습니다")
                    return false
                }
            }
            pendingSwInstallUri = null
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "APK installer not found", e)
            toast("APK 설치 앱을 찾을 수 없습니다")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "APK installer launch blocked", e)
            toast("APK 설치 화면을 열 수 없습니다: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun handleSwUpdateCompleted(id: Long) {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(id)

        dm.query(q)?.use { c ->
            if (!c.moveToFirst()) return@use

            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                // ✅ 다운로드 성공 시각 저장(표시용)
                getSharedPreferences(PREF_SW_UPDATE, MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_SW_LAST_DOWNLOADED_AT, System.currentTimeMillis())
                    .apply()

                updateSwDialogProgress(100, "다운로드 완료. 설치 화면을 엽니다…")

                val installUiLaunched = promptInstallDownloadedApk(id)
                if (installUiLaunched) {
                    swDialog?.dismiss()
                } else {
                    // ✅ 설치 화면을 띄우지 못한 경우에만 다이얼로그를 기본 상태로 복귀
                    swUpdateHandler.postDelayed({ resetSwDialogToIdle() }, 700L)
                }

            } else {
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                updateSwDialogProgress(null, "Download failed (reason=$reason)")

                swUpdateHandler.postDelayed({ resetSwDialogToIdle() }, 700L)
            }
        }

        // 상태 리셋
        swUpdateDownloadId = -1L
        swUpdateHandler.removeCallbacks(swUpdateProgressPoll)

        // 다이얼로그가 열려 있으면 Update 버튼을 다시 활성화
        swDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true

        // 정보 라벨 갱신
        swDlgInfo?.text = buildSwInfoText()
    }

    private fun resetSwDialogToIdle() {
        // 다이얼로그가 닫혔으면 아무것도 하지 않음
        if (swDialog?.isShowing != true) return

        swDlgProgress?.apply {
            progress = 0
            visibility = View.GONE
        }
        swDlgPct?.apply {
            text = ""
            visibility = View.GONE
        }
        swDlgStatus?.text = "${SW_UPDATE_FILE_NAME}를 다운로드하여 업데이트할까요?"
        swDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
    }

    private fun updateSwDialogProgress(pct: Int?, statusMsg: String?) {
        // 다이얼로그가 없으면(닫혀 있으면) UI 업데이트 생략
        val pb = swDlgProgress
        val tvPct = swDlgPct
        val tvStatus = swDlgStatus

        if (pct != null) {
            pb?.visibility = View.VISIBLE
            tvPct?.visibility = View.VISIBLE
            pb?.progress = pct
            tvPct?.text = "$pct%"
        }
        if (statusMsg != null) {
            tvStatus?.text = statusMsg
        }
    }

    private fun clearSwDialogUiRefs() {
        swDialog = null
        swDlgInfo = null
        swDlgStatus = null
        swDlgPct = null
        swDlgProgress = null
    }

    private fun formatEpochMs(ms: Long): String {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
        return dt.format(DT_FMT)
    }

    // ---------------- Toast ----------------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
