package com.hklab.airuler.model

import android.content.Context

object ModelStore {
    private const val PREF = "airuler_model_prefs"
    private const val KEY_SELECTED = "selected_model"

    const val EXTRA_SELECTED_MODEL = "extra_selected_model"

    fun save(context: Context, modelName: String) {
        val canonical = ModelNameCompat.canonical(modelName)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED, canonical.ifBlank { null })
            .apply()
    }

    fun get(context: Context): String? {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val stored = sp.getString(KEY_SELECTED, null)
        val canonical = ModelNameCompat.canonical(stored)
        if (canonical.isBlank()) return null
        if (canonical != stored) {
            sp.edit().putString(KEY_SELECTED, canonical).apply()
        }
        return canonical
    }
}
