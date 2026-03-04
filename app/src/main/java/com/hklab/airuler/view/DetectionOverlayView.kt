package com.hklab.airuler.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.hklab.airuler.cv.OpenCvProcessor
import com.hklab.airuler.inspection.BadBoxPx
import com.hklab.airuler.inspection.BadBoxCirclePx
import com.hklab.airuler.yolo.AirulerYoloClasses
import com.hklab.airuler.yolo.YoloDetection
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 모션 박스 + YOLO 박스 + 기울기 선 + BadBox 원을 그리는 오버레이 뷰
 *  - YOLO/Motion 박스는 0~1 normalized 좌표 기반
 *  - BadBoxCirclePx는 "프레임 픽셀 좌표" 기반
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ================== 표시 옵션 ==================
    private var showGrid = true
    private var showFilmBox = true
    private var showConf = false
    private var showTilt = false
    private var showAngle = false
    private var showBadBox = true
    private var showHand = false
    private var showMotion = false
    private var showTrack = false

    fun updateDisplayOptions(
        showGrid: Boolean,
        showFilmBox: Boolean,
        showConf: Boolean,
        showTilt: Boolean,
        showAngle: Boolean,
        showBadBox: Boolean,
        showHand: Boolean,
        showMotion: Boolean,
        showTrack: Boolean
    ) {
        this.showGrid = showGrid
        this.showFilmBox = showFilmBox
        this.showConf = showConf
        this.showTilt = showTilt
        this.showAngle = showAngle
        this.showBadBox = showBadBox
        this.showHand = showHand
        this.showMotion = showMotion
        this.showTrack = showTrack
        invalidate()
    }

    // ================== Paint ==================
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.WHITE
        alpha = 160
        isAntiAlias = true
    }

    private val motionPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.YELLOW
        isAntiAlias = true
    }

    private val detectPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.GREEN
        isAntiAlias = true
    }

    private val density = resources.displayMetrics.density

    private val textPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 12f * density
        isAntiAlias = true
    }

    private val textBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x80000000.toInt()
        isAntiAlias = true
    }

    private val tiltPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.RED
        isAntiAlias = true
    }

    private val badBoxCirclePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.MAGENTA
        isAntiAlias = true
    }

    // ✅ (요구사항) filtering된 badBox bbox만 "빨간색"으로 표시
    private val badBoxBoxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.RED
        isAntiAlias = true
    }

    // ✅ (요구사항) 4개 코너점(1px)
    private val cornerPointPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.MAGENTA
        isAntiAlias = false
    }

    // ✅ Motion 디버그 색(기존 색과 구분): DeepPink
    private val motionDebugPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FF1493")
        isAntiAlias = true
    }

    // ✅ Hand 디버그: ROI(CYAN), 감지박스(BLUE)
    private val handRoiPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.CYAN
        isAntiAlias = true
    }
    private val handBoxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.BLUE
        isAntiAlias = true
    }

    // ✅ Track 디버그: SpringGreen
    private val trackLinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#00FF7F")
        isAntiAlias = true
    }
    private val trackPointPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00FF7F")
        isAntiAlias = true
    }


    // ================== 데이터 ==================
    private val motionBoxes = mutableListOf<RectF>()
    private val detections = mutableListOf<YoloDetection>()
    private val tiltLines = mutableListOf<OpenCvProcessor.TiltLine>()
    private val badBoxCircles = mutableListOf<BadBoxCirclePx>()

    // ✅ filtering된 badBox bbox (frame px)
    private val badBoxBoxes = mutableListOf<BadBoxPx>()

    // ✅ Film corner points (frame px)
    private val cornerPointsPx = mutableListOf<PointF>()

    // ✅ "현재 badBox 결과가 유효한 상태인가?"
    // true면 박스 색을 "빨강/초록(결함)"으로 강제 표시
    // false면 박스는 d.color(각도 색 등)를 그대로 사용
    @Volatile
    private var badBoxResultValid: Boolean = false

    // ✅ 판정 후 필름 이동 중에는 Tilt/Angle/BadBox 를 숨김하기 위한 플래그
    // MainActivity가 "이동 시작" 시 true로, 화살표가 사라질 때 false로 제어함.
    @Volatile
    private var suppressTiltAngleDiffBadBoxDuringMove: Boolean = false

    /**
     * true  -> Tilt/Angle/BadBox 숨김
     * false -> 기존 옵션(showTilt/showAngle/showBadBox)대로 표시
     */
    fun setDecisionMoveSuppression(suppress: Boolean) {
        if (suppressTiltAngleDiffBadBoxDuringMove == suppress) return
        suppressTiltAngleDiffBadBoxDuringMove = suppress
        postInvalidateOnAnimation()
    }


    // 분석에 사용된 프레임의 가로/세로 비율
    private var frameAspect: Float = 16f / 9f

    // 프레임 픽셀 크기 (BadBoxCirclePx 변환에 필요)
    private var frameW: Int = 0
    private var frameH: Int = 0

    fun setFrameSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            frameW = width
            frameH = height
            frameAspect = width.toFloat() / height.toFloat()
            postInvalidate()
        }
    }

    // ================== 외부 주입 ==================

    fun setMotionBoxes(list: List<RectF>) {
        synchronized(motionBoxes) {
            motionBoxes.clear()
            motionBoxes.addAll(list)
        }
        postInvalidate()
    }

    fun setDetections(list: List<YoloDetection>) {
        synchronized(detections) {
            detections.clear()
            detections.addAll(list)
        }
        postInvalidate()
    }

    fun setTiltLines(list: List<OpenCvProcessor.TiltLine>) {
        synchronized(tiltLines) {
            tiltLines.clear()
            tiltLines.addAll(list)
        }
        postInvalidate()
    }

    // ------------------------------------------------------------------
    // Film corners (frame px)
    // ------------------------------------------------------------------

    fun setCornerPoints(pointsPx: List<PointF>) {
        synchronized(cornerPointsPx) {
            cornerPointsPx.clear()
            cornerPointsPx.addAll(pointsPx)
        }
        postInvalidateOnAnimation()
    }

    fun clearCornerPoints() {
        synchronized(cornerPointsPx) {
            cornerPointsPx.clear()
        }
        postInvalidateOnAnimation()
    }

    /**
     * ✅ 기본 호출(기존 호환용): valid=true 로 처리
     * - 빈 리스트가 들어오면 기존 badBox을 "지움" (valid=true이므로)
     * - "깜빡임 방지"를 원하면 MainActivity에서 setBadBoxCircles(list, valid=badBoxChecked) 를 사용해야 함
     */
    fun setBadBoxCircles(list: List<BadBoxCirclePx>) {
        setBadBoxCircles(list, valid = true)
    }

    /**
     * ✅ 추천 사용 방식
     * valid=true  : 이번 프레임에서 badBox 분석을 했고, 결과가 list(빈 리스트 포함)로 확정됨 → 덮어씀
     * valid=false : 이번 프레임은 badBox 분석을 안 했음(또는 결과 무효) → 기존 badBox 유지(안 지움)
     */
    fun setBadBoxCircles(list: List<BadBoxCirclePx>, valid: Boolean) {
        if (!valid) {
            // 이번 업데이트는 "badBox 결과 없음(무효)"이므로 기존 것을 유지
            postInvalidate()
            return
        }

        synchronized(badBoxCircles) {
            badBoxCircles.clear()
            badBoxCircles.addAll(list)
        }
        badBoxResultValid = true
        postInvalidate()
    }

    /** ✅ 새 필름 시작 등에서 badBox/결함표시를 완전히 리셋하고 싶을 때 호출 */
    fun clearBadBoxCircles() {
        synchronized(badBoxCircles) {
            badBoxCircles.clear()
        }
        synchronized(badBoxBoxes) {
            badBoxBoxes.clear()
        }
        badBoxResultValid = false
        postInvalidate()
    }

    // ------------------------------------------------------------------
    // BadBox boxes (frame px)
    // ------------------------------------------------------------------

    /**
     * ✅ v5 badBox 시각화: filtering된 badBox bbox
     *
     * valid=true  : 이번 프레임에서 badBox 분석 결과가 확정됨(빈 리스트 포함) → 덮어씀
     * valid=false : 이번 프레임은 badBox 분석을 안 했음(또는 결과 무효) → 기존 badBox 유지
     */
    fun setBadBoxBoxes(list: List<BadBoxPx>, valid: Boolean) {
        if (!valid) {
            postInvalidate()
            return
        }

        synchronized(badBoxBoxes) {
            badBoxBoxes.clear()
            badBoxBoxes.addAll(list)
        }
        badBoxResultValid = true
        postInvalidate()
    }

    fun clearBadBoxBoxes() {
        synchronized(badBoxBoxes) {
            badBoxBoxes.clear()
        }
        badBoxResultValid = false
        postInvalidate()
    }

    // ✅ Hand debug data (0..1 normalized)
    private val handLock = Any()
    private var handRoi01: RectF? = null
    private var handBox01: RectF? = null
    private var handScore: Float = 0f
    private var handExists: Boolean = false

    fun setHandDebug(roi01: RectF?, box01: RectF?, score: Float, exists: Boolean) {
        synchronized(handLock) {
            handRoi01 = roi01?.let { RectF(it) }
            handBox01 = box01?.let { RectF(it) }
            handScore = score
            handExists = exists
        }
        postInvalidateOnAnimation()
    }

    fun clearHandDebug() {
        synchronized(handLock) {
            handRoi01 = null
            handBox01 = null
            handScore = 0f
            handExists = false
        }
        postInvalidateOnAnimation()
    }

    // ✅ Track debug points (frame px)
    private val trackLock = Any()
    private val trackPointsPx = mutableListOf<PointF>()

    fun setTrackPoints(points: List<PointF>) {
        synchronized(trackLock) {
            trackPointsPx.clear()
            trackPointsPx.addAll(points)
        }
        postInvalidateOnAnimation()
    }

    fun clearTrack() {
        synchronized(trackLock) {
            trackPointsPx.clear()
        }
        postInvalidateOnAnimation()
    }



    // ================== 내부 유틸 ==================

    /** badBoxResultValid=true일 때: "이 detection 박스 내부에 badBox circle 중심이 들어있는가?" */
    private fun hasBadBoxInsideDetection(det: YoloDetection): Boolean {
        val fw = frameW
        val fh = frameH
        if (fw <= 0 || fh <= 0) return false

        val l = (det.rect.left * fw).toInt()
        val t = (det.rect.top * fh).toInt()
        val r = (det.rect.right * fw).toInt()
        val b = (det.rect.bottom * fh).toInt()

        val leftPx = min(l, r)
        val rightPx = max(l, r)
        val topPx = min(t, b)
        val bottomPx = max(t, b)

        synchronized(badBoxCircles) {
            for (c in badBoxCircles) {
                val cx = c.cx.toFloat()
                val cy = c.cy.toFloat()
                if (cx >= leftPx && cx <= rightPx && cy >= topPx && cy <= bottomPx) {
                    return true
                }
            }
        }
        return false
    }

    // ================== 그리기 ==================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val viewAspect = vw / vh

        // View 안에서 실제 "카메라 프레임"이 차지하는 영역 계산
        var drawLeft = 0f
        var drawTop = 0f
        var drawWidth = vw
        var drawHeight = vh

        if (viewAspect > frameAspect) {
            // View가 더 가로로 넓음 → 좌우 여백
            drawHeight = vh
            drawWidth = vh * frameAspect
            drawLeft = (vw - drawWidth) / 2f
            drawTop = 0f
        } else if (viewAspect < frameAspect) {
            // View가 더 세로로 김 → 위/아래 여백
            drawWidth = vw
            drawHeight = vw / frameAspect
            drawLeft = 0f
            drawTop = (vh - drawHeight) / 2f
        }

        val suppress = suppressTiltAngleDiffBadBoxDuringMove

        // ✅ Frame pixel size (set by InferencePipeline via setFrameSize)
        //    - Used to convert frame-px overlays (corners/badBox/track) into View coords.
        //    - If frame size is unknown, those overlays are simply skipped.
        val fw = frameW
        val fh = frameH

        // --- 3등분 가이드 그리드 ---
        if (showGrid) {
            val v1 = drawLeft + drawWidth / 3f
            val v2 = drawLeft + drawWidth * 2f / 3f
            val h1 = drawTop + drawHeight / 3f
            val h2 = drawTop + drawHeight * 2f / 3f

            canvas.drawLine(v1, drawTop, v1, drawTop + drawHeight, gridPaint)
            canvas.drawLine(v2, drawTop, v2, drawTop + drawHeight, gridPaint)
            canvas.drawLine(drawLeft, h1, drawLeft + drawWidth, h1, gridPaint)
            canvas.drawLine(drawLeft, h2, drawLeft + drawWidth, h2, gridPaint)
        }

        // --- Motion boxes ---
        if (showMotion) {
            synchronized(motionBoxes) {
                for (r in motionBoxes) {
                    val rect = RectF(
                        drawLeft + r.left * drawWidth,
                        drawTop + r.top * drawHeight,
                        drawLeft + r.right * drawWidth,
                        drawTop + r.bottom * drawHeight
                    )
                    canvas.drawRect(rect, motionDebugPaint) // ✅ 색 분리
                }
            }
        }

        if (showHand) {
            val roi: RectF?
            val box: RectF?
            val score: Float
            val exists: Boolean
            synchronized(handLock) {
                roi = handRoi01?.let { RectF(it) }
                box = handBox01?.let { RectF(it) }
                score = handScore
                exists = handExists
            }

            roi?.let { r ->
                val rr = RectF(
                    drawLeft + r.left * drawWidth,
                    drawTop + r.top * drawHeight,
                    drawLeft + r.right * drawWidth,
                    drawTop + r.bottom * drawHeight
                )
                canvas.drawRect(rr, handRoiPaint)

                // ROI 좌상단에 score 표시
                val text = String.format(Locale.US, "hand=%.3f(%s)", score, if (exists) "Y" else "N")
                canvas.drawText(text, rr.left + 6f, rr.top + 24f, textPaint)
            }

            box?.let { b ->
                val br = RectF(
                    drawLeft + b.left * drawWidth,
                    drawTop + b.top * drawHeight,
                    drawLeft + b.right * drawWidth,
                    drawTop + b.bottom * drawHeight
                )
                canvas.drawRect(br, handBoxPaint)
            }
        }


        // --- YOLO detections ---
        synchronized(detections) {
            for (d in detections) {
                val rect = RectF(
                    drawLeft + d.rect.left * drawWidth,
                    drawTop + d.rect.top * drawHeight,
                    drawLeft + d.rect.right * drawWidth,
                    drawTop + d.rect.bottom * drawHeight
                )

                // ✅ View Options 동작 수정
                // - Film Box OFF + Good Box ON 인 경우에도 good bbox는 보이도록 해야 함.
                // - showFilmBox 는 "film bbox" 토글이므로, good 라벨은 예외로 표시합니다.
                val isGood = AirulerYoloClasses.isGood(d)
                val drawThisBox = showFilmBox || isGood

                if (drawThisBox) {
                    // ✅ 박스 색은 파이프라인에서 d.color로 내려줌
                    detectPaint.color = d.color
                    canvas.drawRect(rect, detectPaint)
                }

                if (showConf && drawThisBox) {
                    val text = String.format(Locale.US, "%.2f", d.score)
                    val textWidth = textPaint.measureText(text)
                    val textHeight = textPaint.textSize

                    val padding = 4f
                    val bgRect = RectF(
                        rect.left,
                        rect.top,
                        rect.left + textWidth + padding * 2,
                        rect.top + textHeight + padding * 2
                    )

                    canvas.drawRoundRect(bgRect, 4f, 4f, textBgPaint)
                    canvas.drawText(
                        text,
                        rect.left + padding,
                        rect.top + textHeight + padding,
                        textPaint
                    )
                }
            }
        }

        // --- Film corner points (cv roi corners) ---
        if (!suppress && fw > 0 && fh > 0) {
            val scaleX = drawWidth / fw.toFloat()
            val scaleY = drawHeight / fh.toFloat()
            synchronized(cornerPointsPx) {
                for (p in cornerPointsPx) {
                    val x = drawLeft + p.x * scaleX
                    val y = drawTop + p.y * scaleY
                    canvas.drawPoint(x, y, cornerPointPaint)
                }
            }
        }

        // --- Tilt lines (Hough 기반) ---
        synchronized(tiltLines) {
            if ((showTilt && !suppress) || (showAngle && !suppress)) {
                for (line in tiltLines) {
                    val lineColor = if (line.isGood) Color.GREEN else Color.RED
                    tiltPaint.color = lineColor

                    val x1 = drawLeft + line.x1Norm * drawWidth
                    val y1 = drawTop + line.y1Norm * drawHeight
                    val x2 = drawLeft + line.x2Norm * drawWidth
                    val y2 = drawTop + line.y2Norm * drawHeight

                    if (showTilt && !suppress) {
                        canvas.drawLine(x1, y1, x2, y2, tiltPaint)
                    }

                    if (showAngle && !suppress) {
                        val midX = (x1 + x2) / 2f
                        val midY = (y1 + y2) / 2f
                        val label = line.label ?: String.format(Locale.US, "%.2f°", line.angleDeg)

                        val oldColor = textPaint.color
                        textPaint.color = lineColor
                        canvas.drawText(label, midX, midY - 4f, textPaint)
                        textPaint.color = oldColor
                    }
                }
            }
        }

        if (showTrack && fw > 0 && fh > 0) {
            val pts: List<PointF> = synchronized(trackLock) {
                trackPointsPx.map { PointF(it.x, it.y) }
            }
            if (pts.isNotEmpty()) {
                val scaleX = drawWidth / fw.toFloat()
                val scaleY = drawHeight / fh.toFloat()

                // 라인
                for (k in 1 until pts.size) {
                    val p0 = pts[k - 1]
                    val p1 = pts[k]
                    val x0 = drawLeft + p0.x * scaleX
                    val y0 = drawTop + p0.y * scaleY
                    val x1 = drawLeft + p1.x * scaleX
                    val y1 = drawTop + p1.y * scaleY
                    canvas.drawLine(x0, y0, x1, y1, trackLinePaint)
                }

                // 마지막 점
                val last = pts.last()
                val lx = drawLeft + last.x * scaleX
                val ly = drawTop + last.y * scaleY
                canvas.drawCircle(lx, ly, 8f, trackPointPaint)
            }
        }

        // --- BadBox boxes (v5) ---
        if (fw > 0 && fh > 0) {
            val scaleX = drawWidth / fw.toFloat()
            val scaleY = drawHeight / fh.toFloat()

            if (showBadBox && !suppress) {
                synchronized(badBoxBoxes) {
                    for (b in badBoxBoxes) {
                        val r = RectF(
                            drawLeft + b.left * scaleX,
                            drawTop + b.top * scaleY,
                            drawLeft + b.right * scaleX,
                            drawTop + b.bottom * scaleY
                        )
                        canvas.drawRect(r, badBoxBoxPaint)
                    }
                }
            }
        }
    }
}
