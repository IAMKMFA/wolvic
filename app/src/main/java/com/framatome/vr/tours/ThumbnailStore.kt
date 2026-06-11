package com.framatome.vr.tours

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import com.framatome.vr.content.ContentType
import com.framatome.vr.content.ImmersiveIntentFactory
import com.framatome.vr.content.library.LibraryItem
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generates and serves hub card thumbnails at `/__thumbs__/<sha1>.jpg`.
 *
 * Keys are `sha1(path|mtime|size)` so a replaced file invalidates itself; the
 * route accepts ONLY such keys (no path input, no traversal surface). Sources:
 * manifest poster > image downscale > video frame extract. Generation is
 * throttled with a small semaphore so a cold hub full of 4K videos cannot peg
 * the Quest CPU, and concurrent requests for one key coalesce on a per-key
 * lock.
 */
object ThumbnailStore {
  private const val TAG = "ThumbnailStore"
  private const val MAX_EDGE_PX = 640
  private const val JPEG_QUALITY = 82
  private const val CACHE_DIR = "thumbs"
  private const val MAX_CACHE_BYTES = 128L * 1024 * 1024
  private const val MAX_CACHE_FILES = 1000

  private val keyPattern = Regex("^[a-f0-9]{40}\\.jpg$")
  private val sources = ConcurrentHashMap<String, Source>()
  private val keyLocks = ConcurrentHashMap<String, Any>()
  private val generatePermits = Semaphore(2)
  private val pruned = AtomicBoolean(false)

  /** Injectable for unit tests — Robolectric cannot run MediaMetadataRetriever. */
  @Volatile
  var videoFrameExtractor: (File) -> Bitmap? = ::extractVideoFrame

  private data class Source(val file: File, val isVideoFrame: Boolean)

  /** Registers the item's thumb source and returns its server URL, or null. */
  fun thumbUrlFor(item: LibraryItem): String? {
    val poster = item.posterFile()
    val source = when {
      poster != null -> Source(poster, isVideoFrame = false)
      item.contentType == ContentType.Image2D || item.contentType == ContentType.Image360 ->
        item.path.takeIf { it.isFile }?.let { Source(it, isVideoFrame = false) }
      item.contentType == ContentType.Video2D || item.contentType == ContentType.Video360 ->
        item.path.takeIf { it.isFile }?.let { Source(it, isVideoFrame = true) }
      else -> null
    } ?: return null
    val key = keyFor(source.file)
    sources[key] = source
    return "${ImmersiveIntentFactory.THUMBS_ROUTE_PREFIX}$key"
  }

  /** Resolves a `/__thumbs__/` request to a cached or freshly generated file. */
  fun resolve(context: Context, key: String): File? {
    if (!keyPattern.matches(key)) return null
    val cacheDir = cacheDir(context)
    pruneOnce(cacheDir)
    val cachedFile = File(cacheDir, key)
    if (cachedFile.isFile && cachedFile.length() > 0L) return cachedFile
    val source = sources[key] ?: return null
    synchronized(keyLocks.computeIfAbsent(key) { Any() }) {
      if (cachedFile.isFile && cachedFile.length() > 0L) return cachedFile
      generatePermits.acquire()
      try {
        return generate(source, cachedFile)
      } finally {
        generatePermits.release()
        keyLocks.remove(key)
      }
    }
  }

  fun keyFor(file: File): String {
    val seed = "${file.absolutePath}|${file.lastModified()}|${file.length()}"
    val digest = MessageDigest.getInstance("SHA-1").digest(seed.toByteArray())
    return digest.joinToString("") { "%02x".format(it) } + ".jpg"
  }

  private fun cacheDir(context: Context): File =
    File(context.filesDir, CACHE_DIR).apply { mkdirs() }

  private fun generate(source: Source, target: File): File? {
    return try {
      val bitmap = if (source.isVideoFrame) {
        videoFrameExtractor(source.file)?.let(::downscale)
      } else {
        decodeImageDownscaled(source.file)
      } ?: return null
      val tmp = File(target.parentFile, target.name + ".tmp")
      FileOutputStream(tmp).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
      }
      bitmap.recycle()
      if (tmp.renameTo(target)) target else tmp.takeIf { it.isFile }
    } catch (t: Throwable) {
      Log.w(TAG, "thumb generation failed for ${source.file.name}", t)
      null
    }
  }

  private fun decodeImageDownscaled(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE_PX) {
      sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, options)?.let(::downscale)
  }

  private fun downscale(bitmap: Bitmap): Bitmap {
    val edge = maxOf(bitmap.width, bitmap.height)
    if (edge <= MAX_EDGE_PX) return bitmap
    val scale = MAX_EDGE_PX.toFloat() / edge
    val scaled = Bitmap.createScaledBitmap(
      bitmap,
      (bitmap.width * scale).toInt().coerceAtLeast(1),
      (bitmap.height * scale).toInt().coerceAtLeast(1),
      true
    )
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
  }

  private fun extractVideoFrame(file: File): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (t: Throwable) {
      Log.w(TAG, "video frame extract failed for ${file.name}", t)
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  /** Bounds the on-disk cache once per process start. */
  private fun pruneOnce(cacheDir: File) {
    if (!pruned.compareAndSet(false, true)) return
    runCatching {
      val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
      var total = files.sumOf { it.length() }
      if (files.size <= MAX_CACHE_FILES && total <= MAX_CACHE_BYTES) return
      files.sortedBy { it.lastModified() }.forEach { file ->
        if (total <= MAX_CACHE_BYTES && cacheDir.listFiles().orEmpty().size <= MAX_CACHE_FILES) return
        total -= file.length()
        file.delete()
      }
    }
  }
}
