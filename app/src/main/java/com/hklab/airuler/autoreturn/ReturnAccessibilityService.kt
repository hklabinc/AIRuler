package com.hklab.airuler.autoreturn

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.hklab.airuler.MainActivity
import com.hklab.airuler.samsungcapture.SamsungCaptureContract
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

class ReturnAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var sInstance: ReturnAccessibilityService? = null

        fun instance(): ReturnAccessibilityService? = sInstance

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val list = am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            )
            return list.any {
                val si = it.resolveInfo?.serviceInfo
                si?.packageName == context.packageName &&
                        si.name?.endsWith("ReturnAccessibilityService") == true
            }
        }

        // --- Samsung Camera "확인" 자동 클릭 (ACTION_IMAGE_CAPTURE 리뷰 화면)
        private const val PREF = "return_accessibility"
        private const val KEY_AUTO_CONFIRM_UNTIL_MS = "auto_confirm_until_ms"

        private const val SAMSUNG_CAMERA_PKG = "com.sec.android.app.camera"
        private const val TXT_CONFIRM = "확인"
        private const val TXT_RETRY = "다시 시도"

        /** 카메라 launch 직전에만 호출: N ms 동안만 “확인 자동 클릭”을 잠깐 활성화 */
        fun armSamsungAutoConfirm(context: Context, timeoutMs: Long = 12_000L) {
            val until = SystemClock.elapsedRealtime() + timeoutMs
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_AUTO_CONFIRM_UNTIL_MS, until)
                .apply()
        }

        fun disarmSamsungAutoConfirm(context: Context) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_AUTO_CONFIRM_UNTIL_MS)
                .apply()
        }

        private fun isSamsungAutoConfirmArmed(context: Context): Boolean {
            val until = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getLong(KEY_AUTO_CONFIRM_UNTIL_MS, 0L)
            return until > SystemClock.elapsedRealtime()
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); sInstance = this }
    override fun onUnbind(intent: Intent?): Boolean { sInstance = null; return super.onUnbind(intent) }

    // 과도한 탐색 방지용 throttle
    private var lastScanAtMs: Long = 0L
    private var clickedForThisArm: Boolean = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // 1) 삼성 카메라에서만 동작
        if (event.packageName?.toString() != SAMSUNG_CAMERA_PKG) return

        // 2) arm 된 시간 동안에만 동작 (평소엔 완전히 idle)
        if (!isSamsungAutoConfirmArmed(this)) {
            clickedForThisArm = false
            return
        }
        if (clickedForThisArm) return

        // 3) 의미 있는 이벤트만 처리
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> return
        }

        // 4) throttle
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanAtMs < 120L) return
        lastScanAtMs = now

        val root = rootInActiveWindow ?: return

        // 5) “다시 시도 / 확인” 리뷰 화면인지 확인(오작동 방지)
        val hasRetry = root.findAccessibilityNodeInfosByText(TXT_RETRY).isNotEmpty()
        if (!hasRetry) return

        // 6) '확인' 버튼 클릭
        val confirm = root.findAccessibilityNodeInfosByText(TXT_CONFIRM)
            .firstOrNull { it.isVisibleToUser }
            ?: return

        val clicked = clickNodeOrClickableParent(confirm)
        if (clicked) {
            clickedForThisArm = true
            // 남아있으면 다음 카메라 UI에서도 눌러버릴 수 있으니 바로 해제
            disarmSamsungAutoConfirm(this)
        }
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
        }
        return false
    }

    override fun onInterrupt() {}

    /** HOME → AIRuler 실행 (+ 촬영된 Uri 전달) */
    fun returnToAiruler(capturedUri: Uri?) {
        // 1) HOME: 삼성 카메라 Pro 모드 유지 목적
        performGlobalAction(GLOBAL_ACTION_HOME)

        val main = Handler(Looper.getMainLooper())
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            capturedUri?.let {
                putExtra(SamsungCaptureContract.EXTRA_CAPTURED_URI, it.toString())
            }
        }

        // 전환 안정화를 위해 여러 번 재시도
        longArrayOf(150L, 300L, 600L).forEach { delay ->
            main.postDelayed({ startActivity(intent) }, delay)
        }
    }
}
