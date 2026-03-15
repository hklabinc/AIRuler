package com.hklab.airuler.model

import android.content.Context

object ModelRegistry {
    private const val PREF = "model_registry"
    private const val KEY_ENABLED = "enabled_models"

    /** 최초 1회: assets/overlay 목록으로 seed. 이후엔 prefs에 저장된 목록 사용 */
    fun getEnabledModels(context: Context): MutableList<String> {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val saved = sp.getStringSet(KEY_ENABLED, null)
        if (saved != null) {
            val normalized = ModelNameCompat.canonicalSet(saved)
            if (normalized != saved) {
                sp.edit().putStringSet(KEY_ENABLED, normalized).apply()
            }
            return normalized.toMutableList().sorted().toMutableList()
        }

        val seeded = runCatching {
            // ✅ 요청사항: model_icons 폴더는 사용하지 않음(실제 없음). overlay에서 seed.
            ModelNameCompat.canonicalSet(
                context.assets.list("overlay")
                    ?.filter { it.endsWith(".jpg", true) || it.endsWith(".png", true) }
                    ?.map { it.substringBeforeLast('.') }
                    ?: emptyList()
            )
        }.getOrDefault(linkedSetOf())

        sp.edit().putStringSet(KEY_ENABLED, seeded).apply()
        return seeded.toMutableList().sorted().toMutableList()
    }

    fun add(context: Context, model: String) {
        val canonical = ModelNameCompat.canonical(model)
        if (canonical.isBlank()) return

        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val cur = ModelNameCompat.canonicalSet(sp.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet())
        cur.add(canonical)
        sp.edit().putStringSet(KEY_ENABLED, cur).apply()
    }

    fun remove(context: Context, model: String) {
        val canonical = ModelNameCompat.canonical(model)
        if (canonical.isBlank()) return

        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val cur = ModelNameCompat.canonicalSet(sp.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet())
        cur.remove(canonical)
        sp.edit().putStringSet(KEY_ENABLED, cur).apply()
    }
}
