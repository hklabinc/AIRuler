package com.hklab.airuler.gallery

import java.io.File

data class InternalFileItem(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long
)
