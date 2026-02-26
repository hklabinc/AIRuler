package com.hklab.airuler.inspection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs

/**
 * - Motion: 전체 프레임을 diffW x diffH로 다운샘플 후 gray absdiff 픽셀 카운트로 판단
 * - Hand : 하단 중앙 ROI에서 "어두운 픽셀 비율(darkRatio)"로 판단
 *
 * (중요) 기존 whiteRatio 기반은 배경이 완전한 흰색이 아닐 경우 항상 hand=true가 될 수 있어
 *        darkRatio 방식으로 바꿔 안정화.
 */
class MotionHandDetector(
    // Motion downsample size
    private val diffW: Int = 64,
    private val diffH: Int = 48,

    // Motion thresholding
    private val diffPixel: Int = 32,        // abs(gray-cur - gray-prev) >= diffPixel
    private val motionThreshold: Int = 1,   // score >= motionThreshold => motionExists

    // Hand ROI (bottom center)  ※ python main_detector의 ROI와 동일 계열
    private val handRoiX1Ratio: Float = 0.25f,
    private val handRoiX2Ratio: Float = 0.75f,
    private val handRoiY1Ratio: Float = 0.80f,
    private val handRoiY2Ratio: Float = 0.90f,

    // Hand detection = "dark pixel ratio" in ROI
    private val darkPixelThresh: Int = 110,     // gray < 110 을 "어두운 픽셀"로 간주
    private val darkRatioThresh: Double = 0.05, // darkRatio >= 0.05 => handExists
    private val handSampleStride: Int = 2       // ROI 샘플링 stride (2~4 추천)
) {

    data class Result(
        val motionScore: Int,
        val motionExists: Boolean,
        val motionBoxPx: Rect?,
        val motionBox01: RectF?,

        /** handScore = darkRatio (0~1) */
        val handScore: Float,
        val handExists: Boolean,

        /** hand ROI (px / norm) */
        val roiPx: Rect,
        val roi01: RectF,

        /**
         * ✅ MainActivity의 maybeUpdateDebugOverlay()에서 쓰는 이름과 맞추기 위한 alias 필드들
         * - handRoi01: hand ROI normalized
         * - handBox01: ROI 내 dark 픽셀들의 bounding box normalized (디버그용)
         */
        val handRoi01: RectF,
        val handBoxPx: Rect? = null,
        val handBox01: RectF? = null
    )

    // ----- Motion internal buffers (reused) -----
    private var smallBitmap: Bitmap? = null
    private var smallCanvas: Canvas? = null
    private val smallDstRect: Rect by lazy { Rect(0, 0, diffW, diffH) }

    private var smallPixels: IntArray = IntArray(0)

    private var prevGray: IntArray? = null
    private var curGray: IntArray? = null
    private var hasPrev: Boolean = false

    // ----- Hand ROI buffer (reused) -----
    private var handRoiPixels: IntArray = IntArray(0)

    fun reset() {
        hasPrev = false
        prevGray = null
        curGray = null
        smallPixels = IntArray(0)

        smallBitmap?.recycle()
        smallBitmap = null
        smallCanvas = null

        handRoiPixels = IntArray(0)
    }

    fun analyze(frame: Bitmap): Result {
        val w = frame.width
        val h = frame.height

        val roiPx = makeHandRoi(w, h)
        val roi01 = RectF(
            roiPx.left.toFloat() / w,
            roiPx.top.toFloat() / h,
            roiPx.right.toFloat() / w,
            roiPx.bottom.toFloat() / h
        )

        val hand = computeHandDarkRatio(frame, roiPx)
        val motion = computeMotion(frame, w, h)

        // ✅ 디버그용: dark bounding box를 normalized로도 제공 (MainActivity에서 바로 쓰게)
        val handBox01 = hand.boxPx?.let { b ->
            RectF(
                b.left.toFloat() / w,
                b.top.toFloat() / h,
                b.right.toFloat() / w,
                b.bottom.toFloat() / h
            )
        }

        return Result(
            motionScore = motion.score,
            motionExists = motion.exists,
            motionBoxPx = motion.boxPx,
            motionBox01 = motion.box01,

            // ✅ setHandDebug()가 Float를 기대하면 여기서 Float로 맞추는 게 가장 깔끔함
            handScore = hand.darkRatio.toFloat(),
            handExists = hand.exists,

            roiPx = roiPx,
            roi01 = roi01,

            // ✅ MainActivity에서 쓰는 이름 그대로 제공
            handRoi01 = roi01,
            handBoxPx = hand.boxPx,
            handBox01 = handBox01
        )
    }

    // ---------------- Hand ----------------

    private fun makeHandRoi(w: Int, h: Int): Rect {
        val x1 = (w * handRoiX1Ratio).toInt().coerceIn(0, w - 1)
        val x2 = (w * handRoiX2Ratio).toInt().coerceIn(x1 + 1, w)
        val y1 = (h * handRoiY1Ratio).toInt().coerceIn(0, h - 1)
        val y2 = (h * handRoiY2Ratio).toInt().coerceIn(y1 + 1, h)
        return Rect(x1, y1, x2, y2)
    }

    private data class HandDarkResult(
        val darkRatio: Double,
        val exists: Boolean,
        val boxPx: Rect?
    )

    private fun computeHandDarkRatio(frame: Bitmap, roi: Rect): HandDarkResult {
        val roiW = roi.width()
        val roiH = roi.height()
        if (roiW <= 1 || roiH <= 1) return HandDarkResult(0.0, false, null)

        val totalPx = roiW * roiH
        if (handRoiPixels.size < totalPx) {
            handRoiPixels = IntArray(totalPx)
        }

        // ROI를 한번에 읽고, 내부에서 stride로 샘플링
        frame.getPixels(handRoiPixels, 0, roiW, roi.left, roi.top, roiW, roiH)

        val stride = handSampleStride.coerceAtLeast(1)

        var total = 0
        var dark = 0

        var minX = roiW
        var minY = roiH
        var maxX = -1
        var maxY = -1

        for (y in 0 until roiH step stride) {
            val row = y * roiW
            for (x in 0 until roiW step stride) {
                val p = handRoiPixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val gray = (r * 30 + g * 59 + b * 11) / 100  // 빠른 그레이 근사

                if (gray < darkPixelThresh) {
                    dark++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
                total++
            }
        }

        if (total <= 0) return HandDarkResult(0.0, false, null)

        val darkRatio = dark.toDouble() / total.toDouble()
        val exists = darkRatio >= darkRatioThresh

        // ✅ “exists 여부와 무관하게” dark 픽셀 bounding box는 디버그에 유용하므로 항상 계산
        val box = if (maxX >= minX && maxY >= minY) {
            Rect(
                roi.left + minX,
                roi.top + minY,
                roi.left + maxX + 1,
                roi.top + maxY + 1
            )
        } else null

        return HandDarkResult(darkRatio, exists, box)
    }

    // ---------------- Motion ----------------

    private data class MotionResult(
        val score: Int,
        val exists: Boolean,
        val boxPx: Rect?,
        val box01: RectF?
    )

    private fun computeMotion(frame: Bitmap, w: Int, h: Int): MotionResult {
        val need = diffW * diffH

        // small bitmap/canvas 준비 (재사용)
        if (smallBitmap == null || smallBitmap!!.width != diffW || smallBitmap!!.height != diffH) {
            smallBitmap?.recycle()
            smallBitmap = Bitmap.createBitmap(diffW, diffH, Bitmap.Config.ARGB_8888)
            smallCanvas = Canvas(smallBitmap!!)
        }

        val sb = smallBitmap!!
        val canvas = smallCanvas!!

        // frame -> smallBitmap 으로 스케일 드로우 (alloc 없음)
        canvas.drawBitmap(frame, null, smallDstRect, null)

        if (smallPixels.size != need) smallPixels = IntArray(need)
        sb.getPixels(smallPixels, 0, diffW, 0, 0, diffW, diffH)

        if (curGray == null || curGray!!.size != need) curGray = IntArray(need)
        if (prevGray == null || prevGray!!.size != need) prevGray = IntArray(need)

        val cur = curGray!!
        val prev = prevGray!!

        // grayscale
        for (i in 0 until need) {
            val c = smallPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            cur[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        // 첫 프레임: prev 없음 → motion 계산 불가
        if (!hasPrev) {
            hasPrev = true
            // prevGray가 "이번 프레임"을 들고 있도록 swap
            curGray = prev
            prevGray = cur
            return MotionResult(0, false, null, null)
        }

        var score = 0
        var minX = diffW
        var minY = diffH
        var maxX = -1
        var maxY = -1

        for (y in 0 until diffH) {
            val row = y * diffW
            for (x in 0 until diffW) {
                val i = row + x
                if (abs(cur[i] - prev[i]) >= diffPixel) {
                    score++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }

        val exists = score >= motionThreshold

        var boxPx: Rect? = null
        var box01: RectF? = null

        if (exists && maxX >= minX && maxY >= minY) {
            val xScale = w.toFloat() / diffW.toFloat()
            val yScale = h.toFloat() / diffH.toFloat()

            val left = (minX * xScale).toInt().coerceIn(0, w - 1)
            val top = (minY * yScale).toInt().coerceIn(0, h - 1)
            val right = ((maxX + 1) * xScale).toInt().coerceIn(left + 1, w)
            val bottom = ((maxY + 1) * yScale).toInt().coerceIn(top + 1, h)

            boxPx = Rect(left, top, right, bottom)
            box01 = RectF(
                left.toFloat() / w,
                top.toFloat() / h,
                right.toFloat() / w,
                bottom.toFloat() / h
            )
        }

        // 다음 프레임을 위해 swap (prev=cur)
        curGray = prev
        prevGray = cur

        return MotionResult(score, exists, boxPx, box01)
    }
}
