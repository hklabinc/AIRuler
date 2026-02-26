package com.hklab.airuler.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hklab.airuler.GlobalParams
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLConnection
import java.util.concurrent.TimeUnit

/**
 * AIRuler 결과 이미지(DCIM/Result) 업로드 전용 클라이언트.
 *
 * - 서버: YesunAI (GlobalParams.YESUNAI_BASE_URL)
 * - API: /api/ruler/upload-results
 * - 저장 위치(서버): wwwroot/ruler/results/
 *
 * 주의) EXIF(UserComment) JSON이 포함된 JPEG를 **그대로 바이트 복사**하여
 *       서버에 저장해야 하므로, 업로드 시 이미지 재인코딩을 절대 하지 않습니다.
 */
object AirulerResultsUploadClient {

    // ✅ YesunAI Ruler 결과 업로드
    // - 서버 주소는 GlobalParams.YESUNAI_BASE_URL 에서만 관리
    private val ENDPOINT: String = GlobalParams.yesunaiUrl("/api/ruler/upload-results")

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun uploadResults(
        context: Context,
        uris: List<Uri>
    ): UploadResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext UploadResult(false, "선택된 파일이 없습니다")

        val cr = context.contentResolver
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)

        uris.forEachIndexed { idx, uri ->
            val fileName = resolveFileName(cr = cr, uri = uri, fallback = "airuler_result_${idx}.jpg")
            val mime = resolveMimeType(cr = cr, uri = uri, fileName = fileName)
            val body = ContentUriRequestBody(cr, uri, mime.toMediaTypeOrNull())
            // 서버 쪽 파라미터명: files
            form.addFormDataPart("files", fileName, body)
        }

        val req = Request.Builder()
            .url(ENDPOINT)
            .post(form.build())
            .build()

        return@withContext try {
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.isSuccessful) UploadResult(true, "업로드", code)
                else UploadResult(false, "업로드 실패(code=$code): $bodyStr", code)
            }
        } catch (e: Exception) {
            UploadResult(false, "업로드 오류: ${e.message}")
        }
    }

    private fun resolveFileName(
        cr: android.content.ContentResolver,
        uri: Uri,
        fallback: String
    ): String {
        // 1) file:// 은 File 이름 우선
        if (uri.scheme == "file") {
            val p = uri.path
            if (!p.isNullOrBlank()) {
                val n = File(p).name
                if (n.isNotBlank()) return n
            }
        }

        // 2) content:// 은 OpenableColumns 로
        val fromQuery = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) c.getString(col) else null
            }
        }.getOrNull()
        if (!fromQuery.isNullOrBlank()) return fromQuery

        // 3) 마지막 세그먼트
        val seg = uri.lastPathSegment
        if (!seg.isNullOrBlank()) return seg

        return fallback
    }

    private fun resolveMimeType(
        cr: android.content.ContentResolver,
        uri: Uri,
        fileName: String
    ): String {
        val fromCr = runCatching { cr.getType(uri) }.getOrNull()
        if (!fromCr.isNullOrBlank()) return fromCr

        val guessed = URLConnection.guessContentTypeFromName(fileName)
        if (!guessed.isNullOrBlank()) return guessed

        return "application/octet-stream"
    }
}
