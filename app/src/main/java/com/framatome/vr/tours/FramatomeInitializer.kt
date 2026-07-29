package com.framatome.vr.tours

import android.content.Context
import android.util.Log
import com.framatome.vr.config.DeploymentConfig
import com.framatome.vr.config.DeploymentConfigResolver
import com.framatome.vr.util.DropsWatchdog
import com.framatome.vr.util.IngestReconcileReport
import com.framatome.vr.util.MediaIngestor
import com.framatome.vr.util.StorageLayout
import com.framatome.vr.util.TourIngestor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
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
    private const val REFRESH_IDLE = "idle"
    private const val REFRESH_RUNNING = "running"
    private const val REFRESH_COMPLETE = "complete"
    private const val REFRESH_FAILED = "failed"
    private const val REFRESH_RETRY_INITIAL_MS = 3_000L
    private const val REFRESH_RETRY_MAX_MS = 15_000L
    private const val REFRESH_MAX_WAIT_MS = 15L * 60 * 1_000
    private const val STARTUP_RETRY_INITIAL_MS = 2_000L
    private const val STARTUP_RETRY_MAX_MS = 30_000L
    private const val STARTUP_RETRY_LIMIT = 4

    @Volatile private var ingestStartupState = IngestStartupState.Stopped
    @Volatile private var ingestStartupError: String? = null
    @Volatile private var configResolver: DeploymentConfigResolver? = null
    @Volatile private var tourIngestor: TourIngestor? = null
    @Volatile private var mediaIngestor: MediaIngestor? = null
    @Volatile private var dropsWatchdog: DropsWatchdog? = null
    @Volatile private var applicationContext: Context? = null
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshSequence = AtomicLong(0L)
    private val refreshLock = Any()
    private val ingestObserverJobs = CopyOnWriteArrayList<Job>()
    @Volatile private var refreshJob: Job? = null
    @Volatile private var startupRetryJob: Job? = null
    @Volatile private var refreshStatus = ContentRefreshStatus()
    @Volatile private var shuttingDown = false
    @Volatile private var storageGrantPending = false
    private var startupFailureCount = 0

    data class ContentRefreshStatus(
        val requestId: Long = 0L,
        val phase: String = REFRESH_IDLE,
        val message: String = "Ready",
        val startedAtMs: Long? = null,
        val finishedAtMs: Long? = null,
        val toursCount: Int? = null,
        val mediaCount: Int? = null,
        val videosCount: Int? = null,
        val imagesCount: Int? = null,
        val pendingCount: Int = 0,
        val skippedCount: Int = 0,
        val failedCount: Int = 0,
        val outOfSpaceCount: Int = 0,
        val warnings: List<String> = emptyList()
    ) {
        fun toJson(): String = JSONObject().apply {
            put("requestId", requestId)
            put("phase", phase)
            put("message", message)
            startedAtMs?.let { put("startedAtMs", it) }
            finishedAtMs?.let { put("finishedAtMs", it) }
            toursCount?.let { put("toursCount", it) }
            mediaCount?.let { put("mediaCount", it) }
            videosCount?.let { put("videosCount", it) }
            imagesCount?.let { put("imagesCount", it) }
            put("pendingCount", pendingCount)
            put("skippedCount", skippedCount)
            put("failedCount", failedCount)
            put("outOfSpaceCount", outOfSpaceCount)
            put("warnings", warnings)
        }.toString()
    }

    @JvmStatic
    fun init(context: Context) {
        if (shuttingDown) {
            Log.w(TAG, "Ignoring init after terminal process shutdown")
            return
        }
        val app = context.applicationContext
        applicationContext = app
        // Before anything that can fail: a crash during boot is exactly the one
        // we will be asked about, and there is no other way to see it.
        PlayerDiagnostics.start(app)
        Log.i(TAG, "Starting Framatome Player local web server")
        LocalWebServer.ensureRunning(app)
        // Warm the library index off-thread so the first hub load is instant.
        MediaLibraryIndex.prewarm()

        startIngestAsync(app)
    }

    /**
     * Kick the ingest pipeline off the main thread. This runs inside
     * VRBrowserApplication.onCreate (main thread, before Gecko engine init), and
     * the ingestors' start() does synchronous sdcard I/O (mkdirs, config.json
     * read, stale-artifact sweep, FileObserver registration) that must not jank
     * boot. Guarded so it spawns at most once per process.
     */
    private fun startIngestAsync(app: Context, forceRetry: Boolean = false) {
        synchronized(this) {
            if (
                ingestStartupState == IngestStartupState.Starting ||
                ingestStartupState == IngestStartupState.Running ||
                ingestStartupState == IngestStartupState.Shutdown
            ) {
                return
            }
            if (forceRetry) {
                startupFailureCount = 0
                startupRetryJob?.cancel()
                startupRetryJob = null
            }
            ingestStartupState = IngestStartupState.Starting
            ingestStartupError = null
        }
        refreshScope.launch { startIngest(app) }
    }

    /**
     * Fold in the (formerly launcher-owned) continuous ingest pipeline.
     * Failures are logged, never fatal to boot.
     */
    private suspend fun startIngest(app: Context) {
        var resolver: DeploymentConfigResolver? = null
        var tours: TourIngestor? = null
        var media: MediaIngestor? = null
        var watchdog: DropsWatchdog? = null
        var observerJobs: List<Job> = emptyList()
        runCatching {
            StorageLayout.ensureWritableDirectories()

            resolver = DeploymentConfigResolver(app).also { it.start() }
            tours = TourIngestor(app, checkNotNull(resolver).config)
            media = MediaIngestor(app, checkNotNull(resolver).config)
            // tourCatalog rescan is a launcher-UI concern; the hub auto-refreshes
            // off directory mtimes, so the Player passes no onRescan callback.
            watchdog = DropsWatchdog(
                app,
                checkNotNull(tours),
                onRescan = null,
                mediaIngestor = checkNotNull(media)
            )

            val pendingBeforeStart = takePendingStorageGrant()
            if (pendingBeforeStart) {
                checkNotNull(resolver).refreshAfterStorageGrant()
            }
            // Subscribe before start(): start() queues an immediate pass and the
            // SharedFlows intentionally have no replay.
            observerJobs = createIngestObservers(checkNotNull(tours), checkNotNull(media))
            checkNotNull(tours).start()
            checkNotNull(media).start()
            checkNotNull(watchdog).start()

            synchronized(this) {
                check(ingestStartupState != IngestStartupState.Shutdown) {
                    "Content services shut down during startup"
                }
                configResolver = resolver
                tourIngestor = tours
                mediaIngestor = media
                dropsWatchdog = watchdog
                ingestObserverJobs += observerJobs
                ingestStartupState = IngestStartupState.Running
                ingestStartupError = null
                startupFailureCount = 0
            }
            if (pendingBeforeStart) {
                checkNotNull(tours).refreshWatchers()
                checkNotNull(media).refreshWatchers()
                checkNotNull(tours).requestReconcile()
                checkNotNull(media).requestReconcile()
            }
            applyPendingStorageGrantIfNeeded(
                checkNotNull(resolver),
                checkNotNull(tours),
                checkNotNull(media)
            )
            Log.i(TAG, "Framatome Player content ingest started")
        }.onFailure { error ->
            observerJobs.forEach { it.cancel() }
            runCatching { watchdog?.stop() }
            runCatching { media?.stop() }
            runCatching { tours?.stop() }
            runCatching { resolver?.stop() }
            synchronized(this) {
                if (ingestStartupState != IngestStartupState.Shutdown) {
                    ingestStartupState = IngestStartupState.Failed
                    ingestStartupError = error.message ?: error.javaClass.simpleName
                    startupFailureCount += 1
                }
            }
            Log.e(TAG, "Failed to start content ingest", error)
            scheduleStartupRetry(app)
        }
    }

    private fun takePendingStorageGrant(): Boolean = synchronized(this) {
        if (!storageGrantPending) {
            false
        } else {
            storageGrantPending = false
            true
        }
    }

    private suspend fun applyPendingStorageGrantIfNeeded(
        resolver: DeploymentConfigResolver,
        tours: TourIngestor,
        media: MediaIngestor
    ) {
        while (takePendingStorageGrant()) {
            resolver.refreshAfterStorageGrant()
            tours.refreshWatchers()
            media.refreshWatchers()
            tours.requestReconcile()
            media.requestReconcile()
        }
    }

    private fun scheduleStartupRetry(app: Context) {
        val retryDelay = synchronized(this) {
            if (
                shuttingDown ||
                ingestStartupState != IngestStartupState.Failed ||
                startupFailureCount > STARTUP_RETRY_LIMIT
            ) {
                return
            }
            val shift = (startupFailureCount - 1).coerceAtLeast(0).coerceAtMost(20)
            (STARTUP_RETRY_INITIAL_MS * (1L shl shift))
                .coerceAtMost(STARTUP_RETRY_MAX_MS)
        }
        startupRetryJob?.cancel()
        startupRetryJob = refreshScope.launch {
            delay(retryDelay)
            startIngestAsync(app)
        }
    }

    private fun createIngestObservers(
        tours: TourIngestor,
        media: MediaIngestor
    ): List<Job> = listOf(
        refreshScope.launch(start = CoroutineStart.UNDISPATCHED) {
            tours.events.collect {
                MediaLibraryIndex.invalidate()
                HubIndexServer.invalidate()
            }
        },
        refreshScope.launch(start = CoroutineStart.UNDISPATCHED) {
            media.events.collect {
                MediaLibraryIndex.invalidate()
                HubIndexServer.invalidate()
            }
        }
    )

    /**
     * Nudge the ingest pipeline to re-scan now. Called when All-Files-Access is
     * (re)confirmed — e.g. the operator just granted it — so content that was
     * invisible at boot (empty dirs before the grant) is picked up immediately
     * instead of waiting for the next watchdog tick. No-op until ingest started.
     */
    @JvmStatic
    fun requestReconcile() {
        // Re-attach any drop-root watchers that couldn't bind pre-grant (the
        // shared dirs may not even have existed), then kick a reconcile pass.
        tourIngestor?.refreshWatchers()
        mediaIngestor?.refreshWatchers()
        tourIngestor?.requestReconcile()
        mediaIngestor?.requestReconcile()
    }

    /**
     * Drops any permission-denied snapshot captured during boot, then reattaches
     * ingest watchers after the operator grants storage access.
     */
    @JvmStatic
    fun onStorageAccessConfirmed() {
        MediaLibraryIndex.invalidate()
        HubIndexServer.invalidate()
        synchronized(this) {
            storageGrantPending = true
        }
        val app = applicationContext ?: return
        refreshScope.launch {
            val resolver = configResolver
            val tours = tourIngestor
            val media = mediaIngestor
            if (
                ingestStartupState == IngestStartupState.Running &&
                resolver != null &&
                tours != null &&
                media != null
            ) {
                applyPendingStorageGrantIfNeeded(resolver, tours, media)
            } else {
                // A grant may arrive before startup completes or after an earlier
                // failure. The pending flag is consumed by successful startup.
                startIngestAsync(app, forceRetry = true)
            }
        }
    }

    /**
     * Starts one operator-requested refresh. Repeated taps join the active job
     * instead of launching competing extracts. The HTTP hub polls [contentRefreshStatus]
     * and reloads only after reconcile + a forced library scan complete.
     */
    @JvmStatic
    fun startContentRefresh(): ContentRefreshStatus = synchronized(refreshLock) {
        if (shuttingDown) {
            return@synchronized ContentRefreshStatus(
                requestId = refreshSequence.incrementAndGet(),
                phase = REFRESH_FAILED,
                message = "Content services are shutting down.",
                finishedAtMs = System.currentTimeMillis()
            )
        }
        if (refreshJob?.isActive == true) {
            return@synchronized refreshStatus
        }

        val tours = tourIngestor
        val media = mediaIngestor
        val requestId = refreshSequence.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        if (tours == null || media == null) {
            applicationContext?.let { startIngestAsync(it, forceRetry = true) }
            return@synchronized ContentRefreshStatus(
                requestId = requestId,
                phase = REFRESH_FAILED,
                message = "Content services are starting or restarting. Please retry shortly.",
                startedAtMs = startedAt,
                finishedAtMs = startedAt
            ).also { refreshStatus = it }
        }

        val running = ContentRefreshStatus(
            requestId = requestId,
            phase = REFRESH_RUNNING,
            message = "Checking ArborXR deliveries and rebuilding the library…",
            startedAtMs = startedAt
        )
        refreshStatus = running
        refreshJob = refreshScope.launch {
            runCatching {
                configResolver?.refreshNow()
                tours.refreshWatchers()
                media.refreshWatchers()

                var tourReport = IngestReconcileReport(candidates = 0)
                var mediaReport = IngestReconcileReport(candidates = 0)
                var retryDelayMs = REFRESH_RETRY_INITIAL_MS
                while (true) {
                    tourReport = tours.reconcileNow()
                    mediaReport = media.reconcileNow()

                    val failureMessages =
                        (tourReport.failureMessages + mediaReport.failureMessages).distinct()
                    if (tourReport.hasFailures || mediaReport.hasFailures) {
                        // This status is in-memory and only shown if someone is
                        // watching a refresh; the record on disk is what a
                        // support call actually has to work from.
                        PlayerDiagnostics.recordIngestFailures("tours", tourReport)
                        PlayerDiagnostics.recordIngestFailures("media", mediaReport)
                        return@runCatching ContentRefreshStatus(
                            requestId = requestId,
                            phase = REFRESH_FAILED,
                            message = when (failureMessages.size) {
                                0 -> "One or more content packages could not be imported."
                                1 -> failureMessages.first()
                                else ->
                                    failureMessages.first() +
                                        " (and ${failureMessages.size - 1} more)"
                            },
                            startedAtMs = startedAt,
                            finishedAtMs = System.currentTimeMillis(),
                            failedCount = tourReport.failed + mediaReport.failed,
                            outOfSpaceCount =
                                tourReport.outOfSpace + mediaReport.outOfSpace,
                            // Every failure, not just the one in `message` —
                            // dropping the rest hid packages that also failed.
                            warnings = mediaReport.warnings + failureMessages
                        )
                    }

                    val pendingMessages =
                        (tourReport.pendingMessages + mediaReport.pendingMessages).distinct()
                    val pendingCount = pendingMessages.size.takeIf { it > 0 }
                        ?: (tourReport.pending + mediaReport.pending)
                    if (pendingCount == 0) break
                    if (System.currentTimeMillis() - startedAt >= REFRESH_MAX_WAIT_MS) {
                        throw IllegalStateException(
                            "ArborXR is still copying $pendingCount package(s). " +
                                "Wait for delivery to finish, then retry."
                        )
                    }

                    updateRefreshStatus(
                        ContentRefreshStatus(
                            requestId = requestId,
                            phase = REFRESH_RUNNING,
                            message = "Waiting for ArborXR to finish copying $pendingCount package(s)…",
                            startedAtMs = startedAt,
                            pendingCount = pendingCount
                        )
                    )
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(REFRESH_RETRY_MAX_MS)
                }

                val library = MediaLibraryIndex.refreshNow()
                HubIndexServer.invalidate()
                val hubStatus = applicationContext?.let(HubIndexServer::contentStatus)
                ContentRefreshStatus(
                    requestId = requestId,
                    phase = REFRESH_COMPLETE,
                    message = "Content library is up to date.",
                    startedAtMs = startedAt,
                    finishedAtMs = System.currentTimeMillis(),
                    toursCount = hubStatus?.toursCount ?: 0,
                    mediaCount = hubStatus?.mediaCount ?: library.mediaCount,
                    videosCount = hubStatus?.videosCount ?: library.videos.size,
                    imagesCount = hubStatus?.imagesCount ?: library.images.size,
                    skippedCount = mediaReport.warnings.count {
                        it.contains("no supported media", ignoreCase = true)
                    },
                    warnings = mediaReport.warnings.filterNot {
                        it.contains("3DVista tour zip", ignoreCase = true)
                    }
                )
            }.getOrElse { error ->
                Log.e(TAG, "Manual content refresh failed", error)
                ContentRefreshStatus(
                    requestId = requestId,
                    phase = REFRESH_FAILED,
                    message = error.message ?: "Content refresh failed.",
                    startedAtMs = startedAt,
                    finishedAtMs = System.currentTimeMillis(),
                    failedCount = 1
                )
            }.let { completed ->
                synchronized(refreshLock) {
                    if (!shuttingDown && refreshStatus.requestId == requestId) {
                        refreshStatus = completed
                    }
                }
            }
        }
        running
    }

    private fun updateRefreshStatus(status: ContentRefreshStatus) {
        synchronized(refreshLock) {
            if (!shuttingDown && refreshStatus.requestId == status.requestId) {
                refreshStatus = status
            }
        }
    }

    @JvmStatic
    fun contentRefreshStatus(): ContentRefreshStatus = refreshStatus

    fun deploymentConfig(): DeploymentConfig? = configResolver?.config?.value

    fun contentServicesReady(): Boolean =
        !shuttingDown && ingestStartupState == IngestStartupState.Running

    fun contentServicesError(): String? = ingestStartupError

    @JvmStatic
    fun shutdown() {
        synchronized(refreshLock) {
            shuttingDown = true
            refreshJob?.cancel()
            refreshJob = null
            refreshStatus = ContentRefreshStatus(
                requestId = refreshSequence.incrementAndGet(),
                phase = REFRESH_FAILED,
                message = "Content services are shut down.",
                finishedAtMs = System.currentTimeMillis()
            )
        }
        synchronized(this) {
            ingestStartupState = IngestStartupState.Shutdown
            startupRetryJob?.cancel()
            startupRetryJob = null
            storageGrantPending = false
        }
        LocalWebServer.stop()
        ingestObserverJobs.forEach { it.cancel() }
        ingestObserverJobs.clear()
        runCatching { dropsWatchdog?.stop() }
        runCatching { mediaIngestor?.stop() }
        runCatching { tourIngestor?.stop() }
        runCatching { configResolver?.stop() }
        dropsWatchdog = null
        mediaIngestor = null
        tourIngestor = null
        configResolver = null
        applicationContext = null
    }

    private enum class IngestStartupState {
        Stopped,
        Starting,
        Running,
        Failed,
        Shutdown
    }
}
