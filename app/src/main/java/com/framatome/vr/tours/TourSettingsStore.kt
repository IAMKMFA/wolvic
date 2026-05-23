package com.framatome.vr.tours

import android.app.Application
import android.content.Context

class TourSettingsStore(app: Application) {
    private val prefs = app.getSharedPreferences("tour_settings", Context.MODE_PRIVATE)

    fun isEnabled(relativePath: String): Boolean {
        val disabled = prefs.getStringSet(DISABLED_KEY, emptySet()) ?: emptySet()
        return !disabled.contains(relativePath)
    }

    fun setEnabled(relativePath: String, enabled: Boolean) {
        val current = prefs.getStringSet(DISABLED_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) {
            current.remove(relativePath)
        } else {
            current.add(relativePath)
        }
        prefs.edit().putStringSet(DISABLED_KEY, current.toSet()).apply()
    }

    fun remove(relativePath: String) {
        val current = prefs.getStringSet(DISABLED_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(relativePath)
        prefs.edit().putStringSet(DISABLED_KEY, current.toSet()).apply()
    }

    companion object {
        private const val DISABLED_KEY = "disabled_tours"
    }
}
