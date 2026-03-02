package com.hklab.airuler.pipeline.autoreturn

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hklab.airuler.autoreturn.ReturnAccessibilityService
import com.hklab.airuler.autoreturn.ReturnWatcherService
import com.hklab.airuler.pipeline.state.AppRuntimeState
import com.hklab.airuler.pipeline.state.AppSessionSettings
import com.hklab.airuler.samsungcapture.SamsungCameraProxyActivity
import com.hklab.airuler.samsungcapture.ExpertRawProxyActivity
import com.hklab.airuler.samsungcapture.SamsungCaptureContract
import com.hklab.airuler.samsungcapture.SamsungCaptureStore
import com.hklab.airuler.log.AirulerFileLogger

/**
 * AutoReturn
 * - 삼성 카메라 실행
 * - 촬영 완료 후 ReturnWatcherService가 전달한 Uri(또는 Store)를 소비
 * - 중복 Uri 처리 방지
 */
class AutoReturnManager(
    private val activity: AppCompatActivity,
    private val state: AppRuntimeState,
    private val appendStatus: (String) -> Unit,
    private val onCaptured: (Uri) -> Unit,
) {

    /**
     * Samsung 복귀 시 ReturnWatcherService가 bring-to-front를 여러 번 시도하면
     * 동일 Uri가 여러 번 전달될 수 있습니다.
     * 중복 분석/깜빡임 방지를 위해 1회만 처리합니다.
     */
    private fun shouldHandleSamsungCapturedUri(uri: Uri, windowMs: Long = 60_000L): Boolean {
        val key = uri.toString()
        val now = SystemClock.elapsedRealtime()

        val lastKey = lastHandledSamsungCapturedUri
        val lastAt = lastHandledSamsungCapturedAtMs

        // 이미 캡처 화면이 떠있고 Uri까지 동일하면 무조건 중복으로 간주
        if (state.capturedOverlayVisible && lastKey == key) {
            Log.d("AutoReturn", "Ignore duplicate samsung capture (overlay visible): $key")
            AirulerFileLogger.w("AutoReturn", "Ignore duplicate capture (overlay visible). uri=$key")
            return false
        }

        if (lastKey == key && (now - lastAt) < windowMs) {
            Log.d("AutoReturn", "Ignore duplicate samsung capture: $key (dt=${now - lastAt}ms)")
            AirulerFileLogger.w("AutoReturn", "Ignore duplicate capture (dt=${now - lastAt}ms). uri=$key")
            return false
        }

        lastHandledSamsungCapturedUri = key
        lastHandledSamsungCapturedAtMs = now
        return true
    }

    
    fun launchSamsungCamera() {
        // 캡처 오버레이(삼성/캡처 화면) 떠 있을 때는 무시
        if (state.capturedOverlayVisible) {
            appendStatus("이미 캡처 화면이 표시 중입니다.")
            AirulerFileLogger.w("AutoReturn", "launchSamsungCamera ignored (overlay visible)")
            return
        }

        // ✅ Settings에서 선택한 Capture MP(또는 Retry 등 1회성 override)에 따라 외부 카메라를 분기합니다.
        //  - 50MP  : 기존 삼성 카메라(Pro/50MP) + DCIM/Camera 감지
        //  - 200MP : Expert RAW + DCIM/Expert RAW 감지
        //
        // Retry 버튼 등에서 "이번 캡처 1회"만 MP를 강제할 수 있으므로,
        // state.nextCaptureMegapixelOverride 를 우선 적용합니다.
        val effectiveMp = state.nextCaptureMegapixelOverride ?: AppSessionSettings.captureMegapixel

        AirulerFileLogger.i(
            "AutoReturn",
            "launchSamsungCamera() effectiveMp=$effectiveMp overrideMp=${state.nextCaptureMegapixelOverride}"
        )

        if (effectiveMp >= 200) {
            launchExpertRaw()
            return
        }

        launchSamsungCameraInternal()
    }

    private fun launchSamsungCameraInternal() {
        // ✅ EXTRA_OUTPUT 미사용: 삼성 카메라 전체 UI(프로 모드/50MP)로 촬영 후
        //    DCIM/Camera에 저장되는 신규 JPEG를 감지해 자동으로 앱으로 복귀합니다.
        val launchAt = System.currentTimeMillis()
        SamsungCaptureStore.clear(activity)

        AirulerFileLogger.i("AutoReturn", "Start ReturnWatcherService (SOURCE_SAMSUNG_CAMERA) launchAt=$launchAt")

        // ✅ 접근성 서비스가 꺼져 있으면 자동 복귀가 불안정할 수 있으니 안내(Toast)
        if (!ReturnAccessibilityService.isEnabled(activity)) {
            Toast.makeText(
                activity,
                "접근성 설정이 꺼져 있어 자동 복귀가 제한될 수 있습니다.\n설정에서 AIRuler 접근성 서비스를 켜주세요.",
                Toast.LENGTH_LONG
            ).show()
        }

        val svc = Intent(activity, ReturnWatcherService::class.java)
            .putExtra(SamsungCaptureContract.EXTRA_LAUNCH_AT, launchAt)
            .putExtra(SamsungCaptureContract.EXTRA_CAPTURE_SOURCE, SamsungCaptureContract.SOURCE_SAMSUNG_CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) activity.startForegroundService(svc)
        else activity.startService(svc)

        activity.startActivity(Intent(activity, SamsungCameraProxyActivity::class.java))
        appendStatus("삼성 카메라 실행: 프로 모드로 촬영 후 자동 복귀합니다. (저장은 DCIM/Camera)")
    }

    private fun launchExpertRaw() {
        val launchAt = System.currentTimeMillis()
        SamsungCaptureStore.clear(activity)

        AirulerFileLogger.i("AutoReturn", "Start ReturnWatcherService (SOURCE_EXPERT_RAW) launchAt=$launchAt")

        val svc = Intent(activity, ReturnWatcherService::class.java)
            .putExtra(SamsungCaptureContract.EXTRA_LAUNCH_AT, launchAt)
            .putExtra(SamsungCaptureContract.EXTRA_CAPTURE_SOURCE, SamsungCaptureContract.SOURCE_EXPERT_RAW)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) activity.startForegroundService(svc)
        else activity.startService(svc)

        activity.startActivity(Intent(activity, ExpertRawProxyActivity::class.java))
        appendStatus("Expert RAW 실행: 200MP로 촬영 후 자동 복귀합니다. (저장은 DCIM/Expert RAW)")
    }

    fun consumePendingSamsungCapture(intent: Intent?) {
        // 1) Intent extra 우선
        val extra = intent?.getStringExtra(SamsungCaptureContract.EXTRA_CAPTURED_URI)
        val uriFromIntent = extra?.let { runCatching { Uri.parse(it) }.getOrNull() }

        if (uriFromIntent != null) {
            // 같은 Activity로 여러 번 들어올 수 있어 extra는 제거
            intent.removeExtra(SamsungCaptureContract.EXTRA_CAPTURED_URI)

            // Store는 항상 정리(중복/잔재 방지)
            SamsungCaptureStore.clear(activity)

            // ✅ 중복이면 shouldHandle에서 warning 로그가 남으므로,
            //    여기서는 "실제로 처리할 때"만 info 로그를 남깁니다.
            if (shouldHandleSamsungCapturedUri(uriFromIntent)) {
                AirulerFileLogger.i("AutoReturn", "consumePendingSamsungCapture(intent) uri=$uriFromIntent")
                onCaptured(uriFromIntent)
            }
            return
        }

        // 2) 폴백: Store에 저장된 Uri 소비
        val uriFromStore = SamsungCaptureStore.consume(activity)
        if (uriFromStore != null) {
            if (shouldHandleSamsungCapturedUri(uriFromStore)) {
                AirulerFileLogger.i("AutoReturn", "consumePendingSamsungCapture(store) uri=$uriFromStore")
                onCaptured(uriFromStore)
            }
        }
    }

    private companion object {
        // ✅ 동일 Uri 중복 처리 방지 (프로세스 내 전역)
        @Volatile private var lastHandledSamsungCapturedUri: String? = null
        @Volatile private var lastHandledSamsungCapturedAtMs: Long = 0L
    }
}
