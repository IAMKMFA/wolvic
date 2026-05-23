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

    private val appStorageRoot: File = TourStorage.appStorageRoot(app)
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
        TourStorage.ensureAppDirs(getApplication())
        publicImporter.runImportOnce()
        _tours.value = TourCatalog.scan(appStorageRoot, settingsStore::isEnabled)
    }
}
