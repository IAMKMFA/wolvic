package com.framatome.vr.tours

import android.os.Build
import android.os.Environment
import com.framatome.vr.content.ContentType
import com.framatome.vr.content.library.LibraryItem
import com.framatome.vr.content.library.LibraryScanner
import java.io.File
import java.util.Locale

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
    val scannedAtMs: Long,
    val rootsStamp: Long,
    val storageGranted: Boolean
  ) {
    val mediaCount: Int get() = videos.size + images.size + models.size
  }

  @Volatile
  private var cached: Snapshot? = null

  fun libraryRoots(): List<File> = listOf(
    File(Environment.getExternalStorageDirectory(), "FramatomeVR/Library"),
    File(Environment.getExternalStorageDirectory(), "FramatomeVR/Data")
  )

  @Synchronized
  fun snapshot(force: Boolean = false): Snapshot {
    val now = System.currentTimeMillis()
    val stamp = rootsStamp()
    cached?.let { snapshot ->
      if (!force && now - snapshot.scannedAtMs < TTL_MS && snapshot.rootsStamp == stamp) {
        return snapshot
      }
    }

    val items = runCatching { LibraryScanner.scan(libraryRoots()) }.getOrDefault(emptyList())
    val byTitle = compareBy<LibraryItem> { it.title.lowercase(Locale.US) }
    val fresh = Snapshot(
      videos = items
        .filter { it.contentType == ContentType.Video360 || it.contentType == ContentType.Video2D }
        .sortedWith(byTitle),
      images = items
        .filter { it.contentType == ContentType.Image360 || it.contentType == ContentType.Image2D }
        .sortedWith(byTitle),
      models = items
        .filter { it.contentType == ContentType.Model3D }
        .sortedWith(byTitle),
      scannedAtMs = now,
      rootsStamp = stamp,
      storageGranted = isStorageGranted()
    )
    cached = fresh
    return fresh
  }

  fun isStorageGranted(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Environment.isExternalStorageManager()
    } else {
      true // pre-R relies on the legacy storage flag already in the manifest
    }
  }

  /** Cheap change signal: a file added/removed in a root bumps its mtime. */
  private fun rootsStamp(): Long {
    return libraryRoots().sumOf { root -> if (root.exists()) root.lastModified() else 0L }
  }
}
