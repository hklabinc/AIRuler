package com.hklab.airuler.grid

import android.os.SystemClock
import com.hklab.airuler.film.ruler.PyMath
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Python(HkRuler) core/grid_detector.py 를 Kotlin(OpenCV)로 포팅한 버전.
 * - 목적: Grid 교차점(point grid) 검출 결과가 파이썬과 최대한 동일하게 나오도록 구현
 * - 안정성: 랜덤/triangulation 없음(검출 자체는 완전 deterministic)
 */
class GridDetector(
    // ✅ 기본 파라미터는 Python(HkRuler) core/grid_detector.py 상단 상수와 1:1로 맞춥니다.
    private val tileW: Int = 95,
    private val tileH: Int = 95,
    private val centeringThresholdPx: Double = 7.0,
    private val numNeighbor: Int = 4,
    private val minDistAve: Double = 85.0,
    private val maxDistAve: Double = 100.0,
    private val maxDistStd: Double = 5.0,
    private val maxDistSpan: Double = 15.0,
    private val enableCentering: Boolean = true,
    private val enableAdjust: Boolean = false,
    private val useLabL: Boolean = true,
    private val enableHpPreprocess: Boolean = true,
    private val hpBgSigmaRatio: Double = 0.30,
    private val profileSmoothSigma: Double = 1.2,
    private val profileSmoothRadius: Int = 4,
    private val comWindowRadius: Int = 4,
) {

    data class Point2D(val gx: Double, val gy: Double)

    data class NeighborStats(
        val count: Int,
        val mean: Double,
        val std: Double,
        val histogram: Map<Int, Int>
    )

    data class Timings(
        val buildEnergyMs: Long,
        val markMs: Long,
        val analyzeMs: Long,
        val totalMs: Long
    )

    data class Result(
        val overlay: Mat,
        val pointGrid: List<List<Point2D>>,
        val rowsRaw: Int,
        val colsRaw: Int,
        val validRows: Int,
        val validCols: Int,
        val cellTypeCounts: Map<String, Int>,
        val neighborStats: NeighborStats?,
        val logs: List<String>,
        val timingsMs: Timings
    )

    private data class Cell(
        var gx: Double,
        var gy: Double,
        val row: Int,
        val col: Int,
        val ax0: Int,
        val ay0: Int,
        val bx0: Int,
        val by0: Int,
        var type: String,
        val neighborX: MutableList<Double> = ArrayList(),
        val neighborY: MutableList<Double> = ArrayList(),
        val neighborDist: MutableList<Double> = ArrayList(),
    )

    private val smoothKernel: DoubleArray by lazy {
        gaussianKernel1d(profileSmoothSigma, profileSmoothRadius)
    }

    fun run(imageBgr: Mat, drawOverlay: Boolean = true): Result {
        require(!imageBgr.empty()) { "Input image is empty" }
        require(imageBgr.type() == CvType.CV_8UC3) { "Expected CV_8UC3 (BGR), got type=${imageBgr.type()}" }

        val logs = ArrayList<String>()
        val t0 = SystemClock.elapsedRealtime()

        val tEnergy0 = SystemClock.elapsedRealtime()
        val energy = buildEnergyImage(imageBgr)
        val buildEnergyMs = SystemClock.elapsedRealtime() - tEnergy0

        val tMark0 = SystemClock.elapsedRealtime()
        val gridRowsRaw = markGridCrosses(energy)
        val markMs = SystemClock.elapsedRealtime() - tMark0

        if (gridRowsRaw.isEmpty() || gridRowsRaw.first().isEmpty()) {
            val overlay = if (drawOverlay) imageBgr.clone() else Mat()
            energy.release()
            val totalMs = SystemClock.elapsedRealtime() - t0
            logs.add("GridDetector: no cells found")
            return Result(
                overlay = overlay,
                pointGrid = emptyList(),
                rowsRaw = 0,
                colsRaw = 0,
                validRows = 0,
                validCols = 0,
                cellTypeCounts = emptyMap(),
                neighborStats = null,
                logs = logs,
                timingsMs = Timings(buildEnergyMs, markMs, 0, totalMs)
            )
        }

        // Python: min_cols = min(len(row) ...), then truncate to rectangular
        val minCols = gridRowsRaw.minOf { it.size }
        val gridRows = gridRowsRaw.map { it.subList(0, minCols) }

        val tAnalyze0 = SystemClock.elapsedRealtime()
        analyzeGrid(gridRows)
        val analyzeMs = SystemClock.elapsedRealtime() - tAnalyze0

        val rowsRaw = gridRows.size
        val colsRaw = minCols

        // Cell type counts
        val typeCounts = HashMap<String, Int>()
        for (row in gridRows) {
            for (cell in row) {
                typeCounts[cell.type] = (typeCounts[cell.type] ?: 0) + 1
            }
        }

        logs.add("Grid size (raw): ${rowsRaw}x${colsRaw}, cells=${rowsRaw * colsRaw}")
        logs.add("Cell types: $typeCounts")

        val neighborStats = computeNeighborStats(gridRows)
        if (neighborStats != null) {
            logs.add(
                "Neighbor dists: count=${neighborStats.count}, " +
                    "mean=${String.format("%.2f", neighborStats.mean)}, " +
                    "std=${String.format("%.2f", neighborStats.std)}"
            )
            logs.add("Neighbor hist: ${neighborStats.histogram}")
        } else {
            logs.add("Neighbor dists: none")
        }

        val overlay = if (drawOverlay) imageBgr.clone() else imageBgr.clone()
        if (drawOverlay) {
            drawOverlay(overlay, gridRows)
        }

        // Python: valid_grid = interior cells only (neighbor_dist==NUM_NEIGHBOR)
        val validGrid = ArrayList<List<Cell>>()
        for (row in gridRows) {
            val filtered = row.filter { it.neighborDist.size == numNeighbor }
            if (filtered.isNotEmpty()) validGrid.add(filtered)
        }

        val pointGrid: List<List<Point2D>>
        val validRows: Int
        val validCols: Int
        if (validGrid.isEmpty()) {
            pointGrid = emptyList()
            validRows = 0
            validCols = 0
        } else {
            val minValidCols = validGrid.minOf { it.size }
            val rectGrid = validGrid.map { it.subList(0, minValidCols) }
            validRows = rectGrid.size
            validCols = minValidCols
            pointGrid = rectGrid.map { row -> row.map { Point2D(it.gx, it.gy) } }
        }

        logs.add("Valid point grid: ${validRows}x${validCols}")

        energy.release()
        val totalMs = SystemClock.elapsedRealtime() - t0
        logs.add("Timings(ms): buildEnergy=${buildEnergyMs}, mark=${markMs}, analyze=${analyzeMs}, total=${totalMs}")

        return Result(
            overlay = overlay,
            pointGrid = pointGrid,
            rowsRaw = rowsRaw,
            colsRaw = colsRaw,
            validRows = validRows,
            validCols = validCols,
            cellTypeCounts = typeCounts,
            neighborStats = neighborStats,
            logs = logs,
            timingsMs = Timings(buildEnergyMs, markMs, analyzeMs, totalMs)
        )
    }

    private fun buildEnergyImage(imageBgr: Mat): Mat {
        val gray = Mat()
        if (useLabL) {
            val lab = Mat()
            Imgproc.cvtColor(imageBgr, lab, Imgproc.COLOR_BGR2Lab)
            Core.extractChannel(lab, gray, 0)
            lab.release()
        } else {
            Imgproc.cvtColor(imageBgr, gray, Imgproc.COLOR_BGR2GRAY)
        }

        val inv = Mat()
        Core.bitwise_not(gray, inv)
        gray.release()

        if (!enableHpPreprocess) {
            return inv
        }

        val sigma = max(1.0, tileW.toDouble() * hpBgSigmaRatio)
        val bg = Mat()
        Imgproc.GaussianBlur(inv, bg, Size(0.0, 0.0), sigma, sigma)

        val energy = Mat()
        // Python cv2.subtract on uint8 is saturating (same behavior)
        Core.subtract(inv, bg, energy)

        inv.release()
        bg.release()
        return energy
    }

    private fun markGridCrosses(energy: Mat): List<MutableList<Cell>> {
        val H = energy.rows()
        val W = energy.cols()
        val gridRows: MutableList<MutableList<Cell>> = ArrayList()

        val c0 = detectCell(energy, row = 0, col = 0, ax0 = 0, ay0 = 0) ?: return emptyList()
        val firstRow: MutableList<Cell> = ArrayList()
        firstRow.add(c0)

        // Expand right
        var c = 1
        while (true) {
            val prev = firstRow[c - 1]
            val axNext = prev.bx0 + tileW
            val ayNext = prev.by0
            val cell = detectCell(energy, row = 0, col = c, ax0 = axNext, ay0 = ayNext) ?: break
            firstRow.add(cell)
            c += 1
        }

        gridRows.add(firstRow)

        // Expand downward row-by-row
        var r = 1
        while (true) {
            val prevRow = gridRows.last()
            val newRow: MutableList<Cell> = ArrayList()

            val prev0 = prevRow[0]
            val ax0Next = prev0.bx0
            val ay0Next = prev0.by0 + tileH
            val cell0 = detectCell(energy, row = r, col = 0, ax0 = ax0Next, ay0 = ay0Next) ?: break
            newRow.add(cell0)

            for (cc in 1 until prevRow.size) {
                val left = newRow[cc - 1]
                val axNext = left.bx0 + tileW
                val ayNext = left.by0
                val cell = detectCell(energy, row = r, col = cc, ax0 = axNext, ay0 = ayNext) ?: break
                newRow.add(cell)
            }

            if (newRow.isEmpty()) break
            gridRows.add(newRow)
            r += 1
        }

        return gridRows
    }

    private fun detectCell(energy: Mat, row: Int, col: Int, ax0: Int, ay0: Int): Cell? {
        val H = energy.rows()
        val W = energy.cols()
        if (ax0 + tileW > W || ay0 + tileH > H) return null

        val patch = energy.submat(ay0, ay0 + tileH, ax0, ax0 + tileW)
        val (rx, ry) = findStrongestCrossCenter(patch)
        patch.release()

        val halfW = tileW / 2
        val halfH = tileH / 2

        var type = "ORIGINAL"
        if (enableCentering) {
            if (abs(rx - halfW.toDouble()) > centeringThresholdPx || abs(ry - halfH.toDouble()) > centeringThresholdPx) {
                type = "CENTERED"
            }
        }

        val gx = ax0.toDouble() + rx
        val gy = ay0.toDouble() + ry

        val bx0 = clampTilePos(gx - halfW.toDouble(), tileW, W)
        val by0 = clampTilePos(gy - halfH.toDouble(), tileH, H)

        return Cell(
            gx = gx,
            gy = gy,
            row = row,
            col = col,
            ax0 = ax0,
            ay0 = ay0,
            bx0 = bx0,
            by0 = by0,
            type = type
        )
    }

    private fun findStrongestCrossCenter(patchEnergy: Mat): Pair<Double, Double> {
        val colProfile = sumCols(patchEnergy)
        val rowProfile = sumRows(patchEnergy)

        val colSm = convolveSame(colProfile, smoothKernel)
        val rowSm = convolveSame(rowProfile, smoothKernel)

        val x0 = argmax(colSm)
        val y0 = argmax(rowSm)

        val rx = refineCenter1d(colProfile, x0, comWindowRadius)
        val ry = refineCenter1d(rowProfile, y0, comWindowRadius)
        return Pair(rx, ry)
    }

    private fun sumCols(mat: Mat): DoubleArray {
        val out = Mat()
        Core.reduce(mat, out, 0, Core.REDUCE_SUM, CvType.CV_64F) // 1 x W
        val arr = DoubleArray(out.cols())
        out.get(0, 0, arr)
        out.release()
        return arr
    }

    private fun sumRows(mat: Mat): DoubleArray {
        val out = Mat()
        Core.reduce(mat, out, 1, Core.REDUCE_SUM, CvType.CV_64F) // H x 1
        val arr = DoubleArray(out.rows())
        // Column vector: read one by one (fast enough for small H)
        for (r in 0 until out.rows()) {
            val v = DoubleArray(1)
            out.get(r, 0, v)
            arr[r] = v[0]
        }
        out.release()
        return arr
    }

    private fun gaussianKernel1d(sigma: Double, radius: Int): DoubleArray {
        val size = 2 * radius + 1
        val k = DoubleArray(size)
        val denom = 2.0 * sigma * sigma
        var sum = 0.0
        for (i in -radius..radius) {
            val v = exp(-(i.toDouble() * i.toDouble()) / denom)
            k[i + radius] = v
            sum += v
        }
        if (sum > 0) {
            for (i in k.indices) {
                k[i] /= sum
            }
        }
        return k
    }

    private fun convolveSame(signal: DoubleArray, kernel: DoubleArray): DoubleArray {
        val n = signal.size
        val m = kernel.size
        val radius = m / 2
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (k in 0 until m) {
                val j = i + k - radius
                if (j in 0 until n) {
                    s += signal[j] * kernel[k]
                }
            }
            out[i] = s
        }
        return out
    }

    private fun argmax(arr: DoubleArray): Int {
        var bestIdx = 0
        var bestVal = arr[0]
        for (i in 1 until arr.size) {
            val v = arr[i]
            if (v > bestVal) {
                bestVal = v
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun refineCenter1d(profile: DoubleArray, idx: Int, win: Int): Double {
        val n = profile.size
        val st = max(0, idx - win)
        val en = min(n - 1, idx + win)
        var wSum = 0.0
        var xwSum = 0.0
        for (i in st..en) {
            val w = profile[i]
            wSum += w
            xwSum += i.toDouble() * w
        }
        if (wSum <= 1e-6) return idx.toDouble()
        val refined = xwSum / wSum
        return refined.coerceIn(0.0, (n - 1).toDouble())
    }

    private fun clampTilePos(start: Double, tileSize: Int, maxLen: Int): Int {
        // Python: int(round(x)) where round is bankers(round-half-even)
        val s = PyMath.roundHalfEvenInt(start)
        val maxStart = maxLen - tileSize
        return s.coerceIn(0, maxStart)
    }

    private fun analyzeGrid(grid: List<List<Cell>>) {
        val rows = grid.size
        val cols = grid[0].size

        val dirs = arrayOf(
            intArrayOf(0, -1), // left
            intArrayOf(0, 1), // right
            intArrayOf(-1, 0), // up
            intArrayOf(1, 0), // down
        )

        // fill neighbor lists
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = grid[r][c]
                cell.neighborX.clear()
                cell.neighborY.clear()
                cell.neighborDist.clear()

                for (d in dirs) {
                    val nr = r + d[0]
                    val nc = c + d[1]
                    if (nr in 0 until rows && nc in 0 until cols) {
                        val nb = grid[nr][nc]
                        cell.neighborX.add(nb.gx)
                        cell.neighborY.add(nb.gy)
                        val dx = cell.gx - nb.gx
                        val dy = cell.gy - nb.gy
                        cell.neighborDist.add(sqrt(dx * dx + dy * dy))
                    }
                }
            }
        }

        // adjust
        if (!enableCentering && !enableAdjust) return

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = grid[r][c]
                if (cell.neighborDist.size != numNeighbor) continue

                var isNormal = true
                if (enableAdjust) {
                    val distMean = cell.neighborDist.average()
                    var varSum = 0.0
                    var minD = Double.POSITIVE_INFINITY
                    var maxD = Double.NEGATIVE_INFINITY
                    for (d in cell.neighborDist) {
                        val diff = d - distMean
                        varSum += diff * diff
                        if (d < minD) minD = d
                        if (d > maxD) maxD = d
                    }
                    val distStd = sqrt(varSum / cell.neighborDist.size)
                    val distSpan = maxD - minD
                    if (distMean < minDistAve || distMean > maxDistAve || distStd > maxDistStd || distSpan > maxDistSpan) {
                        isNormal = false
                    }
                }

                val needAdjust = (cell.type == "CENTERED") || (!isNormal)
                if (!needAdjust) continue

                val newGx = cell.neighborX.average()
                val newGy = cell.neighborY.average()
                if (abs(newGx - cell.gx) > tileW.toDouble() * 0.8) continue
                if (abs(newGy - cell.gy) > tileH.toDouble() * 0.8) continue

                cell.gx = newGx
                cell.gy = newGy
                if (cell.type != "CENTERED") {
                    cell.type = "ADJUSTED"
                }
            }
        }
    }

    private fun computeNeighborStats(grid: List<List<Cell>>): NeighborStats? {
        // ✅ Python 구현과 동일하게 "right + down" 거리만 통계에 포함
        val rows = grid.size
        val cols = grid[0].size
        val dists = ArrayList<Double>(rows * cols * 2)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = grid[r][c]
                if (cell.neighborDist.size != numNeighbor) continue

                // right
                if (c + 1 < cols) {
                    val nb = grid[r][c + 1]
                    val dx = nb.gx - cell.gx
                    val dy = nb.gy - cell.gy
                    dists.add(sqrt(dx * dx + dy * dy))
                }
                // down
                if (r + 1 < rows) {
                    val nb = grid[r + 1][c]
                    val dx = nb.gx - cell.gx
                    val dy = nb.gy - cell.gy
                    dists.add(sqrt(dx * dx + dy * dy))
                }
            }
        }
        if (dists.isEmpty()) return null
        val mean = dists.average()
        var varSum = 0.0
        for (d in dists) {
            val diff = d - mean
            varSum += diff * diff
        }
        val std = sqrt(varSum / dists.size)

        val hist = java.util.TreeMap<Int, Int>()
        for (d in dists) {
            val key = PyMath.roundHalfEvenInt(d)
            hist[key] = (hist[key] ?: 0) + 1
        }
        return NeighborStats(
            count = dists.size,
            mean = mean,
            std = std,
            histogram = hist
        )
    }

    private fun drawOverlay(overlay: Mat, grid: List<List<Cell>>) {
        val H = overlay.rows()
        val W = overlay.cols()

        // OpenCV(Mat) 타입이 CV_8UC3(=16) 이므로 put()에는 byte[] 를 사용해야 합니다.
        // (float[]/double[] 로 넣으면 "Mat data type is not compatible: 16" 런타임 에러가 발생)
        val redBgr = byteArrayOf(0.toByte(), 0.toByte(), 255.toByte())

        // ✅ Python drawOverlay 색상/두께 규칙과 최대한 동일하게 맞춤
        val green = Scalar(0.0, 255.0, 0.0)   // BGR
        val red = Scalar(0.0, 0.0, 255.0)
        val blue = Scalar(255.0, 0.0, 0.0)
        val yellow = Scalar(0.0, 255.0, 255.0)

        for (row in grid) {
            for (cell in row) {
                val ix = PyMath.roundHalfEvenInt(cell.gx)
                val iy = PyMath.roundHalfEvenInt(cell.gy)
                if (ix in 0 until W && iy in 0 until H) {
                    // BGR = red
                    overlay.put(iy, ix, redBgr)
                }

                when (cell.type) {
                    "ORIGINAL" -> {
                        Imgproc.rectangle(
                            overlay,
                            Point(cell.ax0.toDouble(), cell.ay0.toDouble()),
                            Point((cell.ax0 + tileW).toDouble(), (cell.ay0 + tileH).toDouble()),
                            green,
                            1
                        )
                    }
                    "CENTERED" -> {
                        Imgproc.rectangle(
                            overlay,
                            Point(cell.ax0.toDouble(), cell.ay0.toDouble()),
                            Point((cell.ax0 + tileW).toDouble(), (cell.ay0 + tileH).toDouble()),
                            red,
                            2
                        )
                        Imgproc.rectangle(
                            overlay,
                            Point(cell.bx0.toDouble(), cell.by0.toDouble()),
                            Point((cell.bx0 + tileW).toDouble(), (cell.by0 + tileH).toDouble()),
                            blue,
                            1
                        )
                    }
                    else -> {
                        // ADJUSTED 등
                        Imgproc.rectangle(
                            overlay,
                            Point(cell.ax0.toDouble(), cell.ay0.toDouble()),
                            Point((cell.ax0 + tileW).toDouble(), (cell.ay0 + tileH).toDouble()),
                            yellow,
                            1
                        )
                    }
                }
            }
        }
    }
}
