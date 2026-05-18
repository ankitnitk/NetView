package com.netview.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netview.app.data.CmExportCell
import com.netview.app.data.CmExportRepository
import com.netview.app.data.LocationData
import com.netview.app.data.LocationRepository
import com.netview.app.data.SettingsRepository
import com.netview.app.data.SimSlotData
import com.netview.app.data.TelephonyRepository
import com.netview.app.utils.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val telephonyRepo = TelephonyRepository(app)
    private val locationRepo = LocationRepository(app)
    private val settingsRepo = SettingsRepository(app)
    private val cmExportRepo = CmExportRepository()

    private val _sims = MutableStateFlow<List<SimSlotData>>(emptyList())
    val sims: StateFlow<List<SimSlotData>> = _sims.asStateFlow()

    private val _location = MutableStateFlow<LocationData?>(null)
    val location: StateFlow<LocationData?> = _location.asStateFlow()

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    private val _cmExportStatus = MutableStateFlow<String>("No CMExport file loaded")
    val cmExportStatus: StateFlow<String> = _cmExportStatus.asStateFlow()

    val refreshSecondsFlow = settingsRepo.refreshSeconds
    val debugLoggingEnabledFlow = settingsRepo.debugLoggingEnabled

    private var refreshSeconds = SettingsRepository.DEFAULT_REFRESH

    init {
        viewModelScope.launch {
            settingsRepo.refreshSeconds.collect { refreshSeconds = it }
        }
        viewModelScope.launch {
            settingsRepo.debugLoggingEnabled.collect {
                DebugLog.enabled = it
                if (it) DebugLog.i("CFG", "Debug logging enabled")
            }
        }
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(refreshSeconds * 1000L)
            }
        }
        viewModelScope.launch {
            telephonyRepo.caFlow.collect { if (telephonyRepo.hasPermissions()) refresh() }
        }
        // Reload previously saved CMExport file on startup
        viewModelScope.launch {
            val savedUri = settingsRepo.cmExportUri.first()
            if (savedUri != null) {
                _cmExportStatus.value = "Loading saved file…"
                loadCmExportUri(Uri.parse(savedUri), persistUri = false)
            }
        }
    }

    fun onPermissionsGranted() {
        _permissionsGranted.value = true
        locationRepo.start()
        refresh()
    }

    fun refresh() {
        if (!telephonyRepo.hasPermissions()) return
        _sims.value = telephonyRepo.readAllSims()
        _location.value = locationRepo.current()
    }

    fun setRefreshSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepo.setRefreshSeconds(seconds) }
    }

    fun setDebugLoggingEnabled(enabled: Boolean) {
        DebugLog.enabled = enabled
        if (enabled) DebugLog.i("CFG", "Debug logging enabled (toggle)")
        viewModelScope.launch { settingsRepo.setDebugLoggingEnabled(enabled) }
    }

    fun loadCmExport(uri: Uri) {
        viewModelScope.launch { loadCmExportUri(uri, persistUri = true) }
    }

    private suspend fun loadCmExportUri(uri: Uri, persistUri: Boolean) {
        _cmExportStatus.value = "Loading…"
        if (persistUri) {
            try {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { /* not all URIs support persistence */ }
            settingsRepo.setCmExportUri(uri.toString())
        }
        val result = cmExportRepo.load(getApplication(), uri)
        _cmExportStatus.value = result.fold(
            onSuccess = { "Loaded $it cells" },
            onFailure = { e -> "Error: ${e.message?.take(80)}" }
        )
    }

    fun clearCmExport() {
        viewModelScope.launch {
            cmExportRepo.clear()
            settingsRepo.setCmExportUri(null)
            _cmExportStatus.value = "No CMExport file loaded"
        }
    }

    fun lookupCmExport(pci: Int, earfcn: Int): CmExportCell? = cmExportRepo.lookup(pci, earfcn)

    override fun onCleared() {
        super.onCleared()
        locationRepo.stop()
        telephonyRepo.release()
    }
}
