package com.framatome.vr.tours

import com.framatome.vr.content.library.LibraryScanResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class MediaLibraryIndexTest {
  @Before
  fun setUp() {
    MediaLibraryIndex.resetForTesting()
  }

  @After
  fun tearDown() {
    MediaLibraryIndex.resetForTesting()
  }

  @Test
  fun `scan failure retains the last published snapshot`() {
    val published = MediaLibraryIndex.refreshNow()
    MediaLibraryIndex.scannerOverrideForTesting = {
      throw IllegalStateException("simulated storage failure")
    }

    runCatching { MediaLibraryIndex.refreshNow() }
      .onSuccess { throw AssertionError("Expected refresh to fail") }
      .onFailure {
        assertEquals(MediaLibraryIndex.LibraryScanException::class.java, it.javaClass)
      }

    val retained = MediaLibraryIndex.snapshot()
    assertEquals(published.contentRevision, retained.contentRevision)
    assertEquals(published.mediaCount, retained.mediaCount)
    assertNotNull(MediaLibraryIndex.lastScanError())
  }

  @Test
  fun `older failed scan cannot overwrite newer successful status`() {
    val calls = AtomicInteger()
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    MediaLibraryIndex.scannerOverrideForTesting = {
      if (calls.incrementAndGet() == 1) {
        firstStarted.countDown()
        releaseFirst.await(5, TimeUnit.SECONDS)
        throw IllegalStateException("older failure")
      }
      LibraryScanResult.EMPTY
    }
    val older = thread(name = "older-library-scan") {
      runCatching { MediaLibraryIndex.refreshNow() }
    }
    assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

    MediaLibraryIndex.refreshNow()
    releaseFirst.countDown()
    older.join(5_000)

    assertNull(MediaLibraryIndex.lastScanError())
  }

  @Test
  fun `retriggered background success wins over an older forced failure`() {
    val calls = AtomicInteger()
    val firstBackgroundStarted = CountDownLatch(1)
    val forcedStarted = CountDownLatch(1)
    val releaseFirstBackground = CountDownLatch(1)
    val releaseForced = CountDownLatch(1)
    val retriggeredBackgroundFinished = CountDownLatch(1)
    MediaLibraryIndex.scannerOverrideForTesting = {
      when (calls.incrementAndGet()) {
        1 -> {
          firstBackgroundStarted.countDown()
          releaseFirstBackground.await(5, TimeUnit.SECONDS)
          LibraryScanResult.EMPTY
        }
        2 -> {
          forcedStarted.countDown()
          releaseForced.await(5, TimeUnit.SECONDS)
          throw IllegalStateException("older forced failure")
        }
        else -> {
          retriggeredBackgroundFinished.countDown()
          LibraryScanResult.EMPTY
        }
      }
    }

    MediaLibraryIndex.prewarm()
    assertTrue(firstBackgroundStarted.await(5, TimeUnit.SECONDS))
    val forced = thread(name = "forced-library-scan") {
      runCatching { MediaLibraryIndex.refreshNow() }
    }
    assertEquals(1, calls.get())
    releaseFirstBackground.countDown()
    assertTrue(forcedStarted.await(5, TimeUnit.SECONDS))
    releaseForced.countDown()
    assertTrue(retriggeredBackgroundFinished.await(5, TimeUnit.SECONDS))
    forced.join(5_000)
    val publishDeadline = System.currentTimeMillis() + 5_000L
    while (
      MediaLibraryIndex.lastScanError() != null &&
      System.currentTimeMillis() < publishDeadline
    ) {
      Thread.sleep(10L)
    }
    assertTrue(MediaLibraryIndex.currentRevision().isNotEmpty())
    assertNull(MediaLibraryIndex.lastScanError())
  }
}
