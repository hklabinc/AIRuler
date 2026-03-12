package com.hklab.airuler.model

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hklab.airuler.databinding.ActivityModelUpdateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * "Update Model" 버튼 클릭 시 열리는 모델 관리(테이블) 화면
 *
 * ✅ 요구사항 반영
 * - 현재 등록/저장된 모델 상태를 테이블(No, Model, Size, Date, JSON)로 표시
 * - 컬럼명 클릭 시 정렬
 * - 행 길게 눌러 선택(멀티 선택)
 * - 상단 버튼 5개: Add All / Add Model / Deleted Selected / Updated Selected / Updated All
 * - Grid 모델 포함
 */
class ModelUpdateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelUpdateBinding
    private lateinit var adapter: ModelUpdateAdapter

    // ✅ Preset models = 기존 (+) 아이콘을 길게 눌렀을 때의 목록
    private val presetModelNames: List<String> = listOf(
        "L1824-03",
        "L1825-03",
        "L1827-00",
        "L1828-00",
        "M1892-00",
        "M1893-00",
        "M2379-00",
        "L2785-02",
        "L2791-02"
    )

    private enum class SortKey { NO, MODEL, SIZE, DATE, JSON }
    private var sortKey: SortKey = SortKey.NO
    private var sortAsc: Boolean = true

    private val selectedModels = linkedSetOf<String>()
    private var baseRows: List<ModelRow> = emptyList()

    @Volatile
    private var updateRunning: Boolean = false

    private val dtOut = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dtRun = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val dtVersionLegacy = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ModelUpdateAdapter(
            selectedModels = selectedModels,
            onLongPress = { model ->
                toggleSelection(model)
            },
            onTap = { model ->
                // 선택 모드일 때만 탭으로 토글
                if (selectedModels.isNotEmpty()) toggleSelection(model)
            }
        )

        binding.recyclerTable.layoutManager = LinearLayoutManager(this)
        binding.recyclerTable.adapter = adapter

        // Buttons
        binding.btnAddAll.setOnClickListener { addAllPresetModels() }
        binding.btnAddModel.setOnClickListener { showAddDialog() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        binding.btnUpdateSelected.setOnClickListener { updateSelected() }
        binding.btnUpdateAll.setOnClickListener { updateAll() }

        // Header sorting
        binding.hNo.setOnClickListener { setSort(SortKey.NO) }
        binding.hModel.setOnClickListener { setSort(SortKey.MODEL) }
        binding.hSize.setOnClickListener { setSort(SortKey.SIZE) }
        binding.hDate.setOnClickListener { setSort(SortKey.DATE) }
        binding.hJson.setOnClickListener { setSort(SortKey.JSON) }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        baseRows = buildRows()
        applySortAndRender()
    }

    private fun buildRows(): List<ModelRow> {
        val names = collectAllModelNames()
        return names.map { model ->
            val base = model.trim().substringBefore("_FO")

            val tflite = ModelFileStore.downloadedModelFile(this, base)
            val tfliteExists = tflite.exists()
            val tfliteParsed = if (tfliteExists) parseTfliteName(tflite.name) else null

            val size = tfliteParsed?.size
            val dateKey = tfliteParsed?.dateKey
            val dateDisp = tfliteParsed?.dateDisplay

            val jsonFile = ModelFileStore.downloadedModelJsonFile(this, base)
            val jsonUpdated = readUpdatedFromJson(jsonFile)

            ModelRow(
                model = base,
                size = size,
                dateKey = dateKey,
                dateDisplay = dateDisp,
                jsonUpdated = jsonUpdated
            )
        }
    }

    /**
     * ✅ 테이블에 표시할 모델명 목록
     * - enabled models(등록된 이름)
     * - internal/models 에 실제 존재하는 *.tflite 기반 이름(등록 안 된 모델도 보이게)
     * - Grid 포함
     */
    private fun collectAllModelNames(): List<String> {
        val set = linkedSetOf<String>()
        set.add("Grid")

        // 1) 등록된 모델
        ModelRegistry.getEnabledModels(this).forEach { set.add(it) }

        // 2) 실제 저장된 tflite 파일 기반
        val dir = ModelFileStore.modelsDir(this)
        dir.listFiles { f -> f.isFile && f.extension.equals("tflite", true) }?.forEach { f ->
            val parsed = parseTfliteName(f.name)
            val model = parsed?.model ?: f.nameWithoutExtension
            if (model.isNotBlank()) set.add(model)
        }

        // ✅ 기본 정렬: Grid 먼저, 그 다음 model 이름 오름차순
        val list = set.toList().distinct()
        val rest = list.filterNot { it.equals("Grid", true) }.sorted()
        return listOf("Grid") + rest
    }

    private data class ParsedTflite(
        val model: String,
        val size: String,
        val dateKey: String,      // YYYYMMDDHHMMSS
        val dateDisplay: String,  // yyyy-MM-dd HH:mm:ss
    )

    /**
     * <model>_<size>_YYYYMMDD_HHMMSS.tflite
     * 예: L1827-00_nano_20251217_044032.tflite
     */
    private fun parseTfliteName(fileName: String): ParsedTflite? {
        val re = Regex("^(.+)_([^_]+)_(\\d{8})_(\\d{6})\\.tflite$", RegexOption.IGNORE_CASE)
        val m = re.matchEntire(fileName) ?: return null

        val model = m.groupValues[1]
        val size = m.groupValues[2]
        val ymd = m.groupValues[3]
        val hms = m.groupValues[4]

        val key = ymd + hms
        val dt = runCatching { LocalDateTime.parse(key, dtRun) }.getOrNull() ?: return null
        val disp = dt.format(dtOut)
        return ParsedTflite(model = model, size = size, dateKey = key, dateDisplay = disp)
    }

    private fun readUpdatedFromJson(jsonFile: File): String? {
        if (!jsonFile.exists()) return null
        return runCatching {
            val obj = JSONObject(jsonFile.readText())
            when {
                obj.has("updated") -> obj.optString("updated").takeIf { it.isNotBlank() }
                obj.has("version") -> {
                    // (호환) 과거 Grid.json: version=yyyyMMdd_HHmmss
                    val v = obj.optString("version").takeIf { it.isNotBlank() } ?: return@runCatching null
                    runCatching {
                        LocalDateTime.parse(v, dtVersionLegacy).format(dtOut)
                    }.getOrNull()
                }
                else -> null
            }
        }.getOrNull()
    }

    // ---------------- Sorting ----------------

    private fun setSort(key: SortKey) {
        if (sortKey == key) {
            sortAsc = !sortAsc
        } else {
            sortKey = key
            sortAsc = true
        }
        applySortAndRender()
    }

    private fun applySortAndRender() {
        val sorted = when (sortKey) {
            SortKey.NO -> baseRows
            SortKey.MODEL -> baseRows.sortedBy { it.model.lowercase() }
            SortKey.SIZE -> baseRows.sortedWith(compareBy<ModelRow> { it.size.isNullOrBlank() }.thenBy { it.size ?: "" })
            SortKey.DATE -> baseRows.sortedWith(compareBy<ModelRow> { it.dateKey.isNullOrBlank() }.thenBy { it.dateKey ?: "" })
            SortKey.JSON -> baseRows.sortedWith(compareBy<ModelRow> { it.jsonUpdated.isNullOrBlank() }.thenBy { it.jsonUpdated ?: "" })
        }.let { if (sortAsc) it else it.asReversed() }

        adapter.submit(sorted)
        binding.txtJobStatus.text = "${sorted.size} models" + if (selectedModels.isNotEmpty()) "  |  selected=${selectedModels.size}" else ""
    }

    // ---------------- Selection ----------------

    private fun toggleSelection(model: String) {
        if (selectedModels.contains(model)) selectedModels.remove(model) else selectedModels.add(model)
        adapter.notifyDataSetChanged()
        binding.txtJobStatus.text = "${adapter.itemCount} models" + if (selectedModels.isNotEmpty()) "  |  selected=${selectedModels.size}" else ""
    }

    // ---------------- Add ----------------

    private fun addAllPresetModels() {
        val before = ModelRegistry.getEnabledModels(this).toSet()
        var added = 0
        presetModelNames.forEach { name ->
            if (!before.contains(name)) {
                ModelRegistry.add(this, name)
                added++
            }
        }
        toast("Preset models added: $added/${presetModelNames.size}")
        reload()
    }

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
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Delete ----------------

    private fun confirmDeleteSelected() {
        if (selectedModels.isEmpty()) {
            toast("선택된 모델이 없습니다")
            return
        }

        val list = selectedModels.toList().sorted()
        val msg = buildString {
            append("선택한 모델을 삭제할까요?\n")
            append("(내부 models 폴더의 tflite/json가 있으면 같이 삭제됩니다)\n\n")
            append(list.joinToString(", "))
        }

        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ ->
                list.forEach { model ->
                    // ✅ Grid는 항상 목록에 포함시키는 정책이므로, 이름은 삭제하지 않고 파일만 삭제
                    if (!model.equals("Grid", true)) {
                        ModelRegistry.remove(this, model)
                    }
                    ModelFileStore.deleteDownloaded(this, model)
                }
                selectedModels.clear()
                toast("삭제 완료")
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Update ----------------

    private fun updateSelected() {
        if (selectedModels.isEmpty()) {
            toast("선택된 모델이 없습니다")
            return
        }
        startUpdate(models = selectedModels.toList().sorted())
    }

    private fun updateAll() {
        // ✅ Grid 포함 (항상)
        startUpdate(models = baseRows.map { it.model }.distinct())
    }

    private fun startUpdate(models: List<String>) {
        if (updateRunning) {
            toast("이미 업데이트가 진행 중입니다")
            return
        }
        if (models.isEmpty()) {
            toast("업데이트할 모델이 없습니다")
            return
        }

        updateRunning = true
        setUiEnabled(false)

        lifecycleScope.launch {
            var ok = 0
            var fail = 0

            for ((idx, m) in models.withIndex()) {
                val base = m.trim().substringBefore("_FO")
                val label = "[${idx + 1}/${models.size}] $base"

                binding.txtJobStatus.text = "$label : 0%"

                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        HawkModelDownloader.downloadOrUpdateModel(
                            context = this@ModelUpdateActivity,
                            modelName = base,
                            onProgress = { p ->
                                runOnUiThread {
                                    binding.txtJobStatus.text = "$label : $p%"
                                }
                            }
                        )
                    }
                }

                if (res.isSuccess) ok++ else fail++
            }

            updateRunning = false
            setUiEnabled(true)

            toast("Update finished: OK=$ok, FAIL=$fail")
            reload()
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        binding.btnAddAll.isEnabled = enabled
        binding.btnAddModel.isEnabled = enabled
        binding.btnDeleteSelected.isEnabled = enabled
        binding.btnUpdateSelected.isEnabled = enabled
        binding.btnUpdateAll.isEnabled = enabled
        binding.sortHeader.isEnabled = enabled
        // RecyclerView는 선택 상태 유지 위해 비활성화는 하지 않음
        binding.progressBar.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
