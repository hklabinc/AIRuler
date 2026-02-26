package com.hklab.airuler.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hklab.airuler.databinding.ActivityAirulerInternalFilesBinding
import com.hklab.airuler.net.YesunaiRulerUploadClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class AirulerInternalFilesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DIR_KEY = "extra_dir_key"   // filesDir 하위 subdir
        const val EXTRA_EXT = "extra_ext"           // json | tflite
        const val EXTRA_TITLE = "extra_title"       // 화면 상단 표시용
        const val EXTRA_ENABLE_UPLOAD = "extra_enable_upload" // 선택 파일 업로드 버튼 표시 여부
    }

    private lateinit var binding: ActivityAirulerInternalFilesBinding
    private lateinit var adapter: AirulerInternalFileAdapter

    private val dirKey: String by lazy {
        intent.getStringExtra(EXTRA_DIR_KEY) ?: "models"
    }

    private val ext: String by lazy {
        intent.getStringExtra(EXTRA_EXT) ?: "json"
    }

    private val headerTitle: String by lazy {
        intent.getStringExtra(EXTRA_TITLE) ?: "internal/$dirKey - .$ext"
    }

    private val enableUpload: Boolean by lazy {
        intent.getBooleanExtra(EXTRA_ENABLE_UPLOAD, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAirulerInternalFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.txtHeader.text = headerTitle

        adapter = AirulerInternalFileAdapter(
            onSelectionChanged = { selectedCount ->
                updateSelectionUi(selectedCount)
            },
            onItemClick = { item ->
                onFileClicked(item)
            }
        )

        binding.recyclerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerFiles.adapter = adapter

        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnDelete.setOnClickListener { deleteSelected() }

        // ✅ logs(.txt) 등에서만 업로드 버튼 노출
        if (enableUpload) {
            binding.btnUpload.visibility = View.VISIBLE
            binding.btnUpload.setOnClickListener { uploadSelected() }
        } else {
            binding.btnUpload.visibility = View.GONE
        }

        binding.btnDelete.isEnabled = false
        binding.btnUpload.isEnabled = false

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val dir = File(filesDir, dirKey)
        val files = listFilesByExt(dir, ext)
        adapter.submitList(files)
        adapter.clearSelection()

        binding.txtEmpty.text = "No .$ext files in internal/$dirKey"
        binding.txtEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.btnSelectAll.isEnabled = files.isNotEmpty()
        binding.btnSelectAll.text = "Select All"
    }

    private fun listFilesByExt(dir: File, ext: String): List<InternalFileItem> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val targetExt = ext.lowercase(Locale.US)
        val fs = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) == targetExt }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        return fs.map { f ->
            InternalFileItem(
                file = f,
                displayName = f.name,
                sizeBytes = f.length(),
                lastModified = f.lastModified()
            )
        }
    }

    private fun updateSelectionUi(selectedCount: Int) {
        binding.txtSelection.text = "Selected: $selectedCount"
        binding.btnDelete.isEnabled = selectedCount > 0
        binding.btnUpload.isEnabled = enableUpload && selectedCount > 0
        binding.btnSelectAll.text = if (adapter.isAllSelected()) "Clear All" else "Select All"
    }

    private fun toggleSelectAll() {
        if (adapter.itemCount <= 0) {
            toast("파일이 없습니다")
            return
        }

        if (adapter.isAllSelected()) {
            adapter.clearSelection()
        } else {
            adapter.selectAll()
        }
    }

    private fun deleteSelected() {
        val files = adapter.selectedFiles()
        if (files.isEmpty()) {
            toast("선택된 파일이 없습니다")
            return
        }

        toast("삭제 시작: ${files.size}개")
        lifecycleScope.launch(Dispatchers.IO) {
            var deleted = 0
            files.forEach { item ->
                if (runCatching { item.file.delete() }.getOrDefault(false)) {
                    deleted++
                }
            }
            withContext(Dispatchers.Main) {
                toast("삭제 완료: $deleted 개")
                refresh()
            }
        }
    }

    private fun onFileClicked(item: InternalFileItem) {
        // JSON/TXT는 내용 보기 지원
        if (ext.equals("json", ignoreCase = true) || ext.equals("txt", ignoreCase = true)) {
            val i = Intent(this, AirulerJsonViewerActivity::class.java).apply {
                putExtra(AirulerJsonViewerActivity.EXTRA_PATH, item.file.absolutePath)
            }
            startActivity(i)
            return
        }

        // TFLite는 내용 보기 필요 없음 (요청사항)
        // => 아무 동작 안 함 (필요하면 여기서 토스트/정보창 추가 가능)
    }

    private fun uploadSelected() {
        if (!enableUpload) return

        val files = adapter.selectedFiles()
        if (files.isEmpty()) {
            toast("선택된 파일이 없습니다")
            return
        }

        val uris = files.map { Uri.fromFile(it.file) }
        toast("업로드 시작: ${uris.size}개")

        lifecycleScope.launch {
            val res = YesunaiRulerUploadClient.uploadLogs(this@AirulerInternalFilesActivity, uris)
            toast(res.message)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
