package com.hklab.airuler.film.ruler

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** HkRuler(filmcalib/edges.py)의 Kotlin 포팅본. */
object EdgeUtils {

    // ===== 파라미터(파이썬과 동일 기본값) =====
    private const val GAUSS_BLUR_KSIZE = 1
    private const val CANNY_LO = 100
    private const val CANNY_HI = 220
    private const val USE_L2 = false

    fun ensureGray(img: Mat): Mat {
        return if (img.channels() == 1) img else {
            val gray = Mat()
            Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
            gray
        }
    }

    private fun cannyThresholds(lo: Int, hi: Int, useL2: Boolean): Pair<Double, Double> {
        val scale = if (useL2) 0.7 else 1.0
        return lo * scale to hi * scale
    }

    /**
     * Grayscale -> GaussianBlur -> Canny.
     * (Python 구현과 동일하게 ksize=1 도 허용)
     */
    fun computeEdges(grayOrBgr: Mat): Mat {
        val gray = ensureGray(grayOrBgr)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(GAUSS_BLUR_KSIZE.toDouble(), GAUSS_BLUR_KSIZE.toDouble()), 0.0)
        val (t1, t2) = cannyThresholds(CANNY_LO, CANNY_HI, USE_L2)
        val edges = Mat(gray.rows(), gray.cols(), CvType.CV_8UC1)
        Imgproc.Canny(blur, edges, t1, t2, 3, USE_L2)
        if (gray !== grayOrBgr) gray.release()
        blur.release()
        return edges
    }

    /**
     * filmcalib/edges.py의 find_dense_windows_horizontal.
     * - y축(행) 방향으로만 가장 edge가 밀집된 띠를 찾고, x는 전체.
     */
    fun findDenseWindowsHorizontal(edges: Mat, windowH: Int): IntArray {
        val h = edges.rows()
        val w = edges.cols()
        val win = windowH.coerceAtLeast(1).coerceAtMost(h)

        val data = ByteArray(h * w)
        edges.get(0, 0, data)

        val ySum = IntArray(h)
        var idx = 0
        for (y in 0 until h) {
            var c = 0
            for (x in 0 until w) {
                if (data[idx].toInt() != 0) c++
                idx++
            }
            ySum[y] = c
        }

        // sliding sum (valid)
        var bestStart = 0
        var bestVal = Long.MIN_VALUE
        var cur = 0L
        for (i in 0 until win) cur += ySum[i].toLong()
        bestVal = cur

        for (s in 1..(h - win)) {
            cur += ySum[s + win - 1].toLong() - ySum[s - 1].toLong()
            if (cur > bestVal) {
                bestVal = cur
                bestStart = s
            }
        }
        val yStart = bestStart
        val yEnd = minOf(yStart + win, h - 1) // Python: min(y_start+window_h, h-1)
        return intArrayOf(yStart, yEnd, 0, w - 1)
    }

    /** filmcalib/edges.py의 find_dense_windows_vertical. */
    fun findDenseWindowsVertical(edges: Mat, windowW: Int): IntArray {
        val h = edges.rows()
        val w = edges.cols()
        val win = windowW.coerceAtLeast(1).coerceAtMost(w)

        val data = ByteArray(h * w)
        edges.get(0, 0, data)

        val xSum = IntArray(w)
        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (data[idx].toInt() != 0) xSum[x]++
                idx++
            }
        }

        var bestStart = 0
        var bestVal = Long.MIN_VALUE
        var cur = 0L
        for (i in 0 until win) cur += xSum[i].toLong()
        bestVal = cur

        for (s in 1..(w - win)) {
            cur += xSum[s + win - 1].toLong() - xSum[s - 1].toLong()
            if (cur > bestVal) {
                bestVal = cur
                bestStart = s
            }
        }

        val xStart = bestStart
        val xEnd = minOf(xStart + win, w - 1)
        return intArrayOf(0, h - 1, xStart, xEnd)
    }
}
