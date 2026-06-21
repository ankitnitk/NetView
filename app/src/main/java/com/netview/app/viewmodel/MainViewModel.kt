package com.netview.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netview.app.data.CellChangeEvent
import com.netview.app.data.CellHistory
import com.netview.app.data.CmExportCell
import com.netview.app.data.CmExportRepository
import com.netview.app.data.GsmCmCell
import com.netview.app.data.GsmCmRepository
import com.netview.app.data.LocationData
import com.netview.app.data.LocationRepository
import com.netview.app.data.NeighborCell
import com.netview.app.data.SettingsRepository
import com.netview.app.data.SimSlotData
import com.netview.app.data.TelephonyRepository
import com.netview.app.data.WcdmaCmCell
import com.netview.app.data.WcdmaCmRepository
import com.netview.app.data.WifiRepository
import com.netview.app.data.WifiState
import com.netview.app.utils.DebugLog
import com.netview.app.utils.StatusNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val telephonyRepo = TelephonyRepository(app)
    private val locationRepo = LocationRepository(app)
    private val settingsRepo = SettingsRepository(app)
    private val cmExportRepo = CmExportRepository()
    private val wcdmaCmRepo = WcdmaCmRepository()
    private val gsmCmRepo = GsmCmRepository()
    private val wifiRepo = WifiRepository(app)

    private val _sims = MutableStateFlow<List<SimSlotData>>(emptyList())
    val sims: StateFlow<List<SimSlotData>> = _sims.asStateFlow()

    private val _location = MutableStateFlow<LocationData?>(null)
    val location: StateFlow<LocationData?> = _location.asStateFlow()

    private val _wifiState = MutableStateFlow<WifiState?>(null)
    val wifiState: StateFlow<WifiState?> = _wifiState.asStateFlow()

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
    val cellChangeLoggingEnabledFlow = settingsRepo.cellChangeLoggingEnabled
    val keepScreenOnFlow = settingsRepo.keepScreenOn
    val statusNotificationEnabledFlow = settingsRepo.statusNotificationEnabled

    @Volatile private var statusNotificationEnabled = false

    private var refreshSeconds = SettingsRepository.DEFAULT_REFRESH

    // Throughput tracking
    private data class TrafficSnapshot(val rxBytes: Long, val txBytes: Long, val timeMs: Long, val isWifi: Boolean)
    private var lastTraffic: TrafficSnapshot? = null

    // Latency ping — runs every 4th refresh on IO thread, result persisted here
    private var refreshCount = 0
    private val _latencyMs = MutableStateFlow<Long?>(null)

    // Cell time tracking: slotIndex → (cellKey, startTimeMs)
    private val cellKeyMap = mutableMapOf<Int, String>()
    private val cellStartMap = mutableMapOf<Int, Long>()
    // Previous network type per slot, for the Cell Change Log's "from → to" transition.
    private val lastNetworkTypeMap = mutableMapOf<Int, String>()

    // Polling only runs while the UI is in the foreground (see start()/stop()).
    // Avoids draining the battery by reading telephony + GPS when the app is hidden.
    @Volatile private var isForeground = false
    private var pollingJob: Job? = null

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
            settingsRepo.cellChangeLoggingEnabled.collect { CellHistory.enabled = it }
        }
        viewModelScope.launch {
            settingsRepo.statusNotificationEnabled.collect { statusNotificationEnabled = it }
        }
        viewModelScope.launch {
            telephonyRepo.caFlow.collect { if (isForeground && telephonyRepo.hasPermissions()) refresh() }
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
        start()
    }

    /** Called from the Activity's onResume — begin foreground polling. */
    fun start() {
        if (!telephonyRepo.hasPermissions()) return
        _permissionsGranted.value = true
        isForeground = true
        StatusNotifier.cancel(getApplication<Application>()) // app visible — no need for the status notification
        // GPS update interval tracks the refresh rate (min 1s) instead of always polling at 1s.
        locationRepo.start((refreshSeconds.coerceAtLeast(1) * 1000L))
        if (pollingJob?.isActive != true) {
            pollingJob = viewModelScope.launch {
                while (isActive) {
                    refresh()
                    delay(refreshSeconds * 1000L)
                }
            }
        }
    }

    /**
     * Called from the Activity's onStop. Normally stops polling to save battery, but
     * keeps a best-effort poll alive when the background status notification is enabled.
     */
    fun stop() {
        isForeground = false
        if (statusNotificationEnabled) return // keep polling so the notification stays current
        pollingJob?.cancel()
        pollingJob = null
        locationRepo.stop()
    }

    fun refresh() {
        if (!telephonyRepo.hasPermissions()) return
        val rawSims = telephonyRepo.readAllSims()
        _location.value = locationRepo.current()

        val now = System.currentTimeMillis()
        val wifiIsDataTransport = isWifiDataActive()

        // Throughput — use total bytes when on WiFi, mobile bytes when on cellular
        val rxBytes = if (wifiIsDataTransport) TrafficStats.getTotalRxBytes() else TrafficStats.getMobileRxBytes()
        val txBytes = if (wifiIsDataTransport) TrafficStats.getTotalTxBytes() else TrafficStats.getMobileTxBytes()
        var dlMbps: Double? = null
        var ulMbps: Double? = null
        if (rxBytes >= 0 && txBytes >= 0) {
            val prev = lastTraffic
            // Reset snapshot when transport type changes to avoid bogus spike
            if (prev != null && prev.isWifi == wifiIsDataTransport) {
                val dtSec = (now - prev.timeMs) / 1000.0
                if (dtSec > 0) {
                    dlMbps = ((rxBytes - prev.rxBytes) * 8.0 / 1_000_000.0 / dtSec).coerceAtLeast(0.0)
                    ulMbps = ((txBytes - prev.txBytes) * 8.0 / 1_000_000.0 / dtSec).coerceAtLeast(0.0)
                }
            }
            lastTraffic = TrafficSnapshot(rxBytes, txBytes, now, wifiIsDataTransport)
        }

        // Ping latency — TCP connect to 8.8.8.8:53, every 4th refresh
        refreshCount++
        if (refreshCount % 4 == 0) {
            viewModelScope.launch(Dispatchers.IO) {
                _latencyMs.value = measureLatencyMs()
            }
        }

        // Assign throughput + latency to WiFi tab or SIM tab depending on active transport
        val wifiBase = wifiRepo.read().let { if (it.isEnabled) it else null }
        _wifiState.value = wifiBase?.let {
            if (wifiIsDataTransport)
                it.copy(dlThroughputMbps = dlMbps, ulThroughputMbps = ulMbps, latencyMs = _latencyMs.value)
            else
                it
        }

        // TrafficStats is device-wide — only credit the active data SIM (when on cellular)
        val defaultDataSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            SubscriptionManager.getDefaultDataSubscriptionId()
        else SubscriptionManager.INVALID_SUBSCRIPTION_ID

        // Cell time tracking + enrich sims
        _sims.value = rawSims.map { sim ->
            val key = cellKey(sim)
            val idx = sim.slotIndex
            if (key != null && key != cellKeyMap[idx]) {
                cellKeyMap[idx] = key
                cellStartMap[idx] = now
                // Guard against DSDS bleed: on a single-modem dual-SIM device the
                // non-data SIM can momentarily mirror the data SIM's serving cell via the
                // shared allCellInfo fallback. Treat a change as bleed only when this SIM's
                // cell did NOT come from its own registration AND another SIM reports the
                // same cell this poll. Genuine same-operator co-location arrives via each
                // SIM's own ServiceState, so it is never wrongly suppressed.
                val bleedDuplicate = !sim.servingFromRegistration &&
                    rawSims.any { it.slotIndex != idx && cellKey(it) == key }
                if (CellHistory.enabled && !bleedDuplicate) {
                    recordCellChange(sim, lastNetworkTypeMap[idx], now)
                }
                lastNetworkTypeMap[idx] = sim.networkType
            }
            val elapsed = if (key != null) (now - (cellStartMap[idx] ?: now)) / 1000L else null
            val isDataSim = defaultDataSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                || sim.subId == defaultDataSubId
            sim.copy(
                dlThroughputMbps = if (!wifiIsDataTransport && isDataSim) dlMbps else null,
                ulThroughputMbps = if (!wifiIsDataTransport && isDataSim) ulMbps else null,
                latencyMs = if (!wifiIsDataTransport && isDataSim) _latencyMs.value else null,
                timeOnCellSeconds = elapsed,
                // Drop neighbours that are actually another SIM's serving cell bleeding
                // through the shared allCellInfo on single-modem DSDS.
                neighborCells = sim.neighborCells.filterNot { isBledNeighbour(it, sim, rawSims) },
                interRatNeighborCells = sim.interRatNeighborCells.filterNot { isBledNeighbour(it, sim, rawSims) },
            )
        }

        // Best-effort background status notification (no foreground service).
        if (statusNotificationEnabled && !isForeground) {
            updateStatusNotification(rawSims, defaultDataSubId)
        }
    }

    /** Build and post the silent background status notification for the data SIM. */
    private fun updateStatusNotification(sims: List<SimSlotData>, dataSubId: Int) {
        val sim = sims.firstOrNull { it.subId == dataSubId } ?: sims.firstOrNull() ?: return
        val c = sim.servingCell
        // Cell name from CM dump if loaded (LTE), else eNB/sector or CID.
        val cellName = when {
            c?.rat == "LTE" -> cmExportRepo.lookup(
                c.enbId?.toInt() ?: -1, c.sectorId ?: -1,
                sim.mcc?.toIntOrNull(), sim.mnc?.toIntOrNull()
            )?.lncelName ?: c.enbId?.let { "eNB $it-${c.sectorId ?: "?"}" }
            c != null -> c.cellId?.let { "CID $it" }
            else -> null
        }
        val ccCount = sim.carrierAggregation.size
        val title = buildString {
            append(sim.networkType)
            cellName?.let { append(" • ").append(it) }
        }
        val text = buildString {
            c?.rsrp?.let { append("RSRP $it") }
            c?.rsrq?.let { append("  RSRQ $it") }
            (c?.rssnr ?: c?.ssSinr)?.let { append("  SINR $it") }
            if (ccCount > 0) append("  •  ${ccCount}CC")
            if (isEmpty()) append("No serving cell")
        }
        StatusNotifier.show(getApplication<Application>(), title, text)
    }

    private fun isWifiDataActive(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun measureLatencyMs(): Long? = try {
        val start = System.currentTimeMillis()
        Socket().use { it.connect(InetSocketAddress("8.8.8.8", 53), 3000) }
        System.currentTimeMillis() - start
    } catch (_: Exception) { null }

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

    /**
     * True when a neighbour cell exactly matches another SIM's serving cell
     * (same RAT + frequency + PCI/PSC). On single-modem DSDS the shared
     * allCellInfo can surface the other SIM's serving cell as a "neighbour" of
     * this one — drop those. (Trade-off: in same-operator co-location where the
     * other SIM genuinely camps on this SIM's real neighbour, that cell is hidden
     * here, but it is still visible on the other SIM's tab as its serving cell.)
     */
    private fun isBledNeighbour(n: NeighborCell, ownSim: SimSlotData, allSims: List<SimSlotData>): Boolean {
        return allSims.any { other ->
            if (other.subId == ownSim.subId) return@any false
            val s = other.servingCell ?: return@any false
            when (n.rat) {
                "LTE" -> s.rat == "LTE" && n.pci != null && n.pci == s.pci &&
                    n.earfcn != null && n.earfcn == s.earfcn
                "WCDMA" -> s.rat == "WCDMA" && n.psc != null && n.psc == s.pci &&
                    n.uarfcn != null && n.uarfcn == s.uarfcn
                else -> false
            }
        }
    }

    /** Build and store a Cell Change Log entry from the SIM's current serving cell. */
    private fun recordCellChange(sim: SimSlotData, fromType: String?, now: Long) {
        val c = sim.servingCell ?: return
        val loc = _location.value
        val arfcn = when (c.rat) {
            "LTE" -> c.earfcn
            "NR" -> c.nrarfcn
            "WCDMA" -> c.uarfcn
            "GSM" -> c.arfcn
            else -> null
        }
        // Map each RAT's primary metrics onto the common rsrp/rsrq/sinr slots.
        val rsrp = c.rsrp ?: c.rscp ?: c.rssi
        val rsrq = c.rsrq ?: c.ecNo
        val sinr = c.rssnr ?: c.ssSinr
        CellHistory.record(
            CellChangeEvent(
                timestampMillis = now,
                slotIndex = sim.slotIndex,
                simLabel = sim.carrierName,
                fromNetworkType = fromType,
                networkType = sim.networkType,
                rat = c.rat,
                enbId = c.enbId ?: c.gnbId,
                cellId = c.cellId,
                sectorId = c.sectorId,
                pci = c.pci,
                tac = c.tac,
                arfcn = arfcn,
                band = c.band,
                mcc = sim.mcc,
                mnc = sim.mnc,
                rsrp = rsrp,
                rsrq = rsrq,
                sinr = sinr,
                latitude = loc?.latitude,
                longitude = loc?.longitude,
            )
        )
    }

    /**
     * Resolve a friendly cell name from a loaded CM dump for a logged event,
     * or null if not loaded / not found. Used by the Cell History screen's
     * expandable rows.
     */
    fun cellNameFor(e: CellChangeEvent): String? {
        val mcc = e.mcc?.toIntOrNull()
        val mnc = e.mnc?.toIntOrNull()
        return when (e.rat) {
            "LTE" -> {
                val enb = e.enbId?.toInt() ?: return null
                val sector = e.sectorId ?: return null
                cmExportRepo.lookup(enb, sector, mcc, mnc)?.lncelName
            }
            "WCDMA" -> {
                val ci = e.cellId ?: return null
                val uarfcn = e.arfcn ?: return null
                val rncId = (ci shr 16).toInt()
                val wcelId = (ci and 0xFFFF).toInt()
                wcdmaCmRepo.lookup(rncId, wcelId, uarfcn, mcc, mnc)?.wcelName
            }
            "GSM" -> {
                val lac = e.tac ?: return null
                val cid = e.cellId?.toInt() ?: return null
                gsmCmRepo.lookup(lac, cid, mcc, mnc)?.cellName
            }
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

    fun setCellChangeLoggingEnabled(enabled: Boolean) {
        CellHistory.enabled = enabled
        viewModelScope.launch { settingsRepo.setCellChangeLoggingEnabled(enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setKeepScreenOn(enabled) }
    }

    fun setStatusNotificationEnabled(enabled: Boolean) {
        statusNotificationEnabled = enabled
        viewModelScope.launch { settingsRepo.setStatusNotificationEnabled(enabled) }
        if (!enabled) StatusNotifier.cancel(getApplication<Application>())
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
        StatusNotifier.cancel(getApplication<Application>())
    }
}
