package com.hklab.airuler.model

import android.content.Context

object ModelRegistry {
    private const val PREF = "model_registry"
    private const val KEY_ENABLED = "enabled_models"

    /** 최초 1회: assets/overlay 목록으로 seed. 이후엔 prefs에 저장된 목록 사용 */
    fun getEnabledModels(context: Context): MutableList<String> {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val saved = sp.getStringSet(KEY_ENABLED, null)
        if (saved != null) return saved.toMutableList().sorted().toMutableList()

        val seeded = runCatching {
            // ✅ 요청사항: model_icons 폴더는 사용하지 않음(실제 없음). overlay에서 seed.
            context.assets.list("overlay")
                ?.filter { it.endsWith(".jpg", true) || it.endsWith(".png", true) }
                ?.map { it.substringBeforeLast('.') }
                ?.toSet()
                ?: emptySet()
        }.getOrDefault(emptySet())

        sp.edit().putStringSet(KEY_ENABLED, seeded).apply()
        return seeded.toMutableList().sorted().toMutableList()
    }

    fun add(context: Context, model: String) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val cur = (sp.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()).toMutableSet()
        cur.add(model)
        sp.edit().putStringSet(KEY_ENABLED, cur).apply()
    }

    fun remove(context: Context, model: String) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val cur = (sp.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()).toMutableSet()
        cur.remove(model)
        sp.edit().putStringSet(KEY_ENABLED, cur).apply()
    }
}
