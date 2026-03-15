package com.hklab.airuler.model

/**
 * 모델명 호환 처리.
 *
 * - 현재 앱의 canonical 모델명은 공백만 제거한 "기본 모델명"입니다.
 * - 과거에 사용하던 `_FO` 접미사는 저장/입력 경계에서만 1회 정리합니다.
 * - 서버에 남아 있는 `_FO` run 폴더는 다운로드 대상에서 제외합니다.
 */
object ModelNameCompat {
    private const val LEGACY_FO_SUFFIX = "_FO"

    fun canonical(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return if (isLegacyFoVariant(trimmed)) {
            trimmed.dropLast(LEGACY_FO_SUFFIX.length).trimEnd()
        } else {
            trimmed
        }
    }

    fun isLegacyFoVariant(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.length > LEGACY_FO_SUFFIX.length &&
            trimmed.endsWith(LEGACY_FO_SUFFIX, ignoreCase = true)
    }

    fun canonicalSet(names: Iterable<String?>): LinkedHashSet<String> {
        val out = linkedSetOf<String>()
        names.forEach { raw ->
            val canonical = canonical(raw)
            if (canonical.isNotBlank()) out += canonical
        }
        return out
    }
}
