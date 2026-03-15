package com.hklab.airuler.model

import android.content.Context
import java.io.File

/**
 * 앱 내부 저장소 파일 경로 관리
 *
 * ✅ 모델 파일
 *   - (신) files/models/<model>_<size>_YYYYMMDD_HHMMSS.tflite   // 최신 run 폴더명과 동일(확장자만 .tflite)
 *   - (구) files/models/<model>.tflite                         // legacy
 *   - files/models/<model>.json
 *   - files/models/<model>.jpg
 *
 * ✅ 레퍼런스 이미지
 *   - files/ref_imgs/<model>_<idx>.jpg
 */
object ModelFileStore {

    private const val DIR_MODELS = "models"
    private const val DIR_MODELS_LEGACY = "downloaded_models"
    private const val DIR_REF_IMGS = "ref_imgs"

    private fun baseModelKey(model: String): String = ModelNameCompat.canonical(model)

    // <size>_YYYYMMDD_HHMMSS.tflite
    private val RUN_TFLITE_SUFFIX_RE: Regex =
        Regex("^([^_]+)_(\\d{8})_(\\d{6})\\.tflite$", RegexOption.IGNORE_CASE)

    // <model>_<size>_YYYYMMDD_HHMMSS
    private val RUN_TFLITE_NAME_RE: Regex =
        Regex("^(.+)_([^_]+)_(\\d{8})_(\\d{6})$", RegexOption.IGNORE_CASE)

    // <model>_<index>.(jpg|jpeg|png)
    private val REF_IMAGE_NAME_RE: Regex =
        Regex("^(.+)_([0-9]+)\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE)

    @Volatile
    private var legacyMigrated: Boolean = false
    private val migrateLock = Any()

    @Volatile
    private var refImgsMigrated: Boolean = false
    private val refImgsMigrateLock = Any()

    /** models/ 디렉터리 생성 + (구) downloaded_models → models 마이그레이션 */
    fun modelsDir(context: Context): File {
        val newDir = File(context.filesDir, DIR_MODELS)
        if (!newDir.exists()) newDir.mkdirs()

        if (!legacyMigrated) {
            synchronized(migrateLock) {
                if (!legacyMigrated) {
                    val legacy = File(context.filesDir, DIR_MODELS_LEGACY)
                    if (legacy.exists() && legacy.isDirectory) {
                        legacy.listFiles()?.forEach { src ->
                            moveOrReplace(src, File(newDir, src.name))
                        }
                        val left = legacy.listFiles()?.isNotEmpty() == true
                        if (!left) runCatching { legacy.delete() }
                    }
                    migrateLegacyFoModelFiles(newDir)
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

        if (!refImgsMigrated) {
            synchronized(refImgsMigrateLock) {
                if (!refImgsMigrated) {
                    migrateLegacyFoRefImages(dir)
                    refImgsMigrated = true
                }
            }
        }
        return dir
    }

    // ----------------------------
    // Model files (internal)
    // ----------------------------

    /**
     * ✅ (신규) run 폴더명(latestFolder) 기반 tflite 파일명 생성
     * - latestFolder 예: {projectName}_{size}_YYYYMMDD_HHMMSS
     * - 저장 파일명 : {latestFolder}.tflite
     */
    fun runTfliteFile(context: Context, latestFolder: String): File =
        File(modelsDir(context), "${latestFolder}.tflite")

    /**
     * ✅ (신규) 특정 모델의 내부 models/ 안에 존재하는 tflite 후보(신규/레거시 포함)를 모두 반환
     */
    fun listModelTfliteFiles(context: Context, model: String): List<File> {
        val base = baseModelKey(model)
        val dir = modelsDir(context)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val modelLc = base.lowercase()
        val list = dir.listFiles { f ->
            if (!f.isFile) return@listFiles false
            if (!f.extension.equals("tflite", ignoreCase = true)) return@listFiles false
            val nameLc = f.name.lowercase()
            nameLc == "${modelLc}.tflite" || nameLc.startsWith("${modelLc}_")
        }?.toList() ?: emptyList()

        return list.sortedBy { it.name }
    }

    /**
     * ✅ (신규) 해당 모델의 tflite 중 최신(run timestamp 기준)을 선택
     * - (신규 네이밍) <model>_<size>_YYYYMMDD_HHMMSS.tflite 가 있으면 이를 우선 사용
     * - 없으면 (구) <model>.tflite 또는 <model>_*.tflite 중 lastModified 최신을 사용
     */
    private fun pickLatestModelTflite(context: Context, model: String): File? {
        val base = baseModelKey(model)
        val candidates = listModelTfliteFiles(context, base)
        if (candidates.isEmpty()) return null

        val modelPrefixLc = base.lowercase() + "_"
        var bestByRunTs: File? = null
        var bestTs: String? = null // YYYYMMDDHHMMSS

        for (f in candidates) {
            val nameLc = f.name.lowercase()
            if (!nameLc.startsWith(modelPrefixLc)) continue
            val rest = f.name.substring(base.length + 1)
            val m = RUN_TFLITE_SUFFIX_RE.matchEntire(rest) ?: continue
            val ymd = m.groupValues[2]
            val hms = m.groupValues[3]
            val ts = ymd + hms
            if (bestTs == null || ts > bestTs!!) {
                bestTs = ts
                bestByRunTs = f
            }
        }
        if (bestByRunTs != null) return bestByRunTs

        return candidates.maxByOrNull { it.lastModified() }
    }

    /**
     * ✅ 모델 tflite 파일 경로 반환
     * - (신규) <model>_<size>_YYYYMMDD_HHMMSS.tflite 가 있으면 그 중 최신을 반환
     * - (구) 없으면 <model>.tflite(레거시)를 반환
     */
    fun downloadedModelFile(context: Context, model: String): File {
        val base = baseModelKey(model)
        val latest = pickLatestModelTflite(context, base)
        if (latest != null) return latest
        return File(modelsDir(context), "$base.tflite")
    }

    /**
     * ✅ 최신 다운로드 후, 같은 모델의 구버전 tflite를 자동 정리해서 1개만 유지
     * - keepFile 이 있으면 해당 파일만 남기고 나머지 tflite는 전부 삭제
     * - keepFile 이 없거나 존재하지 않으면, 현재 보이는 tflite 중 "최신" 1개를 남기고 삭제
     *
     * @return 삭제된 파일 개수
     */
    fun pruneOldModelTflites(context: Context, model: String, keepFile: File? = null): Int {
        val base = baseModelKey(model)

        val candidates = listModelTfliteFiles(context, base)
        if (candidates.size <= 1) return 0

        val keep: File = when {
            keepFile != null && keepFile.exists() ->
                candidates.firstOrNull { it.absolutePath == keepFile.absolutePath }
                    ?: pickLatestModelTflite(context, base)
            else -> pickLatestModelTflite(context, base)
        } ?: return 0

        val keepPath = keep.absolutePath
        var deleted = 0
        candidates.forEach { f ->
            if (f.absolutePath == keepPath) return@forEach
            if (runCatching { f.delete() }.getOrDefault(false)) deleted++
        }
        return deleted
    }

    fun downloadedExists(context: Context, model: String): Boolean =
        downloadedModelFile(context, model).exists()

    fun downloadedModelJsonFile(context: Context, model: String): File =
        File(modelsDir(context), "${baseModelKey(model)}.json")

    fun downloadedJsonExists(context: Context, model: String): Boolean =
        downloadedModelJsonFile(context, model).exists()

    fun downloadedModelJpgFile(context: Context, model: String): File =
        File(modelsDir(context), "${baseModelKey(model)}.jpg")

    fun downloadedJpgExists(context: Context, model: String): Boolean =
        downloadedModelJpgFile(context, model).exists()

    /** 다운로드된 tflite/json를 함께 삭제 */
    fun deleteDownloaded(context: Context, model: String): Boolean =
        runCatching {
            var any = false
            val base = baseModelKey(model)
            listModelTfliteFiles(context, base).forEach { f ->
                any = any || runCatching { f.delete() }.getOrDefault(false)
            }
            val jsonDeleted = downloadedModelJsonFile(context, base).delete()
            val jpgDeleted = downloadedModelJpgFile(context, base).delete()
            any || jsonDeleted || jpgDeleted
        }.getOrDefault(false)

    // ----------------------------
    // Reference images (internal)
    // ----------------------------

    /** files/ref_imgs/<model>_<index>.jpg */
    fun refImageFile(context: Context, model: String, index: Int): File {
        val base = baseModelKey(model)
        return File(refImgsDir(context), "${base}_${index}.jpg")
    }

    /** files/ref_imgs/<model>_*.jpg|png 목록 */
    fun listRefImages(context: Context, model: String): List<File> {
        val base = baseModelKey(model)
        val dir = refImgsDir(context)
        val list = dir.listFiles { _, name ->
            name.startsWith("${base}_") && (name.endsWith(".jpg", true) || name.endsWith(".png", true))
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
    // Assets (overlay / icons)
    // ----------------------------

    /**
     * ✅ 서버(images_overlay)에서 모델 이미지를 더 이상 다운로드하지 않고,
     *    앱에 내장된 assets/overlay/<model>.jpg 를 사용합니다.
     *
     * - 모델명에는 '-' 등 리소스 네이밍 제약이 있을 수 있어(res/drawable 대신) assets 를 사용합니다.
     */
    @Volatile private var overlayAssetNames: Set<String>? = null

    private fun listAssetsOnce(context: Context, dir: String): Set<String> {
        val list = runCatching { context.assets.list(dir) }.getOrNull()
        return list?.toSet() ?: emptySet()
    }

    private fun overlayNames(context: Context): Set<String> {
        val cached = overlayAssetNames
        if (cached != null) return cached
        val v = listAssetsOnce(context, "overlay")
        overlayAssetNames = v
        return v
    }

    private fun pickAssetFileName(names: Set<String>, model: String): String? {
        val candidates = listOf("$model.jpg", "$model.jpeg", "$model.png")
        return candidates.firstOrNull { names.contains(it) }
    }

    /** assets/overlay/<model>.jpg (또는 jpeg/png) URI 반환. 없으면 null */
    fun overlayAssetUriOrNull(context: Context, model: String): String? {
        val base = baseModelKey(model)
        val fn = pickAssetFileName(overlayNames(context), base) ?: return null
        return "file:///android_asset/overlay/$fn"
    }

    /** 모델 선택 타일 아이콘 URI (assets/overlay/<model>.*) */
    fun modelIconAssetUriOrNull(context: Context, model: String): String? {
        return overlayAssetUriOrNull(context, model)
    }

    private fun migrateLegacyFoModelFiles(dir: File) {
        dir.listFiles()?.forEach { src ->
            val normalizedName = normalizedLegacyModelFileName(src.name) ?: return@forEach
            moveOrReplace(src, File(dir, normalizedName))
        }
    }

    private fun migrateLegacyFoRefImages(dir: File) {
        dir.listFiles()?.forEach { src ->
            val normalizedName = normalizedLegacyRefImageFileName(src.name) ?: return@forEach
            moveOrReplace(src, File(dir, normalizedName))
        }
    }

    private fun normalizedLegacyModelFileName(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
        val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)

        if (ext.equals("tflite", ignoreCase = true)) {
            val runMatch = RUN_TFLITE_NAME_RE.matchEntire(stem)
            if (runMatch != null) {
                val model = runMatch.groupValues[1]
                val normalizedModel = ModelNameCompat.canonical(model)
                if (normalizedModel.isNotBlank() && normalizedModel != model) {
                    val size = runMatch.groupValues[2]
                    val ymd = runMatch.groupValues[3]
                    val hms = runMatch.groupValues[4]
                    return "${normalizedModel}_${size}_${ymd}_${hms}.tflite"
                }
            }

            val normalizedStem = ModelNameCompat.canonical(stem)
            if (normalizedStem.isNotBlank() && normalizedStem != stem) {
                return "${normalizedStem}.tflite"
            }
            return null
        }

        if (
            ext.equals("json", ignoreCase = true) ||
            ext.equals("jpg", ignoreCase = true) ||
            ext.equals("jpeg", ignoreCase = true) ||
            ext.equals("png", ignoreCase = true)
        ) {
            val normalizedStem = ModelNameCompat.canonical(stem)
            if (normalizedStem.isNotBlank() && normalizedStem != stem) {
                return "${normalizedStem}.${ext}"
            }
        }

        return null
    }

    private fun normalizedLegacyRefImageFileName(fileName: String): String? {
        val match = REF_IMAGE_NAME_RE.matchEntire(fileName) ?: return null
        val model = match.groupValues[1]
        val index = match.groupValues[2]
        val ext = match.groupValues[3]
        val normalizedModel = ModelNameCompat.canonical(model)
        if (normalizedModel.isBlank() || normalizedModel == model) return null
        return "${normalizedModel}_${index}.${ext}"
    }

    private fun moveOrReplace(src: File, dst: File) {
        if (src.absolutePath == dst.absolutePath) return

        dst.parentFile?.mkdirs()

        if (dst.exists()) {
            val replaceDst = src.lastModified() > dst.lastModified()
            if (replaceDst) {
                val copied = runCatching { src.copyTo(dst, overwrite = true) }.isSuccess
                if (copied) {
                    runCatching { src.delete() }
                }
                return
            }
            runCatching { src.delete() }
            return
        }

        val renamed = runCatching { src.renameTo(dst) }.getOrDefault(false)
        if (!renamed || !dst.exists()) {
            runCatching {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
        }
    }
}
