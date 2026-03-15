package com.hklab.airuler.inspection

import java.util.Locale
import com.hklab.airuler.model.ModelNameCompat

/**
 * HkDetector(main_detector.py) 기준의 "good/bad" 합불 판정 파라미터 포팅.
 *
 * - Python(HkDetector)에서 사용하던 모델별 GOOD 개수 맵(NUM_GOOD_PER_FILM / NUM_GOOD_PER_SHEET)을
 *   Android(AIRuler)에서도 동일하게 사용하기 위한 규칙 모음.
 *
 * ⚠️ 중요
 * - 모델 키는 공백 제거 + legacy `_FO` suffix 정리 후 base 키로 사용합니다.
 * - 아래 맵에 없는 모델은 expectedGoodTotal()이 null을 반환합니다.
 *   (=> PASS 조건(all_good)을 만들 수 없으므로, bad가 나오면 FAIL만 가능)
 */
object GoodBadModelRules {

    /** Python: L27_MODELS */
    val L27_MODELS: Set<String> = setOf(
        "L2786-00", "L2787-00", "L2789-00", "L2790-00"
    )

    /** Python: NUM_GOOD_PER_FILM */
    private val NUM_GOOD_PER_FILM: Map<String, Int> = mapOf(
        // ---- HkDetector 원본 ----
        "L2017-07" to 3,
        "L2024-03" to 3,
        "L2018-07" to 2,
        "L2023-04" to 2,
        "L0625-12" to 3,
        "L1757-01" to 3,
        "L1570-07" to 3,
        "L1824-03" to 4,
        "L1828-00" to 2,
        "L1825-03" to 6,
        "L1827-00" to 3,
        "M1893-01" to 1,
        "M1893-02" to 1,
        "M1892-02" to 1,
        "M2379-02" to 2,
        "L2786-00" to 5,
        "L2787-00" to 5,
        "L2789-00" to 5,
        "L2790-00" to 5,
        "L2785-02" to 0,    // good/bad 없고 film만 존재
        "L2791-02" to 0,    // good/bad 없고 film만 존재

        // ---- AIRuler 프리셋/현장 변형(동일 family의 revision 차이를 흡수하기 위한 alias) ----
        // ※ 값은 HkDetector의 동일 계열 모델을 기준으로 둡니다.
        "L2017-03" to 3,
        "L2018-04" to 2,
        "L0625-05" to 3,
        "L1756-02" to 3,
        "M1893-00" to 1,
        "M1892-00" to 1,
        "M2379-00" to 2,
    )

    /** Python: NUM_GOOD_PER_SHEET */
    private val NUM_GOOD_PER_SHEET: Map<String, Int> = mapOf(
        "M1893-02" to 4,
        "M1892-02" to 4
    )

    private fun normalizeModelKey(modelName: String?): String {
        val raw = (modelName ?: "").trim()
        if (raw.isEmpty()) return ""
        return ModelNameCompat.canonical(raw)
            .substringBefore('.')
            .uppercase(Locale.US)
    }

    /**
     * 해당 모델에서 "완전 정상(all_good)"으로 보기 위해 필요한 GOOD 검출 총 개수.
     *
     * - sheet 모델(M1893-02/M1892-02): sheet 단위로 고정 개수
     * - 그 외: filmCount * NUM_GOOD_PER_FILM
     */
    fun expectedGoodTotal(modelName: String?, filmCount: Int): Int? {
        val key = normalizeModelKey(modelName)
        if (key.isEmpty()) return null

        // (1) sheet 고정 규칙
        NUM_GOOD_PER_SHEET[key]?.let { return it }

        // (2) per-film 규칙
        val perFilm = NUM_GOOD_PER_FILM[key]
        if (perFilm != null) {
            if (filmCount <= 0) return null
            return perFilm * filmCount
        }

        // (3) family prefix 폴백("L2017-03"처럼 revision이 다른 경우를 흡수)
        val family = key.substringBefore('-')
        if (family.isNotEmpty() && filmCount > 0) {
            val familyPerFilm = NUM_GOOD_PER_FILM.entries.firstOrNull {
                it.key.startsWith("$family-")
            }?.value
            if (familyPerFilm != null) return familyPerFilm * filmCount
        }

        return null
    }

    /**
     * good/bad 라벨 없이 film만 존재하는 모델인지 여부.
     *
     * - 현재는 NUM_GOOD_PER_FILM == 0 으로 명시된 모델만 film-only 로 취급합니다.
     * - family prefix 폴백은 의도적으로 사용하지 않아, 다른 모델/개정판에 영향이 없도록 합니다.
     */
    fun isFilmOnlyModel(modelName: String?): Boolean {
        val key = normalizeModelKey(modelName)
        if (key.isEmpty()) return false
        return NUM_GOOD_PER_FILM[key] == 0
    }
}
