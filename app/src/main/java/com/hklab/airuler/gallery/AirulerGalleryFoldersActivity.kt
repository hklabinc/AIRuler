package com.hklab.airuler.gallery

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.hklab.airuler.databinding.ActivityAirulerGalleryFoldersBinding
import com.hklab.airuler.media.AirulerMediaStore
import java.io.File
import java.util.Locale

class AirulerGalleryFoldersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAirulerGalleryFoldersBinding
    private lateinit var adapter: AirulerGalleryFolderAdapter

    data class FolderEntry(
        val kind: String,   // AirulerGalleryActivity.SOURCE_KIND_*
        val key: String,    // dcim: folderName, internal: subdir
        val title: String,
        val imageCount: Int,
        val preview: Any?,
        val extFilter: String? = null // internal/models 를 확장자별로 분리해서 보여줄 때 사용
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAirulerGalleryFoldersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AirulerGalleryFolderAdapter { entry ->
            openFolder(entry)
        }

        // 폴더도 썸네일 기반으로 보이도록 Grid로 (✅ 1행 5열)
        binding.recyclerFolders.layoutManager = GridLayoutManager(this, 5)
        binding.recyclerFolders.adapter = adapter

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.submitList(buildFolderEntries())
    }

    private fun buildFolderEntries(): List<FolderEntry> {
        // ✅ DCIM 폴더들
        val camera = AirulerMediaStore.queryDcimFolderImages(this, "Camera")
        val captures = AirulerMediaStore.queryDcimFolderImages(this, "Capture")
        val results = AirulerMediaStore.queryDcimFolderImages(this, "Result")
        val expertRaw = AirulerMediaStore.queryDcimFolderImages(this, "Expert RAW")

        // ✅ 내부 저장소 폴더들
        val refDir = File(filesDir, "ref_imgs")
        val modelDir = File(filesDir, "models")
        val refImgs = AirulerMediaStore.listInternalImageFiles(refDir)

        // internal/models 는 jpg/json/tflite 로 분리해서 표시
        val modelFiles = modelDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val modelJsonFiles = modelFiles
            .filter { it.extension.equals("json", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }

        // ✅ internal/logs 는 txt 로만 누적 저장
        val logDir = File(filesDir, "logs")
        val logTxtFiles = logDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        return listOf(
            FolderEntry(
                kind = AirulerGalleryActivity.SOURCE_KIND_DCIM,
                key = "Camera",
                title = "DCIM/Camera",
                imageCount = camera.size,
                preview = camera.firstOrNull()?.uri
            ),
            FolderEntry(
                kind = AirulerGalleryActivity.SOURCE_KIND_DCIM,
                key = "Capture",
                title = "DCIM/Capture",
                imageCount = captures.size,
                preview = captures.firstOrNull()?.uri
            ),
            FolderEntry(
                kind = AirulerGalleryActivity.SOURCE_KIND_DCIM,
                key = "Expert RAW",
                title = "DCIM/Expert RAW",
                imageCount = expertRaw.size,
                preview = expertRaw.firstOrNull()?.uri
            ),
            FolderEntry(
                kind = AirulerGalleryActivity.SOURCE_KIND_DCIM,
                key = "Result",
                title = "DCIM/Result",
                imageCount = results.size,
                preview = results.firstOrNull()?.uri
            ),
            FolderEntry(
                kind = AirulerGalleryActivity.SOURCE_KIND_INTERNAL,
                key = "ref_imgs",
                title = "internal/ref_imgs",
                imageCount = refImgs.size,
                preview = refImgs.firstOrNull()?.uri
            ),
            FolderEntry(
                kind = AirulerGalleryActivity.SOURCE_KIND_INTERNAL,
                key = "models",
                title = "internal/models (json)",
                imageCount = modelJsonFiles.size,
                preview = android.R.drawable.ic_menu_agenda,
                extFilter = "json"
            ),
            FolderEntry(
                kind = AirulerGalleryActivity.SOURCE_KIND_INTERNAL,
                key = "logs",
                title = "internal/logs (txt)",
                imageCount = logTxtFiles.size,
                preview = android.R.drawable.ic_menu_edit,
                extFilter = "txt"
            )
        )
    }

    private fun openFolder(entry: FolderEntry) {
        // internal/* (json/tflite/txt) => 바로 파일 리스트 화면으로
        val ext = entry.extFilter?.lowercase(Locale.US)
        if (entry.kind == AirulerGalleryActivity.SOURCE_KIND_INTERNAL && (ext == "json" || ext == "txt")) {
            val title = entry.title
            val i = Intent(this, AirulerInternalFilesActivity::class.java).apply {
                putExtra(AirulerInternalFilesActivity.EXTRA_DIR_KEY, entry.key)
                putExtra(AirulerInternalFilesActivity.EXTRA_EXT, ext)
                putExtra(AirulerInternalFilesActivity.EXTRA_TITLE, title)

                // ✅ logs(txt)에서만 UPLOAD 버튼 표시
                putExtra(AirulerInternalFilesActivity.EXTRA_ENABLE_UPLOAD, entry.key == "logs")
            }
            startActivity(i)
            return
        }

        // 나머지는 기존 이미지 갤러리 화면
        val i = Intent(this, AirulerGalleryActivity::class.java).apply {
            putExtra(AirulerGalleryActivity.EXTRA_SOURCE_KIND, entry.kind)
            putExtra(AirulerGalleryActivity.EXTRA_SOURCE_KEY, entry.key)
            if (!ext.isNullOrBlank()) {
                putExtra(AirulerGalleryActivity.EXTRA_IMAGE_EXT_FILTER, ext)
            }
        }
        startActivity(i)
    }
}