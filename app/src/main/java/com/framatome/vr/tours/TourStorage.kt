package com.framatome.vr.tours

import android.content.Context
import com.igalia.wolvic.BuildConfig
import java.io.File

object TourStorage {
    fun appStorageRoot(context: Context): File {
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    fun toursDir(context: Context): File = File(appStorageRoot(context), "tours")

    fun ensureAppDirs(context: Context): File {
        val root = appStorageRoot(context)
        if (!root.exists()) {
            root.mkdirs()
        }
        return toursDir(context).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    fun displayAppFilesPath(): String = "/Android/data/${BuildConfig.APPLICATION_ID}/files/"

    fun displayToursPath(): String = "${displayAppFilesPath()}tours/"
}
