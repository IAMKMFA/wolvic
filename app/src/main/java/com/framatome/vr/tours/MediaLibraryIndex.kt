package com.framatome.vr.tours

import android.os.Build
import android.os.Environment
import com.framatome.vr.content.ContentType
import com.framatome.vr.content.library.LibraryItem
import com.framatome.vr.content.library.LibraryScanner
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Player-side view of the shared media library. Scans the SAME storage tree
 * the launcher's ingest maintains for ArborXR deliveries — `Library/` for
 * extracted media packages and `Data/` for raw dumps — strictly read-only:
 * ingest (zip extraction, dedupe, state files) stays launcher-owned so the
 * two apps can never race on a delivery.
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
    val scannedAtMs: Long,
    val rootsStamp: Long,
    val storageGranted: Boolean
  ) {
    val mediaCount: Int get() = videos.size + images.size + models.size + clouds.size
  }

  @Volatile
  private var cached: Snapshot? = null
  private val refreshing = AtomicBoolean(false)
  // Single daemon thread keeps library scans off the NanoHTTPD request threads.
  private val scanExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "media-library-index").apply { isDaemon = true }
  }

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
    if (current == null || force) {
      return scanNow(stamp)
    }
    val fresh = now - current.scannedAtMs < TTL_MS && current.rootsStamp == stamp
    if (!fresh) triggerRefresh()
    return current
  }

  /** Kick a background scan at startup so the first hub load is already warm. */
  fun prewarm() {
    triggerRefresh()
  }

  @Synchronized
  private fun scanNow(stamp: Long): Snapshot {
    // Re-check under lock in case another thread populated it while we waited.
    val now = System.currentTimeMillis()
    cached?.let { c ->
      if (now - c.scannedAtMs < TTL_MS && c.rootsStamp == rootsStamp()) return c
    }
    val fresh = buildSnapshot()
    cached = fresh
    return fresh
  }

  private fun triggerRefresh() {
    if (!refreshing.compareAndSet(false, true)) return
    scanExecutor.execute {
      try {
        val fresh = buildSnapshot()
        cached = fresh
      } catch (t: Throwable) {
        // Leave the previous snapshot in place; try again next staleness check.
      } finally {
        refreshing.set(false)
      }
    }
  }

  private fun buildSnapshot(): Snapshot {
    val now = System.currentTimeMillis()
    val items = runCatching { LibraryScanner.scan(libraryRoots()) }.getOrDefault(emptyList())
    val byTitle = compareBy<LibraryItem> { it.title.lowercase(Locale.US) }
    return Snapshot(
      videos = items
        .filter { it.contentType == ContentType.Video360 || it.contentType == ContentType.Video2D }
        .sortedWith(byTitle),
      images = items
        .filter { it.contentType == ContentType.Image360 || it.contentType == ContentType.Image2D }
        .sortedWith(byTitle),
      models = items
        .filter { it.contentType == ContentType.Model3D }
        .sortedWith(byTitle),
      clouds = items
        .filter { it.contentType == ContentType.PointCloud }
        .sortedWith(byTitle),
      scannedAtMs = now,
      rootsStamp = rootsStamp(),
      storageGranted = isStorageGranted()
    )
  }

  fun isStorageGranted(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Environment.isExternalStorageManager()
    } else {
      true // pre-R relies on the legacy storage flag already in the manifest
    }
  }

  /** Cheap change signal: a file added/removed in a root bumps its mtime.
   *  Public so the hub can fold it into its own HTML-cache stamp. */
  fun rootsStamp(): Long {
    return libraryRoots().sumOf { root -> if (root.exists()) root.lastModified() else 0L }
  }
}
