// ModelSelectActivity.kt
package com.hklab.airuler.model

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.hklab.airuler.MainActivity
import com.hklab.airuler.calibration.GridOnlineOffsetCalibrationStore
import com.hklab.airuler.databinding.ActivityModelSelectBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.hklab.airuler.gallery.AirulerGalleryFoldersActivity
import com.hklab.airuler.GlobalParams
import com.hklab.airuler.log.AirulerFileLogger

class ModelSelectActivity : AppCompatActivity() {

    // ===== Model download UI race guard =====
    // 다운로드 완료 후에도 늦게 도착하는 progress 콜백이 progress UI를 다시 켜는 문제 방지
    private val downloadTokenSeq = AtomicLong(0L)
    private val downloadTokenByModel = ConcurrentHashMap<String, Long>()

    private fun newModelDownloadToken(model: String): Long {
        val token = downloadTokenSeq.incrementAndGet()
        downloadTokenByModel[model] = token
        return token
    }

    private fun finishModelDownloadSession(model: String, token: Long) {
        val cur = downloadTokenByModel[model]
        if (cur == token) {
            downloadTokenByModel.remove(model)
        }
    }

    private lateinit var binding: ActivityModelSelectBinding
    private lateinit var adapter: ModelSelectAdapter

    // ===== SW Update (APK) download =====
    private companion object {
        private const val TAG = "ModelSelectActivity"
        // ✅ AIRuler.apk 다운로드 위치(YesunAI)
        // - 서버 주소는 GlobalParams.YESUNAI_BASE_URL 에서만 관리
        private val SW_UPDATE_URL: String = GlobalParams.yesunaiUrl("/ruler/releases/AIRuler.apk")
        private const val SW_UPDATE_FILE_NAME = "AIRuler.apk"
    }

    private var swUpdateDownloadId: Long = -1L
    private var swUpdateLastToastPct: Int = -1
    private val swUpdateHandler = Handler(Looper.getMainLooper())

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
                            // 10% 단위로만 토스트
                            val bucket = (pct / 10) * 10
                            if (bucket != swUpdateLastToastPct) {
                                swUpdateLastToastPct = bucket
                                toast("SW Update: AIRuler.apk 다운로드 ${bucket}%")
                            }
                        }
                        swUpdateHandler.postDelayed(this, 600L)
                    }

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        // receiver에서 완료 토스트 처리
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        swUpdateDownloadId = -1L
                        toast("SW Update 다운로드 실패 (reason=$reason)")
                        updateSwButtonUi(isDownloading = false)
                    }
                }
            }
        }
    }

    // ===== Update All Models =====
    @Volatile private var updateAllRunning: Boolean = false

    // ===== Update Grid =====
    @Volatile private var updateGridRunning: Boolean = false

    // ===== Preset models for long-press Add =====
    private val presetModelNames: List<String> = listOf(
        /*"L0625-05",
        "L1756-02",
        "L2017-03",
        "L2018-04",
        "L2023-04",
        "L2024-03",

        "L2786-00",
        "L2787-00",
        "L2789-00",
        "L2790-00",*/

        "L1824-03",
        "L1825-03",
        "L1827-00",
        "L1828-00",
        "M1892-00",
        "M1893-00",
        "M2379-00",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityModelSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ (요구사항) ModelSelectActivity 진입 시마다 새 로그 파일로 세션 시작
        // - MainActivity/파이프라인 전체에서 동일 파일로 누적 기록
        // - 다시 ModelSelect로 돌아오면(새 인스턴스) 자동으로 새 파일로 회전
        AirulerFileLogger.startNewSession(this, reason = "ModelSelectActivity.onCreate")
        AirulerFileLogger.i(TAG, "ModelSelectActivity created. YESUNAI_BASE_URL=${GlobalParams.YESUNAI_BASE_URL}")

        // ✅ Bottom buttons
        binding.btnSwUpdate.setOnClickListener {
            toast("길게 누르면 AIRuler.apk를 다운로드합니다.")
        }
        binding.btnSwUpdate.setOnLongClickListener {
            startSwUpdateDownload()
            true
        }

        // ✅ Grid: long press -> download Grid.tflite/Grid.jpg (static from /airuler/models)
        //    short press -> open MainActivity in Grid mode (ROI detect in live preview)
        binding.btnUpdateGrid.setOnClickListener {
            val tflite = ModelFileStore.downloadedModelFile(this, "Grid")
            if (!tflite.exists()) {
                toast("Grid.tflite 없음. 'Update Grid'를 길게 눌러 다운로드하세요.")
                return@setOnClickListener
            }
            openMain("Grid")
        }
        binding.btnUpdateGrid.setOnLongClickListener {
            startUpdateGridModel()
            true
        }

        // ✅ Check All Models: 모든 모델 타일에 (Status/Update/Delete) 패널을 한번에 표시
        //    - 다시 누르거나, 배경 터치 시 닫힘(adapter.clearActions)
        binding.btnCheckAllModels.setOnClickListener {
            // toggle
            adapter.toggleActionsForAll()
        }

        binding.btnUpdateAllModels.setOnClickListener {
            toast("길게 누르면 추가된 모든 모델을 순차적으로 업데이트합니다.")
        }
        binding.btnUpdateAllModels.setOnLongClickListener {
            startUpdateAllModels()
            true
        }

        // ✅ DownloadManager 완료 브로드캐스트 등록
        registerSwUpdateReceiver()

        adapter = ModelSelectAdapter(
            onSelect = { model -> openMain(model) },
            onAdd = { showAddDialog() },
            onAddLong = { addPresetModels() },
            onDownloadOrUpdate = { model -> downloadOrUpdate(model) },
            onDelete = { model -> confirmDelete(model) },
            // ✅ Status 버튼은 '표시용'으로만 사용 (상세 팝업 없음)
            onStatus = { _ -> openGalleryFolders() },
            modelExists = { model ->
                // ✅ 앞으로 assets/models는 사용하지 않음. (내부 models 폴더만)
                ModelFileStore.downloadedExists(this, model)
            }
        )

        val span = calcSpanCountFor60Percent()
        binding.recyclerModels.layoutManager = GridLayoutManager(this, span)
        binding.recyclerModels.adapter = adapter

        // ✅ 배경(아이템 아닌 영역) 터치 시 액션 패널 닫기
        binding.recyclerModels.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                val child = binding.recyclerModels.findChildViewUnder(ev.x, ev.y)
                if (child == null) adapter.clearActions()
            }
            false
        }
        binding.root.setOnClickListener { adapter.clearActions() }

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
        val models = ModelRegistry.getEnabledModels(this)

        val items = models.map { name ->
            ModelItem.Model(name = name)
        } + ModelItem.Add

        adapter.submit(items)
    }

    /** "지금보다 60% 정도"로 보이게: 타일 목표 dp를 작게 잡고 spanCount를 자동 증가 */
    private fun calcSpanCountFor60Percent(): Int {
        val dm: DisplayMetrics = resources.displayMetrics
        val targetTileDp = 90f
        val tilePx = (targetTileDp * dm.density).toInt().coerceAtLeast(1)
        return max(3, dm.widthPixels / tilePx)
    }

    // ---------------- Add (single / preset) ----------------

    private fun showAddDialog() {
        val edit = EditText(this).apply {
            hint = "예: L2017-07"
        }

        AlertDialog.Builder(this)
            .setTitle("Add model")
            .setView(edit)
            .setPositiveButton("Add") { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isBlank()) {
                    toast("모델명이 비어있습니다")
                    return@setPositiveButton
                }
                ModelRegistry.add(this, name)
                // 아이콘이 없으면 안내(placeholder로 보일 수 있음)
                if (!ModelFileStore.assetIconExists(this, name) && !ModelFileStore.downloadedModelJpgFile(this, name).exists()) {
                    toast("주의: models/$name.jpg 또는 assets/model_icons/$name.jpg 가 없으면 아이콘이 비어 보일 수 있어요")
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Add 타일을 길게 누르면, 미리 지정된 17개 모델명을 목록에 추가 */
    private fun addPresetModels() {
        val before = ModelRegistry.getEnabledModels(this).toSet()
        var added = 0

        presetModelNames.forEach { name ->
            if (!before.contains(name)) {
                ModelRegistry.add(this, name)
                added++
            }
        }

        refresh()
        toast("Preset models added: $added/${presetModelNames.size}")
    }

    // ---------------- Delete / Status ----------------

    private fun confirmDelete(model: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("모델 '$model' 을 목록에서 제거할까요?\n(내부 models 폴더의 tflite/json/jpg가 있으면 같이 삭제됩니다)")
            .setPositiveButton("Delete") { _, _ ->
                ModelRegistry.remove(this, model)
                ModelFileStore.deleteDownloaded(this, model)
                toast("삭제 완료: $model")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openGalleryFolders() {
        startActivity(Intent(this, AirulerGalleryFoldersActivity::class.java))
    }

    private fun showModelStatus(model: String) {
        val tflite = ModelFileStore.downloadedModelFile(this, model)
        val json = ModelFileStore.downloadedModelJsonFile(this, model)
        val jpg = ModelFileStore.downloadedModelJpgFile(this, model)
        val refs = ModelFileStore.countRefImages(this, model)

        val msg = buildString {
            append("[$model]\n")
            append("tflite: ${if (tflite.exists()) "OK" else "MISSING"}\n")
            append("json  : ${if (json.exists()) "OK" else "MISSING"}\n")
            append("jpg   : ${if (jpg.exists()) "OK" else "MISSING"}\n")
            append("ref_imgs: $refs\n")
            append("models dir: ${tflite.parentFile?.absolutePath}")
        }
        showInfoBoxDialog(title = "Model Status", message = msg)
    }

    // ---------------- Download / Update ----------------

    private fun downloadOrUpdate(model: String) {
        // ✅ 중복 다운로드(연타/동시에 Update All 등) 방지
        if (downloadTokenByModel.containsKey(model)) {
            toast("이미 다운로드 중입니다: $model")
            return
        }

        // ✅ 이번 다운로드 세션 토큰(완료 후에도 늦게 도착하는 progress 콜백이
        //    진행중 표시를 다시 켜는 현상 방지)
        val token = newModelDownloadToken(model)

        //toast("다운로드 시작: $model")

        // 타일 progress overlay 표시 시작
        adapter.showProgress(model, 0)

        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    HawkModelDownloader.downloadOrUpdateModel(
                        context = this@ModelSelectActivity,
                        modelName = model,
                        onProgress = { p ->
                            // ✅ “현재 다운로드 세션” 토큰이 같을 때만 UI 반영
                            runOnUiThread {
                                if (downloadTokenByModel[model] == token) {
                                    adapter.showProgress(model, p)
                                } else {
                                    // stale progress ignore
                                }
                            }
                        }
                    )
                }

                val isGrid = model.equals("Grid", ignoreCase = true)
                val jsonOk = if (isGrid) true else ModelFileStore.downloadedJsonExists(this@ModelSelectActivity, model)
                val jpgOk = if (isGrid) true else ModelFileStore.downloadedJpgExists(this@ModelSelectActivity, model)

                val msg = buildString {
                    append("다운로드 완료: ${file.name}")
                    if (!isGrid) {
                        if (jsonOk) append(" + $model.json") else append(" (주의: $model.json 없음)")
                        if (jpgOk) append(" + $model.jpg") else append(" (주의: $model.jpg 없음)")
                    }
                }
                toast(msg)

            } catch (e: Exception) {
                toast("다운로드 실패: ${e.javaClass.simpleName}: ${e.message}")
                Log.w("ModelSelect", "download failed", e)

            } finally {
                // ✅ 토큰이 같은 “해당 다운로드 세션”만 progress 종료 처리
                if (downloadTokenByModel[model] == token) {
                    finishModelDownloadSession(model, token)
                    adapter.showProgress(model, null) // ✅ 스피너/오버레이 제거
                }
                refresh() // Download → Update로 텍스트 갱신
            }
        }
    }

    /**
     * 기존 downloadOrUpdate 로직을 "await 가능한" suspend 형태로 분리
     * @return 성공 여부
     */
    private suspend fun downloadOrUpdateOnce(
        model: String,
        showStartToast: Boolean,
        showDoneToast: Boolean,
    ): Boolean {
        if (showStartToast) toast("다운로드 시작: $model")

        // 타일 progress overlay 표시 시작
        adapter.showProgress(model, 0)

        return try {
            val file = withContext(Dispatchers.IO) {
                HawkModelDownloader.downloadOrUpdateModel(
                    context = this@ModelSelectActivity,
                    modelName = model,
                    onProgress = { p ->
                        runOnUiThread { adapter.showProgress(model, p) }
                    }
                )
            }

            val isGrid = model.equals("Grid", ignoreCase = true)
            val jsonOk = if (isGrid) true else ModelFileStore.downloadedJsonExists(this@ModelSelectActivity, model)
            val jpgOk = if (isGrid) true else ModelFileStore.downloadedJpgExists(this@ModelSelectActivity, model)

            val msg = buildString {
                append("다운로드 완료: ${file.name}")
                if (!isGrid) {
                    if (jsonOk) append(" + $model.json") else append(" (주의: $model.json 없음)")
                    if (jpgOk) append(" + $model.jpg") else append(" (주의: $model.jpg 없음)")
                }
            }
            if (showDoneToast) toast(msg)
            true

        } catch (e: Exception) {
            if (showDoneToast) toast("다운로드 실패: ${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "download failed", e)
            false

        } finally {
            adapter.showProgress(model, null)
            refresh() // Download → Update로 텍스트 갱신
        }
    }

    // ---------------- Update All Models ----------------

    // ---------------- Update Grid ----------------

    private fun startUpdateGridModel() {
        if (updateGridRunning) {
            toast("이미 Update Grid가 진행 중입니다.")
            return
        }

        updateGridRunning = true
        binding.btnUpdateGrid.isEnabled = false
        binding.btnUpdateGrid.text = "Update Grid (Running…)"

        toast("Grid 모델 다운로드를 시작합니다. (길게 클릭)")

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    HawkModelDownloader.downloadGridStatic(
                        context = this@ModelSelectActivity,
                        onProgress = { p ->
                            runOnUiThread {
                                // progress 는 0..100
                                binding.btnUpdateGrid.text = "Update Grid (${p}%)"
                            }
                        }
                    )
                }

                val tflite = ModelFileStore.downloadedModelFile(this@ModelSelectActivity, "Grid")
                val jpg = ModelFileStore.downloadedModelJpgFile(this@ModelSelectActivity, "Grid")

                toast(
                    "Grid 다운로드 완료: " +
                        "${if (tflite.exists()) "Grid.tflite" else "(tflite missing)"}, " +
                        "${if (jpg.exists()) "Grid.jpg" else "(jpg missing)"}"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Update Grid download failed", e)
                toast("Grid 다운로드 실패: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                updateGridRunning = false
                binding.btnUpdateGrid.isEnabled = true
                binding.btnUpdateGrid.text = "Update Grid"
                refresh()
            }
        }
    }

    private fun startUpdateAllModels() {
        if (updateAllRunning) {
            toast("이미 Update All Models가 진행 중입니다.")
            return
        }

        val models = ModelRegistry.getEnabledModels(this)
        if (models.isEmpty()) {
            toast("업데이트할 모델이 없습니다.")
            return
        }

        updateAllRunning = true
        adapter.clearActions()
        updateAllButtonUi(isRunning = true)

        lifecycleScope.launch {
            var okCount = 0
            var failCount = 0

            try {
                models.forEachIndexed { idx, model ->
                    toast("Update All: (${idx + 1}/${models.size}) $model")
                    val ok = downloadOrUpdateOnce(model, showStartToast = false, showDoneToast = false)
                    if (ok) okCount++ else failCount++
                }
            } finally {
                updateAllRunning = false
                updateAllButtonUi(isRunning = false)
            }

            toast("Update All 완료: OK=$okCount, FAIL=$failCount")
        }
    }

    private fun updateAllButtonUi(isRunning: Boolean) {
        binding.btnUpdateAllModels.isEnabled = !isRunning
        binding.btnUpdateAllModels.text = if (isRunning) "Update All (Running…)" else "Update All Models"
    }

    // ---------------- Open Main ----------------

    private fun openMain(model: String) {
        AirulerFileLogger.i(TAG, "Model selected -> open MainActivity. model=$model")

        // ✅ 요구사항: 모델 선택 후 "처음부터 offset 업데이트"를 위해 offsets/n을 초기화
        // - Offsets 다이얼로그의 Reset 버튼과 동일한 동작
        // - 모델 JSON의 measure_*.offset 을 0으로 초기화하고, 세션 내 calibrator(sampleN)도 0으로 리셋
        runCatching {
            val base = model.trim().substringBefore("_FO")
            GridOnlineOffsetCalibrationStore.resetOffsetsForModel(this, base)
        }

        // 기존 ModelStore 로직이 있다면 저장 후 이동
        ModelStore.save(this, model)

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(ModelStore.EXTRA_SELECTED_MODEL, model)
            }
        )
        finish()
    }

    // ---------------- Toast / Utils ----------------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showInfoBoxDialog(title: String, message: String) {
        val tv = TextView(this).apply {
            text = message
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)

            val pad = dp(16)
            setPadding(pad, dp(12), pad, dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val scroll = ScrollView(this).apply {
            addView(tv)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("닫기", null)
            .setNeutralButton("복사") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("model_status", message))
                Toast.makeText(this, "클립보드에 복사됨", Toast.LENGTH_SHORT).show()
            }
            .create()

        dialog.setOnShowListener {
            // ✅ “화면 위에 info box” 느낌으로 상단 배치
            dialog.window?.setGravity(Gravity.TOP)
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // 상단 여백 약간
            dialog.window?.attributes = dialog.window?.attributes?.apply {
                y = dp(24)
            }
        }

        dialog.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- SW Update helpers ----------------

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

    private fun startSwUpdateDownload() {
        // 이미 다운로드 중이면 중복 방지
        if (swUpdateDownloadId > 0L) {
            toast("이미 SW Update 다운로드가 진행 중입니다.")
            return
        }

        runCatching {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(SW_UPDATE_URL)).apply {
                setTitle("AIRuler SW Update")
                setDescription("AIRuler.apk 다운로드")
                setMimeType("application/vnd.android.package-archive")

                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)

                // 알림은 시스템이 처리 (Android 13+ 알림 권한 없으면 표시 안 될 수 있음)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                // ✅ Download 폴더에 저장
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, SW_UPDATE_FILE_NAME)
            }

            swUpdateDownloadId = dm.enqueue(req)
            swUpdateLastToastPct = -1

            toast("SW Update: 다운로드 시작 (Download/$SW_UPDATE_FILE_NAME)")
            updateSwButtonUi(isDownloading = true)

            // 진행률 폴링 시작
            swUpdateHandler.removeCallbacks(swUpdateProgressPoll)
            swUpdateHandler.post(swUpdateProgressPoll)

        }.onFailure { e ->
            swUpdateDownloadId = -1L
            updateSwButtonUi(isDownloading = false)
            toast("SW Update 시작 실패: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun handleSwUpdateCompleted(id: Long) {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(id)

        dm.query(q)?.use { c ->
            if (!c.moveToFirst()) return@use

            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                toast("SW Update 완료: Download/$SW_UPDATE_FILE_NAME")
            } else {
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                toast("SW Update 실패 (status=$status, reason=$reason)")
            }
        }

        // 상태 리셋
        swUpdateDownloadId = -1L
        swUpdateHandler.removeCallbacks(swUpdateProgressPoll)
        updateSwButtonUi(isDownloading = false)
    }

    private fun updateSwButtonUi(isDownloading: Boolean) {
        binding.btnSwUpdate.isEnabled = !isDownloading
        binding.btnSwUpdate.text = if (isDownloading) "SW Update (Downloading…)" else "SW Update"
    }
}
