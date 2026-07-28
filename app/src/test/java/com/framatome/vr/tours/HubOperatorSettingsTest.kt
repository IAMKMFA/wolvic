package com.framatome.vr.tours

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.framatome.vr.util.StorageLayout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class HubOperatorSettingsTest {
  private lateinit var app: Application
  private lateinit var tourDir: File

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    tourDir = File(TourStorage.sharedToursDir(), "OperatorSettingsTest")
    tourDir.mkdirs()
    File(tourDir, "index.html").writeText("<html></html>")
    TourSettingsStore(app).setEnabled(tourDir.name, true)
    HubIndexServer.invalidate()
    MediaLibraryIndex.invalidate()
  }

  @After
  fun tearDown() {
    TourSettingsStore(app).remove(tourDir.name)
    tourDir.deleteRecursively()
    HubIndexServer.invalidate()
    MediaLibraryIndex.invalidate()
  }

  @Test
  fun `status includes readiness content and installed tours`() {
    val status = JSONObject(HubOperatorSettings.statusJson(app))
    val tours = status.getJSONArray("tours")
    val installed = (0 until tours.length())
      .map { tours.getJSONObject(it) }
      .first { it.getString("relativePath") == tourDir.name }

    assertTrue(status.getBoolean("storageGranted"))
    assertEquals(LocalWebServer.PORT, status.getInt("serverPort"))
    assertTrue(status.has("content"))
    assertTrue(installed.getBoolean("enabled"))
  }

  @Test
  fun `tour visibility can be disabled and restored`() {
    val hidden = HubOperatorSettings.setTourEnabled(app, tourDir.name, false)

    assertTrue(hidden.success)
    assertTrue(hidden.changed)
    assertFalse(TourSettingsStore(app).isEnabled(tourDir.name))

    val restored = HubOperatorSettings.setTourEnabled(app, tourDir.name, true)

    assertTrue(restored.success)
    assertTrue(TourSettingsStore(app).isEnabled(tourDir.name))
  }

  @Test
  fun `preview repair only clears generated thumbnail files`() {
    val source = File(app.filesDir, "source.jpg").apply { writeText("source") }
    val cacheFile = File(app.filesDir, "thumbs/generated.jpg").apply {
      parentFile?.mkdirs()
      writeText("preview")
    }

    val result = HubOperatorSettings.clearPreviews(app)

    assertTrue(result.success)
    assertTrue(result.deletedPreviews >= 1)
    assertFalse(cacheFile.exists())
    assertTrue(source.exists())
    source.delete()
  }

  @Test
  fun `operator panel exposes maintenance storage and visibility controls`() {
    val markup = HubOperatorPanel.markup()
    val script = HubOperatorPanel.script()

    assertTrue(markup.contains("Rescan content"))
    assertTrue(markup.contains("Open Android storage settings"))
    assertTrue(markup.contains("Tour visibility"))
    assertTrue(script.contains("/__operator__/status"))
    assertTrue(script.contains("/__operator__/tour"))
    assertTrue(script.contains("X-Framatome-Request"))
  }

  @Test
  fun `hub keeps operator controls and delivered tours on distinct origins`() {
    val html = HubIndexServer.generateIndexHtml(app)

    assertTrue(html.contains("id=\"operatorPanel\""))
    assertTrue(
      html.contains("${LocalWebServer.baseUrl()}/${tourDir.name}/index.html")
    )
    assertFalse(html.contains("href=\"/${tourDir.name}/index.html\""))
  }

  @Test
  fun `hub cache miss includes files added inside an existing package`() {
    val mediaPackage = File(StorageLayout.libraryDir, "NestedRefreshTest").apply {
      deleteRecursively()
      mkdirs()
    }
    File(mediaPackage, "first.jpg").writeText("first")
    val initialRevision = MediaLibraryIndex.refreshNow().contentRevision
    HubIndexServer.generateIndexHtml(app)

    File(mediaPackage, "new-video.mp4").writeText("second")
    MediaLibraryIndex.invalidate()
    val deadline = System.currentTimeMillis() + 2_000L
    while (
      MediaLibraryIndex.snapshot().contentRevision == initialRevision &&
      System.currentTimeMillis() < deadline
    ) {
      Thread.sleep(10L)
    }
    HubIndexServer.invalidate()
    val refreshed = HubIndexServer.generateIndexHtml(app)

    assertTrue(refreshed.contains("new-video"))
    mediaPackage.deleteRecursively()
  }
}
