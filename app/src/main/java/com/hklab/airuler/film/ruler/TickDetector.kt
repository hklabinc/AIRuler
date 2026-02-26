package com.hklab.airuler.film.ruler

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * HkRuler(filmcalib/ticks.py) 포팅.
 *
 * - RulerCalibration에서 사용하는 경로는 subpixel=True 이므로, 해당 경로를 Python과 동일하게 구현합니다.
 */
object TickDetector {

    // ===== 파라미터 (Python과 동일 기본값) =====
    private const val PROFILE_SMOOTH_SIGMA = 1.2
    private const val PROFILE_SMOOTH_RADIUS = 4
    private const val MIN_SPACING = 2
    private const val MAX_SPACING = 40
    private const val NMS_SEP_FACTOR = 0.8

    // ===== Blur/Low-contrast fallback tuning =====
    // - 기본 경로(energyProfile+Otsu)는 그대로 유지
    // - spacing 검증 실패 시, RulerCalibration에서 이 보강 경로를 "추가 시도"로 사용

    // Edge-profile 기반(흐림/저대비에 상대적으로 강함)
    private const val EDGE_PROFILE_SMOOTH_SIGMA = 2.0
    private const val EDGE_PROFILE_SMOOTH_RADIUS = 6
    private const val EDGE_CANNY_LO = 30.0
    private const val EDGE_CANNY_HI = 90.0
    private const val EDGE_NMS_SEP_FACTOR = 0.9

    // Intensity-profile 강화(CLAHE + unsharp) 기반(흐림 보정용)
    private const val ENH_CLAHE_CLIP_LIMIT = 2.0
    private const val ENH_CLAHE_TILE = 8
    private const val ENH_UNSHARP_SIGMA = 1.1
    private const val ENH_UNSHARP_AMOUNT = 0.6

    private fun gaussianKernel1d(sigma: Double, radius: Int): DoubleArray {
        val r = max(1, radius)
        val size = r * 2 + 1
        val k = DoubleArray(size)
        var sum = 0.0
        for (i in 0 until size) {
            val x = (i - r).toDouble()
            val v = kotlin.math.exp(-(x * x) / (2.0 * sigma * sigma))
            k[i] = v
            sum += v
        }
        if (sum > 0) {
            for (i in k.indices) k[i] /= sum
        }
        return k
    }

    private fun convolveSame(signal: DoubleArray, kernel: DoubleArray): DoubleArray {
        val n = signal.size
        val m = kernel.size
        val rad = m / 2
        val out = DoubleArray(n)
        // np.convolve(mode='same')는 zero-padding 기반. (kernel은 gaussian이라 대칭)
        for (i in 0 until n) {
            var acc = 0.0
            for (j in 0 until m) {
                val p = i + j - rad
                if (p in 0 until n) acc += signal[p] * kernel[j]
            }
            out[i] = acc
        }
        return out
    }

    private fun energyProfile(gray: Mat, orientation: String): Pair<DoubleArray, DoubleArray> {
        require(gray.channels() == 1) { "gray must be 1-channel" }
        val h = gray.rows()
        val w = gray.cols()
        val data = ByteArray(h * w)
        gray.get(0, 0, data)

        val profile = if (orientation == "horizontal") DoubleArray(w) else DoubleArray(h)
        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val g = data[idx].toInt() and 0xFF
                val inv = 255 - g
                if (orientation == "horizontal") {
                    profile[x] += inv.toDouble()
                } else {
                    profile[y] += inv.toDouble()
                }
                idx++
            }
        }

        val k = gaussianKernel1d(PROFILE_SMOOTH_SIGMA, PROFILE_SMOOTH_RADIUS)
        val profileSm = convolveSame(profile, k)
        return profile to profileSm
    }

    /**
     * (보강) 흐림/저대비에서 tick이 끊기거나 약해지는 경우가 있어,
     * Canny edge 기반으로 1D profile을 만들어 tick 후보를 더 안정적으로 얻는 경로입니다.
     *
     * - 기존 energyProfile 경로에는 영향을 주지 않도록 "추가 시도" 용도로만 사용합니다.
     */
    private fun edgeProfileSm(gray: Mat, orientation: String): DoubleArray {
        require(gray.channels() == 1) { "gray must be 1-channel" }

        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blur, edges, EDGE_CANNY_LO, EDGE_CANNY_HI)
        blur.release()

        // 흐림에서 끊긴 edge를 약간 연결
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)
        kernel.release()

        val h = edges.rows()
        val w = edges.cols()
        val data = ByteArray(h * w)
        edges.get(0, 0, data)
        edges.release()

        val profile = if (orientation == "horizontal") DoubleArray(w) else DoubleArray(h)
        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = data[idx].toInt() and 0xFF
                if (v != 0) {
                    if (orientation == "horizontal") profile[x] += 1.0 else profile[y] += 1.0
                }
                idx++
            }
        }

        val k = gaussianKernel1d(EDGE_PROFILE_SMOOTH_SIGMA, EDGE_PROFILE_SMOOTH_RADIUS)
        return convolveSame(profile, k)
    }

    /**
     * (보강) CLAHE + unsharp mask로 tick 대비를 높인 뒤 기존 energyProfile 경로를 재시도하기 위한 전처리.
     *
     * - 반환 Mat은 caller가 반드시 release 해야 합니다.
     */
    private fun enhanceGrayForTicks(gray: Mat): Mat {
        require(gray.channels() == 1) { "gray must be 1-channel" }

        // CLAHE로 국소 대비 향상
        val clahe = Imgproc.createCLAHE(
            ENH_CLAHE_CLIP_LIMIT,
            Size(ENH_CLAHE_TILE.toDouble(), ENH_CLAHE_TILE.toDouble())
        )
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        // unsharp mask: sharp = (1+a)*img - a*blur
        val blur = Mat()
        Imgproc.GaussianBlur(enhanced, blur, Size(0.0, 0.0), ENH_UNSHARP_SIGMA)
        val sharp = Mat()
        Core.addWeighted(
            enhanced,
            1.0 + ENH_UNSHARP_AMOUNT,
            blur,
            -ENH_UNSHARP_AMOUNT,
            0.0,
            sharp
        )
        enhanced.release()
        blur.release()
        return sharp
    }

    private fun otsuMask(profileSm: DoubleArray): BooleanArray {
        val n = profileSm.size
        var mn = Double.POSITIVE_INFINITY
        var mx = Double.NEGATIVE_INFINITY
        for (v in profileSm) {
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val rng = max(mx - mn, 1e-6)

        // scaled = (255*(x-min)/rng).astype(uint8)  (astype는 trunc)
        val scaled = ByteArray(n)
        for (i in 0 until n) {
            val s = (255.0 * (profileSm[i] - mn) / rng)
            val t = PyMath.clampInt(PyMath.truncInt(s), 0, 255)
            scaled[i] = t.toByte()
        }

        val inMat = Mat(1, n, CvType.CV_8UC1)
        inMat.put(0, 0, scaled)
        val outMat = Mat(1, n, CvType.CV_8UC1)
        Imgproc.threshold(inMat, outMat, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        inMat.release()

        val out = ByteArray(n)
        outMat.get(0, 0, out)
        outMat.release()

        val mask = BooleanArray(n)
        for (i in 0 until n) mask[i] = (out[i].toInt() and 0xFF) > 0
        return mask
    }

    private fun findLocalMaxima(signal: DoubleArray, mask: BooleanArray): IntArray {
        val w = signal.size
        val out = ArrayList<Int>()
        for (x in 1 until w - 1) {
            if (mask[x] && signal[x] >= signal[x - 1] && signal[x] >= signal[x + 1]) out.add(x)
        }
        if (w >= 2) {
            if (mask[0] && signal[0] >= signal[1]) out.add(0, 0)
            if (mask[w - 1] && signal[w - 1] >= signal[w - 2]) out.add(w - 1)
        }
        return out.toIntArray()
    }

    private fun robustSpacingFromCandidates(cands: IntArray): Int {
        if (cands.size < 3) return 5
        val bins = IntArray(MAX_SPACING + 1)
        for (i in 1 until cands.size) {
            val d = cands[i] - cands[i - 1]
            if (d in MIN_SPACING..MAX_SPACING) bins[d]++
        }
        var best = 0
        var bestCnt = 0
        for (d in MIN_SPACING..MAX_SPACING) {
            val c = bins[d]
            if (c > bestCnt) {
                bestCnt = c
                best = d
            }
        }
        return max(MIN_SPACING, if (bestCnt == 0) 5 else best)
    }

    private fun lowerBound(arr: IntArray, value: Int): Int {
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upperBound(arr: IntArray, value: Int): Int {
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun nms1d(cands: IntArray, amp: DoubleArray, minSep: Int): IntArray {
        if (cands.isEmpty()) return cands
        val order = cands.indices.sortedWith { a, b ->
            val da = amp[a]
            val db = amp[b]
            when {
                db > da -> 1
                db < da -> -1
                else -> a - b
            }
        }
        val taken = BooleanArray(cands.size)
        val keepIdx = ArrayList<Int>()
        for (oi in order) {
            if (taken[oi]) continue
            val cx = cands[oi]
            keepIdx.add(oi)
            val left = lowerBound(cands, cx - minSep)
            val right = upperBound(cands, cx + minSep)
            for (k in left until right) taken[k] = true
        }
        val out = IntArray(keepIdx.size)
        for (i in keepIdx.indices) out[i] = cands[keepIdx[i]]
        return out
    }

    private fun sampleLinear1d(signal: DoubleArray, x: Double): Double {
        val n = signal.size
        if (n <= 0) return 0.0
        if (x <= 0.0) return signal[0]
        if (x >= (n - 1).toDouble()) return signal[n - 1]
        val x0 = floor(x).toInt()
        val x1 = x0 + 1
        val t = x - x0
        return (1.0 - t) * signal[x0] + t * signal[x1]
    }

    private fun quadraticPeakRefine(signal: DoubleArray, peaks: IntArray, clamp: Double): DoubleArray {
        if (peaks.isEmpty()) return DoubleArray(0)
        val n = signal.size
        val refined = DoubleArray(peaks.size) { peaks[it].toDouble() }
        for (i in peaks.indices) {
            val p = peaks[i]
            if (p <= 0 || p >= n - 1) continue
            val yM1 = signal[p - 1]
            val y0 = signal[p]
            val yP1 = signal[p + 1]
            val denom = (yM1 - 2.0 * y0 + yP1)
            if (abs(denom) < 1e-12) continue
            val delta = 0.5 * (yM1 - yP1) / denom
            if (abs(delta) > clamp) continue
            refined[i] = p.toDouble() + delta
        }
        for (i in refined.indices) {
            refined[i] = PyMath.clampDouble(refined[i], 0.0, max(0.0, (n - 1).toDouble()))
        }
        return refined
    }

    private fun centroidRefine(weights: DoubleArray, centers: DoubleArray, win: Int, subtractBaseline: Boolean): DoubleArray {
        if (centers.isEmpty()) return DoubleArray(0)
        val n = weights.size
        val out = DoubleArray(centers.size)
        for (i in centers.indices) {
            val c = centers[i]
            val c0 = PyMath.roundHalfEvenInt(c)
            val s = max(0, c0 - win)
            val e = min(n, c0 + win + 1)
            if (e - s <= 1) {
                out[i] = PyMath.clampDouble(c, 0.0, max(0.0, (n - 1).toDouble()))
                continue
            }
            var base = 0.0
            if (subtractBaseline) {
                base = Double.POSITIVE_INFINITY
                for (j in s until e) base = min(base, weights[j])
            }
            var denom = 0.0
            var numer = 0.0
            for (j in s until e) {
                var w = weights[j]
                if (subtractBaseline) w = max(0.0, w - base)
                denom += w
                numer += j.toDouble() * w
            }
            out[i] = if (denom <= 1e-9) c else (numer / denom)
            out[i] = PyMath.clampDouble(out[i], 0.0, max(0.0, (n - 1).toDouble()))
        }
        return out
    }

    /**
     * 1D profile(이미 smoothed된 profile)을 기반으로 tick center 후보를 뽑는 공통 로직.
     *
     * - 기본 energyProfile, edgeProfile, enhancedProfile 모두 같은 후처리 파이프라인을 공유합니다.
     */
    private fun detectTickCentersFromProfileSm(
        profileSm: DoubleArray,
        subpixel: Boolean,
        nmsSepFactor: Double = NMS_SEP_FACTOR,
    ): DoubleArray {
        if (profileSm.isEmpty()) return DoubleArray(0)

        val mask = otsuMask(profileSm)
        val cands = findLocalMaxima(profileSm, mask)
        if (cands.isEmpty()) return DoubleArray(0)

        val amps = DoubleArray(cands.size) { profileSm[cands[it]] }

        val step = robustSpacingFromCandidates(cands)
        val minSep = max(2, PyMath.roundHalfEvenInt(nmsSepFactor * step.toDouble()))
        val peaks = nms1d(cands, amps, minSep)
        if (peaks.isEmpty()) return DoubleArray(0)

        val win = max(1, step / 2)

        if (!subpixel) {
            val centers = peaks.sorted().distinct()
            return DoubleArray(centers.size) { centers[it].toDouble() }
        }

        val centers0 = quadraticPeakRefine(profileSm, peaks, clamp = 0.75)
        val centers1 = centroidRefine(profileSm, centers0, win = win, subtractBaseline = true)

        val sorted = centers1.sorted()
        val final = ArrayList<Double>()
        for (x in sorted) {
            val xf = x
            if (final.isEmpty() || (xf - final.last()) >= minSep.toDouble()) {
                final.add(xf)
            } else {
                if (sampleLinear1d(profileSm, xf) > sampleLinear1d(profileSm, final.last())) {
                    final[final.size - 1] = xf
                }
            }
        }
        return final.toDoubleArray()
    }

    /**
     * @param orientation "horizontal"(세로 tick 검출) 또는 "vertical"(가로 tick 검출)
     */
    fun detectTickCenters(gray: Mat, orientation: String, subpixel: Boolean = false): DoubleArray {
        val (_, profileSm) = energyProfile(gray, orientation)
        return detectTickCentersFromProfileSm(profileSm, subpixel = subpixel, nmsSepFactor = NMS_SEP_FACTOR)
    }

    /**
     * (보강) Canny-edge 기반 profile로 tick center를 검출합니다.
     *
     * - blur/저대비에서 energyProfile이 불안정할 때 fallback 용도로 사용하세요.
     */
    fun detectTickCentersByEdges(gray: Mat, orientation: String, subpixel: Boolean = false): DoubleArray {
        val profileSm = edgeProfileSm(gray, orientation)
        return detectTickCentersFromProfileSm(profileSm, subpixel = subpixel, nmsSepFactor = EDGE_NMS_SEP_FACTOR)
    }

    /**
     * (보강) CLAHE + unsharp로 tick 대비를 높인 뒤, 기존 energyProfile 경로로 tick center를 검출합니다.
     */
    fun detectTickCentersEnhanced(gray: Mat, orientation: String, subpixel: Boolean = false): DoubleArray {
        val sharp = enhanceGrayForTicks(gray)
        return try {
            detectTickCenters(sharp, orientation = orientation, subpixel = subpixel)
        } finally {
            sharp.release()
        }
    }
}
