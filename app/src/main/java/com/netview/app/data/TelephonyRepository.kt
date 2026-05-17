package com.netview.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.netview.app.utils.BandMapper
import com.netview.app.utils.Formatters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

/**
 * Reads cell + telephony state for every active SIM.
 * Supports multi-SIM by creating a per-subscription TelephonyManager.
 */
class TelephonyRepository(private val context: Context) {

    private val telephonyManager: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val subscriptionManager: SubscriptionManager =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

    // For Android 12+ PhysicalChannelConfig callbacks — main executor avoids Samsung threading quirks
    private val executor: Executor = ContextCompat.getMainExecutor(context)
    private val caCache = mutableMapOf<Int, List<CarrierComponent>>()
    private val callbacks = mutableMapOf<Int, TelephonyCallback>()
    // Deprecated PhoneStateListener path — works on Samsung where TelephonyCallback silently fails
    private val phoneStateListeners = mutableMapOf<Int, PhoneStateListener>()
    // Diagnostic counters per subId
    private val tcFireCount = mutableMapOf<Int, Int>()
    private val pslFireCount = mutableMapOf<Int, Int>()

    private val _caFlow = MutableStateFlow<Map<Int, List<CarrierComponent>>>(emptyMap())
    val caFlow: StateFlow<Map<Int, List<CarrierComponent>>> = _caFlow

    fun hasPrecisePermission(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
        else ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PRECISE_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

    /** Check the two runtime permissions we need. */
    fun hasPermissions(): Boolean {
        val phone = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val loc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return phone && loc
    }

    /** Returns one SimSlotData per active SIM. */
    @SuppressLint("MissingPermission")
    fun readAllSims(): List<SimSlotData> {
        if (!hasPermissions()) return emptyList()

        val subs = try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

        // Register CA listeners on each subscription. We try BOTH paths — TelephonyCallback
        // (proper public API, S+) and the deprecated PhoneStateListener with hidden event
        // constant (the path NetMonster uses; works on Samsung where TelephonyCallback is blocked).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            subs.forEach { sub ->
                ensureCaListener(sub.subscriptionId)
                ensurePccPhoneStateListener(sub.subscriptionId)
            }
        }

        return subs.map { sub -> readOneSim(sub) }
    }

    @SuppressLint("MissingPermission")
    private fun readOneSim(sub: SubscriptionInfo): SimSlotData {
        val tm = telephonyManager.createForSubscriptionId(sub.subscriptionId)

        val serviceState: ServiceState? = try { tm.serviceState } catch (e: Exception) { null }
        val dataType = try { tm.dataNetworkType } catch (e: Exception) { TelephonyManager.NETWORK_TYPE_UNKNOWN }

        val cellInfos = try { tm.allCellInfo ?: emptyList() } catch (e: Exception) { emptyList() }
        val serving = parseServingCell(cellInfos)
        val signalStrengths = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                tm.signalStrength?.cellSignalStrengths ?: emptyList()
            else emptyList()
        } catch (e: Exception) { emptyList() }

        // NSA primary: CellInfoNr in allCellInfo (may need READ_PRECISE_PHONE_STATE on some builds)
        val nrCellInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            cellInfos.filterIsInstance<CellInfoNr>().firstOrNull() else null
        val hasNrCell = nrCellInfo != null
        // NSA fallback: NR component in SignalStrength (only needs READ_PHONE_STATE — always works)
        val nrSignal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            signalStrengths.filterIsInstance<CellSignalStrengthNr>()
                .firstOrNull { it.ssRsrp != CellInfo.UNAVAILABLE } else null
        val hasNrSignal = nrSignal != null
        val networkType = when {
            dataType == TelephonyManager.NETWORK_TYPE_NR -> "5G SA"
            (hasNrCell || hasNrSignal) && dataType == TelephonyManager.NETWORK_TYPE_LTE -> "5G NSA"
            else -> Formatters.radioMode(dataType, serviceState)
        }
        // Build NR leg display: prefer full CellInfoNr if available, else synthesize from SignalStrength
        val nrCellDisplay: ServingCellInfo? = when {
            nrCellInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> parseNr(nrCellInfo)
            nrSignal != null -> ServingCellInfo(
                rat = "NR", mcc = null, mnc = null, pci = null, tac = null, cellId = null,
                enbId = null, gnbId = null, sectorId = null,
                earfcn = null, nrarfcn = null, uarfcn = null, arfcn = null,
                band = null, bandwidthMhz = null,
                rsrp = nrSignal.ssRsrp.takeIf { it != CellInfo.UNAVAILABLE },
                rsrq = nrSignal.ssRsrq.takeIf { it != CellInfo.UNAVAILABLE },
                rssnr = null,
                ssSinr = nrSignal.ssSinr.takeIf { it != CellInfo.UNAVAILABLE },
                csiRsrp = nrSignal.csiRsrp.takeIf { it != CellInfo.UNAVAILABLE },
                csiRsrq = nrSignal.csiRsrq.takeIf { it != CellInfo.UNAVAILABLE },
                csiSinr = nrSignal.csiSinr.takeIf { it != CellInfo.UNAVAILABLE },
                rscp = null, ecNo = null, rssi = null, cqi = null,
                timingAdvance = null, bsic = null, ber = null
            )
            else -> null
        }

        val volte = isVolteRegistered(tm)
        val vonr = isVonrRegistered(serviceState)
        val voiceTech = when {
            vonr -> "VoNR"
            volte -> "VoLTE"
            else -> "CS"
        }

        // CA: layered fallback. Callback cache → synchronous reflection → cell info heuristic.
        val cached = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            caCache[sub.subscriptionId] ?: emptyList() else emptyList()
        val direct = if (cached.isEmpty()) parsePhysicalChannelsViaReflection(tm) else emptyList()
        val ca = when {
            cached.isNotEmpty() -> cached
            direct.isNotEmpty() -> direct
            else -> detectCaFromCellInfo(cellInfos)
        }

        // Serving network PLMN — use ServiceState.operatorNumeric, not home SIM (critical for roaming)
        val operatorNumeric = serviceState?.operatorNumeric?.takeIf { it.length >= 5 }
        val servingMcc = operatorNumeric?.take(3)
        val servingMnc = operatorNumeric?.drop(3)

        val diagnostics = DiagnosticInfo(
            cellInfoTotal = cellInfos.size,
            cellInfoLte = cellInfos.count { it is CellInfoLte },
            cellInfoNr = cellInfos.count { it is CellInfoNr },
            signalStrengthsTotal = signalStrengths.size,
            signalStrengthsLte = signalStrengths.count { it is CellSignalStrengthLte },
            signalStrengthsNr = signalStrengths.count { it is CellSignalStrengthNr },
            tcRegistered = callbacks.containsKey(sub.subscriptionId),
            tcFires = tcFireCount[sub.subscriptionId] ?: 0,
            pslRegistered = phoneStateListeners.containsKey(sub.subscriptionId),
            pslFires = pslFireCount[sub.subscriptionId] ?: 0
        )

        return SimSlotData(
            subId = sub.subscriptionId,
            slotIndex = sub.simSlotIndex,
            displayName = sub.displayName?.toString() ?: "SIM ${sub.simSlotIndex + 1}",
            carrierName = sub.carrierName?.toString() ?: "—",
            mcc = servingMcc ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sub.mccString else sub.mcc.toString(),
            mnc = servingMnc ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sub.mncString else sub.mnc.toString(),
            isRoaming = tm.isNetworkRoaming,
            networkType = networkType,
            voiceTech = voiceTech,
            imsRegistered = volte || vonr,
            servingCell = serving,
            nrCell = nrCellDisplay,
            carrierAggregation = ca,
            diagnostics = diagnostics
        )
    }

    private fun isVolteRegistered(tm: TelephonyManager): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            tm.isVoiceCapable && tm.voiceNetworkType == TelephonyManager.NETWORK_TYPE_LTE
        } else false
    } catch (e: Exception) { false }

    private fun isVonrRegistered(ss: ServiceState?): Boolean {
        if (ss == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            ss.networkRegistrationInfoList.any {
                it.accessNetworkTechnology == TelephonyManager.NETWORK_TYPE_NR &&
                        it.domain == NetworkRegistrationInfo.DOMAIN_PS
            }
        } catch (e: Exception) { false }
    }

    private fun parseServingCell(cells: List<CellInfo>): ServingCellInfo? {
        val registered = cells.filter { it.isRegistered }
        // On 5G NSA both LTE and NR may be marked registered; prefer LTE (anchor)
        // so we always surface bandwidth, EARFCN, eNB, etc.
        val serving = registered.firstOrNull { it is CellInfoLte }
            ?: registered.firstOrNull()
            ?: cells.firstOrNull()
            ?: return null
        return when (serving) {
            is CellInfoLte -> parseLte(serving)
            is CellInfoWcdma -> parseWcdma(serving)
            is CellInfoGsm -> parseGsm(serving)
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && serving is CellInfoNr) parseNr(serving) else null
        }
    }

    private fun parseLte(c: CellInfoLte): ServingCellInfo {
        val id = c.cellIdentity
        val s = c.cellSignalStrength
        val cid = id.ci.toLong()
        val earfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) id.earfcn else null
        val bw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.bandwidth else CellInfo.UNAVAILABLE
        return ServingCellInfo(
            rat = "LTE",
            mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString else id.mcc.toString(),
            mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString else id.mnc.toString(),
            pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE },
            tac = id.tac.takeIf { it != CellInfo.UNAVAILABLE },
            cellId = if (cid > 0 && cid != CellInfo.UNAVAILABLE.toLong()) cid else null,
            enbId = Formatters.lteEnbId(cid),
            sectorId = Formatters.lteSectorId(cid),
            gnbId = null,
            earfcn = earfcn?.takeIf { it != CellInfo.UNAVAILABLE },
            nrarfcn = null, uarfcn = null, arfcn = null,
            band = BandMapper.lteBand(earfcn),
            bandwidthMhz = if (bw != CellInfo.UNAVAILABLE) bw / 1000.0 else null,
            rsrp = s.rsrp.takeIf { it != CellInfo.UNAVAILABLE },
            rsrq = s.rsrq.takeIf { it != CellInfo.UNAVAILABLE },
            rssnr = s.rssnr.takeIf { it != CellInfo.UNAVAILABLE },
            ssSinr = null, csiRsrp = null, csiRsrq = null, csiSinr = null,
            rscp = null, ecNo = null,
            rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                s.rssi.takeIf { it != CellInfo.UNAVAILABLE } else null,
            cqi = s.cqi.takeIf { it != CellInfo.UNAVAILABLE },
            timingAdvance = s.timingAdvance.takeIf { it != CellInfo.UNAVAILABLE },
            bsic = null, ber = null
        )
    }

    private fun parseNr(c: CellInfoNr): ServingCellInfo {
        val id = c.cellIdentity as CellIdentityNr
        val s = c.cellSignalStrength as CellSignalStrengthNr
        val nci = id.nci
        val nrarfcn = id.nrarfcn
        return ServingCellInfo(
            rat = "NR",
            mcc = id.mccString, mnc = id.mncString,
            pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE },
            tac = id.tac.takeIf { it != CellInfo.UNAVAILABLE },
            cellId = if (nci > 0) nci else null,
            enbId = null,
            gnbId = Formatters.nrGnbId(nci),
            sectorId = null,
            earfcn = null,
            nrarfcn = nrarfcn.takeIf { it != CellInfo.UNAVAILABLE },
            uarfcn = null, arfcn = null,
            band = BandMapper.nrBand(nrarfcn),
            bandwidthMhz = null,
            rsrp = s.ssRsrp.takeIf { it != CellInfo.UNAVAILABLE },
            rsrq = s.ssRsrq.takeIf { it != CellInfo.UNAVAILABLE },
            rssnr = null,
            ssSinr = s.ssSinr.takeIf { it != CellInfo.UNAVAILABLE },
            csiRsrp = s.csiRsrp.takeIf { it != CellInfo.UNAVAILABLE },
            csiRsrq = s.csiRsrq.takeIf { it != CellInfo.UNAVAILABLE },
            csiSinr = s.csiSinr.takeIf { it != CellInfo.UNAVAILABLE },
            rscp = null, ecNo = null, rssi = null, cqi = null,
            timingAdvance = null, bsic = null, ber = null
        )
    }

    private fun parseWcdma(c: CellInfoWcdma): ServingCellInfo {
        val id = c.cellIdentity
        val s = c.cellSignalStrength
        return ServingCellInfo(
            rat = "WCDMA",
            mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString else id.mcc.toString(),
            mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString else id.mnc.toString(),
            pci = id.psc.takeIf { it != CellInfo.UNAVAILABLE },
            tac = id.lac.takeIf { it != CellInfo.UNAVAILABLE },
            cellId = id.cid.toLong().takeIf { it > 0 },
            enbId = null, gnbId = null, sectorId = null,
            earfcn = null, nrarfcn = null,
            uarfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) id.uarfcn else null,
            arfcn = null, band = null, bandwidthMhz = null,
            rsrp = null, rsrq = null, rssnr = null,
            ssSinr = null, csiRsrp = null, csiRsrq = null, csiSinr = null,
            rscp = s.dbm.takeIf { it != CellInfo.UNAVAILABLE },
            ecNo = null,
            rssi = s.dbm.takeIf { it != CellInfo.UNAVAILABLE },
            cqi = null, timingAdvance = null, bsic = null, ber = null
        )
    }

    private fun parseGsm(c: CellInfoGsm): ServingCellInfo {
        val id = c.cellIdentity
        val s = c.cellSignalStrength
        return ServingCellInfo(
            rat = "GSM",
            mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString else id.mcc.toString(),
            mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString else id.mnc.toString(),
            pci = null,
            tac = id.lac.takeIf { it != CellInfo.UNAVAILABLE },
            cellId = id.cid.toLong().takeIf { it > 0 },
            enbId = null, gnbId = null, sectorId = null,
            earfcn = null, nrarfcn = null, uarfcn = null,
            arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) id.arfcn else null,
            band = null, bandwidthMhz = null,
            rsrp = null, rsrq = null, rssnr = null,
            ssSinr = null, csiRsrp = null, csiRsrq = null, csiSinr = null,
            rscp = null, ecNo = null,
            rssi = s.dbm.takeIf { it != CellInfo.UNAVAILABLE },
            cqi = null, timingAdvance = s.timingAdvance.takeIf { it != CellInfo.UNAVAILABLE },
            bsic = id.bsic.takeIf { it != CellInfo.UNAVAILABLE },
            ber = s.bitErrorRate.takeIf { it != CellInfo.UNAVAILABLE }
        )
    }

    /* ===== CA fallbacks when PhysicalChannelConfig callback never fires ===== */

    private fun detectCaFromCellInfo(cells: List<CellInfo>): List<CarrierComponent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        // Conservative: require multiple LTE cells, same eNB, different EARFCNs.
        val lteCells = cells.filterIsInstance<CellInfoLte>().filter {
            it.cellIdentity.ci != CellInfo.UNAVAILABLE && it.cellIdentity.ci > 0
        }
        val pcell = lteCells.firstOrNull { it.isRegistered } ?: return emptyList()
        val pcellEnb = pcell.cellIdentity.ci / 256L
        val sameEnb = lteCells.filter { it.cellIdentity.ci / 256L == pcellEnb }
        if (sameEnb.size < 2) return emptyList()
        val uniqueEarfcns = sameEnb.mapNotNull {
            it.cellIdentity.earfcn.takeIf { e -> e != CellInfo.UNAVAILABLE && e > 0 }
        }.distinct()
        if (uniqueEarfcns.size < 2) return emptyList()  // same band = sectors, not CA
        return sameEnb.mapIndexed { idx, cell ->
            val id = cell.cellIdentity
            val earfcn = id.earfcn.takeIf { it != CellInfo.UNAVAILABLE }
            val bwKhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.bandwidth
                        else CellInfo.UNAVAILABLE
            CarrierComponent(
                index = idx,
                role = if (cell.isRegistered && idx == 0) "PCell" else "SCell",
                band = BandMapper.lteBand(earfcn),
                bandwidthMhz = if (bwKhz > 0 && bwKhz != CellInfo.UNAVAILABLE) bwKhz / 1000.0 else null,
                pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE },
                earfcn = earfcn,
                downlinkFrequencyMhz = null
            )
        }
    }

    /**
     * Try the hidden synchronous TelephonyManager.getPhysicalChannelConfigs() via reflection.
     * On some Samsung builds this works even when the callback-based API silently fails.
     * PhysicalChannelConfig public API is S+; skip on older devices.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parsePhysicalChannelsViaReflection(tm: TelephonyManager): List<CarrierComponent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        val configs: List<PhysicalChannelConfig> = try {
            val m = tm.javaClass.getMethod("getPhysicalChannelConfigs")
            (m.invoke(tm) as? List<PhysicalChannelConfig>) ?: emptyList()
        } catch (e: Throwable) { emptyList() }
        if (configs.isEmpty()) return emptyList()
        return configs.mapIndexed { idx, cfg -> physicalChannelToCarrier(idx, cfg) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun physicalChannelToCarrier(idx: Int, cfg: PhysicalChannelConfig): CarrierComponent {
        val bwKhz = cfg.cellBandwidthDownlinkKhz
        val role = when (cfg.connectionStatus) {
            PhysicalChannelConfig.CONNECTION_PRIMARY_SERVING -> "PCell"
            PhysicalChannelConfig.CONNECTION_SECONDARY_SERVING -> "SCell"
            else -> "—"
        }
        // getRank() may be hidden on some builds; reflect it.
        val rank = try {
            val m = cfg.javaClass.getMethod("getRank")
            (m.invoke(cfg) as? Int)?.takeIf { it > 0 }
        } catch (e: Throwable) { null }
        return CarrierComponent(
            index = idx,
            role = role,
            band = "Band ${cfg.band}",
            bandwidthMhz = if (bwKhz > 0) bwKhz / 1000.0 else null,
            pci = cfg.physicalCellId.takeIf { it >= 0 },
            earfcn = cfg.downlinkChannelNumber.takeIf { it > 0 },
            downlinkFrequencyMhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cfg.downlinkFrequencyKhz.takeIf { it > 0 }?.let { it / 1000.0 }
            } else null,
            mimoLayers = rank
        )
    }

    /* ===== Carrier aggregation (Android 12+) ===== */

    @SuppressLint("MissingPermission")
    private fun ensureCaListener(subId: Int) {
        // Keep if registered AND callback has already fired (cache has an entry)
        if (callbacks.containsKey(subId) && caCache.containsKey(subId)) return
        // Previously registered but callback never fired — unregister and retry
        callbacks.remove(subId)?.let { old ->
            try { telephonyManager.createForSubscriptionId(subId).unregisterTelephonyCallback(old) }
            catch (e: Exception) { /* ignore */ }
        }
        val tm = telephonyManager.createForSubscriptionId(subId)

        val cb = object : TelephonyCallback(), TelephonyCallback.PhysicalChannelConfigListener {
            override fun onPhysicalChannelConfigChanged(configs: MutableList<PhysicalChannelConfig>) {
                tcFireCount[subId] = (tcFireCount[subId] ?: 0) + 1
                val list = configs.mapIndexed { idx, cfg -> physicalChannelToCarrier(idx, cfg) }
                caCache[subId] = list
                _caFlow.value = caCache.toMap()
            }
        }
        try {
            tm.registerTelephonyCallback(executor, cb)
            callbacks[subId] = cb
        } catch (e: Exception) {
            // ignore; CA info just won't be available
        }
    }

    /**
     * Register a deprecated PhoneStateListener with the hidden LISTEN_PHYSICAL_CHANNEL_CONFIGURATION
     * event (0x00100000). The override is matched at runtime by signature — the method is
     * @SystemApi so the compiler won't accept `override`, but the JVM dispatches it.
     * This path bypasses the READ_PRECISE_PHONE_STATE check that blocks TelephonyCallback on Samsung.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun ensurePccPhoneStateListener(subId: Int) {
        if (phoneStateListeners.containsKey(subId) && !caCache[subId].isNullOrEmpty()) return
        phoneStateListeners.remove(subId)?.let { old ->
            try { telephonyManager.createForSubscriptionId(subId).listen(old, PhoneStateListener.LISTEN_NONE) }
            catch (e: Exception) { /* ignore */ }
        }
        val event = try {
            PhoneStateListener::class.java
                .getDeclaredField("LISTEN_PHYSICAL_CHANNEL_CONFIGURATION")
                .apply { isAccessible = true }
                .getInt(null)
        } catch (e: Throwable) { 0x00100000 }  // documented value
        val tm = telephonyManager.createForSubscriptionId(subId)
        val listener = object : PhoneStateListener() {
            // No `override` — both methods are @SystemApi (hidden from compiler).
            // JVM matches by signature at runtime. Android used different names in different
            // releases, so we define BOTH and whichever the framework calls will fire.
            @Suppress("unused")
            fun onPhysicalChannelConfigurationChanged(configs: List<PhysicalChannelConfig>) {
                handleConfigs(configs)
            }
            @Suppress("unused")
            fun onPhysicalChannelConfigChanged(configs: List<PhysicalChannelConfig>) {
                handleConfigs(configs)
            }
            private fun handleConfigs(configs: List<PhysicalChannelConfig>) {
                pslFireCount[subId] = (pslFireCount[subId] ?: 0) + 1
                val list = configs.mapIndexed { idx, cfg -> physicalChannelToCarrier(idx, cfg) }
                caCache[subId] = list
                _caFlow.value = caCache.toMap()
            }
        }
        try {
            tm.listen(listener, event)
            phoneStateListeners[subId] = listener
        } catch (e: Throwable) { /* ignore */ }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callbacks.forEach { (subId, cb) ->
                try {
                    telephonyManager.createForSubscriptionId(subId).unregisterTelephonyCallback(cb)
                } catch (e: Exception) { /* ignore */ }
            }
            callbacks.clear()
            phoneStateListeners.forEach { (subId, ls) ->
                try {
                    telephonyManager.createForSubscriptionId(subId).listen(ls, PhoneStateListener.LISTEN_NONE)
                } catch (e: Exception) { /* ignore */ }
            }
            phoneStateListeners.clear()
        }
    }
}
