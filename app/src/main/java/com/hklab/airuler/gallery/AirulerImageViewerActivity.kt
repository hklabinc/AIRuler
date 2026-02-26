package com.hklab.airuler.gallery

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.hklab.airuler.databinding.ActivityAirulerImageViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.BitmapFactory

class AirulerImageViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_SHOW_MEASURE_BUTTON = "extra_show_measure_button"
    }

    private lateinit var binding: ActivityAirulerImageViewerBinding

    private var cachedWh: Pair<Int, Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAirulerImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.imgViewer.setOnClickListener { binding.imgViewer.requestFocus() }

        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr.isNullOrBlank()) {
            finish()
            return
        }

        val uri = Uri.parse(uriStr)
        val name = intent.getStringExtra(EXTRA_NAME)
            ?: uri.lastPathSegment
            ?: "image"
        val wh = decodeImageSize(uri)
        cachedWh = wh

        // ✅ DCIM/Result 이미지인 경우에만 '측정값 보기' 버튼 노출
        val showMeasureButton = intent.getBooleanExtra(EXTRA_SHOW_MEASURE_BUTTON, false)
        binding.btnShowMeasures.visibility = if (showMeasureButton) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnShowMeasures.setOnClickListener {
            val i = android.content.Intent(this, AirulerMeasureValuesActivity::class.java).apply {
                putExtra(AirulerMeasureValuesActivity.EXTRA_URI, uri.toString())
                putExtra(AirulerMeasureValuesActivity.EXTRA_NAME, name)
                cachedWh?.let { (w, h) ->
                    putExtra(AirulerMeasureValuesActivity.EXTRA_W, w)
                    putExtra(AirulerMeasureValuesActivity.EXTRA_H, h)
                }
            }
            startActivity(i)
        }

        // 먼저 파일명은 즉시 표시
        binding.txtInfo.text = name

        // 이미지 표시 (Zoom/Pan은 ZoomPanImageView가 처리)
        binding.imgViewer.load(uri) {
            allowHardware(false)
        }

        // w*h 정보는 백그라운드에서만 디코드
        lifecycleScope.launch(Dispatchers.IO) {
            val wh = decodeImageSize(uri)
            withContext(Dispatchers.Main) {
                if (wh != null) {
                    binding.txtInfo.text = "$name  (${wh.first} x ${wh.second})"
                } else {
                    binding.txtInfo.text = name
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
            // file:// Uri도 여기로 들어오지만 openInputStream이 실패할 수 있어서 fallback
            runCatching {
                val p = uri.path ?: return null
                val f = File(p)
                if (!f.exists()) return null
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, opts)
                val w = opts.outWidth
                val h = opts.outHeight
                if (w > 0 && h > 0) w to h else null
            }.getOrNull()
        }
    }
}
