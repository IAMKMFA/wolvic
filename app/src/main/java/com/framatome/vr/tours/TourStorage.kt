package com.framatome.vr.tours

import android.content.Context
import android.os.Environment
import com.igalia.wolvic.BuildConfig
import java.io.File

object TourStorage {
    /**
     * Single source of truth for the shared, world-readable tours directory that
     * the launcher extracts into and that [LocalWebServer] serves over HTTP. The
     * in-player tour hub MUST discover tours from this same root so the cards it
     * renders resolve against the running server instead of 404-ing.
     */
    fun sharedToursDir(): File = File(Environment.getExternalStorageDirectory(), "FramatomeVR/Tours")

    fun displaySharedToursPath(): String = "FramatomeVR/Tours/"

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
