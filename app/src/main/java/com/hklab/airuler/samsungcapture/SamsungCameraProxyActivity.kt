package com.hklab.airuler.samsungcapture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast

class SamsungCameraProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val samsungPkg = SamsungCaptureContract.SAMSUNG_CAMERA_PACKAGE

            val launchSamsung = packageManager.getLaunchIntentForPackage(samsungPkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            // 폴백: 시스템 전체 카메라 UI
            val genericStill = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            startActivity(launchSamsung ?: genericStill)
        } catch (e: Exception) {
            Toast.makeText(this, "삼성 카메라 실행 실패: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }
}
