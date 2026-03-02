package com.hklab.airuler.samsungcapture

import android.content.Context
import android.net.Uri

object SamsungCaptureStore {
    private const val PREF = "samsung_capture_store"
    private const val KEY_PENDING_URI = "pending_uri"

    fun save(context: Context, uri: Uri) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_URI, uri.toString())
            .apply()
    }

    /** 읽고 즉시 삭제(중복 표시 방지) */
    fun consume(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val s = prefs.getString(KEY_PENDING_URI, null) ?: return null
        prefs.edit().remove(KEY_PENDING_URI).apply()
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    /**
     * 삭제하지 않고 현재 pending uri를 조회합니다.
     *
     * - ReturnWatcher/Accessibility 쪽에서 "이미 Activity가 capture를 소비했는지" 확인하는 용도.
     * - consume()와 달리 값을 유지하므로, 재시도 로직의 안정성을 해치지 않습니다.
     */
    fun peek(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val s = prefs.getString(KEY_PENDING_URI, null) ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    fun hasPending(context: Context): Boolean {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .contains(KEY_PENDING_URI)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_URI)
            .apply()
    }
}
