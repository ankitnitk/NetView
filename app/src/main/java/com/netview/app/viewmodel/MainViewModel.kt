package com.netview.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.TrafficStats
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netview.app.data.CmExportCell
import com.netview.app.data.CmExportRepository
import com.netview.app.data.GsmCmCell
import com.netview.app.data.GsmCmRepository
import com.netview.app.data.LocationData
import com.netview.app.data.LocationRepository
import com.netview.app.data.SettingsRepository
import com.netview.app.data.SimSlotData
import com.netview.app.data.TelephonyRepository
import com.netview.app.data.WcdmaCmCell
import com.netview.app.data.WcdmaCmRepository
import com.netview.app.utils.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val telephonyRepo = TelephonyRepository(app)
    private val locationRepo = LocationRepository(app)
    private val settingsRepo = SettingsRepository(app)
    private val cmExportRepo = CmExportRepository()
    private val wcdmaCmRepo = WcdmaCmRepository()
    private val gsmCmRepo = GsmCmRepository()

    private val _sims = MutableStateFlow<List<SimSlotData>>(emptyList())
    val sims: StateFlow<List<SimSlotData>> = _sims.asStateFlow()

    private val _location = MutableStateFlow<LocationData?>(null)
    val location: StateFlow<LocationData?> = _location.asStateFlow()

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    private val _cmExportStatus = MutableStateFlow("No 4G CMExport file loaded")
    val cmExportStatus: StateFlow<String> = _cmExportStatus.asStateFlow()

    private val _wcdmaCmExportStatus = MutableStateFlow("No 3G CMExport file loaded")
    val wcdmaCmExportStatus: StateFlow<String> = _wcdmaCmExportStatus.asStateFlow()

    private val _gsmCmExportStatus = MutableStateFlow("No 2G CMExport file loaded")
    val gsmCmExportStatus: StateFlow<String> = _gsmCmExportStatus.asStateFlow()

    val cmExportLoaded: StateFlow<Boolean> = cmExportRepo.cells
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val wcdmaCmExportLoaded: StateFlow<Boolean> = wcdmaCmRepo.cells
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val gsmCmExportLoaded: StateFlow<Boolean> = gsmCmRepo.cells
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val refreshSecondsFlow = settingsRepo.refreshSeconds
    val debugLoggingEnabledFlow = settingsRepo.debugLoggingEnabled

    private var refreshSeconds = SettingsRepository.DEFAULT_REFRESH

    // Throughput tracking
    private data class TrafficSnapshot(val rxBytes: Long, val txBytes: Long, val timeMs: Long)
    private var lastTraffic: TrafficSnapshot? = null

    // Cell time tracking: slotIndex → (cellKey, startTimeMs)
    private val cellKeyMap = mutableMapOf<Int, String>()
    private val cellStartMap = mutableMapOf<Int, Long>()

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
        // Reload saved CMExport files on startup
        viewModelScope.launch {
            val savedUri = settingsRepo.cmExportUri.first()
            if (savedUri != null) {
                _cmExportStatus.value = "Loading saved file…"
                loadCmExportUri(Uri.parse(savedUri), persistUri = false)
            }
        }
        viewModelScope.launch {
            val savedUri = settingsRepo.wcdmaCmExportUri.first()
            if (savedUri != null) {
                _wcdmaCmExportStatus.value = "Loading saved file…"
                loadWcdmaCmExportUri(Uri.parse(savedUri), persistUri = false)
            }
        }
        viewModelScope.launch {
            val savedUri = settingsRepo.gsmCmExportUri.first()
            if (savedUri != null) {
                _gsmCmExportStatus.value = "Loading saved file…"
                loadGsmCmExportUri(Uri.parse(savedUri), persistUri = false)
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
        val rawSims = telephonyRepo.readAllSims()
        _location.value = locationRepo.current()

        val now = System.currentTimeMillis()

        // Throughput
        val rxBytes = TrafficStats.getMobileRxBytes()
        val txBytes = TrafficStats.getMobileTxBytes()
        var dlMbps: Double? = null
        var ulMbps: Double? = null
        if (rxBytes >= 0 && txBytes >= 0) {
            val prev = lastTraffic
            if (prev != null) {
                val dtSec = (now - prev.timeMs) / 1000.0
                if (dtSec > 0) {
                    dlMbps = (rxBytes - prev.rxBytes) * 8.0 / 1_000_000.0 / dtSec
                    ulMbps = (txBytes - prev.txBytes) * 8.0 / 1_000_000.0 / dtSec
                    // Clamp negatives (counter reset after reboot)
                    if (dlMbps!! < 0) dlMbps = 0.0
                    if (ulMbps!! < 0) ulMbps = 0.0
                }
            }
            lastTraffic = TrafficSnapshot(rxBytes, txBytes, now)
        }

        // Cell time tracking + enrich sims
        _sims.value = rawSims.map { sim ->
            val key = cellKey(sim)
            val idx = sim.slotIndex
            if (key != null && key != cellKeyMap[idx]) {
                cellKeyMap[idx] = key
                cellStartMap[idx] = now
            }
            val elapsed = if (key != null) (now - (cellStartMap[idx] ?: now)) / 1000L else null
            sim.copy(
                dlThroughputMbps = dlMbps,
                ulThroughputMbps = ulMbps,
                timeOnCellSeconds = elapsed,
            )
        }
    }

    private fun cellKey(sim: SimSlotData): String? {
        val c = sim.servingCell ?: return null
        return when (c.rat) {
            "LTE" -> "LTE_${c.cellId}_${c.earfcn}"
            "NR" -> "NR_${c.cellId}_${c.nrarfcn}"
            "WCDMA" -> "WCDMA_${c.cellId}_${c.uarfcn}"
            "GSM" -> "GSM_${c.cellId}_${c.tac}"
            else -> null
        }
    }

    fun setRefreshSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepo.setRefreshSeconds(seconds) }
    }

    fun setDebugLoggingEnabled(enabled: Boolean) {
        DebugLog.enabled = enabled
        if (enabled) DebugLog.i("CFG", "Debug logging enabled (toggle)")
        viewModelScope.launch { settingsRepo.setDebugLoggingEnabled(enabled) }
    }

    // 4G CMExport
    fun loadCmExport(uri: Uri) {
        viewModelScope.launch { loadCmExportUri(uri, persistUri = true) }
    }

    private suspend fun loadCmExportUri(uri: Uri, persistUri: Boolean) {
        _cmExportStatus.value = "Loading…"
        if (persistUri) takeUriPermission(uri)
        if (persistUri) settingsRepo.setCmExportUri(uri.toString())
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
            _cmExportStatus.value = "No 4G CMExport file loaded"
        }
    }

    fun lookupCmExport(enbId: Long?, sectorId: Int?, mcc: String?, mnc: String?): CmExportCell? {
        if (enbId == null || sectorId == null) return null
        return cmExportRepo.lookup(enbId.toInt(), sectorId, mcc?.toIntOrNull(), mnc?.toIntOrNull())
    }

    fun lookupCmExportByPciEarfcn(pci: Int, earfcn: Int): CmExportCell? =
        cmExportRepo.lookupByPciEarfcn(pci, earfcn)

    // 3G CMExport
    fun loadWcdmaCmExport(uri: Uri) {
        viewModelScope.launch { loadWcdmaCmExportUri(uri, persistUri = true) }
    }

    private suspend fun loadWcdmaCmExportUri(uri: Uri, persistUri: Boolean) {
        _wcdmaCmExportStatus.value = "Loading…"
        if (persistUri) takeUriPermission(uri)
        if (persistUri) settingsRepo.setWcdmaCmExportUri(uri.toString())
        val result = wcdmaCmRepo.load(getApplication(), uri)
        _wcdmaCmExportStatus.value = result.fold(
            onSuccess = { "Loaded $it cells" },
            onFailure = { e -> "Error: ${e.message?.take(80)}" }
        )
    }

    fun clearWcdmaCmExport() {
        viewModelScope.launch {
            wcdmaCmRepo.clear()
            settingsRepo.setWcdmaCmExportUri(null)
            _wcdmaCmExportStatus.value = "No 3G CMExport file loaded"
        }
    }

    fun lookupWcdmaCmExport(rncId: Int?, wcelId: Int?, uarfcn: Int?, mcc: String?, mnc: String?): WcdmaCmCell? {
        if (rncId == null || wcelId == null || uarfcn == null) return null
        return wcdmaCmRepo.lookup(rncId, wcelId, uarfcn, mcc?.toIntOrNull(), mnc?.toIntOrNull())
    }

    // 2G CMExport
    fun loadGsmCmExport(uri: Uri) {
        viewModelScope.launch { loadGsmCmExportUri(uri, persistUri = true) }
    }

    private suspend fun loadGsmCmExportUri(uri: Uri, persistUri: Boolean) {
        _gsmCmExportStatus.value = "Loading…"
        if (persistUri) takeUriPermission(uri)
        if (persistUri) settingsRepo.setGsmCmExportUri(uri.toString())
        val result = gsmCmRepo.load(getApplication(), uri)
        _gsmCmExportStatus.value = result.fold(
            onSuccess = { "Loaded $it cells" },
            onFailure = { e -> "Error: ${e.message?.take(80)}" }
        )
    }

    fun clearGsmCmExport() {
        viewModelScope.launch {
            gsmCmRepo.clear()
            settingsRepo.setGsmCmExportUri(null)
            _gsmCmExportStatus.value = "No 2G CMExport file loaded"
        }
    }

    fun lookupGsmCmExport(lac: Int?, cellId: Int?, mcc: String?, mnc: String?): GsmCmCell? {
        if (lac == null || cellId == null) return null
        return gsmCmRepo.lookup(lac, cellId, mcc?.toIntOrNull(), mnc?.toIntOrNull())
    }

    private fun takeUriPermission(uri: Uri) {
        try {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
    }

    override fun onCleared() {
        super.onCleared()
        locationRepo.stop()
        telephonyRepo.release()
    }
}
