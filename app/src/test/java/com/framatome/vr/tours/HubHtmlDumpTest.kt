package com.framatome.vr.tours

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.framatome.vr.util.StorageLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Temporary harness: seeds a representative content set and writes the exact
 * hub HTML the headset would render to /tmp/hub-preview/index.html for visual
 * review on the desktop. Not part of the release gate; delete when done.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class HubHtmlDumpTest {
  @Test
  fun `dump hub html for desktop preview`() {
    val app = ApplicationProvider.getApplicationContext<Application>()

    // Three tours.
    for (name in listOf("Unit 2 Containment Walkdown", "Turbine Hall Orientation", "Fuel Handling Refresher")) {
      val d = File(TourStorage.sharedToursDir(), name)
      d.mkdirs()
      File(d, "index.html").writeText("<html></html>")
      TourSettingsStore(app).setEnabled(name, true)
    }

    // Standalone media in the Library.
    val lib = StorageLayout.libraryDir.apply { mkdirs() }
    File(lib, "Reactor Pool Inspection.mp4").writeBytes(ByteArray(64))
    File(lib, "Spent Fuel Crane 360.mp4").writeBytes(ByteArray(64))
    File(lib, "Control Room Panorama 360.jpg").writeBytes(ByteArray(64))
    File(lib, "Steam Generator Cutaway.glb").writeBytes(ByteArray(64))
    File(lib, "Primary Loop Scan.ply").writeBytes(ByteArray(64))

    // A collection: a folder of related images.
    val coll = File(lib, "Outage Prep Photo Set").apply { mkdirs() }
    for (i in 1..4) File(coll, "step-$i.jpg").writeBytes(ByteArray(64))

    HubIndexServer.invalidate()
    MediaLibraryIndex.invalidate()
    MediaLibraryIndex.refreshNow() // synchronous scan so counts are populated

    val html = HubIndexServer.generateIndexHtml(app)
    val out = File("/tmp/hub-preview/index.html")
    out.parentFile?.mkdirs()
    out.writeText(html)
    assertTrue(html.contains("Your Tours"))
    println("WROTE ${out.absolutePath} (${html.length} chars)")
  }
}
