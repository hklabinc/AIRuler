package com.hklab.airuler.pipeline.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.View
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService

/**
 * CameraPipeline
 * - CameraX 바인딩
 * - 프리뷰/분석 해상도 선택
 * - (옵션) Flicker mitigation(AE antibanding / fixed FPS)
 *
 * ✅ 기존 MainActivity.startCamera/bindCameraUseCases/queryCameraResolutions의 동작을
 *    그대로 옮겨온 형태라, 기능 변화 없이 파일 역할만 분리됩니다.
 */
class CameraPipeline(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val cameraExecutor: ExecutorService,
    private val analyzer: (ImageProxy) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onToast: (String) -> Unit,
) {

    // ✅ PreviewView가 아직 레이아웃(크기 확정)되기 전에 CameraX를 바인딩하면,
    //    CameraX ViewPort가 1:1(정사각)로 잡혀서
    //    - Preview가 '확대/크롭'되어 보이거나
    //    - DetectionOverlay의 3분할 그리드가 1:1로 그려지는
    //    현상이 발생할 수 있습니다.
    //    (Settings 화면을 한번 들어갔다 오면 레이아웃이 완료된 상태에서 재바인딩되어 정상화되는 증상과 동일)
    //
    //    따라서 PreviewView의 실제 크기/Display가 준비된 이후에만 start()가 진행되도록
    //    레이아웃을 한 번 기다리는 가드를 둡니다. (기존 로직 영향 최소)
    private var pendingStartAfterLayout: Boolean = false
    private val startAfterLayoutListener = object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int
        ) {
            if (v.width <= 0 || v.height <= 0 || v.display == null) return
            v.removeOnLayoutChangeListener(this)
            pendingStartAfterLayout = false
            if (!activity.isFinishing && !activity.isDestroyed) {
                start()
            }
        }
    }

    private var imageAnalysis: ImageAnalysis? = null
    // ✅ Analyzer enable/disable (측정 대기/오버레이 등에서 불필요한 RGBA 변환/콜백을 멈추기 위함)
    @Volatile private var analyzerEnabled: Boolean = true


    // ---- Flicker/Banding mitigation (Camera2 Interop) ----
    private var preferredAeAntibandingMode: Int? = null
    private var preferredFixedFpsRange: Range<Int>? = null

    // 카메라 해상도 목록 및 선택값
    var previewSizes: List<Size> = emptyList()
        private set

    var selectedPreviewSize: Size? = null
        private set

    fun setSelectedPreviewSize(size: Size?) {
        selectedPreviewSize = size
    }

    fun start() {
        // ✅ PreviewView 레이아웃(크기) 확정 전 바인딩 방지 (초기 진입 시 확대/정사각 오버레이 버그 예방)
        if (previewView.width <= 0 || previewView.height <= 0 || previewView.display == null) {
            if (!pendingStartAfterLayout) {
                pendingStartAfterLayout = true
                previewView.addOnLayoutChangeListener(startAfterLayoutListener)
            }
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 화면 회전에 맞춰 타겟 회전값 설정
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

            // 해상도 목록 먼저 가져오기
            if (previewSizes.isEmpty()) {
                queryCameraResolutions()
            }

            // 1) Flicker mitigation ON으로 먼저 시도
            try {
                bindCameraUseCases(
                    cameraProvider = cameraProvider,
                    rotation = rotation,
                    enableFlickerMitigation = true
                )
                onStatus("Camera started (flicker mitigation ON)")
            } catch (e: Exception) {
                Log.w("CAMERA", "Bind failed with flicker mitigation. Fallback to default.", e)
                onStatus("Flicker mitigation bind 실패 → 기본 설정으로 재시도: ${e.message}")

                // 2) 실패하면 기존 동작(옵션 미적용)으로 폴백
                try {
                    bindCameraUseCases(
                        cameraProvider = cameraProvider,
                        rotation = rotation,
                        enableFlickerMitigation = false
                    )
                    onStatus("Camera started (flicker mitigation OFF - fallback)")
                } catch (e2: Exception) {
                    onToast("Camera bind 실패: ${e2.message}")
                }
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop() {
        runCatching {
            val provider = ProcessCameraProvider.getInstance(activity).get()
            provider.unbindAll()
        }
        imageAnalysis = null
    }

    /**
     * ✅ 분석(Analyzer)만 일시 중단
     * - Preview는 유지되지만 ImageAnalysis가 RGBA 변환/콜백을 하지 않아 발열/배터리 감소에 효과적
     */
    fun pauseAnalyzer() {
        analyzerEnabled = false
        imageAnalysis?.clearAnalyzer()
    }

    /** 분석(Analyzer) 재개 */
    fun resumeAnalyzer() {
        analyzerEnabled = true
        imageAnalysis?.setAnalyzer(cameraExecutor) { image ->
            analyzer(image)
        }
    }


    private fun bindCameraUseCases(
        cameraProvider: ProcessCameraProvider,
        rotation: Int,
        enableFlickerMitigation: Boolean
    ) {
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Preview
        val previewBuilder = Preview.Builder()
            .setTargetRotation(rotation)
        selectedPreviewSize?.let { previewBuilder.setTargetResolution(it) }
        if (enableFlickerMitigation) applyCamera2FlickerOptions(previewBuilder)

        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        // ImageAnalysis
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(rotation)
        selectedPreviewSize?.let { analysisBuilder.setTargetResolution(it) }
        if (enableFlickerMitigation) applyCamera2FlickerOptions(analysisBuilder)

        imageAnalysis = analysisBuilder.build().also { analysis ->
            if (analyzerEnabled) {
                analysis.setAnalyzer(cameraExecutor) { image ->
                    analyzer(image)
                }
            } else {
                // analyzerEnabled=false 상태(대기/절전)에서는 Analyzer를 붙이지 않습니다.
                analysis.clearAnalyzer()
            }
        }

        // 바인딩
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            activity,
            cameraSelector,
            preview,
            imageAnalysis
        )
    }

    // ---- Camera2 request options 적용 (Preview) ----
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCamera2FlickerOptions(builder: Preview.Builder) {
        val extender = Camera2Interop.Extender(builder)

        preferredAeAntibandingMode?.let { mode ->
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                mode
            )
        }

        preferredFixedFpsRange?.let { range ->
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                range
            )
        }
    }

    // ---- Camera2 request options 적용 (ImageAnalysis) ----
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCamera2FlickerOptions(builder: ImageAnalysis.Builder) {
        val extender = Camera2Interop.Extender(builder)

        preferredAeAntibandingMode?.let { mode ->
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                mode
            )
        }

        preferredFixedFpsRange?.let { range ->
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                range
            )
        }
    }

    /**
     * Camera2로부터 해상도 목록을 가져와 Preview 리스트 생성
     * (기존 MainActivity.queryCameraResolutions 동작 동일)
     */
    private fun queryCameraResolutions() {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        var backCameraId: String? = null

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                backCameraId = id
                break
            }
        }

        if (backCameraId == null) {
            onToast("뒤 카메라를 찾을 수 없습니다")
            return
        }

        val chars = manager.getCameraCharacteristics(backCameraId)

        // ---- Flicker/Banding mitigation 후보 계산 ----
        run {
            val antiModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)

            preferredAeAntibandingMode = when {
                antiModes == null -> null
                // 한국(60Hz)에서 가장 효과적: 60Hz 우선
                antiModes.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ) ->
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
                // 60Hz가 없으면 AUTO(기기/국가별 자동)
                antiModes.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO) ->
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
                // 마지막으로 50Hz
                antiModes.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ) ->
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
                else -> null
            }

            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

            // 기존 동작 영향 최소화: "고정" FPS만 적용(없으면 적용 안 함)
            preferredFixedFpsRange =
                fpsRanges?.firstOrNull { it.lower == 30 && it.upper == 30 }
                    ?: fpsRanges?.firstOrNull { it.lower == 60 && it.upper == 60 }

            Log.d(
                "CAMERA",
                "FlickerMitigation candidates: antibanding=$preferredAeAntibandingMode, fixedFps=$preferredFixedFpsRange"
            )
        }

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val preview = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()

        previewSizes = preview.sortedByDescending { it.width * it.height }

        // ★ 16:9 비율에 가장 가까운 preview 해상도를 고른다 (가능하면 1280 근처)
        val targetAspect = 16f / 9f

        fun aspectDiff(size: Size): Float {
            val a = size.width.toFloat() / size.height.toFloat()
            return kotlin.math.abs(a - targetAspect)
        }

        val previewCandidates = previewSizes
            .filter { it.width > it.height } // 가로 모드 후보만
            .sortedWith(
                compareBy<Size> { aspectDiff(it) }
                    .thenBy { kotlin.math.abs(it.width - 1280) }
            )

        // ✅ 이미 선택된 해상도가 있으면(세션 복원/Settings에서 선택) 그대로 유지
        //    - 단, 해당 해상도가 지원 목록에 없으면 16:9 근접 기본값으로 fallback
        val defaultSel = when {
            previewCandidates.isNotEmpty() -> previewCandidates.first()
            else -> previewSizes.firstOrNull()
        }

        val cur = selectedPreviewSize
        selectedPreviewSize = if (cur != null) {
            previewSizes.firstOrNull { it.width == cur.width && it.height == cur.height } ?: defaultSel
        } else {
            defaultSel
        }

        val sel = selectedPreviewSize
        onStatus(
            "Selected preview=${sel?.width}x${sel?.height}"
        )
    }
}
