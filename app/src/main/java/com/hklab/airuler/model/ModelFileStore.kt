package com.hklab.airuler.model

import android.content.Context
import java.io.File

/**
 * 앱 내부 저장소 파일 경로 관리
 *
 * ✅ 모델 파일
 *   - files/models/<model>.tflite
 *   - files/models/<model>.json
 *   - files/models/<model>.jpg
 *
 * ✅ 레퍼런스 이미지
 *   - files/ref_imgs/<model>_<idx>.jpg
 */
object ModelFileStore {

    // ✅ (구) downloaded_models → (신) models
    private const val DIR_MODELS = "models"
    private const val DIR_MODELS_LEGACY = "downloaded_models"
    private const val DIR_REF_IMGS = "ref_imgs"

    // ✅ (성능) legacy 폴더 마이그레이션은 프로세스 lifetime 동안 1회만 수행
    // - modelsDir()는 곳곳에서 자주 호출될 수 있어, 매번 파일 시스템 스캔을 하면 불필요한 I/O가 발생합니다.
    @Volatile
    private var legacyMigrated: Boolean = false
    private val migrateLock = Any()

    /** models/ 디렉터리 생성 + (구) downloaded_models → models 마이그레이션 */
    fun modelsDir(context: Context): File {
        val newDir = File(context.filesDir, DIR_MODELS)
        if (!newDir.exists()) newDir.mkdirs()

        // ✅ legacy 폴더 마이그레이션은 1회만 수행 (불필요한 I/O 감소)
        if (!legacyMigrated) {
            synchronized(migrateLock) {
                if (!legacyMigrated) {
                    val legacy = File(context.filesDir, DIR_MODELS_LEGACY)
                    if (legacy.exists() && legacy.isDirectory) {
                        legacy.listFiles()?.forEach { src ->
                            val dst = File(newDir, src.name)
                            if (!dst.exists()) {
                                // rename이 실패할 수도 있으니 copy fallback
                                val renamed = runCatching { src.renameTo(dst) }.getOrDefault(false)
                                if (!renamed || !dst.exists()) {
                                    runCatching {
                                        src.copyTo(dst, overwrite = false)
                                        src.delete()
                                    }
                                }
                            } else {
                                // 이미 newDir에 있으면 legacy 쪽은 정리
                                runCatching { src.delete() }
                            }
                        }
                        // 비었으면 폴더도 정리
                        val left = legacy.listFiles()?.isNotEmpty() == true
                        if (!left) runCatching { legacy.delete() }
                    }
                    legacyMigrated = true
                }
            }
        }

        return newDir
    }

    /** ref_imgs/ 디렉터리 생성 */
    fun refImgsDir(context: Context): File {
        val dir = File(context.filesDir, DIR_REF_IMGS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ----------------------------
    // Model files (internal)
    // ----------------------------

    fun downloadedModelFile(context: Context, model: String): File =
        File(modelsDir(context), "$model.tflite")

    fun downloadedExists(context: Context, model: String): Boolean =
        downloadedModelFile(context, model).exists()

    fun downloadedModelJsonFile(context: Context, model: String): File =
        File(modelsDir(context), "$model.json")

    fun downloadedJsonExists(context: Context, model: String): Boolean =
        downloadedModelJsonFile(context, model).exists()

    fun downloadedModelJpgFile(context: Context, model: String): File =
        File(modelsDir(context), "$model.jpg")

    fun downloadedJpgExists(context: Context, model: String): Boolean =
        downloadedModelJpgFile(context, model).exists()

    /** 다운로드된 tflite/json/jpg를 함께 삭제 */
    fun deleteDownloaded(context: Context, model: String): Boolean =
        runCatching {
            val a = downloadedModelFile(context, model).delete()
            val b = downloadedModelJsonFile(context, model).delete()
            val c = downloadedModelJpgFile(context, model).delete()
            a || b || c
        }.getOrDefault(false)

    // ----------------------------
    // Reference images (internal)
    // ----------------------------

    /** files/ref_imgs/<model>_<index>.jpg */
    fun refImageFile(context: Context, model: String, index: Int): File =
        File(refImgsDir(context), "${model}_${index}.jpg")


    /** files/ref_imgs/<model>_*.jpg|png 목록 */
    fun listRefImages(context: Context, model: String): List<File> {
        val dir = refImgsDir(context)
        val list = dir.listFiles { _, name ->
            name.startsWith("${model}_") && (name.endsWith(".jpg", true) || name.endsWith(".png", true))
        }?.toList() ?: emptyList()
        return list.sortedBy { it.name }
    }

    fun countRefImages(context: Context, model: String): Int =
        listRefImages(context, model).size

    fun deleteRefImages(context: Context, model: String): Boolean =
        runCatching {
            var any = false
            listRefImages(context, model).forEach { f ->
                any = any || f.delete()
            }
            any
        }.getOrDefault(false)

    // ----------------------------
    // Assets (icons only)
    // ----------------------------

    /**
     * ✅ 모델 아이콘은 아직 assets/model_icons 를 seed/기본값으로 사용 (요청 사항에서 제거 대상 아님)
     */
    fun assetIconExists(context: Context, model: String): Boolean {
        val list = runCatching { context.assets.list("model_icons") }.getOrNull() ?: return false
        return list.any { it.equals("$model.jpg", true) || it.equals("$model.png", true) }
    }
}
