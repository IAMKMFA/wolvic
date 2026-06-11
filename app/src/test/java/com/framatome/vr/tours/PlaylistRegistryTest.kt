package com.framatome.vr.tours

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PlaylistRegistryTest {

  @Before
  fun reset() {
    PlaylistRegistry.clear()
  }

  @Test
  fun `publish then get round trips`() {
    PlaylistRegistry.publish("intent-a", """{"version":1}""")
    assertEquals("""{"version":1}""", PlaylistRegistry.get("intent-a"))
  }

  @Test
  fun `republish overwrites idempotently`() {
    PlaylistRegistry.publish("intent-a", "one")
    PlaylistRegistry.publish("intent-a", "two")
    assertEquals("two", PlaylistRegistry.get("intent-a"))
  }

  @Test
  fun `blank keys and bodies are ignored`() {
    PlaylistRegistry.publish("", "body")
    PlaylistRegistry.publish("key", "")
    assertNull(PlaylistRegistry.get(""))
    assertNull(PlaylistRegistry.get("key"))
  }

  @Test
  fun `oldest entry is evicted past capacity`() {
    for (i in 0 until 17) {
      PlaylistRegistry.publish("key-$i", "json-$i")
    }
    assertNull(PlaylistRegistry.get("key-0"))
    assertEquals("json-16", PlaylistRegistry.get("key-16"))
    assertEquals("json-1", PlaylistRegistry.get("key-1"))
  }

  @Test
  fun `recently accessed entries survive eviction`() {
    for (i in 0 until 16) {
      PlaylistRegistry.publish("key-$i", "json-$i")
    }
    PlaylistRegistry.get("key-0") // touch the eldest
    PlaylistRegistry.publish("key-16", "json-16")
    assertEquals("json-0", PlaylistRegistry.get("key-0"))
    assertNull(PlaylistRegistry.get("key-1")) // the actual eldest got evicted
  }
}
