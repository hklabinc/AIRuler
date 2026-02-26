package com.hklab.airuler.gallery

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.InputDevice
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * - Samsung Gallery처럼 마우스 왼쪽 드래그로 Pan
 * - Ctrl + 마우스 휠로 Zoom
 * - (보너스) 핀치 줌도 지원
 */
class ZoomPanImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val m = Matrix()
    private val viewRect = RectF()
    private val drawableRect = RectF()

    private var minScale = 1f
    private var maxScale = 8f

    private var isDragging = false
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            zoomBy(factor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // 더블탭: 1x <-> 2x 토글 (편의 기능)
            val target = if (currentScale() < minScale * 1.5f) minScale * 2f else minScale
            setScale(target, e.x, e.y)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = m
        isClickable = true
        // 마우스 휠/제스처 이벤트를 안정적으로 받도록 포커스 가능하게
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        // 이미지가 바뀌면 초기 Fit
        post { fitToCenter() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            post { fitToCenter() }
        }
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        m.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun fitToCenter() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return

        // FIT_CENTER 초기 스케일
        val scale = min(vw / dw, vh / dh)
        minScale = scale

        // 너무 큰 이미지에서 기본 확대가 필요하면 minScale이 1보다 작을 수 있음
        // maxScale은 minScale 기준으로 상대 확대
        maxScale = max(minScale * 8f, minScale)

        val dx = (vw - dw * scale) / 2f
        val dy = (vh - dh * scale) / 2f

        m.reset()
        m.postScale(scale, scale)
        m.postTranslate(dx, dy)
        imageMatrix = m
        invalidate()
    }

    private fun zoomBy(factorRaw: Float, px: Float, py: Float) {
        if (drawable == null) return
        val cur = currentScale()
        val target = (cur * factorRaw).coerceIn(minScale, maxScale)
        val factor = if (cur == 0f) 1f else (target / cur)

        if (factor == 1f) return

        m.postScale(factor, factor, px, py)
        fixTranslation()
        imageMatrix = m
        invalidate()
    }

    private fun setScale(targetScale: Float, px: Float, py: Float) {
        val cur = currentScale()
        if (cur == 0f) return
        val target = targetScale.coerceIn(minScale, maxScale)
        val factor = target / cur
        m.postScale(factor, factor, px, py)
        fixTranslation()
        imageMatrix = m
        invalidate()
    }

    private fun fixTranslation() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        // 현재 매트릭스가 적용된 drawable bounds
        drawableRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(drawableRect)

        var dx = 0f
        var dy = 0f

        // 가로
        if (drawableRect.width() <= vw) {
            // 이미지가 뷰보다 작으면 가운데로
            dx = (vw - drawableRect.width()) / 2f - drawableRect.left
        } else {
            // 큰 경우: 경계 안으로
            if (drawableRect.left > 0) dx = -drawableRect.left
            if (drawableRect.right < vw) dx = vw - drawableRect.right
        }

        // 세로
        if (drawableRect.height() <= vh) {
            dy = (vh - drawableRect.height()) / 2f - drawableRect.top
        } else {
            if (drawableRect.top > 0) dy = -drawableRect.top
            if (drawableRect.bottom < vh) dy = vh - drawableRect.bottom
        }

        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging || scaleDetector.isInProgress) return true

                val dx = event.x - lastX
                val dy = event.y - lastY

                m.postTranslate(dx, dy)
                fixTranslation()
                imageMatrix = m
                invalidate()

                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // ✅ 마우스 휠 / 터치패드 스크롤로 줌 인/아웃
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            // 보통 휠은 AXIS_VSCROLL 로 들어옴
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)

            val scroll = when {
                vScroll != 0f -> vScroll
                hScroll != 0f -> hScroll
                else -> 0f
            }

            if (scroll != 0f) {
                // scroll > 0 : 휠 업(확대), scroll < 0 : 휠 다운(축소)
                val base = 1.12f
                val factor = if (scroll > 0) base else (1f / base)
                zoomBy(factor, event.x, event.y)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}
