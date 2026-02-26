package com.hklab.airuler.inspection

import kotlin.math.max

/**
 * HkDetector(main_detector.py)의 decision_count 로직을 Android에서 그대로 사용하기 위한 엔진.
 *
 * Python 로직(요약)
 * - has_bad  : decision_count -= 1
 * - has_good :
 *      - all_good 이면 decision_count += 1
 *      - all_good 아니면 decision_count = max(0, decision_count - 1)
 * - decision_count <= -TH => FAIL
 * - decision_count >= +TH => PASS
 */
class GoodBadDecisionEngine(
    private val threshold: Int = 4 // Python: DECISION_THRESHOLD = 4
) {

    private var decisionCount: Int = 0

    fun reset() {
        decisionCount = 0
    }

    fun update(hasBad: Boolean, hasGood: Boolean, allGood: Boolean): InspectionDecision? {
        if (hasBad) {
            decisionCount -= 1
        } else if (hasGood) {
            decisionCount = if (allGood) {
                decisionCount + 1
            } else {
                max(0, decisionCount - 1)
            }
        }

        if (decisionCount <= -threshold) {
            decisionCount = -threshold
            return InspectionDecision.FAIL
        }
        if (decisionCount >= threshold) {
            decisionCount = threshold
            return InspectionDecision.PASS
        }
        return null
    }

    fun snapshotDecisionCount(): Int = decisionCount
}
