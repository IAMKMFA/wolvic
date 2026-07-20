package com.framatome.vr.tours

import android.content.Context
import android.util.Log
import com.framatome.vr.config.DeploymentConfigResolver
import com.framatome.vr.util.DropsWatchdog
import com.framatome.vr.util.MediaIngestor
import com.framatome.vr.util.StorageLayout
import com.framatome.vr.util.TourIngestor
import kotlin.jvm.JvmStatic

/**
 * Bootstraps the Framatome Player. As the single all-in-one app, the Player now
 * owns BOTH playback and content ingest:
 *
 * 1. The local web server ([LocalWebServer]) serving tours + the in-VR hub.
 * 2. The continuous zip-ingest pipeline (formerly launcher-only): watches the
 *    `/sdcard/FramatomeVR` drop roots and extracts 3DVista tours into
 *    `FramatomeVR/Tours` and media packages into `FramatomeVR/Library` — the
 *    exact paths [HubIndexServer] / [MediaLibraryIndex] already read, so newly
 *    dropped content surfaces in the hub within seconds via the mtime TTL cache.
 */
object FramatomeInitializer {
    private const val TAG = "FramatomeInit"

    @Volatile private var configResolver: DeploymentConfigResolver? = null
    @Volatile private var tourIngestor: TourIngestor? = null
    @Volatile private var mediaIngestor: MediaIngestor? = null
    @Volatile private var dropsWatchdog: DropsWatchdog? = null

    @JvmStatic
    fun init(context: Context) {
        val app = context.applicationContext
        Log.i(TAG, "Starting Framatome Player local web server")
        LocalWebServer.ensureRunning(app)
        // Warm the library index off-thread so the first hub load is instant.
        MediaLibraryIndex.prewarm()

        startIngest(app)
    }

    /**
     * Fold in the (formerly launcher-owned) continuous ingest pipeline. Idempotent
     * on the underlying start() calls; failures are logged, never fatal to boot.
     */
    private fun startIngest(app: Context) {
        if (tourIngestor != null) return
        runCatching {
            StorageLayout.ensureWritableDirectories()

            val resolver = DeploymentConfigResolver(app).also { it.start() }
            val tours = TourIngestor(app, resolver.config)
            val media = MediaIngestor(app, resolver.config)
            // tourCatalog rescan is a launcher-UI concern; the hub auto-refreshes
            // off directory mtimes, so the Player passes no onRescan callback.
            val watchdog = DropsWatchdog(app, tours, onRescan = null, mediaIngestor = media)

            tours.start()
            media.start()
            watchdog.start()

            configResolver = resolver
            tourIngestor = tours
            mediaIngestor = media
            dropsWatchdog = watchdog
            Log.i(TAG, "Framatome Player content ingest started")
        }.onFailure { Log.e(TAG, "Failed to start content ingest", it) }
    }

    @JvmStatic
    fun shutdown() {
        runCatching { dropsWatchdog?.stop() }
        runCatching { mediaIngestor?.stop() }
        runCatching { tourIngestor?.stop() }
        runCatching { configResolver?.stop() }
        dropsWatchdog = null
        mediaIngestor = null
        tourIngestor = null
        configResolver = null
        LocalWebServer.stop()
    }
}
