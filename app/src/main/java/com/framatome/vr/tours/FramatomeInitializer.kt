package com.framatome.vr.tours

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bootstraps the Framatome VR tour subsystem: starts the local web server
 * and runs the initial zip import in the background.
 */
object FramatomeInitializer {
    private const val TAG = "FramatomeInit"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        Log.i(TAG, "Initializing Framatome VR tour subsystem")
        LocalWebServer.ensureRunning(context.applicationContext)
        scope.launch {
            try {
                val app = context.applicationContext as android.app.Application
                PublicInboxImporter(app).runImportOnce()
                Log.i(TAG, "Initial tour import complete")
            } catch (t: Throwable) {
                Log.w(TAG, "Initial import failed (non-fatal)", t)
            }
        }
    }

    fun shutdown() {
        LocalWebServer.stop()
    }
}
