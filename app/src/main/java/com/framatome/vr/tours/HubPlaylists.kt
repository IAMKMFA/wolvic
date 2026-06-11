package com.framatome.vr.tours

import android.content.Context
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
  const val KEY_ALL = "hub-all"

  fun jsonFor(context: Context, key: String): String? {
    val snapshot = MediaLibraryIndex.snapshot()
    val (items, title) = when (key) {
      KEY_VIDEOS -> snapshot.videos to "Videos"
      KEY_IMAGES -> snapshot.images to "Images"
      KEY_MODELS -> snapshot.models to "3D Models"
      KEY_ALL -> (snapshot.videos + snapshot.images + snapshot.models) to "Media Library"
      else -> return null
    }
    val playlistItems = items.mapNotNull(::toPlaylistItem)
    if (playlistItems.isEmpty()) return null
    return PlaylistJson.build(id = key, index = 0, items = playlistItems, title = title)
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
      description = item.description
    )
  }
}
