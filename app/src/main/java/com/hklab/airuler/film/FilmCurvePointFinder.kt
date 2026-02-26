package com.hklab.airuler.film

import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * Python(find_points_curve.py)의 find_points_curve()를 OpenCV(Kotlin)로 포팅.
 *
 * ROI crop 이미지에서 "두 색상(대략 2클러스터)" 사이 경계 curve를 찾아
 * curve 라인 위의 (x,y) 포인트들을 반환합니다.
 *
 * - Lab 색공간에서 k=2 K-means로 대표 색 2개(center) 추정
 * - (어두운 center -> 밝은 center) 방향으로 각 픽셀을 0~1 스칼라(t)로 투영
 * - p(0~1) 값으로 mask(t>=p) 생성
 * - mask의 경계(edge)만 추출
 * - 가장 큰 연결성분만 남겨 노이즈 제거
 * - 가장 큰 contour를 따라 (x,y) 점 리스트로 반환
 */
object FilmCurvePointFinder {

    data class Params(
        val p: Double = 0.5,
        val preBlur: Int = 5,
        val tBlur: Int = 5,
        val sampleMax: Int = 20000,
        val attempts: Int = 5,
        val seed: Int = 1234,
    )

    private fun ensureOdd(k: Int): Int {
        if (k <= 0) return 0
        return if (k % 2 == 1) k else (k + 1)
    }

    private fun parseParams(parameter: Any?): Params {
        // Python(find_points_curve.py) 호출부는 find_points_curve(crop, p) 형태가 대부분
        // -> parameter가 number면 p로만 취급
        return when (parameter) {
            is Number -> Params(p = parameter.toDouble())
            is JSONObject -> {
                val p = when (val v = parameter.opt("p")) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                } ?: 0.5

                val pre = parameter.optInt("pre_blur", 5)
                val tb = parameter.optInt("t_blur", 5)
                val sampleMax = parameter.optInt("sample_max", 20000)
                val attempts = parameter.optInt("attempts", 5)
                val seed = parameter.optInt("seed", 1234)

                Params(
                    p = p.coerceIn(0.0, 1.0),
                    preBlur = pre,
                    tBlur = tb,
                    sampleMax = max(1, sampleMax),
                    attempts = max(1, attempts),
                    seed = seed,
                )
            }
            else -> Params()
        }
    }

    /**
     * @return crop 좌표계 기준 curve 라인 위의 점 리스트 (x,y) (정수 픽셀)
     * @throws IllegalArgumentException if input is empty
     */
    fun findCurvePoints(cropBgrOrGray: Mat, parameter: Any?): List<Pair<Int, Int>> {
        if (cropBgrOrGray.empty()) throw IllegalArgumentException("Empty image")

        val prm = parseParams(parameter)

        // gray 입력이면 BGR로 변환
        val bgr = Mat()
        var needReleaseBgr = true
        when (cropBgrOrGray.channels()) {
            1 -> Imgproc.cvtColor(cropBgrOrGray, bgr, Imgproc.COLOR_GRAY2BGR)
            3 -> {
                cropBgrOrGray.copyTo(bgr)
            }
            4 -> Imgproc.cvtColor(cropBgrOrGray, bgr, Imgproc.COLOR_RGBA2BGR)
            else -> {
                // 예외 케이스: 채널 수가 이상하면 일단 복사
                cropBgrOrGray.copyTo(bgr)
            }
        }

        try {
            val H = bgr.rows()
            val W = bgr.cols()
            if (H <= 0 || W <= 0) throw IllegalArgumentException("Empty image")

            // Lab 변환
            val lab = Mat()
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)

            // pre blur
            val kPre = ensureOdd(prm.preBlur)
            val labS = if (kPre >= 3) {
                val tmp = Mat()
                Imgproc.GaussianBlur(lab, tmp, Size(kPre.toDouble(), kPre.toDouble()), 0.0)
                lab.release()
                tmp
            } else {
                // keep lab
                lab
            }

            // float32
            val lab32 = Mat()
            labS.convertTo(lab32, CvType.CV_32F)
            // lab / tmp 모두 여기서 해제
            labS.release()

            val n = H * W
            val labReshaped = lab32.reshape(1, n) // n x 3, CV_32F

            val all = FloatArray(n * 3)
            labReshaped.get(0, 0, all)

            // sample for kmeans
            val sampleCount = min(n, prm.sampleMax)
            val data: Mat = if (sampleCount >= n) {
                labReshaped
            } else {
                val idx = reservoirSampleIndices(n, sampleCount, prm.seed.toLong())
                val sampleArr = FloatArray(sampleCount * 3)
                for (i in 0 until sampleCount) {
                    val si = idx[i]
                    val src = si * 3
                    val dst = i * 3
                    sampleArr[dst] = all[src]
                    sampleArr[dst + 1] = all[src + 1]
                    sampleArr[dst + 2] = all[src + 2]
                }
                Mat(sampleCount, 3, CvType.CV_32F).also { it.put(0, 0, sampleArr) }
            }

            // kmeans (k=2)
            val labels = Mat()
            val centers = Mat()
            val criteria = TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 60, 0.2)

            // Python 버전은 seed를 sampling에만 쓰지만, Android에서는 재현성을 위해 kmeans RNG도 고정
            Core.setRNGSeed(prm.seed.toInt())

            Core.kmeans(
                data,
                2,
                labels,
                criteria,
                max(1, prm.attempts),
                Core.KMEANS_PP_CENTERS,
                centers
            )

            // sampling Mat은 여기서만 생성했을 수 있음
            if (data !== labReshaped) {
                data.release()
            }
            labels.release()

            val c = FloatArray(6)
            centers.get(0, 0, c)
            centers.release()

            // dark/bright 결정 (L 채널)
            val dark0: Float
            val dark1: Float
            val dark2: Float
            val bright0: Float
            val bright1: Float
            val bright2: Float
            if (c[0] <= c[3]) {
                dark0 = c[0]; dark1 = c[1]; dark2 = c[2]
                bright0 = c[3]; bright1 = c[4]; bright2 = c[5]
            } else {
                dark0 = c[3]; dark1 = c[4]; dark2 = c[5]
                bright0 = c[0]; bright1 = c[1]; bright2 = c[2]
            }

            val v0 = bright0 - dark0
            val v1 = bright1 - dark1
            val v2 = bright2 - dark2
            val denom = (v0 * v0 + v1 * v1 + v2 * v2).toDouble()

            // t projection (0..1)
            val tArr = FloatArray(n)
            if (denom > 1e-8) {
                for (i in 0 until n) {
                    val off = i * 3
                    val p0 = all[off] - dark0
                    val p1 = all[off + 1] - dark1
                    val p2 = all[off + 2] - dark2
                    val tt = ((p0 * v0 + p1 * v1 + p2 * v2).toDouble() / denom)
                    val tc = tt.coerceIn(0.0, 1.0)
                    tArr[i] = tc.toFloat()
                }
            } else {
                // 두 색이 거의 같으면 전부 0
                // (tArr default 0.0)
            }

            // t blur
            val tMat = Mat(H, W, CvType.CV_32F)
            tMat.put(0, 0, tArr)

            val kT = ensureOdd(prm.tBlur)
            val tS = if (kT >= 3) {
                val tmp = Mat()
                Imgproc.GaussianBlur(tMat, tmp, Size(kT.toDouble(), kT.toDouble()), 0.0)
                tMat.release()
                tmp
            } else {
                tMat
            }

            // mask: t >= p
            val mask = Mat()
            Core.compare(tS, Scalar(prm.p), mask, Core.CMP_GE) // 0/255
            tS.release()

            // edge extraction (neighbor label change)
            val edge = Mat.zeros(H, W, CvType.CV_8U)
            if (W > 1) {
                val left = mask.colRange(0, W - 1)
                val right = mask.colRange(1, W)
                val diffH = Mat()
                Core.absdiff(right, left, diffH) // 0/255
                val dst = edge.colRange(1, W)
                Core.bitwise_or(dst, diffH, dst)
                diffH.release()
            }
            if (H > 1) {
                val top = mask.rowRange(0, H - 1)
                val bottom = mask.rowRange(1, H)
                val diffV = Mat()
                Core.absdiff(bottom, top, diffV)
                val dst = edge.rowRange(1, H)
                Core.bitwise_or(dst, diffV, dst)
                diffV.release()
            }
            mask.release()

            val edgeLargest = keepLargestConnectedComponent(edge)
            edge.release()

            val pts = orderedPointsFromEdge(edgeLargest)
            edgeLargest.release()
            lab32.release()

            return pts
        } finally {
            if (needReleaseBgr) {
                bgr.release()
            }
        }
    }

    private fun reservoirSampleIndices(n: Int, k: Int, seed: Long): IntArray {
        // Reservoir sampling: O(n) without allocating n-sized list
        val out = IntArray(k)
        for (i in 0 until k) out[i] = i
        val rnd = Random(seed)
        for (i in k until n) {
            val j = rnd.nextInt(i + 1)
            if (j < k) out[j] = i
        }
        return out
    }

    private fun keepLargestConnectedComponent(edge01: Mat): Mat {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val nLabels = Imgproc.connectedComponentsWithStats(
            edge01,
            labels,
            stats,
            centroids,
            8,
            CvType.CV_32S
        )

        centroids.release()

        if (nLabels <= 1) {
            stats.release()
            labels.release()
            return edge01.clone()
        }

        var bestLabel = 1
        var bestArea = -1
        for (label in 1 until nLabels) {
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
            if (area > bestArea) {
                bestArea = area
                bestLabel = label
            }
        }
        stats.release()

        val out = Mat()
        Core.compare(labels, Scalar(bestLabel.toDouble()), out, Core.CMP_EQ) // 0/255
        labels.release()
        return out
    }

    private fun orderedPointsFromEdge(edge01: Mat): List<Pair<Int, Int>> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val edgeCopy = edge01.clone() // findContours modifies input
        Imgproc.findContours(edgeCopy, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
        edgeCopy.release()
        hierarchy.release()

        try {
            if (contours.isEmpty()) {
                // fallback: 모든 edge 픽셀을 (x,y)로 수집 후 (x,y) 정렬
                val nz = MatOfPoint()
                Core.findNonZero(edge01, nz)
                val arr = nz.toArray()
                nz.release()
                val sorted = arr.sortedWith(compareBy<Point> { it.x }.thenBy { it.y })
                val seen = HashSet<Long>(sorted.size * 2)
                val out = ArrayList<Pair<Int, Int>>(sorted.size)
                for (p in sorted) {
                    val x = p.x.toInt()
                    val y = p.y.toInt()
                    val key = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
                    if (seen.add(key)) out.add(x to y)
                }
                return out
            }

            // largest contour by point count
            var best: MatOfPoint? = null
            var bestLen = -1
            for (c in contours) {
                val len = c.toArray().size
                if (len > bestLen) {
                    bestLen = len
                    best = c
                }
            }

            val arr = best?.toArray().orEmpty()
            val seen = HashSet<Long>(arr.size * 2)
            val out = ArrayList<Pair<Int, Int>>(arr.size)
            for (p in arr) {
                val x = p.x.toInt()
                val y = p.y.toInt()
                val key = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
                if (seen.add(key)) out.add(x to y)
            }
            return out
        } finally {
            for (c in contours) runCatching { c.release() }
        }
    }
}
