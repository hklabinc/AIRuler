package com.hklab.airuler.gallery

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // ✅ 높이를 너비와 동일하게 강제 -> 항상 정사각형
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
