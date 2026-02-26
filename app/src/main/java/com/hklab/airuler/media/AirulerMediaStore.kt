package com.hklab.airuler.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AirulerMediaStore {

    const val FOLDER_NAME = "AIRuler"
    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun makeFileName(prefix: String = FOLDER_NAME): String =
        "${prefix}_${fmt.format(Date())}.jpg"

    fun saveJpegBitmapToDcim(
        context: Context,
        bitmap: Bitmap,
        displayName: String = makeFileName()
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveQAndAbove(context, bitmap, displayName)
        } else {
            saveLegacy(context, bitmap, displayName)
        }
    }

    /**
     * DCIM/<folderName>/displayName 으로 저장.
     * overwrite=true면 동일 파일명(같은 RELATIVE_PATH) 기존 항목을 먼저 삭제 후 저장.
     */
    fun saveJpegBitmapToDcim(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        folderName: String = "AIRuler",
        overwrite: Boolean = false,
        jpegQuality: Int = 100
    ): android.net.Uri? {

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DCIM}/$folderName/"

        // ✅ overwrite: 기존 항목 삭제
        if (overwrite && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?"
            val args = arrayOf(displayName, relativePath)

            resolver.query(collection, projection, selection, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val uri = ContentUris.withAppendedId(collection, id)
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)) {
                    throw RuntimeException("Bitmap compress returned false")
                }
            } ?: throw RuntimeException("openOutputStream returned null")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
            }
            return uri
        } catch (e: Exception) {
            // 실패 시 찌꺼기 정리
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
    }

    private fun saveQAndAbove(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/$FOLDER_NAME")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            } ?: error("openOutputStream returned null")
        }.isSuccess

        return if (ok) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } else {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun saveLegacy(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val dir = File(dcim, FOLDER_NAME).apply { if (!exists()) mkdirs() }
        val file = File(dir, displayName)

        val ok = runCatching {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        }.isSuccess

        return if (ok) Uri.fromFile(file) else null
    }

    data class ImageItem(
        val uri: Uri,
        val displayName: String,
        val dateAddedSec: Long,
        val sizeBytes: Long
    )

    /**
     * content:// / file:// Uri 에서 사람이 읽을 수 있는 파일명을 최대한 안전하게 구합니다.
     * - (업로드 시 모델명 추론 등에 사용)
     */
    fun resolveDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        // file:// 우선
        if (uri.scheme == "file") {
            val p = uri.path
            if (!p.isNullOrBlank()) return File(p).name
        }

        // content:// 인 경우 DISPLAY_NAME
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) c.getString(col) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }


    /**
     * DCIM/<folderName>/ 에 저장된 이미지를 MediaStore에서 조회.
     * - API 29+ : RELATIVE_PATH 기준
     * - API 28- : BUCKET_DISPLAY_NAME 기준
     */
    fun queryDcimFolderImages(context: Context, folderName: String): List<ImageItem> {
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.RELATIVE_PATH
                )
            } else {
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                )
            }

            val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?" to
                        arrayOf("${Environment.DIRECTORY_DCIM}/$folderName/")
            } else {
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" to arrayOf(folderName)
            }

            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val list = mutableListOf<ImageItem>()
            resolver.query(collection, projection, selection, args, sortOrder)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = c.getString(nameCol) ?: "image_$id.jpg"
                    val date = c.getLong(dateCol)
                    val size = c.getLong(sizeCol)
                    list += ImageItem(uri, name, date, size)
                }
            }
            list
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 내부 저장소(/data/user/0/<pkg>/files/<subdir>)에 있는 이미지 파일들을 리스트업.
     * - MediaStore가 아니라 file:// Uri로 반환합니다.
     */
    fun listInternalImageFiles(dir: File): List<ImageItem> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val exts = setOf("jpg", "jpeg", "png", "webp")
        val files = dir.listFiles()
            ?.filter { it.isFile && exts.contains(it.extension.lowercase(Locale.US)) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        return files.map { f ->
            ImageItem(
                uri = Uri.fromFile(f),
                displayName = f.name,
                dateAddedSec = (f.lastModified() / 1000L),
                sizeBytes = f.length()
            )
        }
    }
}
