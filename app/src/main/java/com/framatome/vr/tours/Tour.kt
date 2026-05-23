package com.framatome.vr.tours

import java.io.File

enum class TourSource { BUNDLED, EXTERNAL }

data class Tour(
    val name: String,
    val absolutePath: String,
    val relativePath: String,
    val entryFileName: String,
    val thumbnailPath: String? = null,
    val source: TourSource = TourSource.EXTERNAL,
    val enabled: Boolean = true,
) {
    fun entryFile(): File = File(absolutePath, entryFileName)
}
