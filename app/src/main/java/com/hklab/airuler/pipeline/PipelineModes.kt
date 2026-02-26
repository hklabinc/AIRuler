package com.hklab.airuler.pipeline

/**
 * MainActivity에서 사용하던 모드 enum들을 분리.
 *
 * ✅ SettingsActivity는 enum name(String)을 주고받는 방식이므로,
 *    enum 이름을 바꾸지 않으면 기존 동작이 그대로 유지됩니다.
 */
enum class TiltMode {
    NONE,
    HOUGH
}

/**
 * 치수 재기 모드
 * - NONE   : 측정 안 함 (BadBox 판정이 최종)
 * - MANUAL : BadBox PASS 후 버튼으로 측정
 * - AUTO   : BadBox PASS 후 자동 측정
 */
enum class MeasureMode {
    NONE,
    MANUAL,
    AUTO
}

/**
 * 치수 재기(측정) 계산 방법
 *
 * - GRID  : internal/models/Grid.json을 이용해 Grid warp 기반으로 mm 계산
 * - RULER : 기존(가로/세로 자) tick 기반으로 mm 계산
 */
enum class MeasurementMethod {
    GRID,
    RULER
}
