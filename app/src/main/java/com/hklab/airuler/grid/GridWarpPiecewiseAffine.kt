package com.hklab.airuler.grid

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Grid.json(격자 점)으로부터 구성하는 pixel->mm piecewise affine warp.
 *
 * Python(HkRuler) 구현:
 * - GridWarpPiecewiseAffine.from_point_grid()
 * - scipy.spatial.Delaunay + barycentric
 *
 * Android 구현 목표:
 * - 동일한 입력(Grid.json)에서 가능한 한 동일한 mm 값을 얻도록
 * - 난수/랜덤성 없이 결정적(deterministic)으로 triangulation을 생성
 */
class GridWarpPiecewiseAffine private constructor(
    val pitchMm: Double,
    private val pixX: DoubleArray,
    private val pixY: DoubleArray,
    private val mmX: DoubleArray,
    private val mmY: DoubleArray,
    private val triA: IntArray,
    private val triB: IntArray,
    private val triC: IntArray,
    private val triMinX: DoubleArray,
    private val triMaxX: DoubleArray,
    private val triMinY: DoubleArray,
    private val triMaxY: DoubleArray
) {

    // ✅ (성능) pixelToMm()은 측정 과정에서 다수 호출될 수 있어,
    //    삼각형별 barycentric 계산의 반복 연산을 미리 전개/캐시합니다.
    //    - 출력/로직은 동일(동일한 수식)하되, per-call 연산량만 줄입니다.
    //    - double precision 그대로 사용하므로 정확도 변화는 없습니다.
    private val triAx: DoubleArray = DoubleArray(triA.size)
    private val triAy: DoubleArray = DoubleArray(triA.size)
    private val triV0x: DoubleArray = DoubleArray(triA.size)
    private val triV0y: DoubleArray = DoubleArray(triA.size)
    private val triV1x: DoubleArray = DoubleArray(triA.size)
    private val triV1y: DoubleArray = DoubleArray(triA.size)
    // denom은 그대로 저장하고, u/v 계산은 기존과 동일하게 "division"을 사용합니다.
    // (곱셈으로 바꾸면 부동소수 반올림이 달라질 수 있어, 결과 동일성 관점에서 division 유지)
    private val triDenom: DoubleArray = DoubleArray(triA.size)

    init {
        for (i in triA.indices) {
            val ia = triA[i]
            val ib = triB[i]
            val ic = triC[i]

            val ax = pixX[ia]
            val ay = pixY[ia]
            val v0x = pixX[ib] - ax
            val v0y = pixY[ib] - ay
            val v1x = pixX[ic] - ax
            val v1y = pixY[ic] - ay

            triAx[i] = ax
            triAy[i] = ay
            triV0x[i] = v0x
            triV0y[i] = v0y
            triV1x[i] = v1x
            triV1y[i] = v1y

            val denom = v0x * v1y - v1x * v0y
            triDenom[i] = denom
        }
    }

    val numPoints: Int get() = pixX.size
    val numTriangles: Int get() = triA.size

    /**
     * pixel 좌표를 mm 좌표로 변환
     *
     * @throws IllegalArgumentException if point is outside the convex hull.
     */
    fun pixelToMm(px: Double, py: Double): Pair<Double, Double> {
        val eps = 1e-9

        for (i in triA.indices) {
            if (px < triMinX[i] - eps || px > triMaxX[i] + eps || py < triMinY[i] - eps || py > triMaxY[i] + eps) {
                continue
            }

            // ✅ precomputed barycentric components
            val denom = triDenom[i]
            if (abs(denom) < 1e-18) continue

            val v2x = px - triAx[i]
            val v2y = py - triAy[i]

            // Python _barycentric_weights 기준 (기존과 동일하게 division 사용)
            val u = (v2x * triV1y[i] - triV1x[i] * v2y) / denom
            val v = (triV0x[i] * v2y - v2x * triV0y[i]) / denom
            val w = 1.0 - u - v

            if (w >= -eps && u >= -eps && v >= -eps) {
                val ia = triA[i]
                val ib = triB[i]
                val ic = triC[i]
                val mx = w * mmX[ia] + u * mmX[ib] + v * mmX[ic]
                val my = w * mmY[ia] + u * mmY[ib] + v * mmY[ic]
                return Pair(mx, my)
            }
        }

        throw IllegalArgumentException("Point outside convex hull of grid points")
    }

    companion object {
        private data class XY(val x: Double, val y: Double)

        fun fromGridJsonFile(file: File): GridWarpPiecewiseAffine {
            val root = JSONObject(file.readText())
            val rows = root.optInt("rows", -1)
            val cols = root.optInt("cols", -1)
            val pitch = root.optDouble("pitch_mm", Double.NaN)
            if (rows <= 0 || cols <= 0 || !pitch.isFinite() || pitch <= 0.0) {
                throw IllegalArgumentException("Invalid Grid.json: rows/cols/pitch_mm")
            }

            val pointsArr = root.getJSONArray("points")
            val pix = ArrayList<XY>(rows * cols)
            val mm = ArrayList<XY>(rows * cols)

            for (r in 0 until rows) {
                val rowArr: JSONArray = pointsArr.getJSONArray(r)
                for (c in 0 until cols) {
                    val p = rowArr.getJSONObject(c)
                    val gx = p.getDouble("gx")
                    val gy = p.getDouble("gy")
                    val mx = if (p.has("mx")) p.getDouble("mx") else (c.toDouble() * pitch)
                    val my = if (p.has("my")) p.getDouble("my") else (r.toDouble() * pitch)
                    pix.add(XY(gx, gy))
                    mm.add(XY(mx, my))
                }
            }

            // Python: np.unique(pix_xy, axis=0) (exact duplicates only)
            val uniqPix = ArrayList<XY>(pix.size)
            val uniqMm = ArrayList<XY>(mm.size)
            val seen = HashSet<XY>(pix.size)
            for (i in pix.indices) {
                val p = pix[i]
                if (seen.add(p)) {
                    uniqPix.add(p)
                    uniqMm.add(mm[i])
                }
            }

            if (uniqPix.size < 3) {
                throw IllegalArgumentException("Not enough unique grid points: ${uniqPix.size}")
            }

            val tris = Delaunay.triangulate(uniqPix)
            if (tris.isEmpty()) {
                throw IllegalArgumentException("Delaunay triangulation failed")
            }

            val n = uniqPix.size
            val pixX = DoubleArray(n)
            val pixY = DoubleArray(n)
            val mmX = DoubleArray(n)
            val mmY = DoubleArray(n)

            for (i in 0 until n) {
                pixX[i] = uniqPix[i].x
                pixY[i] = uniqPix[i].y
                mmX[i] = uniqMm[i].x
                mmY[i] = uniqMm[i].y
            }

            val triA = IntArray(tris.size)
            val triB = IntArray(tris.size)
            val triC = IntArray(tris.size)
            val triMinX = DoubleArray(tris.size)
            val triMaxX = DoubleArray(tris.size)
            val triMinY = DoubleArray(tris.size)
            val triMaxY = DoubleArray(tris.size)

            for (i in tris.indices) {
                val t = tris[i]
                val a = t[0]
                val b = t[1]
                val c = t[2]
                triA[i] = a
                triB[i] = b
                triC[i] = c

                val ax = pixX[a]; val ay = pixY[a]
                val bx = pixX[b]; val by = pixY[b]
                val cx = pixX[c]; val cy = pixY[c]
                triMinX[i] = min(ax, min(bx, cx))
                triMaxX[i] = max(ax, max(bx, cx))
                triMinY[i] = min(ay, min(by, cy))
                triMaxY[i] = max(ay, max(by, cy))
            }

            return GridWarpPiecewiseAffine(
                pitchMm = pitch,
                pixX = pixX,
                pixY = pixY,
                mmX = mmX,
                mmY = mmY,
                triA = triA,
                triB = triB,
                triC = triC,
                triMinX = triMinX,
                triMaxX = triMaxX,
                triMinY = triMinY,
                triMaxY = triMaxY
            )
        }
    }

    /**
     * Deterministic Bowyer-Watson incremental Delaunay triangulation.
     */
    private object Delaunay {

        private data class Tri(
            val a: Int,
            val b: Int,
            val c: Int,
            val cx: Double,
            val cy: Double,
            val r2: Double
        )

        private fun edgeKey(u: Int, v: Int): Long {
            val a = min(u, v)
            val b = max(u, v)
            return (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
        }

        private fun orient(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double {
            return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        }

        private fun circumcircle(pa: XY, pb: XY, pc: XY): Triple<Double, Double, Double> {
            val ax = pa.x
            val ay = pa.y
            val bx = pb.x
            val by = pb.y
            val cx = pc.x
            val cy = pc.y

            val d = 2.0 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
            if (abs(d) < 1e-18) {
                // 거의 공선인 경우: 안전한 대체(무한대 회피)
                val mx = (ax + bx + cx) / 3.0
                val my = (ay + by + cy) / 3.0
                val r2 = max(
                    (mx - ax) * (mx - ax) + (my - ay) * (my - ay),
                    max(
                        (mx - bx) * (mx - bx) + (my - by) * (my - by),
                        (mx - cx) * (mx - cx) + (my - cy) * (my - cy)
                    )
                )
                return Triple(mx, my, r2)
            }

            val ax2ay2 = ax * ax + ay * ay
            val bx2by2 = bx * bx + by * by
            val cx2cy2 = cx * cx + cy * cy

            val ux = (ax2ay2 * (by - cy) + bx2by2 * (cy - ay) + cx2cy2 * (ay - by)) / d
            val uy = (ax2ay2 * (cx - bx) + bx2by2 * (ax - cx) + cx2cy2 * (bx - ax)) / d

            val dx = ux - ax
            val dy = uy - ay
            val r2 = dx * dx + dy * dy
            return Triple(ux, uy, r2)
        }

        fun triangulate(inputPts: List<XY>): List<IntArray> {
            val n = inputPts.size
            if (n < 3) return emptyList()

            // --- build super triangle ---
            var minX = inputPts[0].x
            var minY = inputPts[0].y
            var maxX = inputPts[0].x
            var maxY = inputPts[0].y
            for (p in inputPts) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }

            val dx = maxX - minX
            val dy = maxY - minY
            val deltaMax = max(dx, dy)
            val midX = (minX + maxX) / 2.0
            val midY = (minY + maxY) / 2.0

            val p0 = XY(midX - 20.0 * deltaMax, midY - deltaMax)
            val p1 = XY(midX, midY + 20.0 * deltaMax)
            val p2 = XY(midX + 20.0 * deltaMax, midY - deltaMax)

            val pts = ArrayList<XY>(n + 3)
            pts.addAll(inputPts)
            pts.add(p0)
            pts.add(p1)
            pts.add(p2)

            val i0 = n
            val i1 = n + 1
            val i2 = n + 2

            // initial triangle
            val (ccx0, ccy0, r20) = circumcircle(pts[i0], pts[i1], pts[i2])
            var triangles = ArrayList<Tri>(n * 2)
            triangles.add(Tri(i0, i1, i2, ccx0, ccy0, r20))

            // incremental insertion (deterministic: input order)
            for (pi in 0 until n) {
                val p = pts[pi]

                val edgeCounts = HashMap<Long, Int>(64)
                val newTris = ArrayList<Tri>(triangles.size + 8)

                for (t in triangles) {
                    val dxp = p.x - t.cx
                    val dyp = p.y - t.cy
                    val dist2 = dxp * dxp + dyp * dyp

                    if (dist2 <= t.r2 + 1e-9) {
                        // bad triangle
                        val k1 = edgeKey(t.a, t.b)
                        edgeCounts[k1] = (edgeCounts[k1] ?: 0) + 1
                        val k2 = edgeKey(t.b, t.c)
                        edgeCounts[k2] = (edgeCounts[k2] ?: 0) + 1
                        val k3 = edgeKey(t.c, t.a)
                        edgeCounts[k3] = (edgeCounts[k3] ?: 0) + 1
                    } else {
                        newTris.add(t)
                    }
                }

                // boundary edges: count == 1
                val boundary = ArrayList<Long>(edgeCounts.size)
                for ((k, cnt) in edgeCounts) {
                    if (cnt == 1) boundary.add(k)
                }
                boundary.sort()

                for (k in boundary) {
                    val a = (k shr 32).toInt()
                    val b = (k and 0xffffffffL).toInt()

                    // enforce CCW orientation for stability
                    var aa = a
                    var bb = b
                    var cc = pi
                    val o = orient(pts[aa].x, pts[aa].y, pts[bb].x, pts[bb].y, pts[cc].x, pts[cc].y)
                    if (o < 0.0) {
                        val tmp = bb
                        bb = cc
                        cc = tmp
                    }

                    val (ccx, ccy, r2) = circumcircle(pts[aa], pts[bb], pts[cc])
                    newTris.add(Tri(aa, bb, cc, ccx, ccy, r2))
                }

                triangles = newTris
            }

            // remove triangles that contain super triangle vertices
            val out = ArrayList<IntArray>(triangles.size)
            for (t in triangles) {
                if (t.a < n && t.b < n && t.c < n) {
                    out.add(intArrayOf(t.a, t.b, t.c))
                }
            }

            // deterministic ordering
            out.sortWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
            return out
        }
    }
}
