package com.hklab.airuler.model

sealed class ModelItem {

    /** 실제 모델 타일 */
    data class Model(
        val name: String,           // 예: "L2017-07"
    ) : ModelItem()

    /** 마지막의 + 타일 */
    data object Add : ModelItem()
}
