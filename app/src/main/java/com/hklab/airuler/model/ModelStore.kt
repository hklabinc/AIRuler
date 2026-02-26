package com.hklab.airuler.model

import android.content.Context

object ModelStore {
    private const val PREF = "airuler_model_prefs"
    private const val KEY_SELECTED = "selected_model"

    const val EXTRA_SELECTED_MODEL = "extra_selected_model"

    fun save(context: Context, modelName: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED, modelName)
            .apply()
    }

    fun get(context: Context): String? {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, null)
    }
}
