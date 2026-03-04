package com.hklab.airuler

/**
 * Global tuning parameters for AIRuler.
 *
 * 앞으로 앱 전역 파라미터는 이 파일에 모아서 관리합니다.
 * 여기의 값만 수정하면, 기본값으로 사용하는 모든 파이프라인/프로세서에 반영됩니다.
 */
object GlobalParams {
    @Volatile var DEBUG: Boolean = false     // 로그 필터 키워드: ReturnWatcher/AutoReturn/InferencePipeline/FilmTotalMeasureGrid/[ruler]
    @Volatile var LOG_FILE_SAVE: Boolean = true  // true일 때만 내부 로그 파일 저장(옵션)


    // ---------------------------------------------------------------------
    // ✅ YesunAI server (AIRuler 연동)
    //  - 서버 주소는 여기에서만 바꿀 수 있도록 단일화합니다.
    //  - 주의: 반드시 프로토콜(http/https) 포함, 끝에 '/'는 붙이지 않습니다.
    // ---------------------------------------------------------------------
    const val YESUNAI_BASE_URL: String = "http://106.240.235.194:60080"

    /**
     * ✅ SW 버전(표시용)
     * - 개발자가 필요 시 여기 값만 수정하면 됩니다.
     * - UI 표기는 "모델을 선택해주세요. <SW_VERSION>" / "Version: <SW_VERSION>" 형태로 사용합니다.
     */
    const val SW_VERSION: String = "v.26.0304"

    /**
     * YESUNAI_BASE_URL + "/" + path 를 안전하게 합칩니다.
     * - path는 "/api/..." 처럼 앞에 '/'가 있든 없든 동작
     */
    fun yesunaiUrl(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return YESUNAI_BASE_URL
        return if (p.startsWith('/')) YESUNAI_BASE_URL + p else "$YESUNAI_BASE_URL/$p"
    }

    /** YOLO score(confidence) threshold 기본값 */
    const val SCORE_THRESH: Float = 0.9f

    /**
     * ✅ Good/Bad(구멍 검사) score threshold (고정값)
     * - InferencePipeline의 good/bad 필터링에 사용됩니다.
     */
    const val GOOD_BAD_SCORE_THRESH: Float = 0.5f

    /** YOLO NMS IoU threshold 기본값 */
    const val IOU_THRESH: Float = 0.7f




    

    // ---------------------------------------------------------------------
    // Settings defaults (all defaults used in Settings screen / session init)
    //  - 디폴트 값을 바꾸고 싶으면 여기만 수정하세요.
    // ---------------------------------------------------------------------

    object Defaults {
        // Capture MP (Settings)
        const val CAPTURE_MP: Int = 50

        // Overlay options (Settings)
        const val SHOW_GUIDE_GRID: Boolean = true
        const val SHOW_FILM_BOX_OVERLAY: Boolean = true
        const val SHOW_CONF_ON_BOX: Boolean = false
        const val SHOW_ANGLE_ON_PREVIEW: Boolean = true
        const val SHOW_TILT: Boolean = true
        // ✅ (2026-02) Good 라벨 bbox 표시 옵션(디폴트 OFF)
        // - film bbox(기존 Film Box 옵션)과 별개로, good 라벨 bbox를 추가로 보고 싶을 때만 ON
        const val SHOW_GOOD_BOX_OVERLAY: Boolean = false
        const val SHOW_BAD_BOX_OVERLAY: Boolean = true
        const val SHOW_HAND_OVERLAY: Boolean = false
        const val SHOW_MOTION_OVERLAY: Boolean = false
        const val SHOW_TRACK_OVERLAY: Boolean = false

        // Pipeline options (Settings)
        val MEASURE_MODE: com.hklab.airuler.pipeline.MeasureMode =
            com.hklab.airuler.pipeline.MeasureMode.AUTO

        // ✅ 요청사항: Measurement Method 기본값은 RULER
        val MEASUREMENT_METHOD: com.hklab.airuler.pipeline.MeasurementMethod =
            com.hklab.airuler.pipeline.MeasurementMethod.GRID

        // ✅ 요청사항: Tilt Detection Mode 기본값은 NONE
        val TILT_MODE: com.hklab.airuler.pipeline.TiltMode =
            com.hklab.airuler.pipeline.TiltMode.NONE

        // ✅ 요청사항: Offset Calibration 기본값은 OFF
        const val OFFSET_CALIBRATION_ENABLED: Boolean = true

        // ✅ 요청사항: 서버 업로드 기본값은 ON
        const val UPLOAD_TO_SERVER_ENABLED: Boolean = true

        // ✅ 요청사항: 결과 저장(DCIM/Result) 기본값은 ON
        const val SAVE_RESULT_ENABLED: Boolean = true

        // Direction check (Settings)
        const val DIRECTION_CHECK_ENABLED: Boolean = true

        // YOLO interval (Settings) - UI range: 1..10
        const val YOLO_INTERVAL_DEFAULT: Int = 3
        const val YOLO_INTERVAL_MIN: Int = 1
        const val YOLO_INTERVAL_MAX: Int = 10
    }

    // ---------------------------------------------------------------------
    // Capture MP scaling (50MP vs 200MP)
    // ---------------------------------------------------------------------
    /**
     * 외부 촬영(삼성 카메라/Expert RAW) 시 선택한 MP(50/200)에 따라
     * 측정 파이프라인의 "px 기반 파라미터"를 스케일링합니다.
     *
     * - 50MP 기준값(base)을 그대로 사용
     * - 200MP는 2배(scale=2)
     *
     * ✅ 요구사항: tickLength 등도 여기서 함께 관리
     */
    private const val MEASURE_TFLITE_PAD_PX_50MP = 10
    private const val MEASURE_CORNER_PAD_PX_50MP = 15

    // -----------------------------------------------------------------
    // FilmTotalMeasureProcessor (Ruler 기반 측정) 파라미터
    // - Python(film_total_ruler.py) 로직과 동일한 의미
    // - 50MP를 base로 두고, 200MP는 2배(scale=2)로 확장
    // -----------------------------------------------------------------

    // ✅ crop_interest 확장(px) (corner_box 기준, 비대칭)
    // - Python default(200MP): U500/D100/L500/R100
    // - 여기서는 50MP base:    U250/D50/L250/R50
    private const val CROP_INTEREST_EXPAND_UP_PX_50MP = 250
    private const val CROP_INTEREST_EXPAND_DOWN_PX_50MP = 50
    private const val CROP_INTEREST_EXPAND_LEFT_PX_50MP = 250
    private const val CROP_INTEREST_EXPAND_RIGHT_PX_50MP = 50

    // ✅ ruler search ROI pad(px)
    // - Python: PAD_RULER = 200MP 100px / 50MP 50px
    private const val RULER_SEARCH_PAD_PX_50MP = 50

    // (요구사항) TICK_LENGTH: 50MP=60, 200MP=120
    private const val RULER_TICK_LENGTH_50MP = 60
    private const val RULER_TICK_LENGTH_200MP = 120

    /** 현재 선택된 Capture MP (50 or 200) */
    @Volatile var CAPTURE_MP: Int = 50
        private set

    /** 현재 선택된 Capture MP에 따른 px 스케일 (50MP=1, 200MP=2) */
    @Volatile var CAPTURE_SCALE: Int = 1
        private set

    /** Ruler 기반 tickLength(px). 50MP=60, 200MP=120 */
    @Volatile var RULER_TICK_LENGTH_PX: Int = RULER_TICK_LENGTH_50MP

    /** (Ruler 측정) crop_interest 확장(px) - 상(U) */
    @Volatile var CROP_INTEREST_EXPAND_UP_PX: Int = CROP_INTEREST_EXPAND_UP_PX_50MP

    /** (Ruler 측정) crop_interest 확장(px) - 하(D) */
    @Volatile var CROP_INTEREST_EXPAND_DOWN_PX: Int = CROP_INTEREST_EXPAND_DOWN_PX_50MP

    /** (Ruler 측정) crop_interest 확장(px) - 좌(L) */
    @Volatile var CROP_INTEREST_EXPAND_LEFT_PX: Int = CROP_INTEREST_EXPAND_LEFT_PX_50MP

    /** (Ruler 측정) crop_interest 확장(px) - 우(R) */
    @Volatile var CROP_INTEREST_EXPAND_RIGHT_PX: Int = CROP_INTEREST_EXPAND_RIGHT_PX_50MP

    /** (Ruler 측정) ruler search ROI pad(px). 50MP=50, 200MP=100 */
    @Volatile var RULER_SEARCH_PAD_PX: Int = RULER_SEARCH_PAD_PX_50MP

    /**
     * Settings에서 MP가 바뀔 때 호출해서 전역 파라미터를 일관되게 갱신합니다.
     *
     * - mp는 50 또는 200만 유효(그 외는 50으로 정규화)
     */
    fun applyCaptureMegapixel(mp: Int) {
        val normalized = if (mp >= 200) 200 else 50
        val scale = if (normalized == 200) 2 else 1

        CAPTURE_MP = normalized
        CAPTURE_SCALE = scale

        // Measurement ROI pad(픽셀) 스케일
        MEASURE_TFLITE_PAD_PX = MEASURE_TFLITE_PAD_PX_50MP * scale
        MEASURE_CORNER_PAD_PX = MEASURE_CORNER_PAD_PX_50MP * scale

        // crop_interest 확장(px) 스케일
        CROP_INTEREST_EXPAND_UP_PX = CROP_INTEREST_EXPAND_UP_PX_50MP * scale
        CROP_INTEREST_EXPAND_DOWN_PX = CROP_INTEREST_EXPAND_DOWN_PX_50MP * scale
        CROP_INTEREST_EXPAND_LEFT_PX = CROP_INTEREST_EXPAND_LEFT_PX_50MP * scale
        CROP_INTEREST_EXPAND_RIGHT_PX = CROP_INTEREST_EXPAND_RIGHT_PX_50MP * scale

        // ruler search pad(px) 스케일
        RULER_SEARCH_PAD_PX = RULER_SEARCH_PAD_PX_50MP * scale

        // Ruler tickLength 스케일
        RULER_TICK_LENGTH_PX =
            if (normalized == 200) RULER_TICK_LENGTH_200MP else RULER_TICK_LENGTH_50MP
    }

    // ---------------------------------------------------------------------
    // UI status update throttling (performance)
    // ---------------------------------------------------------------------
    /**
     * Status(TextView) 업데이트가 너무 자주 발생하면 UI thread 부하로 FPS가 떨어질 수 있어,
     * Detect/BadBox 등 "고빈도" 상태 문구는 이 간격(ms) 이상일 때만 갱신합니다.
     *
     * - 0 또는 음수면 throttle을 사실상 끄는 효과가 있습니다.
     */
    @Volatile var UI_STATUS_THROTTLE_MS: Long = 200L

    /**
     * 코너 미검출 등으로 같은 메시지가 반복되는 경우(예: corner missing -> badBox skip),
     * Status가 로그처럼 스팸되지 않도록 더 긴 간격(ms)으로 제한합니다.
     */
    @Volatile var UI_STATUS_CORNER_SKIP_THROTTLE_MS: Long = 800L

    // ---------------------------------------------------------------------
    // Film CV-ROI (corner based) parameters
    // ---------------------------------------------------------------------
    /**
     * (Python main_tflite_json_roi_v2.py 동작과 동일)
     * - tflite로 잡힌 필름 bbox를 corner point 검출 전에 확장하는 padding(px)
     */
    @Volatile var FILM_TFLITE_PAD_PX: Int = 3

    /**
     * (Python main_tflite_json_roi_v2.py 동작과 동일)
     * - 4개 코너점(min/max)으로 만든 CV ROI bbox에 추가로 주는 padding(px)
     */
    @Volatile var FILM_CORNER_PAD_PX: Int = 3

    // ---------------------------------------------------------------------
    // Measurement (Samsung captured photo) CV-ROI (corner based) parameters
    // ---------------------------------------------------------------------
    /**
     * 치수 측정(삼성 원본 이미지)에서:
     * - tflite로 잡힌 필름 bbox를 corner point 검출 전에 확장하는 padding(px)
     *
     * (기본값은 live(badBox) 단계와 동일하게 3px)
     */
    @Volatile var MEASURE_TFLITE_PAD_PX: Int = MEASURE_TFLITE_PAD_PX_50MP

    /**
     * 치수 측정(삼성 원본 이미지)에서:
     * - 4개 코너점(min/max)으로 만든 CV ROI bbox를 최종 crop 할 때 추가로 주는 padding(px)
     *
     * 요구사항: 기본 10px, 추후 쉽게 변경 가능해야 함
     */
    @Volatile var MEASURE_CORNER_PAD_PX: Int = MEASURE_CORNER_PAD_PX_50MP

}
