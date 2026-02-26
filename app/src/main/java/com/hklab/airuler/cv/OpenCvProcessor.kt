package com.hklab.airuler.cv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.PointF
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * OpenCV 관련 기능 모음:
 *  - OpenCV 초기화
 *  - Canny edge
 *  - Motion detection (연속 프레임 차이)
 */
object OpenCvProcessor {

    private const val TAG = "OpenCvProcessor"
    @Volatile
    var isInitialized: Boolean = false
        private set

    enum class TiltLevel {
        GOOD,   // 거의 완전 평행 (초록)
        BAD     //  기울어짐 (빨강)
    }
    data class TiltLine(
        val x1Norm: Float,
        val y1Norm: Float,
        val x2Norm: Float,
        val y2Norm: Float,
        val angleDeg: Double,   // 이 선의 기울기(0 = 수평)
        val isGood: Boolean,    // GOOD=true, BAD=false
        val label: String? = null // overlay에 표시할 텍스트(예: dy). null이면 angleDeg(°) 표기
    )
    data class TiltResult(
        val angleDeg: Double,   // 수평에서 얼마나 기울었는지 (도 단위, 0 = 완전 평행)
        val level: TiltLevel,       // GOOD / MEDIUM / BAD
        val line: TiltLine? = null  // 대표 가로선 (없으면 null)
    )

    fun initIfNeeded(context: Context) {
        if (isInitialized) return
        // Maven 패키지에서도 initDebug() 한 번 호출해 주는 것이 안전
        isInitialized = OpenCVLoader.initDebug()
        Log.i(TAG, "OpenCV init: $isInitialized")
    }

    /** Bitmap -> Gray Mat */
    private fun bitmapToGrayMat(bitmap: Bitmap): Mat {
        val rgba = Mat()
        val gray = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return gray
    }

    fun estimateHorizontalTilt(
        bitmap: Bitmap,
        roiNorm: RectF? = null,
        margin: Float = 0.02f
    ): TiltResult {
        val imgW = bitmap.width
        val imgH = bitmap.height

        // 1) ROI 픽셀 영역 계산
        var x = 0
        var y = 0
        var w = imgW
        var h = imgH

        if (roiNorm != null) {
            val marginX = roiNorm.width() * margin
            val marginY = roiNorm.height() * margin
            val leftF = ((roiNorm.left - marginX).coerceAtLeast(0f) * imgW)
            val topF = ((roiNorm.top - marginY).coerceAtLeast(0f) * imgH)
            val rightF = ((roiNorm.right + marginX).coerceAtMost(1f) * imgW)
            val bottomF = ((roiNorm.bottom + marginY).coerceAtMost(1f) * imgH)        // margin을 넣으면 ROI가 겹쳐서 line이 중복검출 될 수 있음 - 바닥은 margin 넣지 말기(??)

            x = leftF.toInt().coerceAtLeast(0)
            y = topF.toInt().coerceAtLeast(0)
            w = (rightF - leftF).toInt().coerceAtLeast(1)
            h = (bottomF - topF).toInt().coerceAtLeast(1)
        }

        if (x + w > imgW) w = imgW - x
        if (y + h > imgH) h = imgH - y
        if (w <= 0 || h <= 0) {
            return TiltResult(90.0, TiltLevel.BAD, null)
        }

        // 2) ROI 비트맵
        val roiBitmap = Bitmap.createBitmap(bitmap, x, y, w, h)

        // 3) Gray + Canny
        val gray = bitmapToGrayMat(roiBitmap)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 80.0, 160.0)

        // 4) HoughLinesP로 가로선 검출
        val lines = Mat()
        val minLineLength = w * 0.5      // ROI 가로의 50% 이상인 선만 사용
        Imgproc.HoughLinesP(
            edges,              // Canny edge 이용
            lines,
            1.0,                  // 1픽셀 단위로 양자화
            Math.PI / 180.0,     // 각도 해상도 (각도를 1도 단위로 구분) -> 각도 정밀도: +-0.5 수준
            80,               // 동일 방향으로 80픽셀 이상 이어야
            minLineLength,              // ROI 가로 길이의 50% 이상인 직선만
            10.0            // 선 위에 최대 10px까지 끊김이 있어도 하나의 선으로 연결
        )

        var bestLength = 0.0
        var bestX1 = 0.0
        var bestY1 = 0.0
        var bestX2 = 0.0
        var bestY2 = 0.0

        for (i in 0 until lines.rows()) {
            val l = lines.get(i, 0) ?: continue
            val x1 = l[0].toDouble()
            val y1 = l[1].toDouble()
            val x2 = l[2].toDouble()
            val y2 = l[3].toDouble()

            val dx = x2 - x1
            val dy = y2 - y1
            val length = Math.hypot(dx, dy)

            if (length > bestLength) {
                bestLength = length
                bestX1 = x1
                bestY1 = y1
                bestX2 = x2
                bestY2 = y2
            }
        }

        gray.release()
        edges.release()
        lines.release()

        if (bestLength <= 0.0) {
            // 선을 못 찾은 경우
            return TiltResult(90.0, TiltLevel.BAD, null)
        }

        // ROI-local → full-frame normalized 좌표 변환
        val gx1 = (bestX1 + x).toFloat() / imgW.toFloat()
        val gy1 = (bestY1 + y).toFloat() / imgH.toFloat()
        val gx2 = (bestX2 + x).toFloat() / imgW.toFloat()
        val gy2 = (bestY2 + y).toFloat() / imgH.toFloat()

        // 기울기 계산
        val dx = gx2 - gx1
        val dy = gy2 - gy1
        var angleDeg = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
        if (angleDeg < 0) angleDeg += 180.0

        val dev = kotlin.math.min(angleDeg, 180.0 - angleDeg)

        // ★ 0.5도 기준 GOOD / BAD
        val level = if (dev < 0.5) TiltLevel.GOOD else TiltLevel.BAD

        val bestLine = TiltLine(
            x1Norm = gx1,
            y1Norm = gy1,
            x2Norm = gx2,
            y2Norm = gy2,
            angleDeg = dev,           // dev: 수평에서의 절대 편차
            isGood = (level == TiltLevel.GOOD)
        )

        return TiltResult(dev, level, bestLine)
    }
}
