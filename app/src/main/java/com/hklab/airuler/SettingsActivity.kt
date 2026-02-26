package com.hklab.airuler

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.hklab.airuler.databinding.ActivitySettingsBinding
import androidx.activity.addCallback
import android.widget.SeekBar

/**
 * 카메라 Preview / Capture 해상도 선택 화면
 *  - MainActivity 에서 전달한 문자열 리스트 사용
 *  - 결과는 "WIDTHxHEIGHT" 형식으로 돌려줌
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREVIEW_LIST = "preview_list"
        const val EXTRA_PREVIEW_SELECTED = "preview_selected"
        const val RESULT_PREVIEW = "result_preview"

        const val EXTRA_SHOW_GRID = "extra_show_grid"
        const val EXTRA_SHOW_FILM_BOX = "extra_show_box"
        const val EXTRA_SHOW_CONF = "extra_show_conf"
        const val EXTRA_SHOW_TILT = "extra_show_tilt"
        const val EXTRA_SHOW_ANGLE = "extra_show_angle"
        const val EXTRA_TILT_MODE = "extra_tilt_mode"

        const val RESULT_SHOW_GRID = "result_show_grid"
        const val RESULT_SHOW_FILM_BOX = "result_show_yolo_box"
        const val RESULT_SHOW_CONF = "result_show_conf"
        const val RESULT_SHOW_TILT = "result_show_tilt"
        const val RESULT_SHOW_ANGLE = "result_show_angle"
        const val RESULT_TILT_MODE = "result_tilt_mode"

        // ✅ View Options: Good 라벨 bbox 표시
        const val EXTRA_SHOW_GOOD_BOX = "extra_show_good_box"
        const val EXTRA_SHOW_BAD_BOX = "extra_show_bad_box"
        const val EXTRA_SHOW_HAND = "extra_show_hand"
        const val EXTRA_SHOW_MOTION = "extra_show_motion"
        const val EXTRA_SHOW_TRACK = "extra_show_track"

        const val RESULT_SHOW_GOOD_BOX = "result_show_good_box"
        const val RESULT_SHOW_BAD_BOX = "result_show_bad_box"
        const val RESULT_SHOW_HAND = "result_show_hand"
        const val RESULT_SHOW_MOTION = "result_show_motion"
        const val RESULT_SHOW_TRACK = "result_show_track"

        const val EXTRA_YOLO_INTERVAL = "extra_yolo_interval"
        const val RESULT_YOLO_INTERVAL = "result_yolo_interval"

        const val EXTRA_DIRECTION_CHECK = "extra_direction_check"
        const val RESULT_DIRECTION_CHECK = "result_direction_check"

        // ✅ DEBUG/LOG_FILE_SAVE 토글
        const val EXTRA_DEBUG_ENABLED = "extra_debug_enabled"
        const val RESULT_DEBUG_ENABLED = "result_debug_enabled"
        const val EXTRA_LOG_FILE_SAVE_ENABLED = "extra_log_file_save_enabled"
        const val RESULT_LOG_FILE_SAVE_ENABLED = "result_log_file_save_enabled"

        // ✅ 치수 재기 방법
        // 값: "NONE" | "MANUAL" | "AUTO"
        const val EXTRA_MEASURE_MODE = "extra_measure_mode"
        const val RESULT_MEASURE_MODE = "result_measure_mode"

        // ✅ 치수 재기 '계산 방법'
        // 값: "GRID" | "RULER"
        const val EXTRA_MEASUREMENT_METHOD = "extra_measurement_method"
        const val RESULT_MEASUREMENT_METHOD = "result_measurement_method"

        // ✅ Capture MP 선택 (50 / 200)
        const val EXTRA_CAPTURE_MP = "extra_capture_mp"
        const val RESULT_CAPTURE_MP = "result_capture_mp"

        // ✅ Online Offset Update(=Calibration) ON/OFF
        //  - true  : offset update 기능 동작(기본값)
        //  - false : offset update 중지(현재 offset만 적용)
        const val EXTRA_OFFSET_UPDATE_ENABLED = "extra_offset_update_enabled"
        const val RESULT_OFFSET_UPDATE_ENABLED = "result_offset_update_enabled"

        // ✅ 서버 업로드 자동 전송 ON/OFF
        const val EXTRA_UPLOAD_TO_SERVER_ENABLED = "extra_upload_to_server_enabled"
        const val RESULT_UPLOAD_TO_SERVER_ENABLED = "result_upload_to_server_enabled"

        // ✅ 결과 이미지 저장(DCIM/Result) ON/OFF
        const val EXTRA_SAVE_RESULT_ENABLED = "extra_save_result_enabled"
        const val RESULT_SAVE_RESULT_ENABLED = "result_save_result_enabled"
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1) 해상도 스피너 세팅
        val previewList = intent.getStringArrayListExtra(EXTRA_PREVIEW_LIST) ?: arrayListOf()
        val previewCurrent = intent.getStringExtra(EXTRA_PREVIEW_SELECTED)

        val previewAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            previewList
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        binding.spinnerPreview.adapter = previewAdapter

        previewCurrent?.let {
            val idx = previewList.indexOf(it)
            if (idx >= 0) binding.spinnerPreview.setSelection(idx)
        }

        // 2) Overlay / Tilt 옵션 초기값
        val showGrid = intent.getBooleanExtra(EXTRA_SHOW_GRID, GlobalParams.Defaults.SHOW_GUIDE_GRID)
        val showFilmBox = intent.getBooleanExtra(EXTRA_SHOW_FILM_BOX, GlobalParams.Defaults.SHOW_FILM_BOX_OVERLAY)
        val showConf = intent.getBooleanExtra(EXTRA_SHOW_CONF, GlobalParams.Defaults.SHOW_CONF_ON_BOX)
        val showAngle = intent.getBooleanExtra(EXTRA_SHOW_ANGLE, GlobalParams.Defaults.SHOW_ANGLE_ON_PREVIEW)
        val showTilt = intent.getBooleanExtra(EXTRA_SHOW_TILT, GlobalParams.Defaults.SHOW_TILT)
        val showGoodBox = intent.getBooleanExtra(EXTRA_SHOW_GOOD_BOX, GlobalParams.Defaults.SHOW_GOOD_BOX_OVERLAY)
        val showBadBox = intent.getBooleanExtra(EXTRA_SHOW_BAD_BOX, GlobalParams.Defaults.SHOW_BAD_BOX_OVERLAY)
        val showHand = intent.getBooleanExtra(EXTRA_SHOW_HAND, GlobalParams.Defaults.SHOW_HAND_OVERLAY)
        val showMotion = intent.getBooleanExtra(EXTRA_SHOW_MOTION, GlobalParams.Defaults.SHOW_MOTION_OVERLAY)
        val showTrack = intent.getBooleanExtra(EXTRA_SHOW_TRACK, GlobalParams.Defaults.SHOW_TRACK_OVERLAY)
        val tiltModeName = intent.getStringExtra(EXTRA_TILT_MODE) ?: GlobalParams.Defaults.TILT_MODE.name
        val useDirectionCheck = intent.getBooleanExtra(EXTRA_DIRECTION_CHECK, GlobalParams.Defaults.DIRECTION_CHECK_ENABLED)

        // ✅ 치수 재기 방법(기본 AUTO)
        val measureModeName = intent.getStringExtra(EXTRA_MEASURE_MODE) ?: GlobalParams.Defaults.MEASURE_MODE.name

        // ✅ 치수 재기 계산 방법(기본 GRID)
        val measurementMethodName = intent.getStringExtra(EXTRA_MEASUREMENT_METHOD) ?: GlobalParams.Defaults.MEASUREMENT_METHOD.name

        // ✅ Capture MP (기본 50)
        val captureMp = intent.getIntExtra(EXTRA_CAPTURE_MP, GlobalParams.Defaults.CAPTURE_MP)

        // ✅ Online offset update (기본 ON)
        val offsetUpdateEnabled = intent.getBooleanExtra(EXTRA_OFFSET_UPDATE_ENABLED, GlobalParams.Defaults.OFFSET_CALIBRATION_ENABLED)


        // ✅ 서버 업로드 자동 전송 (기본 ON)
        val uploadToServerEnabled = intent.getBooleanExtra(EXTRA_UPLOAD_TO_SERVER_ENABLED, GlobalParams.Defaults.UPLOAD_TO_SERVER_ENABLED)

        // ✅ 결과 저장 (기본 ON)
        val saveResultEnabled = intent.getBooleanExtra(EXTRA_SAVE_RESULT_ENABLED, GlobalParams.Defaults.SAVE_RESULT_ENABLED)

        binding.chkGrid.isChecked = showGrid
        binding.chkFilmBox.isChecked = showFilmBox
        binding.chkConf.isChecked = showConf
        binding.chkAngle.isChecked = showAngle
        binding.chkTilt.isChecked = showTilt
        binding.switchDirectionCheck.isChecked = useDirectionCheck

        // ✅ Debug / Log file save 토글
        val debugEnabled = intent.getBooleanExtra(EXTRA_DEBUG_ENABLED, GlobalParams.DEBUG)
        val logFileSaveEnabled = intent.getBooleanExtra(EXTRA_LOG_FILE_SAVE_ENABLED, GlobalParams.LOG_FILE_SAVE)
        binding.switchDebug.isChecked = debugEnabled
        binding.switchLogFileSave.isChecked = logFileSaveEnabled
        // ✅ UI 상에서 "Diff" 대신 "Good Box"로 사용됩니다.
        binding.chkGoodBox.isChecked = showGoodBox
        binding.chkBadBox.isChecked = showBadBox
        binding.chkHand.isChecked = showHand
        binding.chkMotion.isChecked = showMotion
        binding.chkTrack.isChecked = showTrack

        // ✅ 치수 재기 방법 라디오
        when (measureModeName) {
            "NONE" -> binding.rbMeasureOff.isChecked = true
            "MANUAL" -> binding.rbMeasureManual.isChecked = true
            else -> binding.rbMeasureAuto.isChecked = true
        }


        // ✅ Measurement Mode가 AUTO일 때만 Measurement Resolution(Capture MP)을 활성화
        fun updateCaptureMpEnabledByMeasureMode() {
            val enabled = binding.rbMeasureAuto.isChecked
            // 전체 Row는 alpha로만 표시(실제 enable/disable은 RadioButton에 적용)
            binding.layoutCaptureMpRow.alpha = if (enabled) 1.0f else 0.4f
            binding.rbCapture50.isEnabled = enabled
            binding.rbCapture200.isEnabled = enabled
        }
        updateCaptureMpEnabledByMeasureMode()
        binding.rgMeasureMode.setOnCheckedChangeListener { _, _ ->
            updateCaptureMpEnabledByMeasureMode()
        }

        // ✅ 치수 재기 계산 방법 라디오
        when (measurementMethodName) {
            "RULER" -> binding.rbMethodRuler.isChecked = true
            else -> binding.rbMethodGrid.isChecked = true
        }


        // ✅ Capture MP 라디오
        if (captureMp >= 200) {
            binding.rbCapture200.isChecked = true
        } else {
            binding.rbCapture50.isChecked = true
        }

        // 200MP 선택 시 Measurement Method는 Ruler로 고정(요구사항)
        fun updateMethodLockByMp() {
            val is200 = binding.rbCapture200.isChecked
            if (is200) {
                binding.rbMethodRuler.isChecked = true
                binding.rbMethodGrid.isEnabled = false
                binding.rbMethodGrid.alpha = 0.4f
            } else {
                binding.rbMethodGrid.isEnabled = true
                binding.rbMethodGrid.alpha = 1.0f
            }
        }
        updateMethodLockByMp()

        binding.rgCaptureMp.setOnCheckedChangeListener { _, _ ->
            updateMethodLockByMp()
        }

        // ✅ Offset Calibration (Online offset update) 토글
        binding.switchOffsetCalibration.isChecked = offsetUpdateEnabled


        // ✅ Upload to server 토글
        binding.switchUploadToServer.isChecked = uploadToServerEnabled

        // ✅ Save result 토글
        binding.switchSaveResult.isChecked = saveResultEnabled

        when (tiltModeName) {
            "NONE" -> binding.radioTiltNone.isChecked = true
            else   -> binding.radioTiltHough.isChecked = true   // 기본값 Hough line
        }

        val yoloInterval = intent.getIntExtra(EXTRA_YOLO_INTERVAL, GlobalParams.Defaults.YOLO_INTERVAL_DEFAULT)
            .coerceIn(GlobalParams.Defaults.YOLO_INTERVAL_MIN, GlobalParams.Defaults.YOLO_INTERVAL_MAX)
        // 1..10
        binding.seekYoloInterval.max = (GlobalParams.Defaults.YOLO_INTERVAL_MAX - GlobalParams.Defaults.YOLO_INTERVAL_MIN)
        binding.seekYoloInterval.progress = (yoloInterval - GlobalParams.Defaults.YOLO_INTERVAL_MIN)
            .coerceIn(0, binding.seekYoloInterval.max)
        binding.txtYoloIntervalLabel.text = "Every ${(binding.seekYoloInterval.progress + GlobalParams.Defaults.YOLO_INTERVAL_MIN)} frames"

        binding.seekYoloInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.txtYoloIntervalLabel.text = "Every ${(progress + GlobalParams.Defaults.YOLO_INTERVAL_MIN)} frames"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        onBackPressedDispatcher.addCallback(this) {
            applyAndFinish()
        }

        // ✅ Apply 버튼
        binding.btnApply.setOnClickListener {
            applyAndFinish()
        }
    }

    private fun applyAndFinish() {
        val previewSel = binding.spinnerPreview.selectedItem as? String
        val tiltMode = if (binding.radioTiltHough.isChecked) "HOUGH" else "NONE"
        val yoloInterval = binding.seekYoloInterval.progress + GlobalParams.Defaults.YOLO_INTERVAL_MIN

        val measureMode = when {
            binding.rbMeasureOff.isChecked -> "NONE"
            binding.rbMeasureManual.isChecked -> "MANUAL"
            else -> "AUTO"
        }

        val captureMp = if (binding.rbCapture200.isChecked) 200 else 50

        // ✅ 200MP 선택 시에는 Measurement Method를 RULER로 강제
        val measurementMethod = if (captureMp >= 200) {
            "RULER"
        } else {
            if (binding.rbMethodRuler.isChecked) "RULER" else "GRID"
        }


        val offsetUpdate = binding.switchOffsetCalibration.isChecked

        val data = intent.apply {
            putExtra(RESULT_PREVIEW, previewSel)
            putExtra(RESULT_SHOW_GRID, binding.chkGrid.isChecked)
            putExtra(RESULT_SHOW_FILM_BOX, binding.chkFilmBox.isChecked)
            putExtra(RESULT_SHOW_CONF, binding.chkConf.isChecked)
            putExtra(RESULT_SHOW_ANGLE, binding.chkAngle.isChecked)
            putExtra(RESULT_SHOW_TILT, binding.chkTilt.isChecked)
            putExtra(RESULT_SHOW_GOOD_BOX, binding.chkGoodBox.isChecked)
            putExtra(RESULT_SHOW_BAD_BOX, binding.chkBadBox.isChecked)
            putExtra(RESULT_SHOW_HAND, binding.chkHand.isChecked)
            putExtra(RESULT_SHOW_MOTION, binding.chkMotion.isChecked)
            putExtra(RESULT_SHOW_TRACK, binding.chkTrack.isChecked)
            putExtra(RESULT_TILT_MODE, tiltMode)
            putExtra(RESULT_YOLO_INTERVAL, yoloInterval)
            putExtra(RESULT_DIRECTION_CHECK, binding.switchDirectionCheck.isChecked)
            putExtra(RESULT_DEBUG_ENABLED, binding.switchDebug.isChecked)
            putExtra(RESULT_LOG_FILE_SAVE_ENABLED, binding.switchLogFileSave.isChecked)
            putExtra(RESULT_MEASURE_MODE, measureMode)
            putExtra(RESULT_MEASUREMENT_METHOD, measurementMethod)
            putExtra(RESULT_CAPTURE_MP, captureMp)
            putExtra(RESULT_OFFSET_UPDATE_ENABLED, offsetUpdate)
            putExtra(RESULT_UPLOAD_TO_SERVER_ENABLED, binding.switchUploadToServer.isChecked)
            putExtra(RESULT_SAVE_RESULT_ENABLED, binding.switchSaveResult.isChecked)
        }

        setResult(Activity.RESULT_OK, data)
        finish()
    }

    @SuppressLint("MissingSuperCall")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Cancel/Apply 버튼 없이, 뒤로가기 시 항상 현재 설정을 적용
        applyAndFinish()
    }

}
