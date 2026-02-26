package com.hklab.airuler.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import com.hklab.airuler.GlobalParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLConnection
import java.util.concurrent.TimeUnit

/**
 * YesunAI Ruler 연동용 업로드 클라이언트.
 *
 * ✅ 요구사항 반영
 * - ref_imgs(레퍼런스):   /api/ruler/upload-images  -> ruler/profiles/<model>/images_ref
 * - DCIM/Capture(데이터셋):/api/ruler/upload-dataset-images -> ruler/datasets/<model>/images
 *
 * 주의) 업로드 시 이미지 재인코딩을 하지 않습니다(ContentUriRequestBody로 원본 바이트 전송).
 */
object YesunaiRulerUploadClient {

    private val ENDPOINT_UPLOAD_IMAGES_REF: String = GlobalParams.yesunaiUrl("/api/ruler/upload-images")
    private val ENDPOINT_UPLOAD_DATASET_IMAGES: String = GlobalParams.yesunaiUrl("/api/ruler/upload-dataset-images")
    private val ENDPOINT_UPLOAD_LOGS: String = GlobalParams.yesunaiUrl("/api/ruler/upload-logs")

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun uploadImagesRef(
        context: Context,
        modelName: String,
        uris: List<Uri>
    ): UploadResult = uploadMultipart(
        context = context,
        endpoint = ENDPOINT_UPLOAD_IMAGES_REF,
        modelName = modelName,
        uris = uris,
        successLabel = "ref_imgs 업로드"
    )

    suspend fun uploadDatasetImages(
        context: Context,
        modelName: String,
        uris: List<Uri>
    ): UploadResult = uploadMultipart(
        context = context,
        endpoint = ENDPOINT_UPLOAD_DATASET_IMAGES,
        modelName = modelName,
        uris = uris,
        successLabel = "dataset 업로드"
    )

    /**
     * internal/logs 폴더의 log_*.txt 업로드
     * - 서버 저장: ruler/logs(/<deviceId>/...)  (서버 구현에 따라)
     */
    suspend fun uploadLogs(
        context: Context,
        uris: List<Uri>
    ): UploadResult = uploadMultipartLogs(
        context = context,
        endpoint = ENDPOINT_UPLOAD_LOGS,
        uris = uris,
        successLabel = "logs 업로드"
    )

    private suspend fun uploadMultipart(
        context: Context,
        endpoint: String,
        modelName: String,
        uris: List<Uri>,
        successLabel: String
    ): UploadResult = withContext(Dispatchers.IO) {
        val safeModel = modelName.trim()
        if (safeModel.isEmpty()) return@withContext UploadResult(false, "모델명이 비어 있습니다")
        if (uris.isEmpty()) return@withContext UploadResult(false, "선택된 파일이 없습니다")

        val cr = context.contentResolver
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("modelName", safeModel)

        uris.forEachIndexed { idx, uri ->
            val fileName = resolveFileName(cr = cr, uri = uri, fallback = "airuler_${idx}.jpg")
            val mime = resolveMimeType(cr = cr, uri = uri, fileName = fileName)
            val body = ContentUriRequestBody(cr, uri, mime.toMediaTypeOrNull())
            // 서버 파라미터명: files
            form.addFormDataPart("files", fileName, body)
        }

        val req = Request.Builder()
            .url(endpoint)
            .post(form.build())
            .build()

        return@withContext try {
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    UploadResult(true, "$successLabel 완료($safeModel): ${uris.size}개", code)
                } else {
                    UploadResult(false, "$successLabel 실패(code=$code): $bodyStr", code)
                }
            }
        } catch (e: Exception) {
            UploadResult(false, "$successLabel 오류: ${e.message}")
        }
    }

    private suspend fun uploadMultipartLogs(
        context: Context,
        endpoint: String,
        uris: List<Uri>,
        successLabel: String
    ): UploadResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext UploadResult(false, "선택된 파일이 없습니다")

        val cr = context.contentResolver
        val androidId = runCatching {
            Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)

        if (androidId.isNotBlank()) {
            form.addFormDataPart("deviceId", androidId)
        }

        uris.forEachIndexed { idx, uri ->
            val fileName = resolveFileName(cr = cr, uri = uri, fallback = "log_${idx}.txt")
            val mime = resolveMimeType(cr = cr, uri = uri, fileName = fileName)
            val body = ContentUriRequestBody(cr, uri, mime.toMediaTypeOrNull())
            // 서버 파라미터명: files
            form.addFormDataPart("files", fileName, body)
        }

        val req = Request.Builder()
            .url(endpoint)
            .post(form.build())
            .build()

        return@withContext try {
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    UploadResult(true, "$successLabel 완료: ${uris.size}개", code)
                } else {
                    UploadResult(false, "$successLabel 실패(code=$code): $bodyStr", code)
                }
            }
        } catch (e: Exception) {
            UploadResult(false, "$successLabel 오류: ${e.message}")
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
