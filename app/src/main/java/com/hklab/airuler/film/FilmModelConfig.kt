package com.hklab.airuler.film

import com.hklab.airuler.film.ExternalMediaStoreUtils
import org.json.JSONObject
import android.content.Context
import com.hklab.airuler.model.ModelFileStore
import com.hklab.airuler.model.ModelNameCompat

data class RoiCfg(
    val key: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val method: String,
    val parameter: Any?
)

data class MeasureCfg(
    val index: Int,
    val name: String,
    val startKey: String,
    val endKey: String,
    val direction: String,
    val gt: Double?,
    val margin: Double?,
    val adjust: String = "",
    /**
     * offset(보정값). film이 여러 개이면 ","로 구분해 저장할 수 있습니다.
     * - 예) "+0.01234" 또는 "+0.01234,-0.05678"
     */
    val offset: String = "",
    /**
     * NEW(curve_points): curve_points ROI 사용 시, y(mm) 타겟.
     * - Python(main_ruler_selected_2points_GUI_v17.py): y_target_mm
     * - null이면 기본값(CURVE_TARGET_Y_MM_DEFAULT)을 사용
     */
    val yTargetMm: Double? = null
)

data class FilmModelConfig(
    val modelName: String,
    val rois: Map<String, RoiCfg>,
    val measures: List<MeasureCfg>
)

object FilmModelConfigLoader {

    /**
     * 모델 JSON 텍스트에서 `measure_...` 키를 **등장 순서대로** 추출합니다.
     *
     * 이유)
     * - `JSONObject.keys()` 순서는 구현/버전에 따라 보장되지 않을 수 있습니다.
     * - 사용자가 작성한 모델명.json 내 measure 정의 순서가 곧 "결과 출력 순서"가 되도록
     *   **파일에 적힌 순서 그대로** 유지하기 위함입니다.
     */
    private fun extractMeasureKeysInOrder(jsonText: String): List<String> {
        // "measure_...": 형태를 순차적으로 수집
        val re = Regex("\"(measure_[^\"]+)\"\\s*:")
        val ordered = LinkedHashSet<String>() // insertion-order preserved
        re.findAll(jsonText).forEach { m ->
            val key = m.groupValues.getOrNull(1).orEmpty()
            if (key.startsWith("measure_")) ordered.add(key)
        }
        return ordered.toList()
    }

    fun loadFromInternalModels(context: Context, modelName: String): FilmModelConfig? {
        val base = ModelNameCompat.canonical(modelName)

        // internal storage: files/downloaded_models/<model>.json  (현재 ModelFileStore 기준)
        val jsonFile = ModelFileStore.downloadedModelJsonFile(context, base)
        if (!jsonFile.exists()) return null

        val text = runCatching { jsonFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return parse(base, text)
    }

    private fun parse(modelName: String, jsonText: String): FilmModelConfig? {
        val root = JSONObject(jsonText)

        fun optDoubleOrNull(obj: JSONObject, key: String): Double? {
            if (!obj.has(key)) return null
            val v = obj.opt(key)
            return when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
        }

        val rois = LinkedHashMap<String, RoiCfg>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!k.startsWith("roi_")) continue
            val o = root.optJSONObject(k) ?: continue
            val x = o.optDouble("x", Double.NaN)
            val y = o.optDouble("y", Double.NaN)
            val w = o.optDouble("w", Double.NaN)
            val h = o.optDouble("h", Double.NaN)
            // ✅ NEW(curve_points): method 대신 flag만 존재하는 JSON도 지원
            val method0 = o.optString("method", "").trim()
            val method = if (method0.isNotBlank()) method0 else if (o.optBoolean("curve_points", false)) "curve_points" else ""
            val param = if (o.has("parameter")) o.get("parameter") else null
            if (x.isNaN() || y.isNaN() || w.isNaN() || h.isNaN() || method.isBlank()) continue
            rois[k] = RoiCfg(k, x, y, w, h, method, param)
        }

        val nMeasure = root.optInt("n_measure", 0)
        val measuresTmp = ArrayList<MeasureCfg>(kotlin.math.max(nMeasure, 8))

        var foundNumeric = false

        // (1) 구버전: measure_1..measure_n 형태
        if (nMeasure > 0) {
            for (i in 1..nMeasure) {
                val mo = root.optJSONObject("measure_$i") ?: continue
                foundNumeric = true
                val start = mo.optString("start", "")
                val end = mo.optString("end", "")
                val dir = mo.optString("direction", "xy")
                val gt = if (mo.has("gt")) mo.optDouble("gt") else null
                val margin = if (mo.has("margin")) mo.optDouble("margin") else null
                val adjust = mo.optString("adjust", "").trim()
                val offset = mo.optString("offset", "").trim()
                val yTargetMm = optDoubleOrNull(mo, "y_target_mm")
                if (start.isBlank() || end.isBlank()) continue
                measuresTmp += MeasureCfg(
                    index = i,
                    name = i.toString(),
                    startKey = start,
                    endKey = end,
                    direction = dir,
                    gt = gt,
                    margin = margin,
                    adjust = adjust,
                        offset = offset,
                    yTargetMm = yTargetMm
                )
            }
        }

        // (2) 신버전: measure_<name> 형태 (예: measure_CP2-2, measure_No.10-1 ...)
        //     ✅ 모델명.json에 적힌 "measure 정의 순서"를 그대로 유지합니다.
        if (!foundNumeric) {
            // 1순위: 원본 텍스트에서 등장 순서대로 키 추출
            val orderedKeys = extractMeasureKeysInOrder(jsonText)
            if (orderedKeys.isNotEmpty()) {
                for (k in orderedKeys) {
                    val mo = root.optJSONObject(k) ?: continue

                    val name = k.substringAfter("measure_")
                    val start = mo.optString("start", "")
                    val end = mo.optString("end", "")
                    val dir = mo.optString("direction", "xy")
                    val gt = if (mo.has("gt")) mo.optDouble("gt") else null
                    val margin = if (mo.has("margin")) mo.optDouble("margin") else null
                    val adjust = mo.optString("adjust", "").trim()
                    val offset = mo.optString("offset", "").trim()
                    val yTargetMm = optDoubleOrNull(mo, "y_target_mm")

                    if (start.isBlank() || end.isBlank()) continue
                    measuresTmp += MeasureCfg(
                        index = 0, // 아래에서 파일 순서대로 재부여
                        name = name,
                        startKey = start,
                        endKey = end,
                        direction = dir,
                        gt = gt,
                        margin = margin,
                        adjust = adjust,
                        offset = offset,
                        yTargetMm = yTargetMm
                    )
                }
            } else {
                // 2순위(폴백): JSONObject 순회(순서 보장 X)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (!k.startsWith("measure_")) continue
                    val mo = root.optJSONObject(k) ?: continue

                    val name = k.substringAfter("measure_")
                    val start = mo.optString("start", "")
                    val end = mo.optString("end", "")
                    val dir = mo.optString("direction", "xy")
                    val gt = if (mo.has("gt")) mo.optDouble("gt") else null
                    val margin = if (mo.has("margin")) mo.optDouble("margin") else null
                    val adjust = mo.optString("adjust", "").trim()
                    val offset = mo.optString("offset", "").trim()
                    val yTargetMm = optDoubleOrNull(mo, "y_target_mm")

                    if (start.isBlank() || end.isBlank()) continue
                    measuresTmp += MeasureCfg(
                        index = 0,
                        name = name,
                        startKey = start,
                        endKey = end,
                        direction = dir,
                        gt = gt,
                        margin = margin,
                        adjust = adjust,
                        offset = offset,
                        yTargetMm = yTargetMm
                    )
                }
            }
        }

        val measures: List<MeasureCfg> = if (foundNumeric) {
            measuresTmp
        } else {
            // ✅ 모델 JSON에 적힌 순서 그대로 index를 부여
            measuresTmp.mapIndexed { idx, m -> m.copy(index = idx + 1) }
        }

if (rois.isEmpty() || measures.isEmpty()) return null
        return FilmModelConfig(modelName, rois, measures)
    }
}

/**
 * Parse per-film signed delta string.
 *
 * Model JSON fields like `adjust` can be stored as:
 * - single value: "+0.123"
 * - per-film list: "+0.149, +0.111" (Film#1, Film#2, ...)
 *
 * @param filmIndex 1-based film index (Film#1 -> 1)
 * @return parsed value, or 0.0 if empty/invalid
 */
fun parsePerFilmSignedDelta(expr: String?, filmIndex: Int): Double {
    if (filmIndex <= 0) return 0.0
    val raw = expr?.trim().orEmpty()
    if (raw.isBlank()) return 0.0

    // ✅ offset 필드와 동일한 규칙:
    // - "," 가 있으면 filmIndex(1-based) 위치의 토큰을 사용
    // - "," 가 없으면 단일 값을 모든 film에 동일 적용
    val token = if (raw.contains(',')) {
        val parts = raw.split(',').map { it.trim() }
        parts.getOrNull(filmIndex - 1).orEmpty().trim()
    } else {
        raw
    }
    if (token.isBlank()) return 0.0
    return token.toDoubleOrNull() ?: 0.0
}

/** Per-film adjust(mm). */
fun MeasureCfg.adjustForFilm(filmIndex: Int): Double = parsePerFilmSignedDelta(this.adjust, filmIndex)
