package com.hklab.airuler.yolo

import java.util.Locale

/**
 * AIRuler 전용 YOLO class/label 해석 유틸.
 *
 * ✅ 요구사항(2026-02)
 * - 기존 AIRuler는 YOLO 결과를 "필름 bbox"로만 가정했으나,
 *   이제 TFLite YOLO 모델이 flim/film + good + bad 라벨을 함께 출력합니다.
 * - 따라서 "필름 ROI"로 사용할 detection(=film/flim)만 따로 필터링할 수 있어야 하며,
 *   good/bad 라벨은 결함 합불 판정에 사용됩니다.
 *
 * ⚠️ 클래스 ID 매핑(가정)
 * - TFLite export가 [x,y,w,h,conf,cls] 형태를 내보낸다는 전제에서
 *   cls: 0=film(fl im), 1=good, 2=bad 로 해석합니다.
 * - 일부 구형 모델은 classId가 -1일 수 있는데(출력에 cls가 없음),
 *   이 경우 기존처럼 "모든 detection을 film"로 취급해 호환성을 유지합니다.
 */
object AirulerYoloClasses {

    // 기본 class id(권장): 0=film/flim, 1=good, 2=bad
    const val CLASS_FILM: Int = 0
    const val CLASS_GOOD: Int = 1
    const val CLASS_BAD: Int = 2

    private fun normLabel(label: String?): String =
        (label ?: "").trim().lowercase(Locale.US)

    /**
     * 구형 모델 호환:
     * - classId == -1 (cls 미포함)인 경우, 기존 AIRuler 동작과 동일하게 film로 간주합니다.
     */
    fun isFilm(d: YoloDetection): Boolean {
        if (d.classId == -1) return true
        if (d.classId == CLASS_FILM) return true
        val l = normLabel(d.label)
        return (l == "film" || l == "flim")
    }

    fun isGood(d: YoloDetection): Boolean {
        if (d.classId == CLASS_GOOD) return true
        return normLabel(d.label) == "good"
    }

    fun isBad(d: YoloDetection): Boolean {
        if (d.classId == CLASS_BAD) return true
        return normLabel(d.label) == "bad"
    }

    fun filterFilms(list: List<YoloDetection>): List<YoloDetection> = list.filter { isFilm(it) }
    fun filterGoods(list: List<YoloDetection>): List<YoloDetection> = list.filter { isGood(it) }
    fun filterBads(list: List<YoloDetection>): List<YoloDetection> = list.filter { isBad(it) }
}
