package com.hklab.airuler.pipeline.state

import android.net.Uri
import com.hklab.airuler.inspection.InspectionDecision

/**
 * 파이프라인(카메라/추론/측정/복귀) 간에 공유되는 “런타임 상태” 컨테이너.
 *
 * - MainActivity에서 흩어져 있던 volatile flags를 한 곳으로 모아
 *   역할 분리를 안전하게(리스크 낮게) 진행하기 위한 목적입니다.
 * - UI Thread/Analyzer Thread 모두에서 접근될 수 있어 @Volatile 위주로 둡니다.
 */
class AppRuntimeState {

    /**
     * ✅ 측정 관련 플래그(pending/measuring)는 서로 조합이 중요해서
     *    여러 스레드에서 동시에 건드릴 때 “중간 상태”를 보지 않도록 lock으로 묶어줍니다.
     *
     * - InferencePipeline(YOLO thread) ↔ MeasurementPipeline(IO thread) 간 경쟁 완화
     * - 기존 동작을 바꾸지 않되, set/check를 원자적으로 수행할 수 있게 도우미 제공
     */
    private val measureLock = Any()

    // ---------------------------------------------------------------------
    // One-shot overrides (Retry 버튼 등)
    //  - 다음 "삼성 캡처" 1회에만 적용되는 오버라이드 값들
    //  - 캡처 Uri가 돌아와 MeasurementPipeline이 소비(consumed)하면 자동으로 null 처리
    // ---------------------------------------------------------------------

    /** Retry 등으로 다음 캡처 1회에만 적용할 Capture MP(50/200). null이면 Settings 값을 사용 */
    @Volatile var nextCaptureMegapixelOverride: Int? = null

    /** Retry 등으로 다음 캡처 1회에만 적용할 MeasurementMethod. null이면 Settings 값을 사용 */
    @Volatile var nextMeasurementMethodOverride: com.hklab.airuler.pipeline.MeasurementMethod? = null

    /** Retry 버튼으로 "다음 PASS"에서 measureMode와 무관하게 자동 촬영을 강제하는 1회성 플래그 */
    @Volatile var forceAutoMeasureOnce: Boolean = false

    /** next* 오버라이드들을 소비(1회성)하고 null로 정리합니다. */
    fun consumeNextCaptureOverrides(): Pair<Int?, com.hklab.airuler.pipeline.MeasurementMethod?> = synchronized(measureLock) {
        val mp = nextCaptureMegapixelOverride
        val method = nextMeasurementMethodOverride
        nextCaptureMegapixelOverride = null
        nextMeasurementMethodOverride = null
        mp to method
    }

    /** forceAutoMeasureOnce 값을 소비(1회성)하고 false로 정리합니다. */
    fun consumeForceAutoMeasureOnce(): Boolean = synchronized(measureLock) {
        val v = forceAutoMeasureOnce
        forceAutoMeasureOnce = false
        v
    }

    /** Retry 오버라이드/플래그를 모두 정리합니다(실패/취소/리셋 시 안전장치). */
    fun clearOneShotOverrides() = synchronized(measureLock) {
        nextCaptureMegapixelOverride = null
        nextMeasurementMethodOverride = null
        forceAutoMeasureOnce = false
    }

    data class MeasureFlags(
        val pendingMeasureAfterBadBoxPass: Boolean,
        val measuringNow: Boolean
    )

    /** Activity가 background로 가거나 destroy 중이면 true로 설정 (Analyzer에서 빠른 stop) */
    @Volatile var isStopping: Boolean = false

    /** 탭으로 Preview 정지/재시작 */
    @Volatile var previewPaused: Boolean = false

    /** 오류/이동 mismatch 등으로 멈춘 상태(사용자가 탭으로 해제) */
    @Volatile var alertStop: Boolean = false

    /** ImageAnalysis 프레임을 1장 저장 요청 */
    @Volatile var pendingLivePreviewCapture: Boolean = false

    /** 삼성 캡처/결과 오버레이가 떠 있는 상태 */
    @Volatile var capturedOverlayVisible: Boolean = false

    /** 결과 오버레이가 떠 있는 동안 손이 감지되면 자동 닫기 */
    @Volatile var dismissCapturedOverlayOnHand: Boolean = false

    /** BadBox PASS 후 치수 측정이 필요한 상태(최종 판정 보류) */
    @Volatile var pendingMeasureAfterBadBoxPass: Boolean = false

    /** 삼성 카메라/치수 측정 진행 중(중복 launch 방지) */
    @Volatile var measuringNow: Boolean = false

    fun snapshotMeasureFlags(): MeasureFlags = synchronized(measureLock) {
        MeasureFlags(
            pendingMeasureAfterBadBoxPass = pendingMeasureAfterBadBoxPass,
            measuringNow = measuringNow
        )
    }

    fun setMeasureFlags(pending: Boolean, measuring: Boolean) = synchronized(measureLock) {
        pendingMeasureAfterBadBoxPass = pending
        measuringNow = measuring
    }

    /** measuringNow가 이미 true면 false를 반환하고 아무것도 하지 않음(원자적 start) */
    fun tryStartMeasuring(): Boolean = synchronized(measureLock) {
        if (measuringNow) return@synchronized false
        measuringNow = true
        true
    }

    /** 측정 종료(필요 시 pending도 같이 정리) */
    fun finishMeasuring(clearPending: Boolean = true) = synchronized(measureLock) {
        measuringNow = false
        if (clearPending) pendingMeasureAfterBadBoxPass = false
    }

    /** 최종 판정(PASS/FAIL). null이면 아직 미결정 */
    @Volatile var decidedInspection: InspectionDecision? = null

    /** 삼성 카메라로부터 돌아온 마지막 Uri */
    @Volatile var lastSamsungCapturedUri: Uri? = null

    /** 삼성 캡처 세션 토큰(프리뷰/분석 콜백이 뒤섞여도 1번만 적용) */
    @Volatile var currentSamsungCaptureToken: Long = 0L

    /** raw preview가 annotated 결과를 덮어쓰지 않도록 방지용 토큰 */
    @Volatile var resultShownToken: Long = 0L
}
