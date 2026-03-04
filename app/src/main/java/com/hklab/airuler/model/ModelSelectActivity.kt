package com.hklab.airuler.model

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.hklab.airuler.GlobalParams
import com.hklab.airuler.MainActivity
import com.hklab.airuler.calibration.GridOnlineOffsetCalibrationStore
import com.hklab.airuler.databinding.ActivityModelSelectBinding
import com.hklab.airuler.log.AirulerFileLogger
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

class ModelSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelSelectBinding
    private lateinit var adapter: ModelSelectAdapter

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

        // ✅ 타이틀 버전 표기: (v.xx) 괄호 제거 + GlobalParams.SW_VERSION 사용
        binding.txtTitle.text = "모델을 선택해주세요. ${GlobalParams.SW_VERSION}"

        // ✅ (요구사항) ModelSelectActivity 진입 시마다 새 로그 파일로 세션 시작
        AirulerFileLogger.startNewSession(this, reason = "ModelSelectActivity.onCreate")
        AirulerFileLogger.i(TAG, "ModelSelectActivity created. YESUNAI_BASE_URL=${GlobalParams.YESUNAI_BASE_URL}")

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
            toast("Grid.json 없음. Grid 모델에서 'Grid 분석'을 먼저 수행하거나, Update Model에서 Grid를 업데이트하세요.")
            return false
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

                updateSwDialogProgress(100, "Downloaded: Download/$SW_UPDATE_FILE_NAME")

                // ✅ 완료 후 UI를 기본 상태로 복귀(다운로드 화면이 계속 남아있지 않도록)
                swUpdateHandler.postDelayed({ resetSwDialogToIdle() }, 700L)

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
