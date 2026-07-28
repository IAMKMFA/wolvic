package com.framatome.vr.tours

import android.os.Build
import android.os.Environment
import com.framatome.vr.content.ContentType
import com.framatome.vr.content.library.LibraryCollection
import com.framatome.vr.content.library.LibraryItem
import com.framatome.vr.content.library.LibraryScanIssue
import com.framatome.vr.content.library.LibraryScanResult
import com.framatome.vr.content.library.LibraryScanner
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Player-side view of the shared media library. Scans `Library/` for extracted
 * media packages and `Data/` for loose content. Zip extraction and state
 * management are owned by [FramatomeInitializer]'s Player-side ingestors.
 *
 * One snapshot feeds BOTH the hub page and the `hub-*` playlists, so card
 * order and playlist indices can never disagree.
 */
object MediaLibraryIndex {
  private const val TTL_MS = 5_000L

  data class Snapshot(
    val videos: List<LibraryItem>,
    val images: List<LibraryItem>,
    val models: List<LibraryItem>,
    val clouds: List<LibraryItem>,
    val standaloneVideos: List<LibraryItem>,
    val standaloneImages: List<LibraryItem>,
    val standaloneModels: List<LibraryItem>,
    val standaloneClouds: List<LibraryItem>,
    val collections: List<LibraryCollection>,
    val issues: List<LibraryScanIssue>,
    val scannedAtMs: Long,
    val rootsStamp: Long,
    val storageGranted: Boolean,
    val contentRevision: String
  ) {
    val mediaCount: Int get() = videos.size + images.size + models.size + clouds.size
  }

  @Volatile
  private var cached: Snapshot? = null
  @Volatile
  private var cacheGeneration: Long = 0L
  @Volatile
  private var lastScanFailure: String? = null
  private val refreshing = AtomicBoolean(false)
  private val scanExecutionLock = Any()
  // Single daemon thread keeps library scans off the NanoHTTPD request threads.
  private val scanExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "media-library-index").apply { isDaemon = true }
  }
  @Volatile
  internal var scannerOverrideForTesting: ((List<File>) -> LibraryScanResult)? = null

  fun libraryRoots(): List<File> = listOf(
    File(Environment.getExternalStorageDirectory(), "FramatomeVR/Library"),
    File(Environment.getExternalStorageDirectory(), "FramatomeVR/Data")
  )

  /**
   * Stale-while-revalidate: returns the cached snapshot immediately and, when it
   * has gone stale, refreshes on a background thread. Only the first call (cold
   * cache) does a synchronous scan — and [prewarm] front-runs even that at
   * server start. Request threads (hub HTML, playlists, /health polls) therefore
   * never block on the directory walk.
   */
  fun snapshot(force: Boolean = false): Snapshot {
    val now = System.currentTimeMillis()
    val stamp = rootsStamp()
    val current = cached
    if (force) {
      triggerRefresh()
      return current ?: emptySnapshot()
    }
    if (current == null) {
      triggerRefresh()
      return emptySnapshot()
    }
    val fresh = now - current.scannedAtMs < TTL_MS && current.rootsStamp == stamp
    if (!fresh) triggerRefresh()
    return current
  }

  /**
   * Performs a synchronous directory walk and replaces the cached snapshot.
   * Manual refresh uses this only after zip reconciliation has completed, so
   * the hub response reflects the final extracted filesystem state.
   */
  fun refreshNow(): Snapshot {
    return synchronized(scanExecutionLock) {
      val generation = synchronized(this) {
        cacheGeneration += 1L
        cacheGeneration
      }
      val fresh = try {
        buildSnapshot()
      } catch (error: Throwable) {
        synchronized(this) {
          if (cacheGeneration == generation) {
            lastScanFailure = error.message ?: error.javaClass.simpleName
          }
        }
        triggerRefresh()
        throw LibraryScanException("Content library scan failed", error)
      }
      synchronized(this) {
        if (cacheGeneration == generation) {
          cached = fresh
          lastScanFailure = null
        }
      }
      fresh
    }
  }

  /** Drop cached cards/playlist data after an ingest event changes storage. */
  @Synchronized
  fun invalidate() {
    cacheGeneration += 1L
    triggerRefresh()
  }

  /** Kick a background scan at startup so the first hub load is already warm. */
  fun prewarm() {
    triggerRefresh()
  }

  private fun triggerRefresh() {
    if (!refreshing.compareAndSet(false, true)) return
    scanExecutor.execute {
      var generation = -1L
      try {
        synchronized(scanExecutionLock) {
          generation = cacheGeneration
          val fresh = buildSnapshot()
          synchronized(this) {
            if (cacheGeneration == generation) {
              cached = fresh
              lastScanFailure = null
            }
          }
        }
      } catch (t: Throwable) {
        synchronized(this) {
          if (cacheGeneration == generation) {
            lastScanFailure = t.message ?: t.javaClass.simpleName
          }
        }
        // Leave the previous snapshot in place; try again next staleness check.
      } finally {
        refreshing.set(false)
        if (cacheGeneration != generation) triggerRefresh()
      }
    }
  }

  private fun buildSnapshot(): Snapshot {
    val now = System.currentTimeMillis()
    val roots = libraryRoots()
    val scan = scannerOverrideForTesting?.invoke(roots) ?: LibraryScanner.scanGrouped(roots)
    val byTitle = compareBy<LibraryItem> { it.title.lowercase(Locale.US) }
    val allItems = scan.allPlayableItems
    val standalone = scan.standaloneItems.filter(LibraryScanResult::isPlayableMedia)
    val videos = allItems
      .filter { it.contentType == ContentType.Video360 || it.contentType == ContentType.Video2D }
      .sortedWith(byTitle)
    val images = allItems
      .filter { it.contentType == ContentType.Image360 || it.contentType == ContentType.Image2D }
      .sortedWith(byTitle)
    val models = allItems
      .filter { it.contentType == ContentType.Model3D }
      .sortedWith(byTitle)
    val clouds = allItems
      .filter { it.contentType == ContentType.PointCloud }
      .sortedWith(byTitle)
    val standaloneVideos = standalone
      .filter { it.contentType == ContentType.Video360 || it.contentType == ContentType.Video2D }
      .sortedWith(byTitle)
    val standaloneImages = standalone
      .filter { it.contentType == ContentType.Image360 || it.contentType == ContentType.Image2D }
      .sortedWith(byTitle)
    val standaloneModels = standalone
      .filter { it.contentType == ContentType.Model3D }
      .sortedWith(byTitle)
    val standaloneClouds = standalone
      .filter { it.contentType == ContentType.PointCloud }
      .sortedWith(byTitle)
    return Snapshot(
      videos = videos,
      images = images,
      models = models,
      clouds = clouds,
      standaloneVideos = standaloneVideos,
      standaloneImages = standaloneImages,
      standaloneModels = standaloneModels,
      standaloneClouds = standaloneClouds,
      collections = scan.collections,
      issues = scan.issues,
      scannedAtMs = now,
      rootsStamp = rootsStamp(),
      storageGranted = isStorageGranted(),
      contentRevision = contentRevision(
        standalone + scan.collections.flatMap { it.items },
        scan.collections,
        scan.issues
      )
    )
  }

  private fun contentRevision(
    items: List<LibraryItem>,
    collections: List<LibraryCollection>,
    issues: List<LibraryScanIssue>
  ): String {
    var hash = 1_125_899_906_842_597L
    items.sortedBy { it.path.absolutePath }.forEach { item ->
      hash = hashString(hash, item.id)
      hash = hashString(hash, item.title)
      hash = hashString(hash, item.entryRelativePath)
      hash = hashString(hash, item.contentType.name)
      hash = hashString(hash, item.source.name)
      hash = hashString(hash, item.projection)
      hash = hashString(hash, item.stereo)
      hash = hashString(hash, item.poster)
      hash = hashString(hash, item.description)
      hash = hashString(hash, item.audio)
      hash = hashString(hash, item.captions)
      hash = hash * 31 + if (item.autoLaunch) 1L else 0L
      hash = hashString(hash, item.metadata.toSortedMap().toString())
      hash = hashFile(hash, item.path)
      hash = hashFile(hash, item.posterFile())
      hash = hashFile(hash, item.captionsFile())
      hash = hashFile(hash, item.rightEyeFile())
    }
    collections.sortedBy { it.id }.forEach { collection ->
      hash = hashString(hash, collection.id)
      hash = hashString(hash, collection.title)
      hash = hashString(hash, collection.packageRelativePath)
      hash = hashString(hash, collection.description)
      hash = hashString(hash, collection.defaultItem)
      hash = hash * 31 + if (collection.autoLaunch) 1L else 0L
    }
    issues.sortedBy { "${it.category}|${it.source.absolutePath}" }.forEach { issue ->
      hash = hashString(hash, issue.category.name)
      hash = hashString(hash, issue.source.absolutePath)
      hash = hashString(hash, issue.detail)
      hash = hashFile(hash, issue.source)
    }
    return java.lang.Long.toHexString(hash)
  }

  private fun hashString(hash: Long, value: String?): Long =
    hash * 31 + value.orEmpty().hashCode()

  private fun hashFile(hash: Long, file: File?): Long {
    if (file == null) return hash * 31
    var next = hashString(hash, file.absolutePath)
    next = next * 31 + file.length()
    return next * 31 + file.lastModified()
  }

  fun isStorageGranted(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Environment.isExternalStorageManager()
    } else {
      val external = Environment.getExternalStorageDirectory()
      external.canRead() && external.canWrite()
    }
  }

  /** Cheap change signal: a file added/removed in a root bumps its mtime.
   *  Public so the hub can fold it into its own HTML-cache stamp. */
  fun rootsStamp(): Long {
    return libraryRoots().sumOf { root -> if (root.exists()) root.lastModified() else 0L }
  }

  fun currentRevision(): String = cached?.contentRevision.orEmpty()

  fun lastScanError(): String? = lastScanFailure

  @Synchronized
  internal fun resetForTesting() {
    cacheGeneration += 1L
    cached = null
    lastScanFailure = null
    scannerOverrideForTesting = null
  }

  private fun emptySnapshot(): Snapshot = Snapshot(
    videos = emptyList(),
    images = emptyList(),
    models = emptyList(),
    clouds = emptyList(),
    standaloneVideos = emptyList(),
    standaloneImages = emptyList(),
    standaloneModels = emptyList(),
    standaloneClouds = emptyList(),
    collections = emptyList(),
    issues = emptyList(),
    scannedAtMs = 0L,
    rootsStamp = rootsStamp(),
    storageGranted = isStorageGranted(),
    contentRevision = "loading"
  )

  class LibraryScanException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)
}
