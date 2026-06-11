package com.framatome.vr.tours

import android.content.Context
import android.util.Log
import kotlin.jvm.JvmStatic

/**
 * Bootstraps the Framatome Player local web server for 3DVista WebXR tours.
 * Zip ingest is owned by the launcher; this only starts HTTP serving.
 */
object FramatomeInitializer {
    private const val TAG = "FramatomeInit"

    @JvmStatic
    fun init(context: Context) {
        Log.i(TAG, "Starting Framatome Player local web server")
        LocalWebServer.ensureRunning(context.applicationContext)
        // Warm the library index off-thread so the first hub load is instant.
        MediaLibraryIndex.prewarm()
    }

    @JvmStatic
    fun shutdown() {
        LocalWebServer.stop()
    }
}
