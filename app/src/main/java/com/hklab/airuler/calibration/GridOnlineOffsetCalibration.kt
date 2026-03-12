package com.hklab.airuler.calibration

import android.content.Context
import android.util.Log
import com.hklab.airuler.GlobalParams
import com.hklab.airuler.model.ModelFileStore
import org.json.JSONObject
import kotlin.math.abs
import java.util.Locale

/**
 * Online Offset Calibration
 *
 * (요구사항 반영 - 2026-02)
 * - Calibration ON
 *   - n=1..3 : outlier 판별 없이 업데이트 (warmupAlpha=1.0, median 기반)
 *   - n>=4  : outlier(=GT±margin 범위 밖) 샘플은 업데이트에서 제외
 *              업데이트 시 alpha=0.5
 *              ✨ median 대신 EMA(최근값 가중)로 업데이트
 *              offset += alpha * (GT - (raw + offset))
 * - Calibration OFF: offset 업데이트 중지, 현재 offset을 그대로 적용
 *
 * Update rule
 * - n=1..3 (warmup): median 기반(window), offset += alpha * (GT - (median_raw + offset))
 * - n>=4 (main)    : EMA 기반,          offset += alpha * (GT - (raw + offset))
 */
data class OffsetCalibratorConfig(
    val referenceValue: Double,
    /** outlier 판별 기준: |compensated - GT| <= margin 이면 inlier */
    val margin: Double,
    /** outlier 판별 완화값: |compensated - GT| <= (margin + marginExtra) 이면 inlier */
    val marginExtra: Double = DEFAULT_MARGIN_EXTRA,
    /** n=1..warmupSamples 까지는 outlier 판별 없이 업데이트 */
    val warmupSamples: Int = 3,
    val warmupAlpha: Double = 1.0,
    val mainAlpha: Double = 0.5,
    /** median 계산 window size */
    val windowSize: Int = 30,
) {
    companion object {
        /** inlier 판별 완화값 기본값(mm) */
        const val DEFAULT_MARGIN_EXTRA: Double = 0.05
    }
}

/**
 * Online offset calibrator
 *
 * - updateEnabled==true 일 때만 n(sampleN)을 증가시키고, offset 업데이트를 시도합니다.
 * - n=1..warmupSamples : outlier 판별 없이 업데이트 (median 기반)
 * - n>warmupSamples     : outlier(=GT±margin 범위 밖)는 업데이트 제외
 *                         업데이트는 EMA 방식(최근값 가중): offset += alpha*(GT - (raw + offset))
 * - updateEnabled==false : 누적/업데이트 없이 compensated=raw+offset만 반환합니다.
 */
class OnlineMedianOffsetCalibrator(
    private val cfg: OffsetCalibratorConfig,
    initialOffset: Double = 0.0,
) {
    data class Snapshot(
        val offset: Double,
        val sampleN: Int,
        val window: List<Double>,
    )
    /** raw(mm)에 더해지는 보정값 */
    @Volatile
    var offset: Double = initialOffset
        private set

    /** updateEnabled==true(그리고 GT 존재)로 들어온 "시도" 횟수 (outlier 포함) */
    private var seenN: Int = 0

    /** 최근 window(raw) (warmup에서 median 계산에만 사용, 최대 cfg.windowSize) */
    private val window = ArrayDeque<Double>(cfg.windowSize + 2)

    data class UpdateResult(
        val raw: Double,
        val compensated: Double,
        val offset: Double,
        val sampleN: Int,
        val updateEnabled: Boolean,
        /** outlier 판별 결과(업데이트 단계에서만 의미) */
        val inlier: Boolean,
        /** 이번 호출에서 offset 업데이트가 실제로 수행되었는지 */
        val updated: Boolean,
        /** warmup 단계인지(n<=warmupSamples) */
        val warmup: Boolean,
        /** median window size(업데이트에 사용된 샘플 기준) */
        val windowN: Int,
        /** running median of raw samples accumulated while updateEnabled==true */
        val medianRaw: Double,
        /** reference value used for calibration (=GT) */
        val referenceValue: Double,
        /** median(compensated) after update = medianRaw + offset */
        val medianCompensated: Double,
        /** error after update = referenceValue - medianCompensated */
        val errorAfter: Double,
        /** delta applied to offset in this update call (0 if update disabled) */
        val deltaOffset: Double,
        /** alpha used */
        val alpha: Double,
    )

    fun setOffset(value: Double) {
        offset = value
    }

    fun snapshot(): Snapshot = Snapshot(
        offset = offset,
        sampleN = seenN,
        window = window.toList(),
    )

    fun restore(snapshot: Snapshot) {
        offset = snapshot.offset
        seenN = snapshot.sampleN
        window.clear()
        snapshot.window.forEach { window.addLast(it) }
    }

    fun deepCopy(): OnlineMedianOffsetCalibrator =
        OnlineMedianOffsetCalibrator(cfg = cfg, initialOffset = offset).also { copy ->
            copy.restore(snapshot())
        }

    /** 현재 누적된 샘플 시도 횟수(n). (warmup 판단/디버깅 용도) */
    fun getSampleN(): Int = seenN

    /**
     * (요구사항)
     * - offset 값을 지정값으로 리셋
     * - 누적 샘플(running median)도 모두 제거하여 sampleN을 0으로 리셋
     */
    fun reset(newOffset: Double = 0.0) {
        offset = newOffset
        window.clear()
        seenN = 0
    }

    private fun addToWindow(x: Double) {
        window.addLast(x)
        while (window.size > cfg.windowSize) {
            window.removeFirst()
        }
    }

    private fun medianOfWindow(): Double {
        if (window.isEmpty()) return Double.NaN
        val arr = window.toDoubleArray()
        arr.sort()
        val n = arr.size
        return if (n % 2 == 1) {
            arr[n / 2]
        } else {
            (arr[n / 2 - 1] + arr[n / 2]) * 0.5
        }
    }

    fun update(rawValue0: Double, updateEnabled: Boolean, forceSkipUpdateInMain: Boolean = false): UpdateResult {
        val rawValue = rawValue0.toDouble()

        val oldOffset = offset
        var delta = 0.0
        var alphaUsed = Double.NaN
        // warmup(median) 단계가 아닐 때는 median 관련 값은 의미가 없으므로 NaN을 기본값으로 둡니다.
        var medRaw = Double.NaN
        var updated = false
        var inlier = true
        var warmup = false

        // outlier 판정은 "업데이트 직전의 현재 offset" 기준으로 수행
        // (즉, 이번 호출에서 새로 계산되는 offset을 적용하기 전의 값)
        val compensatedBeforeUpdate = rawValue + oldOffset

        if (updateEnabled) {
            // ✅ n은 outlier 여부와 무관하게 "시도" 기준으로 증가
            seenN += 1
            warmup = seenN <= cfg.warmupSamples

            // outlier 판정은 warmup 이후(n>warmupSamples)부터 적용
            val margin = cfg.margin
            inlier = if (warmup) {
                true
            } else {
                // margin이 유효하지 않으면(예: NaN) 항상 inlier로 처리
                // 완화 조건: |compensated - GT| <= (margin + marginExtra)
                if (margin.isNaN()) {
                    true
                } else {
                    val extra = if (cfg.marginExtra.isNaN()) 0.0 else cfg.marginExtra
                    abs(compensatedBeforeUpdate - cfg.referenceValue) <= (margin + extra)
                }
            }

            if (inlier) {
                if (warmup) {
                    // ✅ warmup: median 기반 업데이트 (outlier 판별 없음)
                    addToWindow(rawValue)
                    medRaw = medianOfWindow()

                    alphaUsed = cfg.warmupAlpha
                    if (!medRaw.isNaN()) {
                        val errorBefore = cfg.referenceValue - (medRaw + oldOffset)
                        delta = alphaUsed * errorBefore
                        offset = oldOffset + delta
                        updated = true
                    }
                } else {
                    // ✅ main: EMA 기반 업데이트 (최근값 가중)
                    alphaUsed = cfg.mainAlpha

                    // (요구사항) film 단위 outlier 판정으로 인해 "이번 프레임은 필름 전체 업데이트 금지"인 경우,
                    // n(sampleN)은 증가시키되 offset 업데이트는 수행하지 않습니다.
                    if (forceSkipUpdateInMain) {
                        // updated=false, delta=0 유지
                    } else {
                        val errorBefore = cfg.referenceValue - (rawValue + oldOffset)
                        delta = alphaUsed * errorBefore
                        offset = oldOffset + delta
                        updated = true
                    }

                    // 요구사항: warmup 이후에는 통계치(median)를 만들지 않으므로
                    // window/median 값은 더 이상 갱신하지 않습니다.
                }
            } else {
                // outlier (warmup 이후): 업데이트하지 않음
                alphaUsed = cfg.mainAlpha
            }
        }

        val compensated = rawValue + offset

        // warmup 단계에서만 의미가 있는 값들
        val medComp = if (medRaw.isNaN()) Double.NaN else (medRaw + offset)
        val errAfter = if (medComp.isNaN()) Double.NaN else (cfg.referenceValue - medComp)

        return UpdateResult(
            raw = rawValue,
            compensated = compensated,
            offset = offset,
            sampleN = seenN,
            updateEnabled = updateEnabled,
            inlier = inlier,
            updated = updated,
            warmup = warmup,
            windowN = window.size,
            medianRaw = medRaw,
            referenceValue = cfg.referenceValue,
            medianCompensated = medComp,
            errorAfter = errAfter,
            deltaOffset = delta,
            alpha = alphaUsed,
        )
    }
}

/**
 * measure별 + film별 offset(calibrator) 상태를 세션 동안 유지하는 Store
 *
 * - Grid / Ruler 측정(캡처) 시마다 applyAndUpdate()를 호출해 compensated(mm)를 얻습니다.
 * - Calibration OFF 시에는 updateEnabled=false로 offset을 고정(업데이트 중지)
 * - offset은 모델 JSON의 measure_*.offset 필드에 저장합니다.
 */
object GridOnlineOffsetCalibrationStore {

    private const val TAG = "AIRulerOffset"
    const val WARMUP_SAMPLES: Int = 3

    internal data class ModelSnapshot(
        val modelBase: String,
        val calibrators: List<SnapshotEntry>,
    )

    internal data class SnapshotEntry(
        val measureName: String,
        val filmIndex: Int,
        val calibrator: OnlineMedianOffsetCalibrator,
    )

    private data class Key(val modelBase: String, val measureName: String, val filmIndex: Int)

    private val lock = Any()
    private val calibrators = HashMap<Key, OnlineMedianOffsetCalibrator>()

    /**
     * 측정값(rawMm)에 offset을 적용한 compensated(mm)를 반환합니다.
     *
     * @param offsetFieldInJson  모델 JSON measure_*.offset ("+0.01,-0.02" 등). film별로 ',' 구분
     */
    fun applyAndUpdate(
        modelBase: String,
        measureName: String,
        filmIndex: Int,
        rawMm: Double,
        gtMm: Double?,
        marginMm: Double?,
        offsetFieldInJson: String?,
        updateEnabled: Boolean,
        /**
         * (요구사항)
         * - warmup 이후 구간에서 film 단위 outlier가 하나라도 존재하면,
         *   해당 film의 모든 measure에 대해 offset 업데이트를 금지합니다.
         *
         * 주의)
         * - warmup(n<=3)에서는 outlier 판별 없이 업데이트하므로, 이 값은 무시됩니다.
         * - n(sampleN)은 계속 증가합니다(=프레임 카운트 유지).
         */
        filmUpdateBlocked: Boolean = false,
    ): OnlineMedianOffsetCalibrator.UpdateResult {

        val ref = gtMm
        val margin = marginMm ?: Double.NaN

        val effectiveUpdateEnabled = updateEnabled && (ref != null)

        // GT가 없는 measure는 업데이트 없이 offset만 적용(기존 값 유지)
        if (ref == null) {
            val initial = parseOffsetFromField(offsetFieldInJson, filmIndex)
            return OnlineMedianOffsetCalibrator.UpdateResult(
                raw = rawMm,
                compensated = rawMm + initial,
                offset = initial,
                sampleN = 0,
                updateEnabled = false,
                inlier = true,
                updated = false,
                warmup = false,
                windowN = 0,
                medianRaw = Double.NaN,
                referenceValue = Double.NaN,
                medianCompensated = Double.NaN,
                errorAfter = Double.NaN,
                deltaOffset = 0.0,
                alpha = Double.NaN,
            )
        }

        val key = Key(modelBase, measureName, filmIndex)
        synchronized(lock) {
            val cal = calibrators.getOrPut(key) {
                val initial = parseOffsetFromField(offsetFieldInJson, filmIndex)
                OnlineMedianOffsetCalibrator(
                    cfg = OffsetCalibratorConfig(
                        referenceValue = ref,
                        margin = margin,
                        warmupSamples = WARMUP_SAMPLES,
                        warmupAlpha = 1.0,
                        mainAlpha = 0.5,
                        windowSize = 30,
                    ),
                    initialOffset = initial
                )
            }

            val r = cal.update(rawMm, effectiveUpdateEnabled, forceSkipUpdateInMain = filmUpdateBlocked)

            // ✅ Logcat debug: measure/film별 offset 변화 추적
            // - Calibration ON(update enabled)에서만 출력
            if (GlobalParams.DEBUG && effectiveUpdateEnabled) {
                val mode = if (r.warmup) "MED" else "EMA"
                val filmSkip = (!r.warmup && filmUpdateBlocked)

                // 업데이트 전 상태(=outlier 판정에 쓰는 값)
                val offsetBefore = r.offset - r.deltaOffset
                val comp0 = r.raw + offsetBefore
                val err0 = comp0 - r.referenceValue

                // 업데이트 후(현재 프레임 결과)
                val errCur = r.compensated - r.referenceValue

                val extra = if (r.warmup) {
                    String.format(
                        Locale.US,
                        " medRaw=%.5f medComp=%.5f errMed=%+.5f",
                        r.medianRaw,
                        r.medianCompensated,
                        r.errorAfter,
                    )
                } else {
                    ""
                }
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "[CALIB][%s][M%s][F#%d] mode=%s n=%d raw=%.5f comp0=%.5f err0=%+.5f comp=%.5f errCur=%+.5f off=%+.5f (d=%+.5f, a=%.2f) gt=%.5f margin=%.5f warm=%s inlier=%s upd=%s filmSkip=%s win=%d%s",
                        modelBase,
                        measureName,
                        filmIndex,
                        mode,
                        r.sampleN,
                        r.raw,
                        comp0,
                        err0,
                        r.compensated,
                        errCur,
                        r.offset,
                        r.deltaOffset,
                        r.alpha,
                        r.referenceValue,
                        margin,
                        if (r.warmup) "Y" else "N",
                        if (r.inlier) "Y" else "N",
                        if (r.updated) "Y" else "N",
                        if (filmSkip) "Y" else "N",
                        r.windowN,
                        extra,
                    )
                )
            }

            return r
        }
    }


    /**
     * 현재 세션 기준 "지금 적용 중인 offset"을 반환합니다.
     *
     * - 세션에 calibrator가 있으면 그 값을 사용
     * - 없으면 모델 JSON(measure_*.offset) 문자열에서 파싱한 값을 사용
     *
     * 용도)
     * - film 단위 outlier 판정(업데이트 전 compensated=raw+offset 계산)에 사용
     */
    fun getCurrentOffset(
        modelBase: String,
        measureName: String,
        filmIndex: Int,
        offsetFieldInJson: String?,
    ): Double {
        val key = Key(modelBase, measureName, filmIndex)
        synchronized(lock) {
            val cal = calibrators[key]
            if (cal != null) return cal.offset
        }
        return parseOffsetFromField(offsetFieldInJson, filmIndex)
    }


    /**
     * 현재 모델에서 가장 앞선 calibrator의 sampleN을 반환합니다.
     *
     * 용도)
     * - 모델 선택 직후 warm-up 진행 상황(0/3, 1/3, 2/3, 3/3)을
     *   "이미지(run) 단위"로 안내할 때 사용합니다.
     * - 일반적으로 한 run에서 모든 calibrator가 함께 진행되므로 max sampleN을 대표값으로 사용합니다.
     */
    fun getMaxSampleN(modelBase: String): Int {
        synchronized(lock) {
            return calibrators
                .filterKeys { it.modelBase == modelBase }
                .values
                .maxOfOrNull { it.getSampleN() }
                ?: 0
        }
    }

    /** 현재 모델 calibrator 상태를 deep-copy 하여 저장합니다. */
    internal fun snapshotModel(modelBase: String): ModelSnapshot {
        synchronized(lock) {
            val entries = calibrators
                .filterKeys { it.modelBase == modelBase }
                .map { (k, v) ->
                    SnapshotEntry(
                        measureName = k.measureName,
                        filmIndex = k.filmIndex,
                        calibrator = v.deepCopy(),
                    )
                }
            return ModelSnapshot(modelBase = modelBase, calibrators = entries)
        }
    }

    /** snapshotModel()로 저장한 상태를 그대로 복원합니다. */
    internal fun restoreModel(snapshot: ModelSnapshot) {
        synchronized(lock) {
            calibrators.keys.removeAll { it.modelBase == snapshot.modelBase }
            snapshot.calibrators.forEach { entry ->
                calibrators[Key(snapshot.modelBase, entry.measureName, entry.filmIndex)] =
                    entry.calibrator.deepCopy()
            }
        }
    }

    /** 현재 모델의 (measure -> (filmIndex -> offset)) 스냅샷 */
    fun snapshotOffsets(modelBase: String): Map<String, Map<Int, Double>> {
        synchronized(lock) {
            val out = LinkedHashMap<String, MutableMap<Int, Double>>()
            calibrators.forEach { (k, v) ->
                if (k.modelBase != modelBase) return@forEach
                val mm = out.getOrPut(k.measureName) { LinkedHashMap() }
                mm[k.filmIndex] = v.offset
            }
            return out.mapValues { (_, m) -> m.toMap() }
        }
    }

    data class OffsetState(
        val offset: Double,
        val sampleN: Int,
    )

    /** 현재 모델의 (measure -> (filmIndex -> (offset,n))) 스냅샷 */
    fun snapshotOffsetStates(modelBase: String): Map<String, Map<Int, OffsetState>> {
        synchronized(lock) {
            val out = LinkedHashMap<String, MutableMap<Int, OffsetState>>()
            calibrators.forEach { (k, v) ->
                if (k.modelBase != modelBase) return@forEach
                val mm = out.getOrPut(k.measureName) { LinkedHashMap() }
                mm[k.filmIndex] = OffsetState(v.offset, v.getSampleN())
            }
            return out.mapValues { (_, m) -> m.toMap() }
        }
    }

    /**
     * (요구사항)
     * 오프셋(모델 JSON의 measure_*.offset)과 세션 내 보정기(calibrator) 상태를 모두 리셋합니다.
     *
     * - offset 값: 0으로 초기화
     * - sampleN(n): 0으로 초기화(누적 샘플 삭제)
     * - 이후 다시 Calibration ON으로 측정하면 1번째 샘플부터 새로 누적/업데이트됩니다.
     */
    fun resetOffsetsForModel(
        context: Context,
        modelBase: String,
    ): Boolean {
        // (1) 현재 세션에서 관찰된 filmIndex 최대값을 알아내어, JSON의 토큰 개수를 보존합니다.
        val snap = snapshotOffsets(modelBase)

        val jsonFile = ModelFileStore.downloadedModelJsonFile(context, modelBase)
        if (!jsonFile.exists()) {
            // JSON이 없어도 세션 상태는 초기화
            synchronized(lock) {
                calibrators.keys.removeAll { it.modelBase == modelBase }
            }
            return false
        }

        val wrote = runCatching {
            val original = jsonFile.readText(Charsets.UTF_8)
            val hasBom = original.firstOrNull() == '\uFEFF'
            val text = if (hasBom) original.drop(1) else original

            val root = JSONObject(text)
            val it = root.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (!k.startsWith("measure_")) continue

                val name = k.removePrefix("measure_")
                val mo = root.optJSONObject(k) ?: continue

                val existingOffset = mo.optString("offset", "")
                val existingList = parseOffsetList(existingOffset)

                val perFilm = snap[name]
                val maxFilm = maxOf(
                    existingList.size,
                    (perFilm?.keys?.maxOrNull() ?: 0)
                ).coerceAtLeast(1)

                val zeros = MutableList(maxFilm) { 0.0 }
                val s = zeros.joinToString(",") { String.format(Locale.US, "%+.5f", it) }
                mo.put("offset", s)
            }

            val outText = root.toString(2)
            jsonFile.writeText((if (hasBom) "\uFEFF" else "") + outText, Charsets.UTF_8)
            true
        }.getOrDefault(false)

        // (2) 세션 내 calibrator 상태 리셋 (offset=0, sampleN=0)
        synchronized(lock) {
            // removeAll을 사용해 modelBase에 해당하는 calibrator를 제거하면
            // 다음 업데이트 때 JSON의 offset(현재는 0)에서 다시 시작합니다.
            calibrators.keys.removeAll { it.modelBase == modelBase }
        }

        return wrote
    }

    /**
     * (요구사항) 모델 JSON의 measure_*.offset 에 offset을 저장합니다.
     *
     * - offset 값 형식: "+0.01234,-0.05678" (film 순서대로)
     */
    @JvmStatic
    @JvmOverloads
    fun persistOffsetsToModelJson(
        context: Context,
        modelBase: String,
        /**
         * 힌트: 이번 측정(run)에서 검출된 필름 개수.
         *
         * 목적)
         * - 필름이 2개 이상인 모델/상황에서, measure별로 calibrator가 아직 생성되지 않았더라도
         *   모델 JSON의 measure_*.offset 필드가 "F1,F2,..." 형태로 유지/확장되도록 합니다.
         *
         * 예)
         * - filmCountHint=2 이면, offset은 최소 2개 토큰("+0.10000,-0.20000") 형태로 저장됩니다.
         */
        filmCountHint: Int? = null,
    ): Boolean {
        val snap = snapshotOffsets(modelBase)
        if (snap.isEmpty()) return false

        val jsonFile = ModelFileStore.downloadedModelJsonFile(context, modelBase)
        if (!jsonFile.exists()) return false

        return runCatching {
            val original = jsonFile.readText(Charsets.UTF_8)
            val hasBom = original.firstOrNull() == '\uFEFF'
            val text = if (hasBom) original.drop(1) else original

            val root = JSONObject(text)

            // measure_* 항목 순회하며 offset 업데이트
            val it = root.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (!k.startsWith("measure_")) continue
                val name = k.removePrefix("measure_")
                val perFilm = snap[name] ?: continue

                val mo = root.optJSONObject(k) ?: continue

                val existingOffset = mo.optString("offset", "")
                val existingList = parseOffsetList(existingOffset)

                val maxFilm = maxOf(
                    existingList.size,
                    (perFilm.keys.maxOrNull() ?: 0),
                    (filmCountHint ?: 0)
                ).coerceAtLeast(1)

                val merged = MutableList(maxFilm) { 0.0 }
                // 기존 값 유지
                for (i in 0 until minOf(existingList.size, merged.size)) {
                    merged[i] = existingList[i]
                }
                // 스냅샷 값 반영
                perFilm.forEach { (filmIdx, off) ->
                    val pos = filmIdx - 1
                    if (pos in merged.indices) merged[pos] = off
                }

                val s = merged.joinToString(",") { String.format(Locale.US, "%+.5f", it) }
                mo.put("offset", s)
            }

            val outText = root.toString(2)
            jsonFile.writeText((if (hasBom) "\uFEFF" else "") + outText, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    // ------------------ helpers ------------------

    private fun parseOffsetFromField(offsetExpr: String?, filmIndex: Int): Double {
        val raw = offsetExpr?.trim().orEmpty()
        if (raw.isBlank()) return 0.0

        val token = if (raw.contains(',')) {
            val parts = raw.split(',').map { it.trim() }
            parts.getOrNull(filmIndex - 1).orEmpty().trim()
        } else {
            raw
        }

        if (token.isBlank()) return 0.0
        return token.toDoubleOrNull() ?: 0.0
    }

    private fun parseOffsetList(offsetExpr: String?): List<Double> {
        val raw = offsetExpr?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()

        // film별 offset을 ','로 구분해서 저장하므로 "빈 토큰"도 인덱스 유지를 위해 0.0으로 유지합니다.
        val parts = if (raw.contains(',')) raw.split(',') else listOf(raw)
        return parts.map { it.trim().toDoubleOrNull() ?: 0.0 }
    }
}
