package com.hklab.airuler.film.ruler

import kotlin.math.abs
import kotlin.math.sqrt

/** HkRuler(core/distance_utils.py)의 Kotlin 포팅(필수 함수 위주). */
object DistanceUtils {

    data class TickStructs(
        val ticks: DoubleArray,
        val spacings: DoubleArray,
    )

    fun makeTickStructs(ticks: DoubleArray): TickStructs {
        val t = ticks.map { it.toDouble() }.toDoubleArray()
        if (t.size < 2) throw RuntimeException("Tick 간격을 계산할 수 없습니다(검출된 tick 수가 부족).")
        val spacings = DoubleArray(t.size - 1)
        for (i in 0 until t.size - 1) spacings[i] = t[i + 1] - t[i]
        val t0 = DoubleArray(spacings.size)
        for (i in t0.indices) t0[i] = t[i]
        return TickStructs(t0, spacings)
    }

    fun checkTickSpacingRange(spacings: DoubleArray, loFactor: Double = 0.6, hiFactor: Double = 1.4): Boolean {
        if (spacings.isEmpty()) return false
        val mean = spacings.average()
        val low = mean * loFactor
        val high = mean * hiFactor
        for (s in spacings) {
            if (s < low || s > high) return true
        }
        return false
    }

    fun computeHorizontalDistanceMm(
        x1Abs: Double,
        x2Abs: Double,
        tickStructsH: TickStructs,
        pixelsPerMmH: Double,
        logs: MutableList<String>? = null,
    ): Double {
        var x1 = x1Abs
        var x2 = x2Abs
        val l = logs
        if (x2 < x1) {
            l?.add("⚠ x2($x2) < x1($x1) → swap")
            val tmp = x1; x1 = x2; x2 = tmp
        }

        val ticks = tickStructsH.ticks
        val spacings = tickStructsH.spacings
        // x1 기준: ticks <= x1 중 가장 큰 인덱스
        var nearest1 = -1
        for (i in ticks.indices) {
            if (ticks[i] <= x1) nearest1 = i else break
        }
        if (nearest1 < 0) throw IllegalArgumentException("x1=$x1 보다 작거나 같은 tick이 없습니다.")
        val diff1Px = x1 - ticks[nearest1]
        var localSpacing1 = if (nearest1 in spacings.indices) spacings[nearest1] else pixelsPerMmH
        if (localSpacing1 <= 0) localSpacing1 = pixelsPerMmH
        val diff1Mm = diff1Px / localSpacing1

        // x2 기준
        var nearest2 = -1
        for (i in ticks.indices) {
            if (ticks[i] <= x2) nearest2 = i else break
        }
        if (nearest2 < 0) throw IllegalArgumentException("x2=$x2 보다 작거나 같은 tick이 없습니다.")
        val diff2Px = x2 - ticks[nearest2]
        var localSpacing2 = if (nearest2 in spacings.indices) spacings[nearest2] else pixelsPerMmH
        if (localSpacing2 <= 0) localSpacing2 = pixelsPerMmH
        val diff2Mm = diff2Px / localSpacing2

        val indexDiff = nearest2 - nearest1
        val actual = indexDiff + diff2Mm - diff1Mm
        return actual
    }

    fun computeVerticalDistanceMm(
        y1Abs: Double,
        y2Abs: Double,
        tickStructsV: TickStructs,
        pixelsPerMmV: Double,
        logs: MutableList<String>? = null,
    ): Double {
        var y1 = y1Abs
        var y2 = y2Abs
        val l = logs

        // y1 <= y2 되도록 보정
        if (y2 < y1) {
            l?.add("⚠ y2($y2) < y1($y1) → swap")
            val tmp = y1; y1 = y2; y2 = tmp
        }

        val ticks = tickStructsV.ticks
        val spacings = tickStructsV.spacings

        // y1 기준: ticks <= y1 중 가장 큰 인덱스
        var nearest1 = -1
        for (i in ticks.indices) {
            if (ticks[i] <= y1) nearest1 = i else break
        }
        if (nearest1 < 0) throw IllegalArgumentException("y1=$y1 보다 작거나 같은 tick이 없습니다.")

        val diff1Px = y1 - ticks[nearest1]
        var localSpacing1 = if (nearest1 in spacings.indices) spacings[nearest1] else pixelsPerMmV
        if (localSpacing1 <= 0) {
            l?.add("⚠ localSpacing1<=0 → pixelsPerMmV($pixelsPerMmV) fallback")
            localSpacing1 = pixelsPerMmV
        }
        val diff1Mm = diff1Px / localSpacing1

        // y2 기준
        var nearest2 = -1
        for (i in ticks.indices) {
            if (ticks[i] <= y2) nearest2 = i else break
        }
        if (nearest2 < 0) throw IllegalArgumentException("y2=$y2 보다 작거나 같은 tick이 없습니다.")

        val diff2Px = y2 - ticks[nearest2]
        var localSpacing2 = if (nearest2 in spacings.indices) spacings[nearest2] else pixelsPerMmV
        if (localSpacing2 <= 0) {
            l?.add("⚠ localSpacing2<=0 → pixelsPerMmV($pixelsPerMmV) fallback")
            localSpacing2 = pixelsPerMmV
        }
        val diff2Mm = diff2Px / localSpacing2

        val indexDiff = nearest2 - nearest1
        val actual = indexDiff + diff2Mm - diff1Mm
        return actual
    }

    fun computeEuclideanDistanceMm(
        p1: Pair<Double, Double>,
        p2: Pair<Double, Double>,
        tickStructsH: TickStructs,
        pixelsPerMmH: Double,
        tickStructsV: TickStructs,
        pixelsPerMmV: Double,
        logs: MutableList<String>? = null,
    ): Double {
        val dx = computeHorizontalDistanceMm(p1.first, p2.first, tickStructsH, pixelsPerMmH, logs)
        val dy = computeVerticalDistanceMm(p1.second, p2.second, tickStructsV, pixelsPerMmV, logs)
        return sqrt(dx * dx + dy * dy)
    }
}
