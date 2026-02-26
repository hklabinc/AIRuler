package com.hklab.airuler.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object HawkUploadClient {

    private const val ENDPOINT = "https://hawkai.hknu.ac.kr/api/project/fullupload"

    private const val DEFAULT_LABELS = "film"
    private const val DEFAULT_USER_ID = "hhchoi@hknu.ac.kr"

    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun uploadSelected(
        context: Context,
        uris: List<Uri>,
        labels: String = DEFAULT_LABELS,
        userId: String = DEFAULT_USER_ID
    ): UploadResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext UploadResult(false, "선택된 파일이 없습니다")

        val projectName = "AIRuler_${fmt.format(Date())}"

        val cr = context.contentResolver
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("Name", projectName)
            .addFormDataPart("Labels", labels)
            .addFormDataPart("CreatorUserId", userId)

        uris.forEachIndexed { idx, uri ->
            val fileName = resolveFileName(cr = cr, uri = uri, fallback = "airuler_${idx}.jpg")
            val mime = resolveMimeType(cr = cr, uri = uri, fileName = fileName)
            val body = ContentUriRequestBody(cr, uri, mime.toMediaTypeOrNull())
            form.addFormDataPart("Files", fileName, body)
        }

        val req = Request.Builder()
            .url(ENDPOINT)
            .post(form.build())
            .build()

        return@withContext try {
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.isSuccessful) UploadResult(true, "업로드: $projectName", code)
                else UploadResult(false, "업로드 실패(code=$code): $bodyStr", code)
            }
        } catch (e: Exception) {
            UploadResult(false, "업로드 오류: ${e.message}")
        }
    }

    private fun resolveFileName(cr: android.content.ContentResolver, uri: Uri, fallback: String): String {
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

    private fun resolveMimeType(cr: android.content.ContentResolver, uri: Uri, fileName: String): String {
        val fromCr = runCatching { cr.getType(uri) }.getOrNull()
        if (!fromCr.isNullOrBlank()) return fromCr

        val guessed = URLConnection.guessContentTypeFromName(fileName)
        if (!guessed.isNullOrBlank()) return guessed

        // 마지막 fallback
        return "application/octet-stream"
    }
}
