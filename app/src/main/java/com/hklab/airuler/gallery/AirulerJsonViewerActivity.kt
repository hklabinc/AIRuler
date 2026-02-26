package com.hklab.airuler.gallery

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hklab.airuler.databinding.ActivityAirulerJsonViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AirulerJsonViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "extra_path" // internal file absolute path
    }

    private lateinit var binding: ActivityAirulerJsonViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAirulerJsonViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) {
            finish()
            return
        }

        val file = File(path)
        binding.txtHeader.text = file.name
        binding.txtMeta.text = "${file.length()}B"

        lifecycleScope.launch(Dispatchers.IO) {
            val text = runCatching {
                file.readText()
            }.getOrElse { e ->
                "[read failed] ${e.message ?: e.toString()}"
            }

            withContext(Dispatchers.Main) {
                binding.txtContent.text = text
            }
        }
    }
}
