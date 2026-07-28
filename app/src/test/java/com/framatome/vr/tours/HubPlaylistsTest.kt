package com.framatome.vr.tours

import com.framatome.vr.content.ContentType
import com.framatome.vr.content.library.LibraryCollection
import com.framatome.vr.content.library.LibraryItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class HubPlaylistsTest {
  @get:Rule
  val temp = TemporaryFolder()

  @Test
  fun `loose and collection playlists remain separately scoped`() {
    val storageRoot = temp.newFolder("FramatomeVR")
    val loose = mediaItem(storageRoot, "Data/loose.mp4", "Loose")
    val packageRoot = java.io.File(storageRoot, "Library/Pump Package").apply { mkdirs() }
    val first = mediaItem(packageRoot, "first.mp4", "First")
    val second = mediaItem(packageRoot, "second.mp4", "Second")
    val collection = LibraryCollection(
      id = COLLECTION_ID,
      title = "Pump Package",
      root = packageRoot,
      packageRelativePath = "Pump Package",
      source = LibraryItem.Source.FilesystemScan,
      items = listOf(first, second)
    )
    val snapshot = snapshot(
      videos = listOf(loose, first, second),
      standaloneVideos = listOf(loose),
      collections = listOf(collection)
    )

    val looseItems = JSONObject(
      requireNotNull(HubPlaylists.jsonForSnapshot(snapshot, HubPlaylists.KEY_VIDEOS))
    ).getJSONArray("items")
    val scopedKey = HubPlaylists.scopedKey(COLLECTION_ID, HubPlaylists.KEY_VIDEOS)
    val collectionItems = JSONObject(
      requireNotNull(HubPlaylists.jsonForSnapshot(snapshot, scopedKey))
    ).getJSONArray("items")

    assertEquals(1, looseItems.length())
    assertEquals("Loose", looseItems.getJSONObject(0).getString("title"))
    assertEquals(2, collectionItems.length())
    assertEquals("First", collectionItems.getJSONObject(0).getString("title"))
    assertEquals("Second", collectionItems.getJSONObject(1).getString("title"))
    assertNull(HubPlaylists.jsonForSnapshot(snapshot, "hub-collection-not-safe-videos"))
  }

  private fun mediaItem(root: java.io.File, relativePath: String, title: String): LibraryItem {
    val file = java.io.File(root, relativePath).apply {
      parentFile?.mkdirs()
      writeText("fixture")
    }
    return LibraryItem(
      id = relativePath,
      title = title,
      root = root,
      path = file,
      entryRelativePath = relativePath,
      contentType = ContentType.Video2D,
      source = LibraryItem.Source.FilesystemScan
    )
  }

  private fun snapshot(
    videos: List<LibraryItem>,
    standaloneVideos: List<LibraryItem>,
    collections: List<LibraryCollection>
  ): MediaLibraryIndex.Snapshot = MediaLibraryIndex.Snapshot(
    videos = videos,
    images = emptyList(),
    models = emptyList(),
    clouds = emptyList(),
    standaloneVideos = standaloneVideos,
    standaloneImages = emptyList(),
    standaloneModels = emptyList(),
    standaloneClouds = emptyList(),
    collections = collections,
    issues = emptyList(),
    scannedAtMs = 1L,
    rootsStamp = 1L,
    storageGranted = true,
    contentRevision = "test"
  )

  private companion object {
    private const val COLLECTION_ID = "aaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
