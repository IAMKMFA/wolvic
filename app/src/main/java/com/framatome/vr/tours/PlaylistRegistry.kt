package com.framatome.vr.tours

/**
 * In-memory playlist documents served at `/__playlist__/<key>` for the bundled
 * WebXR media viewer's prev/next navigation.
 *
 * Process-lifetime storage is the point, not a limitation: every content
 * launch republishes its (windowed) gallery through
 * `MediaLaunchRouter.prepareImmersiveContentIntent`, so after a crash or
 * restart the next launch recreates exactly the state the viewer needs, and
 * nothing stale ever survives on disk.
 */
object PlaylistRegistry {
  private const val MAX_ENTRIES = 16

  private val entries = object : LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
      return size > MAX_ENTRIES
    }
  }

  @Synchronized
  fun publish(key: String, json: String) {
    if (key.isBlank() || json.isBlank()) return
    entries[key] = json
  }

  @Synchronized
  fun get(key: String): String? = entries[key]

  @Synchronized
  fun clear() {
    entries.clear()
  }
}
