package com.hklab.airuler.net

import android.content.ContentResolver
import android.net.Uri
import okio.BufferedSink
import okio.source
import okhttp3.MediaType
import okhttp3.RequestBody
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mediaType: MediaType?
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        // ContentResolver.openInputStream() 은 content:// 에 최적화되어 있으며
        // 일부 환경/기기에서는 file:// 에 대해 null 을 반환할 수 있습니다.
        // (측정 결과 '임시 파일 업로드' 지원을 위해 file:// 도 안전하게 처리)
        val inputStream = resolver.openInputStream(uri)
            ?: runCatching {
                if (uri.scheme == "file") {
                    val p = uri.path
                    if (!p.isNullOrBlank()) {
                        val f = File(p)
                        if (f.exists()) FileInputStream(f) else null
                    } else null
                } else null
            }.getOrNull()

        inputStream?.use { input ->
            sink.writeAll(input.source())
        } ?: throw IOException("Cannot open input stream for uri: $uri")
    }
}
