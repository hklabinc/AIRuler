package com.hklab.airuler.inspection

enum class InspectionDecision { PASS, FAIL }
enum class MoveDirection { LEFT, RIGHT, DOWN, UNKNOWN }

data class BadBoxCirclePx(
    val cx: Float,
    val cy: Float,
    val r: Float
)

/**
 * BadBox(결함) 시각화/판정용 Bounding Box (frame pixel 좌표, right/bottom 는 exclusive)
 */
data class BadBoxPx(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
