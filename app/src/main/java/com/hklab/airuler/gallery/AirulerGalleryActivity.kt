package com.hklab.airuler.gallery

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.hklab.airuler.databinding.ActivityAirulerGalleryBinding
import com.hklab.airuler.media.AirulerMediaStore
import com.hklab.airuler.model.ModelStore
import com.hklab.airuler.net.AirulerResultsUploadClient
import com.hklab.airuler.net.YesunaiRulerUploadClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class AirulerGalleryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_KIND = "extra_source_kind"   // "dcim" | "internal"
        const val EXTRA_SOURCE_KEY = "extra_source_key"     // dcim: folderName, internal: subdir("ref_imgs" or "models")

        // ✅ internal/images 화면에서 특정 확장자만 보여주고 싶을 때 사용
        //    예: internal/models (jpg) => "jpg"
        const val EXTRA_IMAGE_EXT_FILTER = "extra_image_ext_filter"

        const val SOURCE_KIND_DCIM = "dcim"
        const val SOURCE_KIND_INTERNAL = "internal"
    }

    private lateinit var binding: ActivityAirulerGalleryBinding
    private lateinit var adapter: AirulerGalleryAdapter

    private var selectionSeq: Int = 0

    private val sourceKind: String by lazy {
        intent.getStringExtra(EXTRA_SOURCE_KIND) ?: SOURCE_KIND_DCIM
    }

    private val sourceKey: String by lazy {
        intent.getStringExtra(EXTRA_SOURCE_KEY)
            ?: if (sourceKind == SOURCE_KIND_INTERNAL) "ref_imgs" else "Camera"
    }

    private val imageExtFilter: String? by lazy {
        intent.getStringExtra(EXTRA_IMAGE_EXT_FILTER)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAirulerGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AirulerGalleryAdapter(
            onSelectionChanged = { selectedCount, lastSelectedItem ->
                updateSelectionUi(selectedCount, lastSelectedItem)
            },
            onItemClick = { item ->
                openImageViewer(item)
            }
        )

        // ✅ 한 row에 8개씩 표시
        binding.recyclerImages.layoutManager = GridLayoutManager(this, 8)
        binding.recyclerImages.adapter = adapter
        binding.recyclerImages.setHasFixedSize(true)

        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnDelete.setOnClickListener { deleteSelected() }
        binding.btnUpload.setOnClickListener { uploadSelected() }

        // internal/models 에만 의미 있음 (나머지 폴더에선 숨김)
        binding.tileJson.setOnClickListener {
            openInternalFileList(ext = "json")
        }
        binding.tileTflite.setOnClickListener {
            openInternalFileList(ext = "tflite")
        }

        refresh()

        binding.btnDelete.isEnabled = false
        binding.btnUpload.isEnabled = false
    }

    private fun openImageViewer(item: AirulerMediaStore.ImageItem) {
        val isResultFolder = (sourceKind == SOURCE_KIND_DCIM) && sourceKey.equals("Result", ignoreCase = true)

        val i = Intent(this, AirulerImageViewerActivity::class.java).apply {
            putExtra(AirulerImageViewerActivity.EXTRA_URI, item.uri.toString())
            putExtra(AirulerImageViewerActivity.EXTRA_NAME, item.displayName)
            putExtra(AirulerImageViewerActivity.EXTRA_SHOW_MEASURE_BUTTON, isResultFolder)
        }
        startActivity(i)
    }

    private fun openInternalFileList(ext: String) {
        // internal/models 전용 기능으로 설계했지만, 다른 internal 폴더에도 재사용 가능
        if (sourceKind != SOURCE_KIND_INTERNAL) return

        val title = when (ext.lowercase()) {
            "json" -> "internal/$sourceKey - JSON"
            "tflite" -> "internal/$sourceKey - TFLite"
            else -> "internal/$sourceKey - $ext"
        }

        val i = Intent(this, AirulerInternalFilesActivity::class.java).apply {
            putExtra(AirulerInternalFilesActivity.EXTRA_DIR_KEY, sourceKey)
            putExtra(AirulerInternalFilesActivity.EXTRA_EXT, ext)
            putExtra(AirulerInternalFilesActivity.EXTRA_TITLE, title)
        }
        startActivity(i)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list: List<AirulerMediaStore.ImageItem> = when (sourceKind) {
            SOURCE_KIND_INTERNAL -> {
                val dir = File(filesDir, sourceKey)
                val raw = AirulerMediaStore.listInternalImageFiles(dir)
                val filter = imageExtFilter?.trim()?.lowercase(Locale.US)
                if (filter.isNullOrBlank()) {
                    raw
                } else {
                    raw.filter { item ->
                        val ext = item.displayName.substringAfterLast('.', "").lowercase(Locale.US)
                        when (filter) {
                            // internal/models (jpg) 는 jpg/jpeg 둘 다 포함
                            "jpg" -> (ext == "jpg" || ext == "jpeg")
                            else -> ext == filter
                        }
                    }
                }
            }
            else -> {
                AirulerMediaStore.queryDcimFolderImages(this, sourceKey)
            }
        }

        adapter.submitList(list)
        adapter.clearSelection()

        binding.txtEmpty.text = when (sourceKind) {
            SOURCE_KIND_INTERNAL -> "No images in internal/$sourceKey"
            else -> "No images in DCIM/$sourceKey"
        }
        binding.txtEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.btnSelectAll.isEnabled = list.isNotEmpty()
        binding.btnSelectAll.text = "Select All"

        // internal/models: JSON/TFLite 타일 표시 + 카운트 갱신
        val showExtras = (sourceKind == SOURCE_KIND_INTERNAL)
                && sourceKey.equals("models", ignoreCase = true)
                && imageExtFilter.isNullOrBlank()
        binding.layoutModelExtras.visibility = if (showExtras) View.VISIBLE else View.GONE
        if (showExtras) {
            val dir = File(filesDir, sourceKey)
            val fs = dir.listFiles()?.toList() ?: emptyList()
            val jsonCount = fs.count { it.isFile && it.extension.equals("json", ignoreCase = true) }
            val tfliteCount = fs.count { it.isFile && it.extension.equals("tflite", ignoreCase = true) }
            binding.txtJsonTile.text = "JSON\n$jsonCount"
            binding.txtTfliteTile.text = "TFLite\n$tfliteCount"
        }
    }

    private fun updateSelectionUi(selectedCount: Int, last: AirulerMediaStore.ImageItem?) {
        // 기본 버튼 상태
        binding.btnDelete.isEnabled = selectedCount > 0
        binding.btnUpload.isEnabled = selectedCount > 0
        binding.btnSelectAll.text = if (adapter.isAllSelected()) "Clear All" else "Select All"

        if (selectedCount <= 0) {
            binding.txtSelection.text = "Selected: 0"
            return
        }

        if (last == null) {
            binding.txtSelection.text = "Selected: $selectedCount"
            return
        }

        // ✅ 방금 선택된 파일명 + 해상도 표시
        val seq = ++selectionSeq
        val name = last.displayName

        // 먼저 파일명까지는 즉시 표시
        binding.txtSelection.text = "Selected: $selectedCount | $name"

        lifecycleScope.launch(Dispatchers.IO) {
            val wh = decodeImageSize(last.uri)
            withContext(Dispatchers.Main) {
                if (seq != selectionSeq) return@withContext
                if (wh != null) {
                    binding.txtSelection.text = "Selected: $selectedCount | $name (${wh.first} x ${wh.second})"
                } else {
                    binding.txtSelection.text = "Selected: $selectedCount | $name"
                }
            }
        }
    }

    private fun decodeImageSize(uri: Uri): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { ins ->
                BitmapFactory.decodeStream(ins, null, opts)
            }
            val w = opts.outWidth
            val h = opts.outHeight
            if (w > 0 && h > 0) w to h else null
        } catch (_: Exception) {
            null
        }
    }

    private fun toggleSelectAll() {
        if (adapter.itemCount <= 0) {
            toast("이미지가 없습니다")
            return
        }

        if (adapter.isAllSelected()) {
            adapter.clearSelection()
        } else {
            adapter.selectAll()
        }
    }

    private fun deleteSelected() {
        val uris = adapter.selectedUris()
        if (uris.isEmpty()) {
            toast("선택된 이미지가 없습니다")
            return
        }

        toast("삭제 시작: ${uris.size}장")
        lifecycleScope.launch(Dispatchers.IO) {
            var deleted = 0
            uris.forEach { uri ->
                val ok = when (uri.scheme) {
                    "file" -> {
                        val p = uri.path
                        if (p.isNullOrBlank()) false else File(p).delete()
                    }
                    else -> {
                        val rows = runCatching { contentResolver.delete(uri, null, null) }.getOrDefault(0)
                        rows > 0
                    }
                }
                if (ok) deleted++
            }
            withContext(Dispatchers.Main) {
                toast("삭제 완료: $deleted 장")
                refresh()
            }
        }
    }

    private fun uploadSelected() {
        val uris = adapter.selectedUris()
        if (uris.isEmpty()) {
            toast("선택된 이미지가 없습니다")
            return
        }

        toast("업로드 시작: ${uris.size}개")
        lifecycleScope.launch {
            val isDcim = (sourceKind == SOURCE_KIND_DCIM)
            val isInternal = (sourceKind == SOURCE_KIND_INTERNAL)
            val isResultFolder = isDcim && sourceKey.equals("Result", ignoreCase = true)
            val isCaptureFolder = isDcim && sourceKey.equals("Capture", ignoreCase = true)
            val isRefImgsFolder = isInternal && sourceKey.equals("ref_imgs", ignoreCase = true)

            val res = when {
                // ✅ 1) DCIM/Result → 결과 업로드 (EXIF(UserComment) JSON 포함 JPEG)
                //    서버 저장: ruler/results/<deviceId>/<modelName>/(image|json)
                isResultFolder -> {
                    AirulerResultsUploadClient.uploadResults(this@AirulerGalleryActivity, uris)
                }

                // ✅ 2) internal/ref_imgs → 레퍼런스 업로드
                //    서버 저장: ruler/profiles/<modelName>/images_ref
                isRefImgsFolder -> {
                    val selectedModel = (ModelStore.get(this@AirulerGalleryActivity) ?: "").ifBlank { "Unknown" }

                    // 파일명이 <model>_<idx>.jpg 패턴인 경우, 파일명에서 model을 우선 추론
                    // (여러 모델의 ref를 한 번에 선택해도 각각 올바른 프로필로 업로드되도록)
                    val groups: Map<String, List<Uri>> = uris.groupBy { uri ->
                        val name = runCatching { AirulerMediaStore.resolveDisplayName(contentResolver, uri) }
                            .getOrNull()
                            ?: uri.lastPathSegment
                            ?: ""
                        val base = name.substringBeforeLast('.')
                        val inferred = base.substringBefore('_').trim()
                        if (inferred.isNotBlank()) inferred else selectedModel
                    }

                    // 그룹별로 업로드
                    var ok = 0
                    val msgs = StringBuilder()
                    for ((model, list) in groups) {
                        val r = YesunaiRulerUploadClient.uploadImagesRef(this@AirulerGalleryActivity, model, list)
                        if (r.ok) ok++
                        if (msgs.isNotEmpty()) msgs.append("\n")
                        msgs.append(r.message)
                    }

                    // 여러 번 호출한 결과를 하나로 합쳐 반환
                    com.hklab.airuler.net.UploadResult(
                        ok = ok == groups.size,
                        message = msgs.toString()
                    )
                }

                // ✅ 3) DCIM/Capture → 데이터셋 업로드
                //    서버 저장: ruler/datasets/<modelName>/images
                isCaptureFolder -> {
                    val model = (ModelStore.get(this@AirulerGalleryActivity) ?: "").ifBlank { "Unknown" }
                    YesunaiRulerUploadClient.uploadDatasetImages(this@AirulerGalleryActivity, model, uris)
                }

                // ✅ 기타 폴더(DCIM/Camera, DCIM/Expert RAW 등)는 안전하게 dataset 업로드로 처리
                // - 기존(legacy) hawkai 업로드는 제거(서버 전환 요구사항)
                isDcim -> {
                    val model = (ModelStore.get(this@AirulerGalleryActivity) ?: "").ifBlank { "Unknown" }
                    YesunaiRulerUploadClient.uploadDatasetImages(this@AirulerGalleryActivity, model, uris)
                }

                else -> {
                    com.hklab.airuler.net.UploadResult(false, "업로드 대상 폴더가 아닙니다")
                }
            }

            // 성공/실패 메시지는 각 클라이언트가 생성한 문구를 그대로 표시
            toast(res.message)

            adapter.clearSelection()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
