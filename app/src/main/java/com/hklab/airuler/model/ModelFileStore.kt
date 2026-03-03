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

    // ✅ (구) downloaded_models → (신) models
    private const val DIR_MODELS = "models"
    private const val DIR_MODELS_LEGACY = "downloaded_models"
    private const val DIR_REF_IMGS = "ref_imgs"

    /** 모델 키 정규화: InferencePipeline/다운로더와 동일하게 `_FO` 등 suffix 제거 */
    private fun baseModelKey(model: String): String = model.trim().substringBefore("_FO")

    // ✅ 모델 tflite(신규 네이밍) 패턴: <model>_<size>_YYYYMMDD_HHMMSS.tflite
    // - 예: L1827-00_nano_20251217_044032.tflite
    private val RUN_TFLITE_SUFFIX_RE: Regex =
        Regex("^([^_]+)_(\\d{8})_(\\d{6})\\.tflite$", RegexOption.IGNORE_CASE)

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
            // (구) <model>.tflite  또는 (신) <model>_... .tflite
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

        // 1) 신규 네이밍 매칭되는 것만 timestamp로 비교
        val modelPrefixLc = base.lowercase() + "_"
        var bestByRunTs: File? = null
        var bestTs: String? = null // YYYYMMDDHHMMSS

        for (f in candidates) {
            val nameLc = f.name.lowercase()
            if (!nameLc.startsWith(modelPrefixLc)) continue
            // f.name은 반드시 "<model>_..." 로 저장되므로, model.length + 1 로 rest를 만들 수 있습니다.
            val rest = f.name.substring(base.length + 1)
            // rest: <size>_YYYYMMDD_HHMMSS.tflite
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

        // 2) 신규 네이밍이 없다면, 그냥 lastModified 최신(레거시/기타)을 선택
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
        // fallback: legacy path(존재하지 않을 수도 있음)
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

    /** 다운로드된 tflite/json/jpg를 함께 삭제 */
    fun deleteDownloaded(context: Context, model: String): Boolean =
        runCatching {
            // ✅ tflite는 버전별로 여러 개가 있을 수 있으므로 모두 삭제
            var a = false
            val base = baseModelKey(model)
            listModelTfliteFiles(context, base).forEach { f ->
                a = a || runCatching { f.delete() }.getOrDefault(false)
            }
            val b = downloadedModelJsonFile(context, base).delete()
            val c = downloadedModelJpgFile(context, base).delete()
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
