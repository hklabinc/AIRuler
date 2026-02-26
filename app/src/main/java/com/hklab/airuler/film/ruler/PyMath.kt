package com.hklab.airuler.film.ruler

import kotlin.math.max
import kotlin.math.min

/**
 * Python(및 NumPy)와 동일한 반올림 규칙을 맞추기 위한 유틸.
 *
 * - Python `round()` / NumPy `np.round()`는 **banker's rounding(half-to-even)** 입니다.
 * - Kotlin `roundToInt()`는 half-up 이라 0.5 경계에서 결과가 달라질 수 있습니다.
 */
object PyMath {

    /** half-to-even (banker's rounding) */
    fun roundHalfEven(x: Double): Double = Math.rint(x)

    /** Python `int(round(x))`와 동일(half-to-even) */
    fun roundHalfEvenInt(x: Double): Int = Math.rint(x).toInt()

    fun roundHalfEvenLong(x: Double): Long = Math.rint(x).toLong()

    /** Python `int(x)`와 동일(0으로의 절삭). */
    fun truncInt(x: Double): Int = x.toInt()

    fun clampInt(v: Int, lo: Int, hi: Int): Int = min(hi, max(lo, v))

    fun clampDouble(v: Double, lo: Double, hi: Double): Double = min(hi, max(lo, v))
}
