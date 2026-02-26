package com.hklab.airuler.film.ruler

import android.util.Log
import com.hklab.airuler.GlobalParams
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.sqrt

/** HkRuler(core/ruler_calibration.py)의 Kotlin 포팅본. */
object RulerCalibration {

    private const val DBG_TAG = "AIRulerRulerDbg"

    // ---------------------------------------------------------------------
    // Debug helpers (Logcat)
    // ---------------------------------------------------------------------

    private fun fmtRoi(roi: IntArray?): String =
        roi?.joinToString(prefix = "[", postfix = "]") ?: "[]"

    private fun logD(msg: String) {
        if (GlobalParams.DEBUG) {
            Log.i(DBG_TAG, msg)
        }
    }

    private fun logE(msg: String, t: Throwable) {
        if (GlobalParams.DEBUG) {
            Log.e(DBG_TAG, msg, t)
        }
    }

    private fun logDoubleArray(label: String, arr: DoubleArray, fmt: String = "%.3f", chunk: Int = 20) {
        if (!GlobalParams.DEBUG) return

        if (arr.isEmpty()) {
            Log.i(DBG_TAG, "$label: []")
            return
        }

        var i = 0
        while (i < arr.size) {
            val end = kotlin.math.min(arr.size, i + chunk)
            val part = buildString {
                for (k in i until end) {
                    if (k > i) append(",")
                    append(String.format(Locale.US, fmt, arr[k]))
                }
            }
            Log.i(DBG_TAG, String.format(Locale.US, "%s[%d..%d]/%d: %s", label, i, end - 1, arr.size, part))
            i = end
        }
    }

    private fun spacingStatsString(spacings: DoubleArray, loFactor: Double = 0.6, hiFactor: Double = 1.4): String {
        if (spacings.isEmpty()) return "(empty)"

        val mean = spacings.average()
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        var varSum = 0.0
        for (s in spacings) {
            if (s < minV) minV = s
            if (s > maxV) maxV = s
            val d = s - mean
            varSum += d * d
        }
        val std = sqrt(varSum / spacings.size.toDouble())

        val sorted = spacings.clone()
        sorted.sort()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) * 0.5

        val low = mean * loFactor
        val high = mean * hiFactor
        var outCnt = 0
        val outIdx = ArrayList<Int>(8)
        for (i in spacings.indices) {
            val s = spacings[i]
            if (s < low || s > high) {
                outCnt++
                if (outIdx.size < 8) outIdx.add(i)
            }
        }

        return String.format(
            Locale.US,
            "mean=%.3f med=%.3f std=%.3f min=%.3f max=%.3f lo=%.3f hi=%.3f out=%d idx=%s",
            mean,
            median,
            std,
            minV,
            maxV,
            low,
            high,
            outCnt,
            outIdx.joinToString(prefix = "[", postfix = "]")
        )
    }

    // ---------------------------------------------------------------------
    // Tick repair helpers (blur tolerance)
    // ---------------------------------------------------------------------

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.clone()
        s.sort()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) * 0.5
    }

    private fun countOutliersByMedian(
        spacings: DoubleArray,
        medianSpacing: Double,
        loFactor: Double = 0.6,
        hiFactor: Double = 1.4,
    ): Int {
        if (spacings.isEmpty() || medianSpacing <= 0.0) return 0
        val lo = medianSpacing * loFactor
        val hi = medianSpacing * hiFactor
        var out = 0
        for (s in spacings) {
            if (s < lo || s > hi) out++
        }
        return out
    }

    /**
     * spacing 검증에 실패한 tick list가 "대부분 정상인데 일부 구간에서 tick이 누락/중복"된 케이스를
     * (안전 조건 하에서만) 보정합니다.
     *
     * - 큰 gap(=정상 간격의 정수배)에 대해: 누락 tick을 균등 분할로 삽입
     * - 너무 작은 gap(중복 peak)에 대해: 중복 tick 제거
     *
     * ⚠️ 주의
     * - gap이 "정수배에 가깝다"는 조건을 만족할 때만 삽입합니다.
     *   (그렇지 않으면 존재하지 않는 tick을 추가하여 mm 인덱스가 1mm 이상 밀릴 수 있어 위험)
     */
    private fun tryRepairTickSequence(
        ticksAbsIn: DoubleArray,
        label: String,
    ): DoubleArray? {
        if (ticksAbsIn.size < 10) return null

        val ticks = ticksAbsIn.clone()
        ticks.sort()

        val spacings0 = DoubleArray(ticks.size - 1)
        for (i in 0 until ticks.size - 1) spacings0[i] = ticks[i + 1] - ticks[i]

        val med = median(spacings0)
        if (!med.isFinite() || med <= 0.0) return null

        // sanity: px/mm이 너무 비정상적인 경우는 repair 시도 자체를 하지 않음
        // - 50MP(18px/mm), 200MP(36px/mm) 근방을 고려해서 넉넉히 잡음
        if (med < 6.0 || med > 90.0) return null

        val out0 = countOutliersByMedian(spacings0, med, loFactor = 0.6, hiFactor = 1.4)
        if (out0 == 0) return null

        // "대부분 정상"일 때만 repair 시도 (너무 망가진 케이스는 삽입/삭제가 위험)
        val outRatio0 = out0.toDouble() / spacings0.size.toDouble()
        if (out0 > 8 && outRatio0 > 0.03) {
            logD(String.format(Locale.US, "[%s][Repair] skip: too many outliers (out=%d, ratio=%.3f, med=%.3f)", label, out0, outRatio0, med))
            return null
        }

        val loDup = med * 0.55
        // 누락 tick 삽입은 "정수배"로 보이는 큰 gap에 대해서만 수행
        val hiGap = med * 1.75
        val intTol = 0.20 // ratio가 정수에 근접해야 함 (예: 1.95~2.05)

        val repaired = ArrayList<Double>(ticks.size + 16)
        var last = ticks[0]
        repaired.add(last)

        var removed = 0
        var inserted = 0

        for (i in 1 until ticks.size) {
            val cur = ticks[i]
            val gap = cur - last

            // 중복/잡음 peak 제거 (너무 가까우면 skip)
            if (gap in 0.0..loDup) {
                removed++
                continue
            }

            // 누락 tick 보정
            if (gap >= hiGap) {
                val ratio = gap / med
                val k0 = kotlin.math.round(ratio).toInt()
                val k = k0.coerceIn(2, 20)

                // ratio가 "정수"에 충분히 가깝지 않으면 삽입하지 않음(위험)
                if (k >= 2 && kotlin.math.abs(ratio - k.toDouble()) <= intTol) {
                    val step = gap / k.toDouble()
                    for (j in 1 until k) {
                        repaired.add(last + step * j.toDouble())
                        inserted++
                    }
                }
            }

            repaired.add(cur)
            last = cur
        }

        if (repaired.size < 2 || (inserted == 0 && removed == 0)) return null
        val repairedArr = repaired.toDoubleArray()

        // repair 후 spacing 검증
        val structs = try {
            DistanceUtils.makeTickStructs(repairedArr)
        } catch (_: Throwable) {
            return null
        }
        val bad = DistanceUtils.checkTickSpacingRange(structs.spacings)

        if (bad) {
            logD(
                String.format(
                    Locale.US,
                    "[%s][Repair] reject: still failed after repair (ins=%d, rm=%d, med=%.3f, stats=%s)",
                    label,
                    inserted,
                    removed,
                    med,
                    spacingStatsString(structs.spacings)
                )
            )
            return null
        }

        logD(
            String.format(
                Locale.US,
                "[%s][Repair] OK: ins=%d, rm=%d, med=%.3f -> spacingStats=%s",
                label,
                inserted,
                removed,
                med,
                spacingStatsString(structs.spacings)
            )
        )
        return repairedArr
    }

    data class RulerAxisResult(
        val tickStructs: DistanceUtils.TickStructs,
        val pixelsPerMm: Double,
    )

    // ----------------------------------------------------------------------------------------
    // NEW: search ROI 기반 Ruler 검출 (Python core/ruler_calibration.py 방식)
    // - FilmTotalMeasureProcessor에서 crop_interest + corner_box 기반으로 search ROI를 만든 뒤
    //   여기 함수에 전달합니다.
    // - 기존 detectTopRuler/detectLeftRuler( unionBBox + extRatio 기반 )는 그대로 유지합니다.
    // ----------------------------------------------------------------------------------------

    /**
     * searchRoi(x1,y1,x2,y2) 영역 안에서 Top Ruler(가로자)를 검출합니다.
     * - grayFull은 전체(또는 crop_interest) 기준 grayscale 이미지
     * - tick centers는 x좌표(double) 배열로 반환됩니다.
     */
    fun detectTopRulerBySearchRoi(
        grayFull: Mat,
        searchRoi: IntArray, // [x1,y1,x2,y2]
        tickLength: Int,
        startRatio: Double,
        endRatio: Double,
        overlay: Mat? = null,
    ): RulerAxisResult {
        if (searchRoi.size < 4) throw IllegalArgumentException("searchRoi must be [x1,y1,x2,y2]")

        val hFull = grayFull.rows()
        val wFull = grayFull.cols()

        var x1 = searchRoi[0]
        var y1 = searchRoi[1]
        var x2 = searchRoi[2]
        var y2 = searchRoi[3]

        // clamp (submat end는 exclusive)
        x1 = x1.coerceIn(0, wFull - 1)
        y1 = y1.coerceIn(0, hFull - 1)
        x2 = x2.coerceIn(x1 + 1, wFull)
        y2 = y2.coerceIn(y1 + 1, hFull)

        if (x2 <= x1 || y2 <= y1) {
            throw RuntimeException("Top ruler search ROI invalid: [$x1,$y1,$x2,$y2]")
        }

        val searchRoiClamped = intArrayOf(x1, y1, x2, y2)
        logD(
            String.format(
                Locale.US,
                "[TopRuler] grayFull=%dx%d searchRoiIn=%s searchRoiClamp=%s tickLength=%d startRatio=%.3f endRatio=%.3f",
                wFull,
                hFull,
                searchRoi.contentToString(),
                fmtRoi(searchRoiClamped),
                tickLength,
                startRatio,
                endRatio,
            )
        )

        // search roi
        val top = grayFull.submat(y1, y2, x1, x2)

        overlay?.let {
            Imgproc.rectangle(
                it,
                Point(x1.toDouble(), y1.toDouble()),
                Point(x2.toDouble(), y2.toDouble()),
                Scalar(0.0, 255.0, 255.0),
                2,
                Imgproc.LINE_AA
            )
        }

        val edgesTop = EdgeUtils.computeEdges(top)
        val roi = EdgeUtils.findDenseWindowsHorizontal(edgesTop, tickLength)
        edgesTop.release()
        top.release()

        val yS = roi[0]
        val yE = roi[1]
        val xS = roi[2]
        val xE = roi[3]

        val ySFull = y1 + yS
        val yEFull = y1 + yE
        val xSFull = x1 + xS
        val xEFull = x1 + xE

        val denseRoiFull = intArrayOf(xSFull, ySFull, xEFull, yEFull)
        logD("[TopRuler] denseWindowLocal=${fmtRoi(roi)} denseWindowFull=${fmtRoi(denseRoiFull)}")

        overlay?.let {
            Imgproc.rectangle(
                it,
                Point(xSFull.toDouble(), ySFull.toDouble()),
                Point(xEFull.toDouble(), yEFull.toDouble()),
                Scalar(255.0, 0.0, 0.0),
                2,
                Imgproc.LINE_AA
            )
        }

        val yStart = ySFull
        val yEnd = yEFull
        val xStart = xSFull
        val xEnd = xEFull

        // ratio clamp
        var sr = startRatio
        var er = endRatio
        if (er < sr) {
            val tmp = sr
            sr = er
            er = tmp
        }
        sr = sr.coerceIn(0.0, 1.0)
        er = er.coerceIn(0.0, 1.0)

        var yTop = PyMath.roundHalfEvenInt(yStart + (yEnd - yStart) * sr)
        var yBottom = PyMath.roundHalfEvenInt(yStart + (yEnd - yStart) * er)

        // clamp
        yTop = yTop.coerceIn(0, hFull)
        yBottom = yBottom.coerceIn(0, hFull)

        // fallback: edge-dense ROI 전체
        if (yBottom <= yTop) {
            yTop = yStart.coerceIn(0, hFull)
            yBottom = yEnd.coerceIn(0, hFull)
        }

        val x0 = xStart.coerceIn(0, wFull - 1)
        val x1e = xEnd.coerceIn(x0 + 1, wFull)

        val tickCropRoi = intArrayOf(x0, yTop, x1e, yBottom)
        logD(
            String.format(
                Locale.US,
                "[TopRuler] tickCropRoi=%s (sr=%.3f, er=%.3f)",
                fmtRoi(tickCropRoi),
                sr,
                er,
            )
        )

        // 2nd crop: 약간 더 넓게(blur 상황에서 긴 tick/배경 대비가 더 살아있는 영역 포함)
        val widen = 0.15
        val srWide = (sr - widen).coerceIn(0.0, 1.0)
        val erWide = (er + widen).coerceIn(0.0, 1.0)
        var yTopWide = PyMath.roundHalfEvenInt(yStart + (yEnd - yStart) * srWide)
        var yBottomWide = PyMath.roundHalfEvenInt(yStart + (yEnd - yStart) * erWide)
        yTopWide = yTopWide.coerceIn(0, hFull)
        yBottomWide = yBottomWide.coerceIn(0, hFull)
        if (yBottomWide <= yTopWide) {
            yTopWide = yStart.coerceIn(0, hFull)
            yBottomWide = yEnd.coerceIn(0, hFull)
        }

        data class DetectedAxis(
            val result: RulerAxisResult,
            val ticksAbs: DoubleArray,
            val yTop: Int,
            val yBottom: Int,
            val method: String,
        )

        var dbgTicksAbs: DoubleArray? = null
        var dbgSpacings: DoubleArray? = null
        var firstFailure: Throwable? = null

        fun detectTicksLocal(gray: Mat, kind: String): DoubleArray {
            return when (kind) {
                "default" -> TickDetector.detectTickCenters(gray, orientation = "horizontal", subpixel = true)
                "enhanced" -> TickDetector.detectTickCentersEnhanced(gray, orientation = "horizontal", subpixel = true)
                "edges" -> TickDetector.detectTickCentersByEdges(gray, orientation = "horizontal", subpixel = true)
                else -> TickDetector.detectTickCenters(gray, orientation = "horizontal", subpixel = true)
            }
        }

        fun attempt(
            method: String,
            yT: Int,
            yB: Int,
            detectorKind: String,
            verboseArrays: Boolean,
        ): DetectedAxis? {
            if (yB <= yT) return null

            return try {
                val gray = grayFull.submat(
                    yT.coerceAtLeast(0).coerceAtMost(hFull - 1),
                    yB.coerceAtLeast(0).coerceAtMost(hFull),
                    x0,
                    x1e
                )

                val ticksLocal = try {
                    detectTicksLocal(gray, detectorKind)
                } finally {
                    gray.release()
                }

                if (ticksLocal.size < 2) {
                    logD("[TopRuler][$method] ticks too few: n=${ticksLocal.size}")
                    return null
                }

                val ticks = DoubleArray(ticksLocal.size)
                for (i in ticksLocal.indices) ticks[i] = ticksLocal[i] + x0.toDouble()
                dbgTicksAbs = ticks

                val structs0 = DistanceUtils.makeTickStructs(ticks)
                dbgSpacings = structs0.spacings
                val px0 = structs0.spacings.average()
                logD(String.format(Locale.US, "[TopRuler][%s] ticksN=%d pxPerMm=%.6f spacingStats=%s", method, ticks.size, px0, spacingStatsString(structs0.spacings)))

                if (verboseArrays) {
                    logDoubleArray("[TopRuler][$method] ticks", ticks, fmt = "%.2f", chunk = 32)
                    logDoubleArray("[TopRuler][$method] spacings", structs0.spacings, fmt = "%.2f", chunk = 32)
                }

                val bad0 = DistanceUtils.checkTickSpacingRange(structs0.spacings)
                if (!bad0) {
                    return DetectedAxis(RulerAxisResult(structs0, px0), ticks, yT, yB, method)
                }

                // (보강2) spacing outlier가 "소수"인 케이스는 누락 tick 삽입/중복 제거로 복구 시도
                val repaired = tryRepairTickSequence(ticks, label = "TopRuler/$method")
                if (repaired != null) {
                    dbgTicksAbs = repaired
                    val structs1 = DistanceUtils.makeTickStructs(repaired)
                    dbgSpacings = structs1.spacings
                    val px1 = structs1.spacings.average()
                    logD(String.format(Locale.US, "[TopRuler][%s+repair] ticksN=%d pxPerMm=%.6f spacingStats=%s", method, repaired.size, px1, spacingStatsString(structs1.spacings)))

                    if (!DistanceUtils.checkTickSpacingRange(structs1.spacings)) {
                        return DetectedAxis(RulerAxisResult(structs1, px1), repaired, yT, yB, "$method+repair")
                    }
                }

                if (firstFailure == null) {
                    firstFailure = RuntimeException("[검증 중단] 가로 자 tick 간격 이상 감지됨.")
                }
                null
            } catch (t: Throwable) {
                if (firstFailure == null) firstFailure = t
                logD("[TopRuler][$method] attempt failed: ${t.message}")
                null
            }
        }

        try {
            val found =
                attempt(method = "default", yT = yTop, yB = yBottom, detectorKind = "default", verboseArrays = true)
                    ?: attempt(method = "enhanced", yT = yTop, yB = yBottom, detectorKind = "enhanced", verboseArrays = false)
                    ?: attempt(method = "edges", yT = yTop, yB = yBottom, detectorKind = "edges", verboseArrays = false)
                    ?: attempt(method = "wide_default", yT = yTopWide, yB = yBottomWide, detectorKind = "default", verboseArrays = false)
                    ?: attempt(method = "wide_edges", yT = yTopWide, yB = yBottomWide, detectorKind = "edges", verboseArrays = false)

            if (found != null) {
                overlay?.let {
                    for (cx in found.ticksAbs) {
                        Imgproc.line(
                            it,
                            Point(cx.toInt().toDouble(), found.yTop.toDouble()),
                            Point(cx.toInt().toDouble(), found.yBottom.toDouble()),
                            Scalar(255.0, 0.0, 0.0),
                            1,
                            Imgproc.LINE_8
                        )
                    }
                }
                return found.result
            }

            throw (firstFailure ?: RuntimeException("[검증 중단] 가로 자 tick 간격 이상 감지됨."))
        } catch (t: Throwable) {
            logE(
                "[TopRuler] FAILED: ${t.message} | searchRoi=${fmtRoi(searchRoiClamped)} denseRoi=${fmtRoi(denseRoiFull)} tickCropRoi=${fmtRoi(tickCropRoi)}",
                t
            )
            dbgTicksAbs?.let { logDoubleArray("[TopRuler][EX] ticks", it, fmt = "%.2f", chunk = 32) }
            dbgSpacings?.let { logDoubleArray("[TopRuler][EX] spacings", it, fmt = "%.2f", chunk = 32) }
            throw t
        }
    }

    /**
     * searchRoi(x1,y1,x2,y2) 영역 안에서 Left Ruler(세로자)를 검출합니다.
     * - tick centers는 y좌표(double) 배열로 반환됩니다.
     */
    fun detectLeftRulerBySearchRoi(
        grayFull: Mat,
        searchRoi: IntArray, // [x1,y1,x2,y2]
        tickLength: Int,
        startRatio: Double,
        endRatio: Double,
        overlay: Mat? = null,
    ): RulerAxisResult {
        if (searchRoi.size < 4) throw IllegalArgumentException("searchRoi must be [x1,y1,x2,y2]")

        val hFull = grayFull.rows()
        val wFull = grayFull.cols()

        var x1 = searchRoi[0]
        var y1 = searchRoi[1]
        var x2 = searchRoi[2]
        var y2 = searchRoi[3]

        // clamp
        x1 = x1.coerceIn(0, wFull - 1)
        y1 = y1.coerceIn(0, hFull - 1)
        x2 = x2.coerceIn(x1 + 1, wFull)
        y2 = y2.coerceIn(y1 + 1, hFull)

        if (x2 <= x1 || y2 <= y1) {
            throw RuntimeException("Left ruler search ROI invalid: [$x1,$y1,$x2,$y2]")
        }

        val searchRoiClamped = intArrayOf(x1, y1, x2, y2)
        logD(
            String.format(
                Locale.US,
                "[LeftRuler] grayFull=%dx%d searchRoiIn=%s searchRoiClamp=%s tickLength=%d startRatio=%.3f endRatio=%.3f",
                wFull,
                hFull,
                searchRoi.contentToString(),
                fmtRoi(searchRoiClamped),
                tickLength,
                startRatio,
                endRatio,
            )
        )

        val left = grayFull.submat(y1, y2, x1, x2)

        overlay?.let {
            Imgproc.rectangle(
                it,
                Point(x1.toDouble(), y1.toDouble()),
                Point(x2.toDouble(), y2.toDouble()),
                Scalar(0.0, 255.0, 255.0),
                2,
                Imgproc.LINE_AA
            )
        }

        val edgesLeft = EdgeUtils.computeEdges(left)
        val roi = EdgeUtils.findDenseWindowsVertical(edgesLeft, tickLength)
        edgesLeft.release()
        left.release()

        val yS = roi[0]
        val yE = roi[1]
        val xS = roi[2]
        val xE = roi[3]

        val ySFull = y1 + yS
        val yEFull = y1 + yE
        val xSFull = x1 + xS
        val xEFull = x1 + xE

        val denseRoiFull = intArrayOf(xSFull, ySFull, xEFull, yEFull)
        logD("[LeftRuler] denseWindowLocal=${fmtRoi(roi)} denseWindowFull=${fmtRoi(denseRoiFull)}")

        overlay?.let {
            Imgproc.rectangle(
                it,
                Point(xSFull.toDouble(), ySFull.toDouble()),
                Point(xEFull.toDouble(), yEFull.toDouble()),
                Scalar(255.0, 0.0, 0.0),
                2,
                Imgproc.LINE_AA
            )
        }

        val yStart = ySFull
        val yEnd = yEFull
        val xStart = xSFull
        val xEnd = xEFull

        // ratio clamp
        var sr = startRatio
        var er = endRatio
        if (er < sr) {
            val tmp = sr
            sr = er
            er = tmp
        }
        sr = sr.coerceIn(0.0, 1.0)
        er = er.coerceIn(0.0, 1.0)

        var xLeft = PyMath.roundHalfEvenInt(xStart + (xEnd - xStart) * sr)
        var xRight = PyMath.roundHalfEvenInt(xStart + (xEnd - xStart) * er)

        xLeft = xLeft.coerceIn(0, wFull)
        xRight = xRight.coerceIn(0, wFull)

        if (xRight <= xLeft) {
            xLeft = xStart.coerceIn(0, wFull)
            xRight = xEnd.coerceIn(0, wFull)
        }

        val y0 = yStart.coerceIn(0, hFull - 1)
        val y1e = yEnd.coerceIn(y0 + 1, hFull)

        val tickCropRoi = intArrayOf(xLeft, y0, xRight, y1e)
        logD(
            String.format(
                Locale.US,
                "[LeftRuler] tickCropRoi=%s (sr=%.3f, er=%.3f)",
                fmtRoi(tickCropRoi),
                sr,
                er,
            )
        )

        // 2nd crop: 약간 더 넓게(blur 상황에서 대비가 더 살아있는 영역 포함)
        val widen = 0.15
        val srWide = (sr - widen).coerceIn(0.0, 1.0)
        val erWide = (er + widen).coerceIn(0.0, 1.0)
        var xLeftWide = PyMath.roundHalfEvenInt(xStart + (xEnd - xStart) * srWide)
        var xRightWide = PyMath.roundHalfEvenInt(xStart + (xEnd - xStart) * erWide)
        xLeftWide = xLeftWide.coerceIn(0, wFull)
        xRightWide = xRightWide.coerceIn(0, wFull)
        if (xRightWide <= xLeftWide) {
            xLeftWide = xStart.coerceIn(0, wFull)
            xRightWide = xEnd.coerceIn(0, wFull)
        }

        data class DetectedAxis(
            val result: RulerAxisResult,
            val ticksAbs: DoubleArray,
            val xLeft: Int,
            val xRight: Int,
            val method: String,
        )

        var dbgTicksAbs: DoubleArray? = null
        var dbgSpacings: DoubleArray? = null
        var firstFailure: Throwable? = null

        fun detectTicksLocal(gray: Mat, kind: String): DoubleArray {
            return when (kind) {
                "default" -> TickDetector.detectTickCenters(gray, orientation = "vertical", subpixel = true)
                "enhanced" -> TickDetector.detectTickCentersEnhanced(gray, orientation = "vertical", subpixel = true)
                "edges" -> TickDetector.detectTickCentersByEdges(gray, orientation = "vertical", subpixel = true)
                else -> TickDetector.detectTickCenters(gray, orientation = "vertical", subpixel = true)
            }
        }

        fun attempt(
            method: String,
            xL: Int,
            xR: Int,
            detectorKind: String,
            verboseArrays: Boolean,
        ): DetectedAxis? {
            if (xR <= xL) return null

            return try {
                val gray = grayFull.submat(
                    y0,
                    y1e,
                    xL.coerceAtLeast(0).coerceAtMost(wFull - 1),
                    xR.coerceAtLeast(0).coerceAtMost(wFull)
                )

                val ticksLocal = try {
                    detectTicksLocal(gray, detectorKind)
                } finally {
                    gray.release()
                }

                if (ticksLocal.size < 2) {
                    logD("[LeftRuler][$method] ticks too few: n=${ticksLocal.size}")
                    return null
                }

                val ticks = DoubleArray(ticksLocal.size)
                for (i in ticksLocal.indices) ticks[i] = ticksLocal[i] + y0.toDouble()
                dbgTicksAbs = ticks

                val structs0 = DistanceUtils.makeTickStructs(ticks)
                dbgSpacings = structs0.spacings
                val px0 = structs0.spacings.average()
                logD(String.format(Locale.US, "[LeftRuler][%s] ticksN=%d pxPerMm=%.6f spacingStats=%s", method, ticks.size, px0, spacingStatsString(structs0.spacings)))

                if (verboseArrays) {
                    logDoubleArray("[LeftRuler][$method] ticks", ticks, fmt = "%.2f", chunk = 32)
                    logDoubleArray("[LeftRuler][$method] spacings", structs0.spacings, fmt = "%.2f", chunk = 32)
                }

                val bad0 = DistanceUtils.checkTickSpacingRange(structs0.spacings)
                if (!bad0) {
                    return DetectedAxis(RulerAxisResult(structs0, px0), ticks, xL, xR, method)
                }

                val repaired = tryRepairTickSequence(ticks, label = "LeftRuler/$method")
                if (repaired != null) {
                    dbgTicksAbs = repaired
                    val structs1 = DistanceUtils.makeTickStructs(repaired)
                    dbgSpacings = structs1.spacings
                    val px1 = structs1.spacings.average()
                    logD(String.format(Locale.US, "[LeftRuler][%s+repair] ticksN=%d pxPerMm=%.6f spacingStats=%s", method, repaired.size, px1, spacingStatsString(structs1.spacings)))

                    if (!DistanceUtils.checkTickSpacingRange(structs1.spacings)) {
                        return DetectedAxis(RulerAxisResult(structs1, px1), repaired, xL, xR, "$method+repair")
                    }
                }

                if (firstFailure == null) {
                    firstFailure = RuntimeException("[검증 중단] 세로 자 tick 간격 이상 감지됨.")
                }
                null
            } catch (t: Throwable) {
                if (firstFailure == null) firstFailure = t
                logD("[LeftRuler][$method] attempt failed: ${t.message}")
                null
            }
        }

        try {
            val found =
                attempt(method = "default", xL = xLeft, xR = xRight, detectorKind = "default", verboseArrays = true)
                    ?: attempt(method = "enhanced", xL = xLeft, xR = xRight, detectorKind = "enhanced", verboseArrays = false)
                    ?: attempt(method = "edges", xL = xLeft, xR = xRight, detectorKind = "edges", verboseArrays = false)
                    ?: attempt(method = "wide_default", xL = xLeftWide, xR = xRightWide, detectorKind = "default", verboseArrays = false)
                    ?: attempt(method = "wide_edges", xL = xLeftWide, xR = xRightWide, detectorKind = "edges", verboseArrays = false)

            if (found != null) {
                overlay?.let {
                    for (cy in found.ticksAbs) {
                        Imgproc.line(
                            it,
                            Point(found.xLeft.toDouble(), cy.toInt().toDouble()),
                            Point(found.xRight.toDouble(), cy.toInt().toDouble()),
                            Scalar(255.0, 0.0, 0.0),
                            1,
                            Imgproc.LINE_8
                        )
                    }
                }
                return found.result
            }

            throw (firstFailure ?: RuntimeException("[검증 중단] 세로 자 tick 간격 이상 감지됨."))
        } catch (t: Throwable) {
            logE(
                "[LeftRuler] FAILED: ${t.message} | searchRoi=${fmtRoi(searchRoiClamped)} denseRoi=${fmtRoi(denseRoiFull)} tickCropRoi=${fmtRoi(tickCropRoi)}",
                t
            )
            dbgTicksAbs?.let { logDoubleArray("[LeftRuler][EX] ticks", it, fmt = "%.2f", chunk = 32) }
            dbgSpacings?.let { logDoubleArray("[LeftRuler][EX] spacings", it, fmt = "%.2f", chunk = 32) }
            throw t
        }
    }

    fun detectTopRuler(
        grayFull: Mat,
        imgFull: Mat,
        unionBBox: IntArray, // [x1,y1,x2,y2]
        tickLength: Int,
        extRatio: Double,
        startRatio: Double,
        endRatio: Double,
        overlay: Mat? = null,
    ): RulerAxisResult {
        val hFull = grayFull.rows()
        val wFull = grayFull.cols()
        val x1 = unionBBox[0]
        val y1 = unionBBox[1]
        val x2 = unionBBox[2]
        val y2 = unionBBox[3]

        val marginX = PyMath.roundHalfEvenInt((x2 - x1).toDouble() * extRatio)
        val x1Exp = maxOf(0, x1 - marginX)
        val x2Exp = minOf(wFull, x2 + marginX)

        // top area: [0:y1, x1Exp:x2Exp]
        val top = grayFull.submat(0, y1.coerceAtMost(hFull), x1Exp, x2Exp)

        overlay?.let {
            Imgproc.rectangle(
                it,
                Point(x1Exp.toDouble(), 0.0),
                Point(x2Exp.toDouble(), y1.toDouble()),
                Scalar(0.0, 255.0, 255.0),
                2,
                Imgproc.LINE_AA
            )
        }

        val edgesTop = EdgeUtils.computeEdges(top)
        val roi = EdgeUtils.findDenseWindowsHorizontal(edgesTop, tickLength)
        edgesTop.release()
        top.release()

        val yS = roi[0]
        val yE = roi[1]
        val xS = roi[2]
        val xE = roi[3]
        val ySFull = yS
        val yEFull = yE
        val xSFull = x1Exp + xS
        val xEFull = x1Exp + xE

        overlay?.let {
            Imgproc.rectangle(
                it,
                Point(xSFull.toDouble(), ySFull.toDouble()),
                Point(xEFull.toDouble(), yEFull.toDouble()),
                Scalar(255.0, 0.0, 0.0),
                2,
                Imgproc.LINE_AA
            )
        }

        val yStart = ySFull
        val yEnd = yEFull
        val xStart = xSFull
        val xEnd = xEFull

        val yTop = PyMath.roundHalfEvenInt(yStart + (yEnd - yStart) * startRatio)
        val yBottom = PyMath.roundHalfEvenInt(yStart + (yEnd - yStart) * endRatio)

        val box = imgFull.submat(
            yTop.coerceAtLeast(0).coerceAtMost(hFull - 1),
            yBottom.coerceAtLeast(0).coerceAtMost(hFull),
            xStart.coerceAtLeast(0).coerceAtMost(wFull - 1),
            xEnd.coerceAtLeast(0).coerceAtMost(wFull)
        )
        val gray = EdgeUtils.ensureGray(box)
        val ticksLocal = TickDetector.detectTickCenters(gray, orientation = "horizontal", subpixel = true)
        if (gray !== box) gray.release()
        box.release()

        val ticks = DoubleArray(ticksLocal.size)
        for (i in ticksLocal.indices) ticks[i] = ticksLocal[i] + xStart.toDouble()

        val structs = DistanceUtils.makeTickStructs(ticks)
        val pxPerMm = structs.spacings.average()

        if (DistanceUtils.checkTickSpacingRange(structs.spacings)) {
            throw RuntimeException("[검증 중단] 가로 자 tick 간격 이상 감지됨.")
        }

        overlay?.let {
            for (cx in ticks) {
                Imgproc.line(
                    it,
                    Point(cx.toInt().toDouble(), yTop.toDouble()),
                    Point(cx.toInt().toDouble(), yBottom.toDouble()),
                    Scalar(255.0, 0.0, 0.0),
                    1,
                    Imgproc.LINE_8
                )
            }
        }

        return RulerAxisResult(structs, pxPerMm)
    }

    fun detectLeftRuler(
        grayFull: Mat,
        imgFull: Mat,
        unionBBox: IntArray,
        tickLength: Int,
        extRatio: Double,
        startRatio: Double,
        endRatio: Double,
        overlay: Mat? = null,
    ): RulerAxisResult {
        val hFull = grayFull.rows()
        val wFull = grayFull.cols()
        val x1 = unionBBox[0]
        val y1 = unionBBox[1]
        val x2 = unionBBox[2]
        val y2 = unionBBox[3]

        val marginY = PyMath.roundHalfEvenInt((y2 - y1).toDouble() * extRatio)
        val y1Exp = maxOf(0, y1 - marginY)
        val y2Exp = minOf(hFull, y2 + marginY)

        val left = grayFull.submat(y1Exp, y2Exp, 0, x1.coerceAtLeast(1))

        overlay?.let {
            Imgproc.rectangle(
                it,
                Point(0.0, y1Exp.toDouble()),
                Point(x1.toDouble(), y2Exp.toDouble()),
                Scalar(0.0, 255.0, 255.0),
                3,
                Imgproc.LINE_AA
            )
        }

        val edgesLeft = EdgeUtils.computeEdges(left)
        val roi = EdgeUtils.findDenseWindowsVertical(edgesLeft, tickLength)
        edgesLeft.release()
        left.release()

        val yS = roi[0]
        val yE = roi[1]
        val xS = roi[2]
        val xE = roi[3]

        val ySFull = y1Exp + yS
        val yEFull = y1Exp + yE
        val xSFull = xS
        val xEFull = xE

        overlay?.let {
            Imgproc.rectangle(
                it,
                Point(xSFull.toDouble(), ySFull.toDouble()),
                Point(xEFull.toDouble(), yEFull.toDouble()),
                Scalar(255.0, 0.0, 0.0),
                2,
                Imgproc.LINE_AA
            )
        }

        val yStart = ySFull
        val yEnd = yEFull
        val xStart = xSFull
        val xEnd = xEFull

        val xLeft = PyMath.roundHalfEvenInt(xStart + (xEnd - xStart) * startRatio)
        val xRight = PyMath.roundHalfEvenInt(xStart + (xEnd - xStart) * endRatio)

        val box = imgFull.submat(
            yStart.coerceAtLeast(0).coerceAtMost(hFull - 1),
            yEnd.coerceAtLeast(0).coerceAtMost(hFull),
            xLeft.coerceAtLeast(0).coerceAtMost(wFull - 1),
            xRight.coerceAtLeast(0).coerceAtMost(wFull)
        )
        val gray = EdgeUtils.ensureGray(box)
        val ticksLocal = TickDetector.detectTickCenters(gray, orientation = "vertical", subpixel = true)
        if (gray !== box) gray.release()
        box.release()

        val ticks = DoubleArray(ticksLocal.size)
        for (i in ticksLocal.indices) ticks[i] = ticksLocal[i] + yStart.toDouble()

        val structs = DistanceUtils.makeTickStructs(ticks)
        val pxPerMm = structs.spacings.average()

        if (DistanceUtils.checkTickSpacingRange(structs.spacings)) {
            throw RuntimeException("[검증 중단] 세로 자 tick 간격 이상 감지됨.")
        }

        overlay?.let {
            for (cy in ticks) {
                Imgproc.line(
                    it,
                    Point(xLeft.toDouble(), cy.toInt().toDouble()),
                    Point(xRight.toDouble(), cy.toInt().toDouble()),
                    Scalar(255.0, 0.0, 0.0),
                    1,
                    Imgproc.LINE_8
                )
            }
        }

        return RulerAxisResult(structs, pxPerMm)
    }
}
