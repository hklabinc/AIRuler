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

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_URI)
            .apply()
    }
}
