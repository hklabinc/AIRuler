package com.hklab.airuler.film

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.hklab.airuler.model.ModelFileStore
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExternalMediaStoreUtils {

    // Download/AIRuler/
    const val DOWNLOADS_AIRULER_RELATIVE_PATH: String = "Download/AIRuler/"

    data class SaveResult(
        val uri: Uri,
        val relativePath: String,
        val displayName: String
    )

    /** Download/AIRuler/<displayName> 파일 Uri 찾기 (API 29+에서는 MediaStore.Files에서 검색) */
    fun findDownloadsAirulerUri(context: Context, displayName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Files.getContentUri("external")
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection =
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
                val args = arrayOf(DOWNLOADS_AIRULER_RELATIVE_PATH, displayName)

                context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(0)
                        ContentUris.withAppendedId(collection, id)
                    } else null
                }
            } else {
                val file = legacyDownloadsAirulerFile(displayName)
                if (file.exists()) Uri.fromFile(file) else null
            }
        } catch (e: Exception) {
            android.util.Log.w("ExternalMediaStoreUtils", "findDownloadsAirulerUri failed name=$displayName", e)
            null
        }
    }

    /**
     * JSON 읽기 우선순위
     * 1) (모델 json만) internal downloaded_models/<model>.json
     * 2) Download/AIRuler/<displayName>
     */
    fun readTextFromDownloadsAiruler(context: Context, displayName: String): String? {
        // ✅ 모델 json이면 internal 우선
        if (displayName.endsWith(".json", ignoreCase = true) &&
            !displayName.equals("Grid.json", ignoreCase = true)
        ) {
            val model = displayName.substringBeforeLast('.')
            val internal = ModelFileStore.downloadedModelJsonFile(context, model)
            if (internal.exists()) {
                return runCatching { internal.readText(Charsets.UTF_8) }
                    .onFailure { e ->
                        android.util.Log.w("ExternalMediaStoreUtils",
                            "Failed to read internal json: ${internal.absolutePath}", e)
                    }
                    .getOrNull()
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = findDownloadsAirulerUri(context, displayName) ?: return null
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).readText()
                }
            } else {
                val file = legacyDownloadsAirulerFile(displayName)
                if (!file.exists()) return null
                file.readText(Charsets.UTF_8)
            }
        } catch (e: SecurityException) {
            android.util.Log.w("ExternalMediaStoreUtils",
                "SecurityException reading $displayName from Download/AIRuler", e)
            null
        } catch (e: Exception) {
            android.util.Log.w("ExternalMediaStoreUtils",
                "Exception reading $displayName from Download/AIRuler", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDownloadsAirulerFile(displayName: String): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(dir, "AIRuler/$displayName")
    }

    // --- Result Image Save + EXIF ---
    fun saveJpegBytesToDcimAirulerResultWithExif(
        context: Context,
        jpegBytes: ByteArray,
        exifUserCommentJson: String
    ): SaveResult? {

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "Result_${ts}.jpg"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = "${Environment.DIRECTORY_DCIM}/Result/"

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = context.contentResolver.insert(collection, values) ?: return null

                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(jpegBytes)
                        os.flush()
                    } ?: throw RuntimeException("openOutputStream failed")

                    // EXIF(UserComment) 쓰기
                    context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, exifUserCommentJson)
                        exif.saveAttributes()
                    }

                    val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    context.contentResolver.update(uri, done, null, null)

                    SaveResult(uri, relativePath, displayName)

                } catch (e: Exception) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    android.util.Log.w("ExternalMediaStoreUtils", "saveJpegBytesToDcimAirulerResultWithExif failed", e)
                    null
                }

            } else {
                @Suppress("DEPRECATION")
                val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val outDir = File(dcim, "Result").apply { mkdirs() }
                val outFile = File(outDir, displayName)

                FileOutputStream(outFile).use { fos ->
                    fos.write(jpegBytes)
                    fos.flush()
                }

                val exif = ExifInterface(outFile.absolutePath)
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, exifUserCommentJson)
                exif.saveAttributes()

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outFile.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )

                SaveResult(Uri.fromFile(outFile), "DCIM/Result/", displayName)
            }
        } catch (e: Exception) {
            android.util.Log.w("ExternalMediaStoreUtils", "saveJpegBytesToDcimAirulerResultWithExif outer failed", e)
            null
        }
    }

}
