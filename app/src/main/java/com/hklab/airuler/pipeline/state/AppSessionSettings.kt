package com.hklab.airuler.pipeline.state

import android.util.Size
import com.hklab.airuler.pipeline.MeasureMode
import com.hklab.airuler.pipeline.MeasurementMethod
import com.hklab.airuler.pipeline.TiltMode
import com.hklab.airuler.GlobalParams

/**
 * ✅ 프로세스(앱 실행 세션) 동안만 유지되는 설정 스냅샷
 *
 * 요구사항:
 * - “홈 → 다른 모델 선택”처럼 Activity가 재생성되어도 Settings 값이 디폴트로 돌아가지 않게
 * - 단, 앱이 완전히 종료(프로세스 kill)되어 다시 실행되면 디폴트로 초기화
 *
 * 구현:
 * - SharedPreferences에 저장하지 않고, 메모리(object)로만 유지
 */
object AppSessionSettings {

    /** Settings(해상도)에서 선택한 Preview 크기(세션 동안 유지) */
    @Volatile var selectedPreviewSize: Size? = null

    // ----- External capture MP (Samsung / Expert RAW) -----
    /**
     * ✅ 외부 촬영(삼성 카메라/Expert RAW) 시 사용할 메가픽셀 선택
     *
     * - 50MP : 기존 동작(삼성 카메라 Pro/50MP) + DCIM/Camera JPEG 감지
     * - 200MP: Expert RAW 앱으로 촬영 + DCIM/Expert RAW JPEG 감지
     *
     * 기본값: 50
     *
     * ⚠️ 주의
     * - 이 값은 "세션 동안만" 유지됩니다(프로세스 kill 후 재실행 시 50MP로 초기화).
     * - 200MP 선택 시, 치수 측정은 Ruler 기반으로만 동작합니다(Settings 화면에서 자동 강제).
     */
    @Volatile var captureMegapixel: Int = GlobalParams.Defaults.CAPTURE_MP

    /** captureMegapixel에 따른 px 파라미터 스케일 (50MP=1, 200MP=2) */
    val captureScale: Int
        get() = if (captureMegapixel >= 200) 2 else 1


    // ----- Overlay / debug switches -----
    @Volatile var showGuideGrid: Boolean = GlobalParams.Defaults.SHOW_GUIDE_GRID
    @Volatile var showFilmBox: Boolean = GlobalParams.Defaults.SHOW_FILM_BOX_OVERLAY
    @Volatile var showConfOnBox: Boolean = GlobalParams.Defaults.SHOW_CONF_ON_BOX
    @Volatile var showAngleOnPreview: Boolean = GlobalParams.Defaults.SHOW_ANGLE_ON_PREVIEW
    @Volatile var showTilt: Boolean = GlobalParams.Defaults.SHOW_TILT
    /** ✅ View Options: Good Box (default OFF) */
    @Volatile var showGoodBoxOverlay: Boolean = GlobalParams.Defaults.SHOW_GOOD_BOX_OVERLAY
    @Volatile var showBadBoxOverlay: Boolean = GlobalParams.Defaults.SHOW_BAD_BOX_OVERLAY
    @Volatile var showHandOverlay: Boolean = GlobalParams.Defaults.SHOW_HAND_OVERLAY
    @Volatile var showMotionOverlay: Boolean = GlobalParams.Defaults.SHOW_MOTION_OVERLAY
    @Volatile var showTrackOverlay: Boolean = GlobalParams.Defaults.SHOW_TRACK_OVERLAY

    // ----- Mode / pipeline options -----
    @Volatile var tiltMode: TiltMode = GlobalParams.Defaults.TILT_MODE
    @Volatile var yoloFrameInterval: Int = GlobalParams.Defaults.YOLO_INTERVAL_DEFAULT
    @Volatile var directionCheckEnabled: Boolean = GlobalParams.Defaults.DIRECTION_CHECK_ENABLED

    /** ✅ 기본값(디폴트): GlobalParams.Defaults.MEASURE_MODE */
    @Volatile var measureMode: MeasureMode = GlobalParams.Defaults.MEASURE_MODE

    /**
     * 치수 재기 계산 방법
     *
     * - 기본값(디폴트): GlobalParams.Defaults.MEASUREMENT_METHOD
     */
    @Volatile var measurementMethod: MeasurementMethod = GlobalParams.Defaults.MEASUREMENT_METHOD

    /**
     * ✅ Online Offset Calibration ON/OFF (세션 동안 유지)
     *
     * - ON  : warmup/outlier/window 없이, 들어오는 모든 샘플을 update에 사용하여 offset을 온라인 업데이트하고
     *         compensated = raw + offset 으로 측정값에 적용합니다. (alpha=0.5)
     * - OFF : offset 업데이트를 중지하고, 현재 offset을 그대로 사용합니다.
     *
     * offset은 모델 JSON의 각 measure_* 하위 `offset` 필드에 저장됩니다.
     */
    // ✅ 기본값(디폴트)은 GlobalParams.Defaults.OFFSET_CALIBRATION_ENABLED 를 따릅니다.

    /**
     * 사용자가 Settings에서 Offset Calibration Mode를 변경했는지 여부(세션 동안 유지)
     *
     * - false: 앱 실행 직후(초기값). 기본값(ON)을 강제할 수 있음
     * - true : 사용자가 한 번이라도 Settings에서 값을 적용
     */
    @Volatile var offsetCalibrationUserOverridden: Boolean = false

    @Volatile var gridCalibrationEnabled: Boolean = GlobalParams.Defaults.OFFSET_CALIBRATION_ENABLED

    /**
     * ✅ 치수 측정 결과 이미지(DCIM/Result) 서버 업로드 자동 전송 ON/OFF
     * - Settings의 "Enable Upload to Server" 스위치로 제어됩니다.
     * - OFF면 측정 후 자동 업로드를 수행하지 않습니다(수동 업로드는 Gallery에서 가능).
     */
    @Volatile var uploadToServerEnabled: Boolean = GlobalParams.Defaults.UPLOAD_TO_SERVER_ENABLED

    /**
     * ✅ 치수 측정 결과 이미지(DCIM/Result) 저장 ON/OFF
     * - Settings의 "Enable Save Result" 스위치로 제어됩니다.
     * - OFF면 측정 후 DCIM/Result에 저장하지 않습니다.
     */
    @Volatile var saveResultEnabled: Boolean = GlobalParams.Defaults.SAVE_RESULT_ENABLED

}