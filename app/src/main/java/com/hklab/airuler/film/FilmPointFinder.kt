package com.hklab.airuler.film

import com.hklab.airuler.film.ruler.PyMath
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * HkRuler.zip의 find/ 폴더(Python) 로직을 Kotlin(OpenCV)으로 최대한 그대로 포팅.
 *
 * 주의:
 * - Python의 `round()`/`np.round()`(half-to-even)과 동일하게 맞추기 위해 PyMath.roundHalfEvenInt()를 사용합니다.
 */
object FilmPointFinder {

    data class Pt(val x: Double, val y: Double)

    fun find(method: String, cropBgrOrGray: Mat, parameter: Any?): Pt? {
        return when (method) {
            "line_x_by_prob" -> findLineXByProb(cropBgrOrGray, parameter)
            "line_y_by_prob" -> findLineYByProb(cropBgrOrGray, parameter)
            "line_x_by_level" -> findLineXByLevel(cropBgrOrGray, parameter)
            "line_y_by_level" -> findLineYByLevel(cropBgrOrGray, parameter)
            "corner" -> findCorner(cropBgrOrGray, parameter)
            "curve_x" -> findCurveX(cropBgrOrGray, parameter)
            "curve_y" -> findCurveY(cropBgrOrGray, parameter)
            "round" -> findRound(cropBgrOrGray, parameter)
            "round_x" -> findRoundX(cropBgrOrGray, parameter)
            "cross" -> findCross(cropBgrOrGray, parameter)
            "corner_smooth" -> findCornerSmooth(cropBgrOrGray, parameter)
            "corner_sharp" -> findCornerSharp(cropBgrOrGray, parameter)
            else -> null
        }
    }

    // --------------------------------------------------------------------------------------------
    // Param helpers
    // --------------------------------------------------------------------------------------------
    private fun extractDouble(param: Any?, key: String, defaultValue: Double): Double {
        return when (param) {
            is Number -> if (key == "p") param.toDouble() else defaultValue
            is JSONObject -> if (param.has(key)) param.optDouble(key, defaultValue) else defaultValue
            else -> defaultValue
        }
    }

    private fun extractInt(param: Any?, key: String, defaultValue: Int): Int {
        return when (param) {
            is Number -> if (key == "level") param.toInt() else defaultValue
            is JSONObject -> if (param.has(key)) param.optInt(key, defaultValue) else defaultValue
            else -> defaultValue
        }
    }

    private fun extractString(param: Any?, key: String, defaultValue: String): String {
        return when (param) {
            is JSONObject -> if (param.has(key)) param.optString(key, defaultValue) else defaultValue
            else -> defaultValue
        }
    }


    private fun extractBoolean(param: Any?, key: String, defaultValue: Boolean): Boolean {
        return when (param) {
            is JSONObject -> {
                if (!param.has(key)) {
                    defaultValue
                } else {
                    val v = param.opt(key)
                    when (v) {
                        is Boolean -> v
                        is Number -> v.toInt() != 0
                        is String -> {
                            val s = v.trim()
                            when {
                                s.equals("true", ignoreCase = true) -> true
                                s.equals("false", ignoreCase = true) -> false
                                else -> s.toDoubleOrNull()?.let { it != 0.0 } ?: defaultValue
                            }
                        }
                        else -> defaultValue
                    }
                }
            }
            else -> defaultValue
        }
    }

    private fun extractPairDouble(param: Any?, key: String, defaultA: Double, defaultB: Double): Pair<Double, Double> {
        return when (param) {
            is JSONObject -> {
                val v = param.opt(key)
                if (v is JSONArray && v.length() >= 2) {
                    (v.optDouble(0, defaultA) to v.optDouble(1, defaultB))
                } else {
                    defaultA to defaultB
                }
            }
            else -> defaultA to defaultB
        }
    }

    /**
     * Python(find_point_corner_sharp/smooth)의 `_as_float01()`과 동일한 의미.
     * - null -> default
     * - JSONObject -> p / prob / threshold / value 순서로 사용
     * - Number/String -> float 변환
     */
    private fun asFloat01(v: Any?, defaultValue: Double = 0.5): Double {
        if (v == null) return defaultValue
        val raw: Any? = when (v) {
            is JSONObject -> when {
                v.has("p") -> v.opt("p")
                v.has("prob") -> v.opt("prob")
                v.has("threshold") -> v.opt("threshold")
                v.has("value") -> v.opt("value")
                else -> null
            }
            else -> v
        }
        val f = when (raw) {
            null -> defaultValue
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: defaultValue
            else -> defaultValue
        }
        return f.coerceIn(0.0, 1.0)
    }

    private fun ensureOdd(k: Int): Int {
        var kk = k
        if (kk <= 1) return 1
        if (kk % 2 == 0) kk += 1
        return kk
    }

    /**
     * numpy.percentile(..., method='linear') (기본)과 동일한 방식의 percentile.
     * qPercent: 0..100
     */
    private fun percentileLinear(values: FloatArray, qPercent: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val q = qPercent.coerceIn(0.0, 100.0)
        val a = DoubleArray(values.size) { values[it].toDouble() }
        a.sort()
        if (a.size == 1) return a[0]
        val pos = (a.size - 1).toDouble() * (q / 100.0)
        val idx = floor(pos).toInt().coerceIn(0, a.size - 1)
        val frac = pos - idx.toDouble()
        val lo = a[idx]
        val hi = a[min(idx + 1, a.size - 1)]
        return lo + frac * (hi - lo)
    }

    // --------------------------------------------------------------------------------------------
    // Basic numeric helpers (median/quantile/gradient)
    // --------------------------------------------------------------------------------------------
    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val a = values.copyOf()
        a.sort()
        val n = a.size
        return if (n % 2 == 1) a[n / 2] else 0.5 * (a[n / 2 - 1] + a[n / 2])
    }

    private fun quantile(values: FloatArray, q: Double): Float {
        if (values.isEmpty()) return Float.NaN
        val qq = q.coerceIn(0.0, 1.0)
        val a = values.copyOf()
        a.sort()
        if (a.size == 1) return a[0]
        val pos = (a.size - 1).toDouble() * qq
        val idx = floor(pos).toInt().coerceIn(0, a.size - 1)
        val frac = pos - idx.toDouble()
        val lo = a[idx].toDouble()
        val hi = a[min(idx + 1, a.size - 1)].toDouble()
        return (lo + frac * (hi - lo)).toFloat()
    }

    private fun downsamplePointsEven(points: List<Pair<Double, Double>>, maxPoints: Int): List<Pair<Double, Double>> {
        if (maxPoints <= 1 || points.size <= maxPoints) return points
        val out = ArrayList<Pair<Double, Double>>(maxPoints)
        val last = points.size - 1
        for (k in 0 until maxPoints) {
            val idx = floor(k.toDouble() * last.toDouble() / (maxPoints - 1).toDouble())
                .toInt()
                .coerceIn(0, last)
            out.add(points[idx])
        }
        return out
    }

    private fun gradient1d(x: DoubleArray): DoubleArray {
        val n = x.size
        val g = DoubleArray(n)
        if (n == 0) return g
        if (n == 1) {
            g[0] = 0.0
            return g
        }
        g[0] = x[1] - x[0]
        for (i in 1 until n - 1) {
            g[i] = 0.5 * (x[i + 1] - x[i - 1])
        }
        g[n - 1] = x[n - 1] - x[n - 2]
        return g
    }

    private fun thresholdCrossingVec(vec: DoubleArray, t: Double, rising: Boolean): Double? {
        val n = vec.size
        for (i in 0 until n - 1) {
            val v0 = vec[i]
            val v1 = vec[i + 1]
            val ok = if (rising) (v0 < t && v1 >= t) else (v0 > t && v1 <= t)
            if (!ok) continue
            if (v1 == v0) return i + 0.5
            val frac = ((t - v0) / (v1 - v0)).coerceIn(0.0, 1.0)
            return i + frac
        }
        return null
    }

    private fun gaussianKernel1d(sigma: Double, radius: Int? = null): DoubleArray {
        if (sigma <= 0.0) return doubleArrayOf(1.0)
        val r = radius ?: max(1, PyMath.roundHalfEvenInt(3.0 * sigma))
        val size = r * 2 + 1
        val k = DoubleArray(size)
        var sum = 0.0
        for (i in 0 until size) {
            val x = (i - r).toDouble()
            val v = exp(-(x * x) / (2.0 * sigma * sigma))
            k[i] = v
            sum += v
        }
        if (sum > 0) for (i in k.indices) k[i] /= sum
        return k
    }

    private fun convolveSame(signal: DoubleArray, kernel: DoubleArray): DoubleArray {
        val n = signal.size
        val m = kernel.size
        val rad = m / 2
        val out = DoubleArray(n)
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

    // --------------------------------------------------------------------------------------------
    // Image conversion: BGR/Gray -> gray float (0..255) -> linear01 double
    // --------------------------------------------------------------------------------------------
    private fun toGrayFloatU8(img: Mat): FloatArray {
        val h = img.rows()
        val w = img.cols()
        return if (img.channels() == 1) {
            val data = ByteArray(h * w)
            img.get(0, 0, data)
            FloatArray(h * w) { (data[it].toInt() and 0xFF).toFloat() }
        } else {
            val data = ByteArray(h * w * 3)
            img.get(0, 0, data)
            val out = FloatArray(h * w)
            var i = 0
            var o = 0
            while (o < out.size) {
                val b = (data[i].toInt() and 0xFF)
                val g = (data[i + 1].toInt() and 0xFF)
                val r = (data[i + 2].toInt() and 0xFF)
                out[o] = (0.114f * b + 0.587f * g + 0.299f * r)
                i += 3
                o++
            }
            out
        }
    }

    private fun srgbToLinear01(grayU8: FloatArray): DoubleArray {
        val out = DoubleArray(grayU8.size)
        val a = 0.055
        for (i in grayU8.indices) {
            val g = (grayU8[i] / 255.0f).toDouble().coerceIn(0.0, 1.0)
            out[i] = if (g <= 0.04045) g / 12.92 else ((g + a) / (1.0 + a)).pow(2.4)
        }
        return out
    }

    private fun grayTo01(grayU8: FloatArray, assumeSrgb: Boolean): DoubleArray {
        if (assumeSrgb) return srgbToLinear01(grayU8)
        return DoubleArray(grayU8.size) { (grayU8[it] / 255.0f).toDouble().coerceIn(0.0, 1.0) }
    }

    private fun smoothAlongX(img: DoubleArray, h: Int, w: Int, sigma: Double): DoubleArray {
        if (sigma <= 0.0) return img.copyOf()
        val k = gaussianKernel1d(sigma)
        val rad = k.size / 2
        val out = DoubleArray(h * w)
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                var acc = 0.0
                for (i in -rad..rad) {
                    val xx = (x + i).coerceIn(0, w - 1)
                    acc += k[i + rad] * img[rowOff + xx]
                }
                out[rowOff + x] = acc
            }
        }
        return out
    }

    private fun smoothAlongY(img: DoubleArray, h: Int, w: Int, sigma: Double): DoubleArray {
        if (sigma <= 0.0) return img.copyOf()
        val k = gaussianKernel1d(sigma)
        val rad = k.size / 2
        val out = DoubleArray(h * w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0.0
                for (i in -rad..rad) {
                    val yy = (y + i).coerceIn(0, h - 1)
                    acc += k[i + rad] * img[yy * w + x]
                }
                out[y * w + x] = acc
            }
        }
        return out
    }

    // --------------------------------------------------------------------------------------------
    // (A) line_x_by_prob / line_y_by_prob
    // --------------------------------------------------------------------------------------------
    private fun findLineXByProb(img: Mat, param: Any?): Pt? {
        val p = extractDouble(param, "p", 0.5).coerceIn(0.0, 1.0)
        val sigma = extractDouble(param, "sigma", 1.5).coerceAtLeast(0.0)
        val assumeSrgb = extractBoolean(param, "assume_srgb", true)
        val startFrac = extractDouble(param, "start_frac", 0.10).coerceIn(0.0, 0.49)
        val endFrac = extractDouble(param, "end_frac", 0.10).coerceIn(0.0, 0.49)
        val combine = extractString(param, "combine", "median")
        val eps = 1e-6

        val h = img.rows()
        val w = img.cols()

        val grayU8 = toGrayFloatU8(img)
        var a = grayTo01(grayU8, assumeSrgb)
        a = smoothAlongX(a, h, w, sigma)

        // profile = median(a, axis=0)
        val profile = DoubleArray(w)
        val tmp = DoubleArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) tmp[y] = a[y * w + x]
            profile[x] = median(tmp)
        }

        val n = profile.size
        val sw = max(1, PyMath.roundHalfEvenInt(startFrac * n))
        val ew = max(1, PyMath.roundHalfEvenInt(endFrac * n))
        val startRef = median(profile.copyOfRange(0, sw))
        val endRef = median(profile.copyOfRange(n - ew, n))

        var rising = (endRef > startRef + eps)
        if (abs(endRef - startRef) <= eps) {
            val g = gradient1d(profile)
            var best = 0
            var bestAbs = -1.0
            for (i in g.indices) {
                val ag = abs(g[i])
                if (ag > bestAbs) {
                    bestAbs = ag
                    best = i
                }
            }
            rising = (g[best] > 0.0)
        }

        val dark = min(startRef, endRef)
        val bright = max(startRef, endRef)
        val t = (1.0 - p) * dark + p * bright

        val coords = DoubleArray(h)
        var cnt = 0
        val row = DoubleArray(w)
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) row[x] = a[off + x]
            val xc = thresholdCrossingVec(row, t, rising) ?: continue
            if (xc.isFinite()) {
                coords[cnt++] = xc
            }
        }
        if (cnt == 0) return null
        val rep = if (combine == "mean") coords.copyOfRange(0, cnt).average() else median(coords.copyOfRange(0, cnt))
        // ✅ Python(find_point_line_x_by_prob): subpixel x(rep: float) 유지
        //    y는 기존과 동일하게 round(H/2.0) (banker's rounding)
        val yRep = PyMath.roundHalfEvenInt(h / 2.0)
        return Pt(rep, yRep.toDouble())
    }

    private fun findLineYByProb(img: Mat, param: Any?): Pt? {
        val p = extractDouble(param, "p", 0.5).coerceIn(0.0, 1.0)
        val sigma = extractDouble(param, "sigma", 1.5).coerceAtLeast(0.0)
        val assumeSrgb = extractBoolean(param, "assume_srgb", true)
        val startFrac = extractDouble(param, "start_frac", 0.10).coerceIn(0.0, 0.49)
        val endFrac = extractDouble(param, "end_frac", 0.10).coerceIn(0.0, 0.49)
        val combine = extractString(param, "combine", "median")
        val eps = 1e-6

        val h = img.rows()
        val w = img.cols()

        val grayU8 = toGrayFloatU8(img)
        var a = grayTo01(grayU8, assumeSrgb)
        a = smoothAlongY(a, h, w, sigma)

        // profile = median(a, axis=1)
        val profile = DoubleArray(h)
        val tmp = DoubleArray(w)
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) tmp[x] = a[off + x]
            profile[y] = median(tmp)
        }

        val n = profile.size
        val sw = max(1, PyMath.roundHalfEvenInt(startFrac * n))
        val ew = max(1, PyMath.roundHalfEvenInt(endFrac * n))
        val startRef = median(profile.copyOfRange(0, sw))
        val endRef = median(profile.copyOfRange(n - ew, n))

        var rising = (endRef > startRef + eps)
        if (abs(endRef - startRef) <= eps) {
            val g = gradient1d(profile)
            var best = 0
            var bestAbs = -1.0
            for (i in g.indices) {
                val ag = abs(g[i])
                if (ag > bestAbs) {
                    bestAbs = ag
                    best = i
                }
            }
            rising = (g[best] > 0.0)
        }

        val dark = min(startRef, endRef)
        val bright = max(startRef, endRef)
        val t = (1.0 - p) * dark + p * bright

        val coords = DoubleArray(w)
        var cnt = 0
        val col = DoubleArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = a[y * w + x]
            val yc = thresholdCrossingVec(col, t, rising) ?: continue
            if (yc.isFinite()) coords[cnt++] = yc
        }
        if (cnt == 0) return null
        val rep = if (combine == "mean") coords.copyOfRange(0, cnt).average() else median(coords.copyOfRange(0, cnt))
        // ✅ Python(find_point_line_y_by_prob): subpixel y(rep: float) 유지
        //    x는 기존과 동일하게 round(W/2.0) (banker's rounding)
        val xRep = PyMath.roundHalfEvenInt(w / 2.0)
        return Pt(xRep.toDouble(), rep)
    }

    // --------------------------------------------------------------------------------------------
    // (B) line_x_by_level / line_y_by_level
    // --------------------------------------------------------------------------------------------
    private fun findLineXByLevel(img: Mat, param: Any?): Pt? {
        val level = extractInt(param, "level", 128).coerceIn(0, 255)

        // Python(find_point_line_x_by_level) 동작을 그대로:
        // 1) blur(gray) -> threshold(binary_inv)
        // 2) 각 row에서 "가장 오른쪽" transition 위치 i(=i와 i+1 사이)를 찾고 mode(최빈값) 계산
        // 3) mode에 해당하는 row들에서 blur 값의 threshold 교차를 선형보간하여 subpixel x로 보정

        val gray = Mat()
        if (img.channels() == 1) img.copyTo(gray) else Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        val mask = Mat()
        Imgproc.threshold(blur, mask, level.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)

        val h = mask.rows()
        val w = mask.cols()

        val maskData = ByteArray(h * w)
        mask.get(0, 0, maskData)
        val blurData = ByteArray(h * w)
        blur.get(0, 0, blurData)

        gray.release(); blur.release(); mask.release()

        // (row -> rightmost transition)
        val ys = IntArray(h)
        val xs = IntArray(h)
        var cnt = 0
        for (y in 0 until h) {
            val off = y * w
            var foundX = -1
            for (xr in 0 until w - 1) {
                val x = (w - 2) - xr
                val v0 = maskData[off + x].toInt() and 0xFF
                val v1 = maskData[off + x + 1].toInt() and 0xFF
                val trans = (v0 == 255 && v1 == 0) || (v0 == 0 && v1 == 255)
                if (trans) {
                    foundX = x
                    break
                }
            }
            if (foundX >= 0) {
                ys[cnt] = y
                xs[cnt] = foundX
                cnt++
            }
        }
        if (cnt == 0) return null

        // mode(rep_x_int)
        val hist = IntArray(w)
        for (i in 0 until cnt) {
            val x = xs[i]
            if (x in 0 until w) hist[x]++
        }
        var repXInt = 0
        var bestC = -1
        for (x in 0 until w) {
            val c = hist[x]
            if (c > bestC) {
                bestC = c
                repXInt = x
            }
        }
        if (bestC <= 0) return null

        // rows whose x == repXInt (fallback: |x-repXInt|<=1)
        val sel = ArrayList<Int>()
        for (i in 0 until cnt) if (xs[i] == repXInt) sel.add(i)
        if (sel.isEmpty()) {
            for (i in 0 until cnt) if (abs(xs[i] - repXInt) <= 1) sel.add(i)
        }

        val thr = level.toDouble()
        val xCrosses = ArrayList<Double>(sel.size)
        for (k in sel) {
            val y = ys[k]
            val i = xs[k]
            if (i < 0 || i + 1 >= w) continue
            val a0 = (blurData[y * w + i].toInt() and 0xFF).toDouble()
            val a1 = (blurData[y * w + i + 1].toInt() and 0xFF).toDouble()
            val t = if (a1 == a0) 0.5 else ((thr - a0) / (a1 - a0)).coerceIn(0.0, 1.0)
            xCrosses.add(i.toDouble() + t)
        }

        val repX = if (xCrosses.isNotEmpty()) median(xCrosses.toDoubleArray()) else repXInt.toDouble()
        val repY = (h / 2).toDouble() // Python: H//2
        return Pt(repX, repY)
    }

    private fun findLineYByLevel(img: Mat, param: Any?): Pt? {
        val level = extractInt(param, "level", 128).coerceIn(0, 255)

        // Python(find_point_line_y_by_level) 동작을 그대로:
        // 1) blur(gray) -> threshold(binary_inv)
        // 2) 각 column에서 "가장 아래" transition 위치 i(=i와 i+1 사이)를 찾고 mode(최빈값) 계산
        // 3) mode에 해당하는 column들에서 blur 값의 threshold 교차를 선형보간하여 subpixel y로 보정

        val gray = Mat()
        if (img.channels() == 1) img.copyTo(gray) else Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        val mask = Mat()
        Imgproc.threshold(blur, mask, level.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)

        val h = mask.rows()
        val w = mask.cols()

        val maskData = ByteArray(h * w)
        mask.get(0, 0, maskData)
        val blurData = ByteArray(h * w)
        blur.get(0, 0, blurData)

        gray.release(); blur.release(); mask.release()

        // (col -> bottommost transition)
        val xs = IntArray(w)
        val ys = IntArray(w)
        var cnt = 0
        for (x in 0 until w) {
            var foundY = -1
            for (yr in 0 until h - 1) {
                val y = (h - 2) - yr
                val v0 = maskData[y * w + x].toInt() and 0xFF
                val v1 = maskData[(y + 1) * w + x].toInt() and 0xFF
                val trans = (v0 == 255 && v1 == 0) || (v0 == 0 && v1 == 255)
                if (trans) {
                    foundY = y
                    break
                }
            }
            if (foundY >= 0) {
                xs[cnt] = x
                ys[cnt] = foundY
                cnt++
            }
        }
        if (cnt == 0) return null

        // mode(rep_y_int)
        val hist = IntArray(h)
        for (i in 0 until cnt) {
            val y = ys[i]
            if (y in 0 until h) hist[y]++
        }
        var repYInt = 0
        var bestC = -1
        for (y in 0 until h) {
            val c = hist[y]
            if (c > bestC) {
                bestC = c
                repYInt = y
            }
        }
        if (bestC <= 0) return null

        // columns whose y == repYInt (fallback: |y-repYInt|<=1)
        val sel = ArrayList<Int>()
        for (i in 0 until cnt) if (ys[i] == repYInt) sel.add(i)
        if (sel.isEmpty()) {
            for (i in 0 until cnt) if (abs(ys[i] - repYInt) <= 1) sel.add(i)
        }

        val thr = level.toDouble()
        val yCrosses = ArrayList<Double>(sel.size)
        for (k in sel) {
            val x = xs[k]
            val i = ys[k]
            if (i < 0 || i + 1 >= h) continue
            val a0 = (blurData[i * w + x].toInt() and 0xFF).toDouble()
            val a1 = (blurData[(i + 1) * w + x].toInt() and 0xFF).toDouble()
            val t = if (a1 == a0) 0.5 else ((thr - a0) / (a1 - a0)).coerceIn(0.0, 1.0)
            yCrosses.add(i.toDouble() + t)
        }

        val repY = if (yCrosses.isNotEmpty()) median(yCrosses.toDoubleArray()) else repYInt.toDouble()
        val repX = (w / 2).toDouble() // Python: W//2
        return Pt(repX, repY)
    }

    // --------------------------------------------------------------------------------------------
    // (C) corner (median / fitLine)
    // --------------------------------------------------------------------------------------------
    private fun collectCrossingsCorner(imgGrayU8: FloatArray, h: Int, w: Int, axis: Char, p: Double, sigma: Double, edgeFrac: Double): List<Pair<Double, Double>> {
        val I = srgbToLinear01(imgGrayU8)
        val Is = if (axis == 'x') smoothAlongX(I, h, w, sigma) else smoothAlongY(I, h, w, sigma)

        if (axis == 'x') {
            // profile = median(Is, axis=0)
            val profile = DoubleArray(w)
            val tmp = DoubleArray(h)
            for (x in 0 until w) {
                for (y in 0 until h) tmp[y] = Is[y * w + x]
                profile[x] = median(tmp)
            }
            val n = w
            val ww = max(1, PyMath.roundHalfEvenInt(edgeFrac * n))
            val startRef = median(profile.copyOfRange(0, ww))
            val endRef = median(profile.copyOfRange(n - ww, n))
            val dark = min(startRef, endRef)
            val light = max(startRef, endRef)
            val t = (1.0 - p) * dark + p * light
            val forward = startRef <= endRef + 1e-6
            val pts = ArrayList<Pair<Double, Double>>()
            val row = DoubleArray(w)
            val rowR = DoubleArray(w)
            for (r in 0 until h) {
                val off = r * w
                for (x in 0 until w) row[x] = Is[off + x]
                val xCross = if (forward) {
                    thresholdCrossingVec(row, t, rising = true)
                } else {
                    for (i in 0 until w) rowR[i] = row[w - 1 - i]
                    val xr = thresholdCrossingVec(rowR, t, rising = true)
                    xr?.let { (w - 1).toDouble() - it }
                }
                if (xCross != null && xCross.isFinite()) pts.add(xCross to r.toDouble())
            }
            return pts
        } else {
            // axis == 'y'
            val profile = DoubleArray(h)
            val tmp = DoubleArray(w)
            for (y in 0 until h) {
                val off = y * w
                for (x in 0 until w) tmp[x] = Is[off + x]
                profile[y] = median(tmp)
            }
            val n = h
            val ww = max(1, PyMath.roundHalfEvenInt(edgeFrac * n))
            val startRef = median(profile.copyOfRange(0, ww))
            val endRef = median(profile.copyOfRange(n - ww, n))
            val dark = min(startRef, endRef)
            val light = max(startRef, endRef)
            val t = (1.0 - p) * dark + p * light
            val forward = startRef <= endRef + 1e-6
            val pts = ArrayList<Pair<Double, Double>>()
            val col = DoubleArray(h)
            val colR = DoubleArray(h)
            for (c in 0 until w) {
                for (y in 0 until h) col[y] = Is[y * w + c]
                val yCross = if (forward) {
                    thresholdCrossingVec(col, t, rising = true)
                } else {
                    for (i in 0 until h) colR[i] = col[h - 1 - i]
                    val yr = thresholdCrossingVec(colR, t, rising = true)
                    yr?.let { (h - 1).toDouble() - it }
                }
                if (yCross != null && yCross.isFinite()) pts.add(c.toDouble() to yCross)
            }
            return pts
        }
    }

    private fun fitLineCv(points: List<Pair<Double, Double>>, mode: Char): Triple<Double, Double, Double>? {
        if (points.size < 2) return null
        val pts = points.map { Point(it.first, it.second) }.toTypedArray()
        val matPts = MatOfPoint2f(*pts)
        val line = Mat()
        Imgproc.fitLine(matPts, line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
        val v = FloatArray(4)
        line.get(0, 0, v)
        line.release()
        matPts.release()

        val vx = v[0].toDouble()
        val vy = v[1].toDouble()
        val x0 = v[2].toDouble()
        val y0 = v[3].toDouble()

        return if (mode == 'x') {
            if (abs(vy) < 1e-12) null else {
                val a = vx / vy
                val b = x0 - a * y0
                Triple(0.0, a, b) // tag unused
            }
        } else {
            if (abs(vx) < 1e-12) null else {
                val c = vy / vx
                val d = y0 - c * x0
                Triple(1.0, c, d)
            }
        }
    }

    private fun findCorner(img: Mat, param: Any?): Pt? {
        val p = extractDouble(param, "p", 0.5).coerceIn(0.0, 1.0)
        val sigma = extractDouble(param, "sigma", 1.0).coerceAtLeast(0.0)
        val edgeFrac = extractDouble(param, "edge_frac", 0.10).coerceIn(0.0, 0.49)
        val method = extractString(param, "method", "median")

        val h = img.rows()
        val w = img.cols()
        val grayU8 = toGrayFloatU8(img)

        val ptsX = collectCrossingsCorner(grayU8, h, w, 'x', p, sigma, edgeFrac)
        val ptsY = collectCrossingsCorner(grayU8, h, w, 'y', p, sigma, edgeFrac)
        if (ptsX.isEmpty() || ptsY.isEmpty()) return null

        if (method == "median") {
            val xs = DoubleArray(ptsX.size) { ptsX[it].first }
            val ys = DoubleArray(ptsY.size) { ptsY[it].second }
            // ✅ Python(find_point_corner, method='median'): subpixel median 그대로 유지
            val xRep = median(xs)
            val yRep = median(ys)
            return Pt(xRep, yRep)
        }

        val lineX = fitLineCv(ptsX, mode = 'x') ?: return null
        val lineY = fitLineCv(ptsY, mode = 'y') ?: return null
        val a = lineX.second
        val b = lineX.third
        val c = lineY.second
        val d = lineY.third
        val denom = (1.0 - a * c)
        if (abs(denom) < 1e-9) return null
        val xInt = (a * d + b) / denom
        val yInt = c * xInt + d
        return Pt(xInt, yInt)
    }

    // --------------------------------------------------------------------------------------------
    // (D) curve_x / curve_y
    // --------------------------------------------------------------------------------------------
    private fun robustStdFromMad(vals: DoubleArray): Double {
        val med = median(vals)
        val absDev = DoubleArray(vals.size) { abs(vals[it] - med) }
        val mad = median(absDev)
        return 1.4826 * mad
    }

    private fun interpNans1d(y: DoubleArray): DoubleArray {
        val n = y.size
        val out = y.copyOf()
        val idx = ArrayList<Int>()
        for (i in 0 until n) if (out[i].isFinite()) idx.add(i)
        if (idx.size < 2) return out

        // np.interp: 양 끝은 boundary value로 채움
        var last = idx[0]
        for (i in 0 until last) out[i] = out[last]
        for (k in 0 until idx.size - 1) {
            val i0 = idx[k]
            val i1 = idx[k + 1]
            val y0 = out[i0]
            val y1 = out[i1]
            for (i in i0 + 1 until i1) {
                val t = (i - i0).toDouble() / (i1 - i0).toDouble()
                out[i] = (1.0 - t) * y0 + t * y1
            }
            last = i1
        }
        for (i in last + 1 until n) out[i] = out[last]
        return out
    }

    private fun nanGaussianSmooth1d(y: DoubleArray, sigma: Double): DoubleArray {
        if (sigma <= 0.0) return y.copyOf()
        val k = gaussianKernel1d(sigma)
        val mask = DoubleArray(y.size) { if (y[it].isFinite()) 1.0 else 0.0 }
        val y0 = DoubleArray(y.size) { if (y[it].isFinite()) y[it] else 0.0 }
        val num = convolveSame(y0, k)
        val den = convolveSame(mask, k)
        val out = DoubleArray(y.size)
        for (i in y.indices) {
            out[i] = if (den[i] > 1e-6) num[i] / den[i] else Double.NaN
        }
        return out
    }

    private fun curveEdgeThresholdAutoX(
        img: Mat,
        p: Double,
        sigma: Double,
        curveSigma: Double,
        startFrac: Double,
        endFrac: Double,
        assumeSrgb: Boolean = true,
    ): DoubleArray {
        val h = img.rows()
        val w = img.cols()
        val grayU8 = toGrayFloatU8(img)
        var a = grayTo01(grayU8, assumeSrgb)
        a = smoothAlongX(a, h, w, sigma)

        val curve = DoubleArray(h) { Double.NaN }
        val sw = max(1, PyMath.roundHalfEvenInt(startFrac * w))
        val ew = max(1, PyMath.roundHalfEvenInt(endFrac * w))
        val row = DoubleArray(w)
        for (r in 0 until h) {
            val off = r * w
            for (x in 0 until w) row[x] = a[off + x]
            val startRef = median(row.copyOfRange(0, sw))
            val endRef = median(row.copyOfRange(w - ew, w))
            var rising = (endRef > startRef + 1e-6)
            if (abs(endRef - startRef) <= 1e-6) {
                val g = gradient1d(row)
                var best = 0
                var bestAbs = -1.0
                for (i in g.indices) {
                    val ag = abs(g[i])
                    if (ag > bestAbs) { bestAbs = ag; best = i }
                }
                rising = g[best] > 0
            }
            val dark = min(startRef, endRef)
            val bright = max(startRef, endRef)
            val t = (1.0 - p) * dark + p * bright
            val xc = thresholdCrossingVec(row, t, rising) ?: continue
            if (xc.isFinite()) curve[r] = xc
        }

        if (curve.any { it.isFinite() }) {
            val filled = interpNans1d(curve)
            return nanGaussianSmooth1d(filled, curveSigma)
        }
        return curve
    }

    private fun findCurveX(img: Mat, param: Any?): Pt? {
        val p = extractDouble(param, "p", 0.5).coerceIn(0.0, 1.0)
        val sigma = extractDouble(param, "sigma", 1.5).coerceAtLeast(0.0)
        val curveSigma = extractDouble(param, "curve_sigma", 2.0).coerceAtLeast(0.0)
        val startFrac = extractDouble(param, "start_frac", 0.10).coerceIn(0.0, 0.49)
        val endFrac = extractDouble(param, "end_frac", 0.10).coerceIn(0.0, 0.49)
        val assumeSrgb = extractBoolean(param, "assume_srgb", true)

        val h = img.rows()
        val w = img.cols()
        val curve = curveEdgeThresholdAutoX(img, p, sigma, curveSigma, startFrac, endFrac, assumeSrgb)

        val ys = ArrayList<Int>()
        val xs = ArrayList<Double>()
        for (i in 0 until h) {
            val v = curve[i]
            if (v.isFinite()) { ys.add(i); xs.add(v) }
        }
        if (ys.isEmpty()) return null

        val left = xs.first()
        val right = xs.last()
        val mid = xs[xs.size / 2]

        var bestIdx = 0
        if (mid > left && mid > right) {
            // 오른쪽 볼록 → 최우측
            var maxV = -1e18
            for (i in xs.indices) if (xs[i] > maxV) { maxV = xs[i]; bestIdx = i }
        } else {
            // 왼쪽 볼록 → 최좌측
            var minV = 1e18
            for (i in xs.indices) if (xs[i] < minV) { minV = xs[i]; bestIdx = i }
        }

        // ✅ Python(find_point_curve_x): subpixel 극점 보정(포물선 3점 보간)
        var xRef = xs[bestIdx]
        var yRef = ys[bestIdx].toDouble()

        if (bestIdx > 0 && bestIdx < xs.size - 1) {
            val xm1 = xs[bestIdx - 1]
            val x0 = xs[bestIdx]
            val xp1 = xs[bestIdx + 1]
            val denom = (xm1 - 2.0 * x0 + xp1)
            if (abs(denom) > 1e-12) {
                var delta = 0.5 * (xm1 - xp1) / denom
                delta = delta.coerceIn(-1.0, 1.0)

                yRef = ys[bestIdx].toDouble() + delta
                val a = 0.5 * (xm1 + xp1 - 2.0 * x0)
                val b = 0.5 * (xp1 - xm1)
                xRef = a * delta * delta + b * delta + x0
            }
        }

        // 안전 클램프 (Python과 동일)
        xRef = xRef.coerceIn(0.0, (w - 1).toDouble())
        yRef = yRef.coerceIn(0.0, (h - 1).toDouble())

        return Pt(xRef, yRef)
    }

    private fun curveEdgeThresholdAutoY(
        img: Mat,
        p: Double,
        sigma: Double,
        curveSigma: Double,
        startFrac: Double,
        endFrac: Double,
        assumeSrgb: Boolean = true,
    ): DoubleArray {
        val h = img.rows()
        val w = img.cols()
        val grayU8 = toGrayFloatU8(img)
        var a = grayTo01(grayU8, assumeSrgb)
        a = smoothAlongY(a, h, w, sigma)

        val curve = DoubleArray(w) { Double.NaN }
        val sw = max(1, PyMath.roundHalfEvenInt(startFrac * h))
        val ew = max(1, PyMath.roundHalfEvenInt(endFrac * h))
        val col = DoubleArray(h)
        for (c in 0 until w) {
            for (y in 0 until h) col[y] = a[y * w + c]
            val startRef = median(col.copyOfRange(0, sw))
            val endRef = median(col.copyOfRange(h - ew, h))
            var rising = (endRef > startRef + 1e-6)
            if (abs(endRef - startRef) <= 1e-6) {
                val g = gradient1d(col)
                var best = 0
                var bestAbs = -1.0
                for (i in g.indices) {
                    val ag = abs(g[i])
                    if (ag > bestAbs) { bestAbs = ag; best = i }
                }
                rising = g[best] > 0
            }
            val dark = min(startRef, endRef)
            val bright = max(startRef, endRef)
            val t = (1.0 - p) * dark + p * bright
            val yc = thresholdCrossingVec(col, t, rising) ?: continue
            if (yc.isFinite()) curve[c] = yc
        }

        if (curve.any { it.isFinite() }) {
            val filled = interpNans1d(curve)
            return nanGaussianSmooth1d(filled, curveSigma)
        }
        return curve
    }

    private fun findCurveY(img: Mat, param: Any?): Pt? {
        val p = extractDouble(param, "p", 0.5).coerceIn(0.0, 1.0)
        val sigma = extractDouble(param, "sigma", 1.5).coerceAtLeast(0.0)
        val curveSigma = extractDouble(param, "curve_sigma", 2.0).coerceAtLeast(0.0)
        val startFrac = extractDouble(param, "start_frac", 0.10).coerceIn(0.0, 0.49)
        val endFrac = extractDouble(param, "end_frac", 0.10).coerceIn(0.0, 0.49)
        val assumeSrgb = extractBoolean(param, "assume_srgb", true)

        val h = img.rows()
        val w = img.cols()
        val curve = curveEdgeThresholdAutoY(img, p, sigma, curveSigma, startFrac, endFrac, assumeSrgb)

        val xs = ArrayList<Int>()
        val ys = ArrayList<Double>()
        for (i in 0 until w) {
            val v = curve[i]
            if (v.isFinite()) { xs.add(i); ys.add(v) }
        }
        if (xs.isEmpty()) return null

        val top = ys.first()
        val bottom = ys.last()
        val mid = ys[ys.size / 2]

        var bestIdx = 0
        if (mid > top && mid > bottom) {
            var maxV = -1e18
            for (i in ys.indices) if (ys[i] > maxV) { maxV = ys[i]; bestIdx = i }
        } else {
            var minV = 1e18
            for (i in ys.indices) if (ys[i] < minV) { minV = ys[i]; bestIdx = i }
        }

        // ✅ Python(find_point_curve_y): subpixel 극점 보정(포물선 3점 보간)
        var xRef = xs[bestIdx].toDouble()
        var yRef = ys[bestIdx]

        if (bestIdx > 0 && bestIdx < ys.size - 1) {
            val ym1 = ys[bestIdx - 1]
            val y0 = ys[bestIdx]
            val yp1 = ys[bestIdx + 1]
            val denom = (ym1 - 2.0 * y0 + yp1)
            if (abs(denom) > 1e-12) {
                var delta = 0.5 * (ym1 - yp1) / denom
                delta = delta.coerceIn(-1.0, 1.0)

                xRef = xs[bestIdx].toDouble() + delta
                val a = 0.5 * (ym1 + yp1 - 2.0 * y0)
                val b = 0.5 * (yp1 - ym1)
                yRef = a * delta * delta + b * delta + y0
            }
        }

        // 안전 클램프 (Python과 동일)
        xRef = xRef.coerceIn(0.0, (w - 1).toDouble())
        yRef = yRef.coerceIn(0.0, (h - 1).toDouble())

        return Pt(xRef, yRef)
    }

    // --------------------------------------------------------------------------------------------
    // (E) round
    // --------------------------------------------------------------------------------------------
    private fun srgbToLinear01MatU8(grayU8: Mat): Mat {
        val h = grayU8.rows()
        val w = grayU8.cols()
        val data = ByteArray(h * w)
        grayU8.get(0, 0, data)
        val out = Mat(h, w, CvType.CV_32FC1)
        val f = FloatArray(h * w)
        val a = 0.055
        for (i in data.indices) {
            val g = (data[i].toInt() and 0xFF) / 255.0
            val lin = if (g <= 0.04045) g / 12.92 else ((g + a) / (1.0 + a)).pow(2.4)
            f[i] = lin.toFloat()
        }
        out.put(0, 0, f)
        return out
    }

    private fun autoTopHorizontalEdgeY(grayLin32F: Mat, searchFrac: Pair<Double, Double>, blur: Double): Int {
        val h = grayLin32F.rows()
        val w = grayLin32F.cols()
        val y0 = PyMath.roundHalfEvenInt(h * searchFrac.first)
        val y1 = max(y0 + 3, PyMath.roundHalfEvenInt(h * searchFrac.second))
        val ys = y0.coerceIn(0, h - 1)
        val ye = y1.coerceIn(ys + 1, h)
        val roi = grayLin32F.submat(ys, ye, 0, w)
        val roiBlur = if (blur > 0) {
            val tmp = Mat()
            Imgproc.GaussianBlur(roi, tmp, Size(0.0, 0.0), blur, blur)
            roi.release()
            tmp
        } else roi

        val gy = Mat()
        Imgproc.Sobel(roiBlur, gy, CvType.CV_32F, 0, 1, 3)
        roiBlur.release()

        // energy = median(abs(Gy), axis=1)
        val data = FloatArray(gy.rows() * gy.cols())
        gy.get(0, 0, data)
        val rh = gy.rows()
        val rw = gy.cols()
        gy.release()

        var bestRow = 0
        var bestVal = -1.0
        val tmp = DoubleArray(rw)
        for (r in 0 until rh) {
            val off = r * rw
            for (c in 0 until rw) tmp[c] = abs(data[off + c].toDouble())
            val v = median(tmp)
            if (v > bestVal) { bestVal = v; bestRow = r }
        }
        return (ys + bestRow).coerceIn(0, h - 1)
    }

    private fun smooth1d(y: DoubleArray, sigma: Double): DoubleArray {
        if (sigma <= 0.0) return y.copyOf()
        val k = gaussianKernel1d(sigma)
        return convolveSame(y, k)
    }

    private fun thresholdCrossing1d(y: DoubleArray, t: Double, rising: Boolean): Double? {
        for (i in 0 until y.size - 1) {
            val v0 = y[i]
            val v1 = y[i + 1]
            val ok = if (rising) (v0 < t && v1 >= t) else (v0 > t && v1 <= t)
            if (!ok) continue
            if (v1 == v0) return i + 0.5
            val frac = ((t - v0) / (v1 - v0)).coerceIn(0.0, 1.0)
            return i + frac
        }
        return null
    }

    private fun findRound(img: Mat, param: Any?): Pt? {
        val p = extractDouble(param, "p", 0.5).coerceIn(0.0, 1.0)
        val band = extractInt(param, "band", 2).coerceAtLeast(0)
        val leftFrac = extractDouble(param, "left_frac", 0.15).coerceIn(0.0, 0.49)
        val rightFrac = extractDouble(param, "right_frac", 0.15).coerceIn(0.0, 0.49)
        val smoothSigma = extractDouble(param, "smooth_sigma", 1.0).coerceAtLeast(0.0)
        val edgeScalar = extractString(param, "edge_scalar", "proj")
        val searchFrac = extractPairDouble(param, "search_frac", 0.0, 0.5)
        val blurForY = extractDouble(param, "blur_for_y", 1.0).coerceAtLeast(0.0)

        val h = img.rows()
        val w = img.cols()

        val grayU8 = Mat()
        if (img.channels() == 1) img.copyTo(grayU8) else Imgproc.cvtColor(img, grayU8, Imgproc.COLOR_BGR2GRAY)
        val grayLin = srgbToLinear01MatU8(grayU8)
        grayU8.release()

        // rgb_lin: only if color input
        val rgbLin: Array<FloatArray>? = if (img.channels() == 3) {
            val data = ByteArray(h * w * 3)
            img.get(0, 0, data)
            val r = FloatArray(h * w)
            val g = FloatArray(h * w)
            val b = FloatArray(h * w)
            val a = 0.055
            var i = 0
            var o = 0
            while (o < r.size) {
                val bb = (data[i].toInt() and 0xFF) / 255.0
                val gg = (data[i + 1].toInt() and 0xFF) / 255.0
                val rr = (data[i + 2].toInt() and 0xFF) / 255.0
                fun lin(v: Double): Float {
                    val vv = v.coerceIn(0.0, 1.0)
                    return (if (vv <= 0.04045) vv / 12.92 else ((vv + a) / (1.0 + a)).pow(2.4)).toFloat()
                }
                // Python: BGR -> RGB
                r[o] = lin(rr)
                g[o] = lin(gg)
                b[o] = lin(bb)
                i += 3
                o++
            }
            arrayOf(r, g, b)
        } else null

        val yRef = autoTopHorizontalEdgeY(grayLin, searchFrac, blurForY)

        val y0 = max(0, yRef - band)
        val y1 = min(h, yRef + band + 1)

        val nL = max(1, PyMath.roundHalfEvenInt(w * leftFrac))
        val nR = max(1, PyMath.roundHalfEvenInt(w * rightFrac))

        val cxRgb: Array<DoubleArray>? = if (rgbLin != null) {
            Array(3) { ch ->
                DoubleArray(w) { x ->
                    val vals = DoubleArray(y1 - y0)
                    for (yy in y0 until y1) {
                        vals[yy - y0] = rgbLin[ch][yy * w + x].toDouble()
                    }
                    median(vals)
                }
            }
        } else null

        val s = DoubleArray(w)
        var t = 0.0
        var rising = true

        if (edgeScalar == "proj" && cxRgb != null) {
            // Cx = median(rgb_lin[y0:y1, :, :], axis=0)
            val cL = DoubleArray(3)
            val cR = DoubleArray(3)
            for (ch in 0..2) {
                cL[ch] = median(cxRgb[ch].copyOfRange(0, nL))
                cR[ch] = median(cxRgb[ch].copyOfRange(w - nR, w))
            }
            val dC = doubleArrayOf(cR[0] - cL[0], cR[1] - cL[1], cR[2] - cL[2])
            val norm = sqrt(dC[0] * dC[0] + dC[1] * dC[1] + dC[2] * dC[2]) + 1e-12
            val u = doubleArrayOf(dC[0] / norm, dC[1] / norm, dC[2] / norm)

            for (x in 0 until w) {
                val vx = doubleArrayOf(cxRgb[0][x] - cL[0], cxRgb[1][x] - cL[1], cxRgb[2][x] - cL[2])
                s[x] = vx[0] * u[0] + vx[1] * u[1] + vx[2] * u[2]
            }
            t = p * norm
            rising = s[w - 1] > s[0]
        } else {
            // grayscale 기반
            val cg = DoubleArray(w)
            if (cxRgb != null) {
                for (x in 0 until w) {
                    cg[x] = 0.2126 * cxRgb[0][x] + 0.7152 * cxRgb[1][x] + 0.0722 * cxRgb[2][x]
                }
            } else {
                val data = FloatArray(h * w)
                grayLin.get(0, 0, data)
                for (x in 0 until w) {
                    val vals = DoubleArray(y1 - y0)
                    for (yy in y0 until y1) {
                        vals[yy - y0] = data[yy * w + x].toDouble()
                    }
                    cg[x] = median(vals)
                }
            }

            val lRef = median(cg.copyOfRange(0, nL))
            val rRef = median(cg.copyOfRange(w - nR, w))
            val dark = min(lRef, rRef)
            val bright = max(lRef, rRef)
            for (x in 0 until w) s[x] = cg[x]
            t = (1.0 - p) * dark + p * bright
            rising = rRef > lRef
        }

        val sSmooth = smooth1d(s, smoothSigma)
        val xCross = thresholdCrossing1d(sSmooth, t, rising) ?: run {
            grayLin.release()
            return null
        }

        // ✅ Python(find_point_round): subpixel x_cross 유지, y_ref는 정수이지만 float으로 반환
        val xRef = xCross.coerceIn(0.0, (w - 1).toDouble())
        val yRefF = yRef.toDouble().coerceIn(0.0, (h - 1).toDouble())
        grayLin.release()
        return Pt(xRef, yRefF)
    }

    // --------------------------------------------------------------------------------------------
    // (E-2) round_x
    //   - Python: find_point_round_x.py
    //   - 상단 "배경 띠"가 포함된 ROI(예: 1.png / 3.png)에서
    //     배경 영역을 자동으로 제외한 뒤 find_point_curve_x(=curve_x) 방식으로 x(y) 곡선을 찾고
    //     튀어나온(round/protruding) 부분의 x 좌표를 subpixel(double)로 반환합니다.
    // --------------------------------------------------------------------------------------------

    /**
     * Python(find_point_round_x.py)의 `_estimate_top_background_cut_y()` 포팅.
     *
     * - 각 row마다 좌측(start_frac) / 우측(end_frac) 구간의 median을 비교해 contrast를 계산
     * - 상단 배경은 좌/우가 비슷하여 contrast가 작고,
     *   본 영역(파란/검은 분리)은 contrast가 커짐
     * - contrast가 thr 이상인 row가 stable_rows 연속으로 처음 나타나는 y를 반환
     */
    private fun estimateTopBackgroundCutY(
        img: Mat,
        sigma: Double,
        startFrac: Double,
        endFrac: Double,
        assumeSrgb: Boolean,
        contrastRatio: Double,
        minContrast: Double,
        stableRows: Int,
        topSearchRatio: Double,
    ): Int? {
        val h = img.rows()
        val w = img.cols()
        if (h <= 0 || w <= 0) return null

        // gray -> (linear01 or raw01)
        val grayU8 = toGrayFloatU8(img) // 0..255
        var a: DoubleArray = if (assumeSrgb) {
            srgbToLinear01(grayU8)
        } else {
            DoubleArray(grayU8.size) { (grayU8[it] / 255.0f).toDouble().coerceIn(0.0, 1.0) }
        }

        // smoothing along x
        a = smoothAlongX(a, h, w, sigma)

        val sw = max(1, PyMath.roundHalfEvenInt(startFrac * w))
        val ew = max(1, PyMath.roundHalfEvenInt(endFrac * w))

        // Python: search_H = round(H * top_search_ratio) then clamp, and ensure >= stable_rows
        var searchH = PyMath.roundHalfEvenInt(h * topSearchRatio)
        searchH = max(1, min(h, searchH))
        val stable = max(1, stableRows)
        searchH = max(searchH, stable)
        searchH = min(searchH, h)

        val contrast = FloatArray(searchH)
        for (r in 0 until searchH) {
            val off = r * w
            val left = DoubleArray(sw) { a[off + it] }
            val right = DoubleArray(ew) { a[off + (w - ew + it)] }
            val l = median(left)
            val rr = median(right)
            contrast[r] = abs(rr - l).toFloat()
        }

        // Python: c_ref = percentile(c, 95) (linear)
        val cRef = if (contrast.isNotEmpty()) percentileLinear(contrast, 95.0) else 0.0
        val thr = max(minContrast, contrastRatio * cRef)

        // stable run detection (equivalent to np.convolve(mask, ones(stable_rows), 'valid'))
        if (stable <= 1) {
            for (i in 0 until searchH) {
                if (contrast[i].toDouble() >= thr) return i
            }
            return null
        }

        var run = 0
        for (i in 0 until searchH) {
            run = if (contrast[i].toDouble() >= thr) run + 1 else 0
            if (run >= stable) return i - stable + 1
        }
        return null
    }

    /**
     * Python(find_point_curve_x.py)의 assume_srgb 옵션을 round_x에서만 동일하게 쓰기 위해,
     * curve_x 로직을 assumeSrgb 파라미터 포함 버전으로 분리(기존 findCurveX/curveEdgeThresholdAutoX는 영향 없음).
     */
    private fun curveEdgeThresholdAutoXAssume(
        img: Mat,
        p: Double,
        sigma: Double,
        curveSigma: Double,
        startFrac: Double,
        endFrac: Double,
        assumeSrgb: Boolean,
    ): DoubleArray {
        return curveEdgeThresholdAutoX(img, p, sigma, curveSigma, startFrac, endFrac, assumeSrgb)
    }

    private fun findCurveXAssume(
        img: Mat,
        p: Double,
        sigma: Double,
        curveSigma: Double,
        startFrac: Double,
        endFrac: Double,
        assumeSrgb: Boolean,
    ): Pt? {
        val h = img.rows()
        val w = img.cols()
        val curve = curveEdgeThresholdAutoXAssume(img, p, sigma, curveSigma, startFrac, endFrac, assumeSrgb)

        val ys = ArrayList<Int>()
        val xs = ArrayList<Double>()
        for (i in 0 until h) {
            val v = curve[i]
            if (v.isFinite()) { ys.add(i); xs.add(v) }
        }
        if (ys.isEmpty()) return null

        val left = xs.first()
        val right = xs.last()
        val mid = xs[xs.size / 2]

        var bestIdx = 0
        if (mid > left && mid > right) {
            var maxV = -1e18
            for (i in xs.indices) if (xs[i] > maxV) { maxV = xs[i]; bestIdx = i }
        } else {
            var minV = 1e18
            for (i in xs.indices) if (xs[i] < minV) { minV = xs[i]; bestIdx = i }
        }

        // subpixel peak (parabola 3pt)
        var xRef = xs[bestIdx]
        var yRef = ys[bestIdx].toDouble()

        if (bestIdx > 0 && bestIdx < xs.size - 1) {
            val xm1 = xs[bestIdx - 1]
            val x0 = xs[bestIdx]
            val xp1 = xs[bestIdx + 1]
            val denom = (xm1 - 2.0 * x0 + xp1)
            if (abs(denom) > 1e-12) {
                var delta = 0.5 * (xm1 - xp1) / denom
                delta = delta.coerceIn(-1.0, 1.0)

                yRef = ys[bestIdx].toDouble() + delta
                val a = 0.5 * (xm1 + xp1 - 2.0 * x0)
                val b = 0.5 * (xp1 - xm1)
                xRef = a * delta * delta + b * delta + x0
            }
        }

        xRef = xRef.coerceIn(0.0, (w - 1).toDouble())
        yRef = yRef.coerceIn(0.0, (h - 1).toDouble())
        return Pt(xRef, yRef)
    }

    private fun findRoundX(img: Mat, param: Any?): Pt? {
        // ---- curve_x 파라미터 (Python find_point_round_x.py와 동일 키)
        val p = extractDouble(param, "p", 0.5).coerceIn(0.0, 1.0)
        val sigma = extractDouble(param, "sigma", 1.5).coerceAtLeast(0.0)
        val curveSigma = extractDouble(param, "curve_sigma", 2.0).coerceAtLeast(0.0)
        val startFrac = extractDouble(param, "start_frac", 0.10).coerceIn(0.0, 0.49)
        val endFrac = extractDouble(param, "end_frac", 0.10).coerceIn(0.0, 0.49)
        val assumeSrgb = extractBoolean(param, "assume_srgb", true)

        // ---- 상단 배경 제거 파라미터
        val bgEnable = extractBoolean(param, "bg_enable", true)
        val bgMinRows = extractInt(param, "bg_min_rows", 5).coerceAtLeast(0)
        val bgPadDown = extractInt(param, "bg_pad_down", 5).coerceAtLeast(0)
        val bgContrastRatio = extractDouble(param, "bg_contrast_ratio", 0.30).coerceAtLeast(0.0)
        val bgMinContrast = extractDouble(param, "bg_min_contrast", 0.01).coerceAtLeast(0.0)
        val bgStableRows = extractInt(param, "bg_stable_rows", 3).coerceAtLeast(1)
        val bgTopSearchRatio = extractDouble(param, "bg_top_search_ratio", 0.60).coerceIn(0.01, 1.0)

        val h = img.rows()
        val w = img.cols()
        if (h < 3 || w < 3) return null

        // ---- 상단 배경 cut y 추정
        var yCrop = 0
        if (bgEnable) {
            val y0 = estimateTopBackgroundCutY(
                img = img,
                sigma = sigma.coerceIn(0.0, 3.0), // Python: max(0,min(3,sigma))
                startFrac = startFrac,
                endFrac = endFrac,
                assumeSrgb = assumeSrgb,
                contrastRatio = bgContrastRatio,
                minContrast = bgMinContrast,
                stableRows = bgStableRows,
                topSearchRatio = bgTopSearchRatio,
            )
            if (y0 != null && y0 >= bgMinRows) {
                yCrop = min(h - 1, y0 + bgPadDown)
            }
        }

        if (yCrop >= h - 1) return null

        // ---- ROI에서 curve_x 실행
        val roi = img.submat(yCrop, h, 0, w)
        val ptRoi = if (assumeSrgb) {
            // 기존 curve_x 포팅 로직 사용(기존 코드에 영향 없음)
            findCurveX(roi, param)
        } else {
            // assume_srgb=False 대응
            findCurveXAssume(roi, p, sigma, curveSigma, startFrac, endFrac, assumeSrgb = false)
        }
        roi.release()

        ptRoi ?: return null

        val xRef = ptRoi.x.coerceIn(0.0, (w - 1).toDouble())
        val yRef = (ptRoi.y + yCrop.toDouble()).coerceIn(0.0, (h - 1).toDouble())
        return Pt(xRef, yRef)
    }



    // --------------------------------------------------------------------------------------------
    // (F) corner_smooth / corner_sharp
    //     - Python: find_point_corner_smooth.py / find_point_corner_sharp.py
    //     - ROI(method)에 "corner_smooth" 또는 "corner_sharp"가 들어오면 동일한 좌표를 반환하도록 포팅
    // --------------------------------------------------------------------------------------------

    private data class DarkMaskResult(
        val mask: Mat,
        val grayBlur: Mat,
    )

    /**
     * Python(find_point_corner_sharp.py)의 `_build_dark_mask()` 동일 포팅.
     * - grayU8: CV_8UC1
     * - 반환 mask: CV_8UC1 (0/255)
     */
    private fun buildDarkMask(
        grayU8: Mat,
        p01: Double,
        qDark: Double = 2.0,
        qLight: Double = 98.0,
        blurKsize: Int = 5,
        morphKsize: Int = 3,
    ): DarkMaskResult {
        // blur
        val bk = ensureOdd(blurKsize)
        val grayBlur = Mat()
        if (bk <= 1) {
            grayU8.copyTo(grayBlur)
        } else {
            Imgproc.GaussianBlur(grayU8, grayBlur, Size(bk.toDouble(), bk.toDouble()), 0.0)
        }

        // sRGB -> Linear(0~1)
        val lin = srgbToLinear01MatU8(grayBlur)
        val linData = FloatArray(lin.rows() * lin.cols())
        lin.get(0, 0, linData)

        var dark = percentileLinear(linData, qDark)
        var light = percentileLinear(linData, qLight)
        if (light < dark) {
            val tmp = dark
            dark = light
            light = tmp
        }

        // Python: T = (1-p)*dark + p*light
        val T = (1.0 - p01) * dark + p01 * light

        // mask = (lin < T) * 255  (strict '<'로 비교)
        val mask = Mat()
        Core.compare(lin, Scalar(T), mask, Core.CMP_LT)
        lin.release()

        // morphology로 노이즈 제거/연결 보강
        if (morphKsize >= 2) {
            val mk = ensureOdd(morphKsize)
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(mk.toDouble(), mk.toDouble())
            )
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel, Point(-1.0, -1.0), 1)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            kernel.release()
        }

        return DarkMaskResult(mask = mask, grayBlur = grayBlur)
    }

    /**
     * Python(find_point_corner_sharp.py)의 `_contour_corner_by_curvature()` 동일 포팅.
     * - contour에서 "가장 뾰족한" 후보(top_k) 중, 이미지 중심에 가장 가까운 점을 선택
     */
    private fun contourCornerByCurvature(
        cnt: MatOfPoint,
        imgH: Int,
        imgW: Int,
        centerRoi: Double = 0.7,
        kStep: Int = 7,
        topK: Int = 30,
        borderMargin: Int = 1,
    ): Point? {
        val pts = cnt.toArray()
        val n = pts.size
        if (n < 3) return null

        val step = max(1, kStep)
        val angles = DoubleArray(n)

        // 내부각(angle): 작을수록 더 "뾰족"
        for (i in 0 until n) {
            val pPrev = pts[(i - step).floorMod(n)]
            val p0 = pts[i]
            val pNext = pts[(i + step).floorMod(n)]

            val v1x = pPrev.x - p0.x
            val v1y = pPrev.y - p0.y
            val v2x = pNext.x - p0.x
            val v2y = pNext.y - p0.y

            val n1 = hypot(v1x, v1y)
            val n2 = hypot(v2x, v2y)
            if (n1 < 1e-6 || n2 < 1e-6) {
                angles[i] = Math.PI
            } else {
                var cos = (v1x * v2x + v1y * v2y) / (n1 * n2)
                cos = cos.coerceIn(-1.0, 1.0)
                angles[i] = kotlin.math.acos(cos)
            }
        }

        // 중앙 ROI 설정 (가운데쯤 후보만)
        val rw = centerRoi * imgW.toDouble()
        val rh = centerRoi * imgH.toDouble()
        val x0 = (imgW - rw) / 2.0
        val x1 = x0 + rw
        val y0 = (imgH - rh) / 2.0
        val y1 = y0 + rh

        val bm = borderMargin.toDouble()
        val insideIdx = ArrayList<Int>()
        for (i in 0 until n) {
            val x = pts[i].x
            val y = pts[i].y
            val inside =
                (x >= x0 + bm) && (x <= x1 - bm) &&
                        (y >= y0 + bm) && (y <= y1 - bm)
            if (inside) insideIdx.add(i)
        }
        val idx = if (insideIdx.isNotEmpty()) insideIdx else (0 until n).toList()

        // 각도 작은 순으로 top_k 후보
        val sortedIdx = idx.sortedBy { angles[it] }
        val top = sortedIdx.take(min(topK, sortedIdx.size))
        if (top.isEmpty()) return null

        // 그 중에서 "이미지 중심"에 가장 가까운 점 선택
        val cx = imgW / 2.0
        val cy = imgH / 2.0
        var best = top[0]
        var bestDist = hypot(pts[best].x - cx, pts[best].y - cy)
        for (i in top) {
            val d = hypot(pts[i].x - cx, pts[i].y - cy)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return Point(pts[best].x, pts[best].y)
    }

    /** 음수 인덱스도 Python의 modulo wrap-around와 동일하게 처리하기 위한 floorMod. */
    private fun Int.floorMod(mod: Int): Int {
        val r = this % mod
        return if (r < 0) r + mod else r
    }

    /**
     * Python(find_point_corner_sharp) 포팅.
     * - 반환 좌표: crop 내부 subpixel(float) 동일 유지
     */
    private fun findCornerSharp(img: Mat, param: Any?): Pt? {
        if (img.empty()) return null

        val H = img.rows()
        val W = img.cols()

        val p01 = asFloat01(param, defaultValue = 0.5)
        val centerRoi = extractDouble(param, "center_roi", 0.7).coerceIn(0.05, 1.0)
        val kStep = extractInt(param, "k_step", 7).coerceAtLeast(1)
        val topK = extractInt(param, "top_k", 30).coerceAtLeast(1)
        val borderMargin = extractInt(param, "border_margin", 1).coerceAtLeast(0)
        val blurKsize = extractInt(param, "blur_ksize", 5)
        val morphKsize = extractInt(param, "morph_ksize", 3)
        val qDark = extractDouble(param, "q_dark", 2.0)
        val qLight = extractDouble(param, "q_light", 98.0)
        val doSubpixel = when (param) {
            is JSONObject -> param.optBoolean("do_subpixel", true)
            else -> true
        }

        // gray
        val gray = Mat()
        if (img.channels() == 1) img.copyTo(gray) else Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)

        val (mask, grayBlur) = buildDarkMask(
            grayU8 = gray,
            p01 = p01,
            qDark = qDark,
            qLight = qLight,
            blurKsize = blurKsize,
            morphKsize = morphKsize,
        )
        gray.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
        hierarchy.release()
        mask.release()

        if (contours.isEmpty()) {
            grayBlur.release()
            return null
        }

        // cnt = max(contours, key=cv2.contourArea)
        var bestCnt: MatOfPoint? = null
        var bestArea = -1.0
        for (c in contours) {
            val a = Imgproc.contourArea(c)
            if (a > bestArea) {
                bestArea = a
                bestCnt = c
            }
        }
        val cnt = bestCnt
        if (cnt == null) {
            contours.forEach { it.release() }
            grayBlur.release()
            return null
        }

        val initPt = contourCornerByCurvature(
            cnt = cnt,
            imgH = H,
            imgW = W,
            centerRoi = centerRoi,
            kStep = kStep,
            topK = topK,
            borderMargin = borderMargin,
        )

        if (initPt == null) {
            contours.forEach { it.release() }
            grayBlur.release()
            return null
        }

        var tip = Point(initPt.x, initPt.y)

        // 서브픽셀 보정(옵션)
        if (doSubpixel) {
            val corners = MatOfPoint2f(tip)
            val criteria = TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 40, 0.01)
            try {
                Imgproc.cornerSubPix(grayBlur, corners, Size(7.0, 7.0), Size(-1.0, -1.0), criteria)
                corners.toArray().firstOrNull()?.let { tip = it }
            } catch (_: Exception) {
                // cornerSubPix 실패 시 initPt 사용
            } finally {
                corners.release()
            }
        }

        contours.forEach { it.release() }
        grayBlur.release()
        return Pt(tip.x, tip.y)
    }

    /**
     * Python(find_point_corner_smooth) 포팅.
     * - 반환 좌표: crop 내부 subpixel(float) 동일 유지
     */
    private fun findCornerSmooth(img: Mat, param: Any?): Pt? {
        if (img.empty()) return null

        val H = img.rows()
        val W = img.cols()

        val p01 = asFloat01(param, defaultValue = 0.5)
        val epsRatio = extractDouble(param, "eps_ratio", 0.01).coerceAtLeast(1e-6)
        val centerRoi = extractDouble(param, "center_roi", 0.7).coerceIn(0.05, 1.0)
        val blurKsize = extractInt(param, "blur_ksize", 5)
        val morphKsize = extractInt(param, "morph_ksize", 5)
        val doSubpixel = when (param) {
            is JSONObject -> param.optBoolean("do_subpixel", true)
            else -> true
        }

        val gray = Mat()
        if (img.channels() == 1) img.copyTo(gray) else Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)

        val k = ensureOdd(blurKsize)
        val grayBlur = Mat()
        if (k > 1) Imgproc.GaussianBlur(gray, grayBlur, Size(k.toDouble(), k.toDouble()), 0.0) else gray.copyTo(grayBlur)
        gray.release()

        // 1) Otsu로 기준 threshold 계산
        val tmp = Mat()
        val tOtsu = Imgproc.threshold(
            grayBlur,
            tmp,
            0.0,
            255.0,
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU
        )
        tmp.release()

        // 2) p로 threshold를 이동 (p=0.5면 Otsu와 동일)
        val data = ByteArray(H * W)
        grayBlur.get(0, 0, data)
        val vals = FloatArray(data.size) { (data[it].toInt() and 0xFF).toFloat() }
        val lo = percentileLinear(vals, 2.0)
        val hi = percentileLinear(vals, 98.0)
        val span = max(1.0, hi - lo)
        val t = (tOtsu + (p01 - 0.5) * span).coerceIn(0.0, 255.0)

        // 3) 어두운 물체가 흰색(255)이 되도록 INV
        val bw = Mat()
        Imgproc.threshold(grayBlur, bw, t, 255.0, Imgproc.THRESH_BINARY_INV)

        // 마스크가 너무 이상하면(거의 전부 흰색/검정) 뒤집기
        val whiteRatio = Core.countNonZero(bw).toDouble() / (W.toDouble() * H.toDouble())
        if (whiteRatio < 0.01 || whiteRatio > 0.99) {
            Core.bitwise_not(bw, bw)
        }

        // 4) 노이즈 제거(열림/닫힘)
        if (morphKsize >= 2) {
            val mk = ensureOdd(morphKsize)
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(mk.toDouble(), mk.toDouble())
            )
            Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_OPEN, kernel, Point(-1.0, -1.0), 1)
            Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            kernel.release()
        }

        // 5) 외곽선
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(bw, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
        hierarchy.release()

        if (contours.isEmpty()) {
            bw.release()
            grayBlur.release()
            return null
        }

        fun maxAreaContour(cs: List<MatOfPoint>): MatOfPoint? {
            var best: MatOfPoint? = null
            var bestA = -1.0
            for (c in cs) {
                val a = Imgproc.contourArea(c)
                if (a > bestA) {
                    bestA = a
                    best = c
                }
            }
            return best
        }

        var cnt = maxAreaContour(contours)
        if (cnt == null) {
            contours.forEach { it.release() }
            bw.release()
            grayBlur.release()
            return null
        }

        // 혹시 배경이 잡혔으면(면적이 너무 크면) 반전해서 재시도
        // (Python은 findContours가 bw를 in-place로 바꾼 뒤 bw를 뒤집습니다. 동일하게 bw 기준으로 처리)
        if (Imgproc.contourArea(cnt) > 0.95 * (W.toDouble() * H.toDouble())) {
            val bw2 = Mat()
            Core.bitwise_not(bw, bw2)
            val contours2 = ArrayList<MatOfPoint>()
            val hierarchy2 = Mat()
            Imgproc.findContours(bw2, contours2, hierarchy2, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
            hierarchy2.release()
            if (contours2.isNotEmpty()) {
                cnt = maxAreaContour(contours2) ?: cnt
            }
            bw2.release()
            contours2.forEach { it.release() }
        }

        val cnt2f = MatOfPoint2f(*cnt.toArray())
        val peri = Imgproc.arcLength(cnt2f, true)
        if (peri <= 1e-6) {
            cnt2f.release()
            contours.forEach { it.release() }
            bw.release()
            grayBlur.release()
            return null
        }

        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(cnt2f, approx, epsRatio * peri, true)
        cnt2f.release()

        val pts = approx.toArray()
        if (pts.isEmpty()) {
            approx.release()
            contours.forEach { it.release() }
            bw.release()
            grayBlur.release()
            return null
        }

        // 7) 가운데쯤 ROI 안의 꼭지점을 우선
        val roiW = centerRoi * W.toDouble()
        val roiH = centerRoi * H.toDouble()
        val x0 = (W - roiW) / 2.0
        val x1 = x0 + roiW
        val y0 = (H - roiH) / 2.0
        val y1 = y0 + roiH

        val cand = pts.filter { it.x >= x0 && it.x <= x1 && it.y >= y0 && it.y <= y1 }
            .ifEmpty { pts.toList() }

        val cx = W / 2.0
        val cy = H / 2.0
        var best = cand[0]
        var bestDist = hypot(best.x - cx, best.y - cy)
        for (p in cand) {
            val d = hypot(p.x - cx, p.y - cy)
            if (d < bestDist) {
                bestDist = d
                best = p
            }
        }

        var bestPt = Point(best.x, best.y)

        // 8) 서브픽셀 보정
        if (doSubpixel) {
            val corners = MatOfPoint2f(bestPt)
            val criteria = TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 40, 0.01)
            try {
                Imgproc.cornerSubPix(grayBlur, corners, Size(7.0, 7.0), Size(-1.0, -1.0), criteria)
                corners.toArray().firstOrNull()?.let { bestPt = it }
            } catch (_: Exception) {
                // cornerSubPix가 실패해도 원래 좌표 사용
            } finally {
                corners.release()
            }
        }

        approx.release()
        contours.forEach { it.release() }
        bw.release()
        grayBlur.release()

        return Pt(bestPt.x, bestPt.y)
    }

    // --------------------------------------------------------------------------------------------
    // (G) cross (안정화 포팅: LSD + deterministic consensus(TLS+Huber) + optional p-shift)
    // --------------------------------------------------------------------------------------------
    private fun findCross(img: Mat, param: Any?): Pt? {
        val p = extractDouble(param, "p", 0.5).coerceIn(0.0, 1.0)
        val pVertical = p
        val pHorizontal = p

        val method = extractString(param, "method", "lsd")
        val angleWinV = extractDouble(param, "angle_win_v", 40.0)
        val angleWinH = extractDouble(param, "angle_win_h", 12.0)
        val blur = extractDouble(param, "blur", 1.2)
        val magQ = extractDouble(param, "mag_q", 0.75)
        val useCanny = extractBoolean(param, "use_canny", false)
        val cannyLow: Int? = when (param) {
            is JSONObject -> if (param.has("canny_low")) param.optInt("canny_low") else null
            else -> null
        }
        val cannyHigh: Int? = when (param) {
            is JSONObject -> if (param.has("canny_high")) param.optInt("canny_high") else null
            else -> null
        }
        val maxGradPoints = extractInt(param, "max_points", 20000).coerceAtLeast(32)

        val lsdStep = extractDouble(param, "lsd_step", 1.5)
        val lsdLenMin = extractDouble(param, "lsd_len_min", 5.0)
        val segMinContrast = extractDouble(param, "seg_min_contrast", 0.03)
        val segDelta = extractDouble(param, "seg_delta", 2.0)

        val ransacTol = extractDouble(param, "ransac_tol", 2.0)
        val minInliersRatio = extractDouble(param, "min_inliers_ratio", 0.15)
        val exactMaxCandidatePoints = extractInt(param, "exact_max_candidate_points", 120).coerceAtLeast(2)
        val exactMaxEvalPoints = extractInt(param, "exact_max_eval_points", 1500).coerceAtLeast(exactMaxCandidatePoints)

        val doPShift = extractBoolean(param, "do_p_shift", false)
        val pLsamp = extractDouble(param, "p_Lsamp", 12.0)
        val pStep = extractDouble(param, "p_step", 0.5).coerceAtLeast(0.1)
        val pRef = extractString(param, "p_ref", "percentile")
        val qDark = extractDouble(param, "q_dark", 0.10).coerceIn(0.0, 1.0)
        val qBright = extractDouble(param, "q_bright", 0.90).coerceIn(0.0, 1.0)
        val sideLockVertical = extractString(param, "side_lock_vertical", "none")
        val sideLockHorizontal = extractString(param, "side_lock_horizontal", "none")

        val refineLocal = extractBoolean(param, "refine_local", true)
        val refineMaxShift = extractDouble(param, "refine_max_shift", 2.5).coerceAtLeast(0.0)
        val refineKeepMargin = extractDouble(param, "refine_keep_margin", 4.0).coerceAtLeast(0.0)
        val refineAnchorCountV = extractInt(param, "refine_anchor_count_v", 60).coerceAtLeast(12)
        val refineAnchorCountH = extractInt(param, "refine_anchor_count_h", 80).coerceAtLeast(12)
        val refineResidualAbs = extractDouble(param, "refine_residual_abs", 0.35).coerceAtLeast(0.05)
        val refineResidualImprove = extractDouble(param, "refine_residual_improve", 0.90).coerceIn(0.1, 1.0)
        val refineAngleMaxDeg = extractDouble(param, "refine_angle_max_deg", 5.0).coerceAtLeast(0.0)

        // 1) gray linear 0..1
        val h = img.rows()
        val w = img.cols()
        val grayU8 = Mat()
        if (img.channels() == 1) img.copyTo(grayU8) else Imgproc.cvtColor(img, grayU8, Imgproc.COLOR_BGR2GRAY)
        val grayLin = srgbToLinear01MatU8(grayU8)
        grayU8.release()

        fun detectLsdSegments(grayLin32F: Mat): FloatArray? {
            val g8 = Mat()
            grayLin32F.convertTo(g8, CvType.CV_8UC1, 255.0)

            val lsd = try {
                Imgproc.createLineSegmentDetector(Imgproc.LSD_REFINE_STD)
            } catch (_: Throwable) {
                Imgproc.createLineSegmentDetector(1)
            }

            val lines = Mat()
            lsd.detect(g8, lines)
            g8.release()

            if (lines.empty()) {
                lines.release()
                return null
            }

            val out = FloatArray(lines.rows() * 4)
            for (i in 0 until lines.rows()) {
                val v = lines.get(i, 0)
                out[i * 4 + 0] = v[0].toFloat()
                out[i * 4 + 1] = v[1].toFloat()
                out[i * 4 + 2] = v[2].toFloat()
                out[i * 4 + 3] = v[3].toFloat()
            }
            lines.release()
            return out
        }

        fun angleDegOfSegment(x1: Float, y1: Float, x2: Float, y2: Float): Double {
            var th = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()))
            if (th >= 90.0) th -= 180.0
            if (th < -90.0) th += 180.0
            return th
        }

        data class Seg(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

        fun splitSegments(segs: FloatArray, lenMin: Double): Pair<List<Seg>, List<Seg>> {
            val v = ArrayList<Seg>()
            val hList = ArrayList<Seg>()
            val n = segs.size / 4
            for (i in 0 until n) {
                val x1 = segs[i * 4 + 0]
                val y1 = segs[i * 4 + 1]
                val x2 = segs[i * 4 + 2]
                val y2 = segs[i * 4 + 3]
                val L = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
                if (L < lenMin) continue
                val th = angleDegOfSegment(x1, y1, x2, y2)
                if (abs(th) <= angleWinH) hList.add(Seg(x1, y1, x2, y2))
                else if (abs(abs(th) - 90.0) <= angleWinV) v.add(Seg(x1, y1, x2, y2))
            }
            return v to hList
        }

        fun bilinearSample(I: Mat, x: Double, y: Double): Double {
            val hh = I.rows()
            val ww = I.cols()
            if (x < 0 || y < 0 || x > ww - 1 || y > hh - 1) return 0.0
            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val x1 = min(x0 + 1, ww - 1)
            val y1 = min(y0 + 1, hh - 1)
            val dx = x - x0
            val dy = y - y0
            val v00 = I.get(y0, x0)[0]
            val v01 = I.get(y0, x1)[0]
            val v10 = I.get(y1, x0)[0]
            val v11 = I.get(y1, x1)[0]
            return v00 * (1 - dx) * (1 - dy) + v01 * dx * (1 - dy) + v10 * (1 - dx) * dy + v11 * dx * dy
        }

        fun filterSegmentsStepLike(segs: List<Seg>): List<Seg> {
            if (segs.isEmpty() || segMinContrast <= 0) return segs
            val keep = ArrayList<Seg>()
            for (s in segs) {
                val vx = (s.x2 - s.x1).toDouble()
                val vy = (s.y2 - s.y1).toDouble()
                val L = hypot(vx, vy)
                if (L < 1e-5) continue
                val nx = vy / L
                val ny = -vx / L
                var acc = 0.0
                for (t in doubleArrayOf(0.25, 0.5, 0.75)) {
                    val xc = s.x1 + vx * t
                    val yc = s.y1 + vy * t
                    val vp = bilinearSample(grayLin, xc + segDelta * nx, yc + segDelta * ny)
                    val vm = bilinearSample(grayLin, xc - segDelta * nx, yc - segDelta * ny)
                    acc += (vp - vm)
                }
                if (abs(acc / 3.0) >= segMinContrast) keep.add(s)
            }
            return if (keep.isNotEmpty()) keep else segs
        }

        fun samplePointsFromSegments(segs: List<Seg>, step: Double): ArrayList<Pair<Double, Double>> {
            val pts = ArrayList<Pair<Double, Double>>()
            for (s in segs) {
                val L = hypot((s.x2 - s.x1).toDouble(), (s.y2 - s.y1).toDouble())
                val n = max(2, PyMath.roundHalfEvenInt(L / step))
                for (k in 0 until n) {
                    val t = if (n == 1) 0.0 else k.toDouble() / (n - 1).toDouble()
                    val x = s.x1 + (s.x2 - s.x1) * t
                    val y = s.y1 + (s.y2 - s.y1) * t
                    pts.add(x to y)
                }
            }
            return pts
        }

        fun twoPointLine(p1: Pair<Double, Double>, p2: Pair<Double, Double>): DoubleArray? {
            val vx = p2.first - p1.first
            val vy = p2.second - p1.second
            var nx = vy
            var ny = -vx
            val norm = hypot(nx, ny)
            if (norm < 1e-9) return null
            nx /= norm
            ny /= norm
            val c = -(nx * p1.first + ny * p1.second)
            return doubleArrayOf(nx, ny, c)
        }

        fun lineTls(points: List<Pair<Double, Double>>): DoubleArray {
            val n = points.size
            var mx = 0.0
            var my = 0.0
            for (pnt in points) {
                mx += pnt.first
                my += pnt.second
            }
            mx /= max(1, n)
            my /= max(1, n)

            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (pnt in points) {
                val dx = pnt.first - mx
                val dy = pnt.second - my
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }
            sxx /= max(1, n)
            syy /= max(1, n)
            sxy /= max(1, n)

            val tr = sxx + syy
            val det = sxx * syy - sxy * sxy
            val tmp = max(0.0, tr * tr / 4.0 - det)
            val lambda2 = tr / 2.0 - sqrt(tmp)

            val nx: Double
            val ny: Double
            if (abs(sxy) > 1e-12) {
                nx = lambda2 - syy
                ny = sxy
            } else {
                if (sxx < syy) {
                    nx = 1.0
                    ny = 0.0
                } else {
                    nx = 0.0
                    ny = 1.0
                }
            }

            val norm = hypot(nx, ny)
            val nnx = nx / norm
            val nny = ny / norm
            val c = -(nnx * mx + nny * my)
            return doubleArrayOf(nnx, nny, c)
        }

        fun huberIrls(points: List<Pair<Double, Double>>, L: DoubleArray, delta: Double, iters: Int): DoubleArray {
            var nx = L[0]
            var ny = L[1]
            var c = L[2]
            val norm0 = hypot(nx, ny)
            nx /= norm0
            ny /= norm0

            var d = DoubleArray(points.size) { (points[it].first * nx + points[it].second * ny + c) }
            for (iter in 0 until iters) {
                val med = median(d)
                val absDev = DoubleArray(d.size) { abs(d[it] - med) }
                val s = 1.4826 * median(absDev) + 1e-8
                val k = max(delta, 1.345 * s)
                val wts = DoubleArray(d.size) { 1.0 }

                for (i in d.indices) {
                    val ad = abs(d[i])
                    if (ad > k) wts[i] = k / max(ad, 1e-8)
                }

                var wSum = 0.0
                var mx = 0.0
                var my = 0.0
                for (i in points.indices) {
                    val wv = wts[i]
                    wSum += wv
                    mx += wv * points[i].first
                    my += wv * points[i].second
                }
                mx /= (wSum + 1e-9)
                my /= (wSum + 1e-9)

                var sxx = 0.0
                var syy = 0.0
                var sxy = 0.0
                for (i in points.indices) {
                    val wv = wts[i]
                    val dx = points[i].first - mx
                    val dy = points[i].second - my
                    sxx += wv * dx * dx
                    syy += wv * dy * dy
                    sxy += wv * dx * dy
                }
                sxx /= (wSum + 1e-9)
                syy /= (wSum + 1e-9)
                sxy /= (wSum + 1e-9)

                val tr = sxx + syy
                val det = sxx * syy - sxy * sxy
                val tmp = max(0.0, tr * tr / 4.0 - det)
                val lambda2 = tr / 2.0 - sqrt(tmp)

                val nnx: Double
                val nny: Double
                if (abs(sxy) > 1e-12) {
                    nnx = lambda2 - syy
                    nny = sxy
                } else {
                    if (sxx < syy) {
                        nnx = 1.0
                        nny = 0.0
                    } else {
                        nnx = 0.0
                        nny = 1.0
                    }
                }

                val nrm = hypot(nnx, nny)
                nx = nnx / nrm
                ny = nny / nrm
                c = -(nx * mx + ny * my)

                d = DoubleArray(points.size) { points[it].first * nx + points[it].second * ny + c }
            }
            return doubleArrayOf(nx, ny, c)
        }

        fun ransacLine(points: List<Pair<Double, Double>>, iters: Int, tol: Double, minInliersRatio: Double, seed: Long): Pair<DoubleArray, List<Pair<Double, Double>>> {
            if (points.size < 2) return lineTls(points) to points

            val rng = java.util.Random(seed)
            var bestIdx: IntArray? = null
            var bestL: DoubleArray? = null
            val N = points.size

            repeat(iters) {
                val i = rng.nextInt(N)
                var j = rng.nextInt(N)
                while (j == i) j = rng.nextInt(N)

                val L = twoPointLine(points[i], points[j]) ?: return@repeat
                val nx = L[0]
                val ny = L[1]
                val c = L[2]

                val idx = ArrayList<Int>()
                for (k in 0 until N) {
                    val d = abs(points[k].first * nx + points[k].second * ny + c)
                    if (d <= tol) idx.add(k)
                }

                if (bestIdx == null || idx.size > bestIdx!!.size) {
                    bestIdx = idx.toIntArray()
                    bestL = L
                }
            }

            val minInliers = max(2, (minInliersRatio * N).toInt())
            if (bestIdx == null || bestIdx!!.size < minInliers) {
                return lineTls(points) to points
            }

            val inliers = bestIdx!!.map { points[it] }
            val Ltls = lineTls(inliers)
            val Lrls = huberIrls(inliers, Ltls, delta = max(0.8, tol * 0.7), iters = 6)
            return Lrls to inliers
        }

        fun exactConsensusLine(
            points: List<Pair<Double, Double>>,
            tol: Double,
            minInliersRatio: Double,
            maxCandidatePoints: Int,
            maxEvalPoints: Int,
            centerPt: Pair<Double, Double>
        ): Pair<DoubleArray, List<Pair<Double, Double>>> {
            if (points.size < 2) return lineTls(points) to points

            val candidatePts = downsamplePointsEven(points, maxCandidatePoints)
            val evalPts = downsamplePointsEven(points, maxEvalPoints)
            if (candidatePts.size < 2 || evalPts.size < 2) {
                return ransacLine(points, iters = 1200, tol = tol, minInliersRatio = minInliersRatio, seed = 0L)
            }

            var bestL: DoubleArray? = null
            var bestCount = -1
            var bestMedResidual = Double.POSITIVE_INFINITY
            var bestCenterDist = Double.POSITIVE_INFINITY

            for (i in 0 until candidatePts.size - 1) {
                val p1 = candidatePts[i]
                for (j in i + 1 until candidatePts.size) {
                    val p2 = candidatePts[j]
                    val L = twoPointLine(p1, p2) ?: continue

                    val nx = L[0]
                    val ny = L[1]
                    val c = L[2]

                    val inlierResiduals = DoubleArray(evalPts.size)
                    var cnt = 0
                    for (k in evalPts.indices) {
                        val d = abs(evalPts[k].first * nx + evalPts[k].second * ny + c)
                        if (d <= tol) {
                            inlierResiduals[cnt] = d
                            cnt++
                        }
                    }
                    if (cnt == 0) continue
                    if (cnt < bestCount) continue

                    val medResidual = median(inlierResiduals.copyOf(cnt))
                    val centerDist = abs(centerPt.first * nx + centerPt.second * ny + c)

                    val better = when {
                        cnt > bestCount -> true
                        medResidual < bestMedResidual - 1e-9 -> true
                        abs(medResidual - bestMedResidual) <= 1e-9 &&
                            centerDist < bestCenterDist - 1e-9 -> true
                        else -> false
                    }
                    if (!better) continue

                    bestL = L
                    bestCount = cnt
                    bestMedResidual = medResidual
                    bestCenterDist = centerDist
                }
            }

            if (bestL == null) {
                return lineTls(points) to points
            }

            val minInliers = max(2, (minInliersRatio * points.size).toInt())
            val inliers = ArrayList<Pair<Double, Double>>()
            val nx = bestL[0]
            val ny = bestL[1]
            val c = bestL[2]
            for (pt in points) {
                val d = abs(pt.first * nx + pt.second * ny + c)
                if (d <= tol) inliers.add(pt)
            }

            if (inliers.size < minInliers) {
                return lineTls(points) to points
            }

            val Ltls = lineTls(inliers)
            val Lrls = huberIrls(inliers, Ltls, delta = max(0.8, tol * 0.7), iters = 6)
            return Lrls to inliers
        }

        fun offsetLineToP(
            I: Mat,
            L: DoubleArray,
            inliers: List<Pair<Double, Double>>,
            pValue: Double,
            lSamp: Double,
            step: Double,
            refMode: String,
            qDarkValue: Double,
            qBrightValue: Double,
            sideLock: String?
        ): DoubleArray {
            val nx0 = L[0]
            val ny0 = L[1]
            val c0 = L[2]
            val norm = hypot(nx0, ny0)
            val nx = nx0 / norm
            val ny = ny0 / norm

            val deltas = ArrayList<Double>()
            val stride = max(1, inliers.size / 200)

            for (idx in inliers.indices step stride) {
                val x = inliers[idx].first
                val y = inliers[idx].second

                val count = max(2, floor((2.0 * lSamp) / step).toInt() + 1)
                val ts = DoubleArray(count)
                val prof = FloatArray(count)
                for (i in 0 until count) {
                    val t = -lSamp + step * i.toDouble()
                    ts[i] = t
                    prof[i] = bilinearSample(I, x + t * nx, y + t * ny).toFloat()
                }

                val dark: Double
                val bright: Double
                if (refMode == "percentile") {
                    dark = percentileLinear(prof, qDarkValue * 100.0)
                    bright = percentileLinear(prof, qBrightValue * 100.0)
                } else {
                    val k = max(2, (0.2 * prof.size).toInt())
                    val left = DoubleArray(k) { prof[it].toDouble() }
                    val right = DoubleArray(k) { prof[prof.size - k + it].toDouble() }
                    val leftRef = median(left)
                    val rightRef = median(right)
                    dark = min(leftRef, rightRef)
                    bright = max(leftRef, rightRef)
                }

                val target = (1.0 - pValue) * dark + pValue * bright

                if (prof[prof.lastIndex] < prof[0]) {
                    prof.reverse()
                    ts.reverse()
                }

                var tCross: Double? = null
                for (i in 0 until prof.size - 1) {
                    val y0 = prof[i].toDouble()
                    val y1 = prof[i + 1].toDouble()
                    if (y0 < target && y1 >= target) {
                        val frac = if (y1 == y0) 0.0 else (target - y0) / (y1 - y0)
                        tCross = ts[i] + frac * (ts[i + 1] - ts[i])
                        break
                    }
                }

                if (tCross != null) deltas.add(tCross)
            }

            if (deltas.isEmpty()) return L

            var delta = median(deltas.toDoubleArray())
            when (sideLock) {
                "dark" -> delta += -0.25
                "bright" -> delta += +0.25
            }

            return doubleArrayOf(nx, ny, c0 - delta)
        }

        fun intersectLines(L1: DoubleArray, L2: DoubleArray): Pair<Double, Double>? {
            val nx1 = L1[0]
            val ny1 = L1[1]
            val c1 = L1[2]
            val nx2 = L2[0]
            val ny2 = L2[1]
            val c2 = L2[2]
            val det = nx1 * ny2 - nx2 * ny1
            if (abs(det) < 1e-12) return null
            val x = (-c1 * ny2 - (-c2) * ny1) / det
            val y = (nx1 * (-c2) - nx2 * (-c1)) / det
            return x to y
        }

        fun pointLineDistance(L: DoubleArray, x: Double, y: Double): Double {
            return L[0] * x + L[1] * y + L[2]
        }

        fun lineXAtY(L: DoubleArray, y: Double): Double? {
            val nx = L[0]
            if (abs(nx) < 1e-12) return null
            return -(L[1] * y + L[2]) / nx
        }

        fun lineYAtX(L: DoubleArray, x: Double): Double? {
            val ny = L[1]
            if (abs(ny) < 1e-12) return null
            return -(L[0] * x + L[2]) / ny
        }

        fun lineAngleDeg(L: DoubleArray): Double {
            var ang = Math.toDegrees(atan2(L[0], -L[1]))
            if (ang >= 90.0) ang -= 180.0
            if (ang < -90.0) ang += 180.0
            return ang
        }

        fun angleDiffDeg(a: Double, b: Double): Double {
            var d = abs(a - b) % 180.0
            if (d > 90.0) d = 180.0 - d
            return abs(d)
        }

        fun medianResidualToLine(points: List<Pair<Double, Double>>, L: DoubleArray): Double {
            if (points.isEmpty()) return Double.POSITIVE_INFINITY
            val d = DoubleArray(points.size) { idx ->
                abs(pointLineDistance(L, points[idx].first, points[idx].second))
            }
            return median(d)
        }

        fun lineShiftAtPoint(L0: DoubleArray, L1: DoubleArray, pt: Pair<Double, Double>): Double {
            val d0 = pointLineDistance(L0, pt.first, pt.second)
            val d1 = pointLineDistance(L1, pt.first, pt.second)
            return abs(d0 - d1)
        }

        fun sampleAnchorsOnLine(
            L: DoubleArray,
            otherL: DoubleArray?,
            sideSign: Double?,
            margin: Double,
            count: Int
        ): List<Pair<Double, Double>> {
            val out = ArrayList<Pair<Double, Double>>()
            if (count < 2) return out

            if (abs(L[0]) >= abs(L[1])) {
                for (k in 0 until count) {
                    val y = if (count == 1) 0.0 else (h - 1).toDouble() * k.toDouble() / (count - 1).toDouble()
                    val x = lineXAtY(L, y) ?: continue
                    if (x < 1.0 || x > w - 2.0) continue
                    if (otherL != null) {
                        val d = pointLineDistance(otherL, x, y)
                        if (sideSign != null) {
                            if (d * sideSign <= margin) continue
                        } else {
                            if (abs(d) <= margin) continue
                        }
                    }
                    out.add(x to y)
                }
            } else {
                for (k in 0 until count) {
                    val x = if (count == 1) 0.0 else (w - 1).toDouble() * k.toDouble() / (count - 1).toDouble()
                    val y = lineYAtX(L, x) ?: continue
                    if (y < 1.0 || y > h - 2.0) continue
                    if (otherL != null) {
                        val d = pointLineDistance(otherL, x, y)
                        if (sideSign != null) {
                            if (d * sideSign <= margin) continue
                        } else {
                            if (abs(d) <= margin) continue
                        }
                    }
                    out.add(x to y)
                }
            }
            return out
        }

        fun profileCrossingDelta(
            I: Mat,
            x: Double,
            y: Double,
            nx: Double,
            ny: Double,
            pValue: Double,
            lSamp: Double,
            step: Double,
            refMode: String,
            qDarkValue: Double,
            qBrightValue: Double,
            sideLock: String?
        ): Double? {
            val count = max(2, floor((2.0 * lSamp) / step).toInt() + 1)
            val ts = DoubleArray(count)
            val prof = FloatArray(count)
            for (i in 0 until count) {
                val t = -lSamp + step * i.toDouble()
                ts[i] = t
                prof[i] = bilinearSample(I, x + t * nx, y + t * ny).toFloat()
            }

            val dark: Double
            val bright: Double
            if (refMode == "percentile") {
                dark = percentileLinear(prof, qDarkValue * 100.0)
                bright = percentileLinear(prof, qBrightValue * 100.0)
            } else {
                val k = max(2, (0.2 * prof.size).toInt())
                val left = DoubleArray(k) { prof[it].toDouble() }
                val right = DoubleArray(k) { prof[prof.size - k + it].toDouble() }
                val leftRef = median(left)
                val rightRef = median(right)
                dark = min(leftRef, rightRef)
                bright = max(leftRef, rightRef)
            }

            val target = (1.0 - pValue) * dark + pValue * bright
            if (prof[prof.lastIndex] < prof[0]) {
                prof.reverse()
                ts.reverse()
            }

            var tCross: Double? = null
            for (i in 0 until prof.size - 1) {
                val y0 = prof[i].toDouble()
                val y1 = prof[i + 1].toDouble()
                if (y0 < target && y1 >= target) {
                    val frac = if (y1 == y0) 0.0 else (target - y0) / (y1 - y0)
                    tCross = ts[i] + frac * (ts[i + 1] - ts[i])
                    break
                }
            }
            if (tCross == null) return null

            when (sideLock) {
                "dark" -> tCross += -0.25
                "bright" -> tCross += +0.25
            }
            return tCross
        }

        fun fitLineFromProfileAnchors(
            I: Mat,
            baseL: DoubleArray,
            anchors: List<Pair<Double, Double>>,
            pValue: Double,
            lSamp: Double,
            step: Double,
            refMode: String,
            qDarkValue: Double,
            qBrightValue: Double,
            sideLock: String?
        ): Pair<DoubleArray, List<Pair<Double, Double>>>? {
            if (anchors.size < 6) return null

            val norm = hypot(baseL[0], baseL[1])
            val nx = baseL[0] / norm
            val ny = baseL[1] / norm
            val pts = ArrayList<Pair<Double, Double>>()

            for (a in anchors) {
                val delta = profileCrossingDelta(
                    I = I,
                    x = a.first,
                    y = a.second,
                    nx = nx,
                    ny = ny,
                    pValue = pValue,
                    lSamp = lSamp,
                    step = step,
                    refMode = refMode,
                    qDarkValue = qDarkValue,
                    qBrightValue = qBrightValue,
                    sideLock = sideLock
                ) ?: continue
                pts.add((a.first + delta * nx) to (a.second + delta * ny))
            }

            if (pts.size < 6) return null
            val (Lfit, inPts) = exactConsensusLine(
                points = pts,
                tol = max(0.75, ransacTol * 0.75),
                minInliersRatio = 0.50,
                maxCandidatePoints = min(exactMaxCandidatePoints, 80),
                maxEvalPoints = min(exactMaxEvalPoints, 300),
                centerPt = centerPt
            )
            return Lfit to inPts
        }

        fun maybeAcceptRefinedLine(
            baseL: DoubleArray,
            refined: Pair<DoubleArray, List<Pair<Double, Double>>>?,
            pivotPt: Pair<Double, Double>
        ): DoubleArray {
            if (refined == null) return baseL
            val newL = refined.first
            val samplePts = refined.second
            if (samplePts.size < 6) return baseL

            val resBase = medianResidualToLine(samplePts, baseL)
            val resNew = medianResidualToLine(samplePts, newL)
            val shift = lineShiftAtPoint(baseL, newL, pivotPt)
            val angleDiff = angleDiffDeg(lineAngleDeg(baseL), lineAngleDeg(newL))
            val goodResidual = (resNew <= min(refineResidualAbs, resBase * refineResidualImprove)) ||
                (resNew <= refineResidualAbs * 0.8)

            return if (goodResidual && shift <= refineMaxShift && angleDiff <= refineAngleMaxDeg) {
                newL
            } else {
                baseL
            }
        }

        val ptsV: List<Pair<Double, Double>>
        val ptsH: List<Pair<Double, Double>>

        if (method.lowercase() == "lsd") {
            val segs = detectLsdSegments(grayLin)
            if (segs == null) {
                val (pv, ph) = orientedPointsGrad(
                    grayLin,
                    angleWinV,
                    angleWinH,
                    magQ,
                    useCanny,
                    cannyLow,
                    cannyHigh,
                    blur,
                    maxGradPoints
                )
                ptsV = pv
                ptsH = ph
            } else {
                var (vSegs, hSegs) = splitSegments(segs, lsdLenMin)
                vSegs = filterSegmentsStepLike(vSegs)
                hSegs = filterSegmentsStepLike(hSegs)

                var pv = samplePointsFromSegments(vSegs, lsdStep)
                var ph = samplePointsFromSegments(hSegs, lsdStep)

                if (pv.size < 10 || ph.size < 10) {
                    val (pv2, ph2) = orientedPointsGrad(
                        grayLin,
                        angleWinV,
                        angleWinH,
                        magQ,
                        useCanny,
                        cannyLow,
                        cannyHigh,
                        blur,
                        maxGradPoints
                    )
                    if (pv.size < 10) pv = ArrayList(pv2)
                    if (ph.size < 10) ph = ArrayList(ph2)
                }

                ptsV = pv
                ptsH = ph
            }
        } else {
            val (pv, ph) = orientedPointsGrad(
                grayLin,
                angleWinV,
                angleWinH,
                magQ,
                useCanny,
                cannyLow,
                cannyHigh,
                blur,
                maxGradPoints
            )
            ptsV = pv
            ptsH = ph
        }

        if (ptsV.size < 2 || ptsH.size < 2) {
            grayLin.release()
            return null
        }

        val centerPt = ((w - 1) / 2.0) to ((h - 1) / 2.0)
        val (Lv0, inV) = exactConsensusLine(
            points = ptsV,
            tol = ransacTol,
            minInliersRatio = minInliersRatio,
            maxCandidatePoints = exactMaxCandidatePoints,
            maxEvalPoints = exactMaxEvalPoints,
            centerPt = centerPt
        )
        val (Lh0, inH) = exactConsensusLine(
            points = ptsH,
            tol = ransacTol,
            minInliersRatio = minInliersRatio,
            maxCandidatePoints = exactMaxCandidatePoints,
            maxEvalPoints = exactMaxEvalPoints,
            centerPt = centerPt
        )

        val Lv = if (doPShift) {
            offsetLineToP(
                I = grayLin,
                L = Lv0,
                inliers = inV,
                pValue = pVertical,
                lSamp = pLsamp,
                step = pStep,
                refMode = pRef,
                qDarkValue = qDark,
                qBrightValue = qBright,
                sideLock = if (sideLockVertical == "none") null else sideLockVertical
            )
        } else {
            Lv0
        }

        val Lh = if (doPShift) {
            offsetLineToP(
                I = grayLin,
                L = Lh0,
                inliers = inH,
                pValue = pHorizontal,
                lSamp = pLsamp,
                step = pStep,
                refMode = pRef,
                qDarkValue = qDark,
                qBrightValue = qBright,
                sideLock = if (sideLockHorizontal == "none") null else sideLockHorizontal
            )
        } else {
            Lh0
        }

        val pt0 = intersectLines(Lv, Lh)
        if (pt0 == null) {
            grayLin.release()
            return null
        }

        var bestPt = pt0
        if (refineLocal) {
            val vSideDistances = DoubleArray(inV.size) {
                pointLineDistance(Lh, inV[it].first, inV[it].second)
            }
            val vSideSign = if (vSideDistances.isEmpty() || median(vSideDistances) >= 0.0) 1.0 else -1.0

            val refinePVertical = if (doPShift) pVertical else 0.5
            val refinePHorizontal = if (doPShift) pHorizontal else 0.5
            val refineSideLockV = if (doPShift && sideLockVertical != "none") sideLockVertical else null
            val refineSideLockH = if (doPShift && sideLockHorizontal != "none") sideLockHorizontal else null

            val anchorsV = sampleAnchorsOnLine(
                L = Lv,
                otherL = Lh,
                sideSign = vSideSign,
                margin = refineKeepMargin,
                count = refineAnchorCountV
            )
            val anchorsH = sampleAnchorsOnLine(
                L = Lh,
                otherL = Lv,
                sideSign = null,
                margin = refineKeepMargin,
                count = refineAnchorCountH
            )

            val LvRef = maybeAcceptRefinedLine(
                baseL = Lv,
                refined = fitLineFromProfileAnchors(
                    I = grayLin,
                    baseL = Lv,
                    anchors = anchorsV,
                    pValue = refinePVertical,
                    lSamp = pLsamp,
                    step = pStep,
                    refMode = pRef,
                    qDarkValue = qDark,
                    qBrightValue = qBright,
                    sideLock = refineSideLockV
                ),
                pivotPt = pt0
            )
            val LhRef = maybeAcceptRefinedLine(
                baseL = Lh,
                refined = fitLineFromProfileAnchors(
                    I = grayLin,
                    baseL = Lh,
                    anchors = anchorsH,
                    pValue = refinePHorizontal,
                    lSamp = pLsamp,
                    step = pStep,
                    refMode = pRef,
                    qDarkValue = qDark,
                    qBrightValue = qBright,
                    sideLock = refineSideLockH
                ),
                pivotPt = pt0
            )

            val ptRef = intersectLines(LvRef, LhRef)
            if (ptRef != null) {
                val shift = hypot(ptRef.first - pt0.first, ptRef.second - pt0.second)
                if (shift <= refineMaxShift) {
                    bestPt = ptRef
                }
            }
        }

        grayLin.release()
        return Pt(bestPt.first, bestPt.second)
    }

    private fun orientedPointsGrad(
        grayLin32F: Mat,
        angleWinV: Double,
        angleWinH: Double,
        magQ: Double,
        useCanny: Boolean,
        cannyLow: Int?,
        cannyHigh: Int?,
        blur: Double,
        maxPoints: Int
    ): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        val I = if (blur > 0) {
            val tmp = Mat()
            Imgproc.GaussianBlur(grayLin32F, tmp, Size(0.0, 0.0), blur, blur)
            tmp
        } else {
            grayLin32F
        }

        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(I, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(I, gy, CvType.CV_32F, 0, 1, 3)
        if (I !== grayLin32F) I.release()

        val h = gx.rows()
        val w = gx.cols()
        val gxData = FloatArray(h * w)
        val gyData = FloatArray(h * w)
        gx.get(0, 0, gxData)
        gy.get(0, 0, gyData)
        gx.release()
        gy.release()

        val mag = FloatArray(h * w)
        val angT = FloatArray(h * w)
        for (i in mag.indices) {
            val gxx = gxData[i]
            val gyy = gyData[i]
            mag[i] = hypot(gxx.toDouble(), gyy.toDouble()).toFloat()
            var deg = Math.toDegrees(atan2(gyy.toDouble(), gxx.toDouble()) + Math.PI / 2.0)
            deg = ((deg + 90.0) % 180.0) - 90.0
            angT[i] = deg.toFloat()
        }

        val thr = quantile(mag, magQ)
        val base = BooleanArray(h * w) { mag[it] >= thr }

        if (useCanny) {
            val g8 = Mat()
            grayLin32F.convertTo(g8, CvType.CV_8UC1, 255.0)

            val low: Int
            val high: Int
            if (cannyLow != null && cannyHigh != null) {
                low = cannyLow
                high = cannyHigh
            } else {
                val g8Data = ByteArray(h * w)
                g8.get(0, 0, g8Data)
                val vals = DoubleArray(g8Data.size) { (g8Data[it].toInt() and 0xFF).toDouble() }
                val med = median(vals)
                low = max(0, (0.66 * med).toInt())
                high = min(255, (1.33 * med).toInt())
            }

            val ed = Mat()
            Imgproc.Canny(g8, ed, low.toDouble(), high.toDouble(), 3, true)
            val edData = ByteArray(h * w)
            ed.get(0, 0, edData)
            for (i in base.indices) {
                base[i] = base[i] && ((edData[i].toInt() and 0xFF) > 0)
            }
            ed.release()
            g8.release()
        }

        val ptsV = ArrayList<Pair<Double, Double>>()
        val ptsH = ArrayList<Pair<Double, Double>>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!base[idx]) continue
                val a = abs(angT[idx].toDouble())
                if (a <= angleWinH) {
                    ptsH.add(x.toDouble() to y.toDouble())
                } else if (abs(a - 90.0) <= angleWinV) {
                    ptsV.add(x.toDouble() to y.toDouble())
                }
            }
        }

        return downsamplePointsEven(ptsV, maxPoints) to downsamplePointsEven(ptsH, maxPoints)
    }
}
