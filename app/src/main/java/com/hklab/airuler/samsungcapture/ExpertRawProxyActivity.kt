package com.hklab.airuler.samsungcapture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Expert RAW 실행 프록시 Activity
 *
 * - 200MP 설정 시 AutoReturnManager에서 호출됩니다.
 * - Expert RAW 앱 UI에서 촬영 → DCIM/Expert RAW 에 JPEG 저장
 * - ReturnWatcherService가 신규 파일을 감지하여 AIRuler로 복귀시킵니다.
 */
class ExpertRawProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val pkg = SamsungCaptureContract.EXPERT_RAW_PACKAGE
            val launch = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            if (launch != null) {
                startActivity(launch)
            } else {
                Toast.makeText(
                    this,
                    "Expert RAW 앱을 찾을 수 없습니다. 설치 후 다시 시도해 주세요.",
                    Toast.LENGTH_LONG
                ).show()

                // (폴백) 삼성 기본 카메라라도 띄워 사용자 조작이 막히지 않게 처리
                val samsung = packageManager.getLaunchIntentForPackage(SamsungCaptureContract.SAMSUNG_CAMERA_PACKAGE)
                samsung?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(it)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Expert RAW 실행 실패: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }
}
