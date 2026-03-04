package com.hklab.airuler.model

import android.content.Context
import android.util.Log
import com.hklab.airuler.GlobalParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object HawkModelDownloader {

    // ---------------------------------------------------------------------
    // ✅ YesunAI 연동 (서버 주소는 GlobalParams 에서만 관리)
    //  - runs:   /ruler/runs
    //  - list:   /api/files/list-runs?scope=ruler
    //  - json:   /ruler/profiles/<model>/<model>.json
    //  - (overlay 이미지는 더 이상 다운로드하지 않음: 앱 내장 assets/overlay 사용)
    // ---------------------------------------------------------------------
    private fun baseRunsUrl(): String = GlobalParams.yesunaiUrl("/ruler/runs")
    private fun listRunsApi(): String = GlobalParams.yesunaiUrl("/api/files/list-runs?scope=ruler")
    private fun baseProfilesUrl(): String = GlobalParams.yesunaiUrl("/ruler/profiles")

    /**
     * (요청사항) Grid 전용 다운로드
     * - 일반 모델과 동일하게 ruler/runs(=tflite) + ruler/profiles(=overlay)에서 가져옵니다.
     * - Grid.json 은 서버에 없다고 가정(기존 동작과 동일)
     */
    suspend fun downloadGridStatic(
        context: Context,
        onProgress: (percent: Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        // ✅ 요구사항 변경: Grid도 일반 모델과 동일한 경로(ruler/runs + ruler/profiles) 사용.
        // - json은 원래 없으므로 downloadOrUpdateModel 내부에서 자동 skip.
        downloadOrUpdateModel(context, "Grid", onProgress)
    }

    suspend fun downloadOrUpdateModel(
        context: Context,
        modelName: String,
        onProgress: (percent: Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        // ✅ 혹시 modelName에 "_FO"가 붙어 들어와도 base 모델명만 사용
        val baseModel = modelName.trim().substringBefore("_FO")
        val projectName = baseModel
        val isGrid = baseModel.equals("Grid", ignoreCase = true)

        // ✅ nano 고정 + 형식 일치하는 폴더만 필터링해서 최신 선택
        val latestFolder = findLatestRunFolderStrict(projectName = projectName, size = "nano")
            ?: throw IllegalStateException("서버에 run 폴더가 없습니다: ${projectName}_nano_YYYYMMDD_HHMMSS")

        // ✅ 요구사항 변경:
        // - (구) /weights/best_saved_model/best_float16.tflite
        // - (신) /export/best_float16.tflite (파일명 고정)
        //   ※ 폴더 listing 파싱/HEAD 체크 등 추가 처리 없이 URL을 고정합니다.
        val tfliteUrl = "${baseRunsUrl()}/$latestFolder/export/best_float16.tflite"

        // ✅ 저장 파일명 요구사항: 모델명_모델사이즈_날짜_시간.tflite
        // - latestFolder 자체가 "{projectName}_{size}_YYYYMMDD_HHMMSS" 이므로
        //   로컬 저장은 "{latestFolder}.tflite" 로 고정
        val tfliteDst = ModelFileStore.runTfliteFile(context, latestFolder)
        val tfliteTmp = File(tfliteDst.parentFile, tfliteDst.name + ".tmp")

        // 0~95% : tflite
        httpDownload(tfliteUrl, tfliteTmp) { p ->
            onProgress(((p * 95) / 100).coerceIn(0, 95))
        }

        // ✅ Grid 모델은 서버에 Grid.json 이 없으므로, tflite만 있으면 됨
        if (!isGrid) {
            // 95~100% : json
            val jsonUrl = "${baseProfilesUrl()}/${baseModel}/${baseModel}.json"
            val jsonDst = ModelFileStore.downloadedModelJsonFile(context, baseModel)
            val jsonTmp = File(jsonDst.parentFile, jsonDst.name + ".tmp")

            runCatching {
                httpDownload(jsonUrl, jsonTmp) { p ->
                    onProgress((95 + (p * 5) / 100).coerceIn(95, 100))
                }
                atomicReplace(jsonTmp, jsonDst)
            }.onFailure { e ->
                Log.w(
                    "HawkModelDownloader",
                    "Model JSON download failed: $jsonUrl (${e.javaClass.simpleName}: ${e.message})",
                    e
                )
                runCatching { if (jsonTmp.exists()) jsonTmp.delete() }
                // json은 필름 치수 측정에 필요할 수 있으니, Status 버튼에서 누락 확인 가능
                onProgress(95)
            }
        }

        // atomic replace (tflite)
        atomicReplace(tfliteTmp, tfliteDst)

        // ✅ 최신 다운로드 후, 같은 모델의 구버전 tflite 자동 정리(1개만 유지)
        // - 정리 실패가 다운로드 자체를 실패시키면 안 되므로 예외는 삼킵니다.
        runCatching {
            ModelFileStore.pruneOldModelTflites(context, baseModel, keepFile = tfliteDst)
        }.onFailure { e ->
            Log.w(
                "HawkModelDownloader",
                "Old tflite cleanup failed (model=$baseModel): ${e.javaClass.simpleName}: ${e.message}",
                e
            )
        }

        onProgress(100)
        tfliteDst
    }

    /**
     * list-runs 응답이:
     *  - ["A","B",...]  (JSONArray) 로 올 수도 있고
     *  - {"folders":["A","B",...]} (JSONObject) 로 올 수도 있으니 둘 다 처리.
     *
     * 그리고 폴더명은 반드시: {projectName}_{size}_YYYYMMDD_HHMMSS 형식만 인정.
     * 예: L1827-00_nano_20251217_044032
     */
    private fun findLatestRunFolderStrict(projectName: String, size: String): String? {
        val conn = (URL(listRunsApi()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }
            ?: ""

        conn.disconnect()

        if (code !in 200..299 || body.isBlank()) return null

        val arr: JSONArray = parseFoldersArray(body) ?: return null

        val prefix = "${projectName}_${size}_"
        val pattern = Regex("^${Regex.escape(prefix)}\\d{8}_\\d{6}$")

        var best: String? = null
        for (i in 0 until arr.length()) {
            val f = arr.optString(i)
            if (!pattern.matches(f)) continue
            // ✅ 고정폭 숫자라 문자열 비교로 최신 선택 가능
            if (best == null || f > best!!) best = f
        }
        return best
    }

    /** 서버 응답이 JSONArray 또는 JSONObject(folders=JSONArray) 인 경우를 모두 처리. */
    private fun parseFoldersArray(body: String): JSONArray? {
        val v = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return null
        return when (v) {
            is JSONArray -> v
            is JSONObject -> v.optJSONArray("folders")
            else -> null
        }
    }

    private fun atomicReplace(tmp: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (dst.exists()) runCatching { dst.delete() }
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
    }

    private fun httpDownload(urlStr: String, outFile: File, onProgress: (Int) -> Unit) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            doInput = true
            instanceFollowRedirects = true
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("다운로드 실패 HTTP $code")
        }

        val total = conn.contentLengthLong
        val buf = ByteArray(8 * 1024)
        var done = 0L
        var lastPercent = -1

        outFile.parentFile?.mkdirs()

        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(outFile).use { output ->
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    output.write(buf, 0, r)
                    done += r

                    if (total > 0) {
                        val p = ((done * 100) / total).toInt()
                        if (p != lastPercent) {
                            lastPercent = p
                            onProgress(p)
                        }
                    }
                }
            }
        }

        conn.disconnect()
        onProgress(100)
    }
}
