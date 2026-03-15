package com.framatome.vr.tours

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class TourListViewModel(app: Application) : AndroidViewModel(app) {
    private val _tours = MutableStateFlow<List<Tour>>(emptyList())
    val tours: StateFlow<List<Tour>> = _tours

    val baseDir: File = File(app.getExternalFilesDir(null), "tours")
    private val settingsStore = TourSettingsStore(app)
    private val publicImporter = PublicInboxImporter(app)

    init {
        rescan()
    }

    fun rescan() {
        viewModelScope.launch(Dispatchers.IO) { performRescan() }
    }

    fun deleteTour(tour: Tour) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(tour.absolutePath)
            if (dir.exists()) dir.deleteRecursively()
            settingsStore.remove(tour.relativePath)
            _tours.value = _tours.value.filter { it.relativePath != tour.relativePath }
            performRescan()
        }
    }

    fun saveTourOrder(list: List<Tour>) {
        _tours.value = list.toList()
    }

    fun setTourEnabled(tour: Tour, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(tour.absolutePath)
            if (!dir.exists()) {
                settingsStore.remove(tour.relativePath)
                performRescan()
                return@launch
            }
            settingsStore.setEnabled(tour.relativePath, enabled)
            _tours.value = _tours.value.map {
                if (it.relativePath == tour.relativePath) it.copy(enabled = enabled) else it
            }
        }
    }

    private fun performRescan() {
        if (!baseDir.exists()) baseDir.mkdirs()
        publicImporter.runImportOnce()

        val baseCanonical = baseDir.canonicalFile
        val list = baseDir.listFiles { file ->
            file.isDirectory && !file.name.startsWith(".")
        }?.mapNotNull { dir ->
            val indexHtml = File(dir, "index.html")
            val indexHtm = File(dir, "index.htm")
            val entry = when {
                indexHtml.exists() -> "index.html"
                indexHtm.exists() -> "index.htm"
                else -> null
            }
            entry?.let {
                val thumb = listOf("thumbnail.jpg", "thumbnail.png").map { File(dir, it) }
                    .firstOrNull { it.exists() }?.absolutePath
                val rel = dir.canonicalFile.relativeTo(baseCanonical).invariantSeparatorsPath
                val enabled = settingsStore.isEnabled(rel)
                val source = when {
                    File(dir, ".bundled").exists() -> TourSource.BUNDLED
                    File(dir, ".imported").exists() -> TourSource.EXTERNAL
                    else -> TourSource.EXTERNAL
                }
                Tour(
                    name = dir.name,
                    absolutePath = dir.absolutePath,
                    relativePath = rel,
                    entryFileName = it,
                    thumbnailPath = thumb,
                    source = source,
                    enabled = enabled
                )
            }
        }?.sortedBy { it.name } ?: emptyList()
        _tours.value = list
    }
}
