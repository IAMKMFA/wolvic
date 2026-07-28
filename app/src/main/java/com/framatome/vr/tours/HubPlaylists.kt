package com.framatome.vr.tours

import android.content.Context
import com.framatome.vr.content.ContentType
import com.framatome.vr.content.MediaViewerUrls
import com.framatome.vr.content.library.ContentBadges
import com.framatome.vr.content.library.LibraryItem
import com.framatome.vr.content.library.PlaylistJson

/**
 * Deterministic, computed-on-demand playlists for the hub's media sections
 * (`hub-videos`, `hub-images`, `hub-all`). Unlike intent playlists these are
 * never registered: they are derived from the same [MediaLibraryIndex]
 * snapshot that rendered the hub page, so playlist order always matches the
 * cards the user just saw.
 */
object HubPlaylists {
  const val KEY_VIDEOS = "hub-videos"
  const val KEY_IMAGES = "hub-images"
  const val KEY_MODELS = "hub-models"
  const val KEY_CLOUDS = "hub-clouds"
  const val KEY_ALL = "hub-all"
  private const val COLLECTION_PREFIX = "hub-collection-"
  private val collectionKeyPattern = Regex(
    "^${COLLECTION_PREFIX}([a-f0-9]{24})-(videos|images|models|clouds)$"
  )

  @Suppress("UNUSED_PARAMETER")
  fun jsonFor(context: Context, key: String): String? =
    jsonForSnapshot(MediaLibraryIndex.snapshot(), key)

  internal fun jsonForSnapshot(snapshot: MediaLibraryIndex.Snapshot, key: String): String? {
    val (items, title) = when (key) {
      KEY_VIDEOS -> snapshot.standaloneVideos to "Videos"
      KEY_IMAGES -> snapshot.standaloneImages to "Images"
      KEY_MODELS -> snapshot.standaloneModels to "3D Models"
      KEY_CLOUDS -> snapshot.standaloneClouds to "Point Clouds"
      KEY_ALL -> (snapshot.videos + snapshot.images + snapshot.models + snapshot.clouds) to "Media Library"
      else -> collectionItems(snapshot, key) ?: return null
    }
    val playlistItems = items.mapNotNull(::toPlaylistItem)
    if (playlistItems.isEmpty()) return null
    return PlaylistJson.build(id = key, index = 0, items = playlistItems, title = title)
  }

  fun scopedKey(collectionId: String, sectionKey: String): String {
    val suffix = when (sectionKey) {
      KEY_VIDEOS -> "videos"
      KEY_IMAGES -> "images"
      KEY_MODELS -> "models"
      KEY_CLOUDS -> "clouds"
      else -> throw IllegalArgumentException("Unsupported collection playlist section: $sectionKey")
    }
    require(collectionId.matches(Regex("[a-f0-9]{24}"))) { "Invalid collection id" }
    return "$COLLECTION_PREFIX$collectionId-$suffix"
  }

  private fun collectionItems(
    snapshot: MediaLibraryIndex.Snapshot,
    key: String
  ): Pair<List<LibraryItem>, String>? {
    val match = collectionKeyPattern.matchEntire(key) ?: return null
    val collection = snapshot.collections.firstOrNull { it.id == match.groupValues[1] } ?: return null
    val suffix = match.groupValues[2]
    val items = when (suffix) {
      "videos" -> collection.items.filter {
        it.contentType == ContentType.Video360 || it.contentType == ContentType.Video2D
      }
      "images" -> collection.items.filter {
        it.contentType == ContentType.Image360 || it.contentType == ContentType.Image2D
      }
      "models" -> collection.items.filter { it.contentType == ContentType.Model3D }
      "clouds" -> collection.items.filter { it.contentType == ContentType.PointCloud }
      else -> return null
    }
    val sectionTitle = when (suffix) {
      "videos" -> "Videos"
      "images" -> "Images"
      "models" -> "3D Models"
      "clouds" -> "Point Clouds"
      else -> return null
    }
    return items to "${collection.title} — $sectionTitle"
  }

  /**
   * The viewer reconciles its launched item against playlist entries by `id`;
   * the storage-relative path is the one identifier that is unique across the
   * whole tree and identical in hub card URLs and playlist entries.
   */
  fun itemId(item: LibraryItem): String? =
    MediaViewerUrls.mediaRelativePath(item.path.absolutePath)

  fun toPlaylistItem(item: LibraryItem): PlaylistJson.Item? {
    val src = itemId(item) ?: return null
    return PlaylistJson.Item(
      id = src,
      title = item.title,
      type = item.contentType.token,
      src = src,
      projection = item.projection,
      stereo = item.stereo,
      right = item.rightEyeFile()?.let { MediaViewerUrls.mediaRelativePath(it.absolutePath) },
      badge = ContentBadges.labelFor(item.contentType, item.projection),
      thumb = ThumbnailStore.thumbUrlFor(item),
      description = item.description,
      audio = item.audio,
      captions = item.captionsFile()?.let { MediaViewerUrls.mediaRelativePath(it.absolutePath) }
    )
  }
}
