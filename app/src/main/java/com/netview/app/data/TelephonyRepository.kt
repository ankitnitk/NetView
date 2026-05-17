package com.netview.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import androidx.core.content.ContextCompat
import com.netview.app.utils.BandMapper
import com.netview.app.utils.Formatters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Reads cell + telephony state for every active SIM.
 * Supports multi-SIM by creating a per-subscription TelephonyManager.
 */
class TelephonyRepository(private val context: Context) {

    private val telephonyManager: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val subscriptionManager: SubscriptionManager =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

    // For Android 12+ PhysicalChannelConfig callbacks
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private val caCache = mutableMapOf<Int, List<CarrierComponent>>()
    private val callbacks = mutableMapOf<Int, TelephonyCallback>()

    private val _caFlow = MutableStateFlow<Map<Int, List<CarrierComponent>>>(emptyMap())
    val caFlow: StateFlow<Map<Int, List<CarrierComponent>>> = _caFlow

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

        // First-call: register CA listener for each sub (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            subs.forEach { sub -> ensureCaListener(sub.subscriptionId) }
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

        // NSA: modem reports LTE as data RAT but also surfaces a CellInfoNr entry
        val hasNrCell = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                cellInfos.any { it is CellInfoNr }
        val networkType = when {
            dataType == TelephonyManager.NETWORK_TYPE_NR -> "5G SA"
            hasNrCell && dataType == TelephonyManager.NETWORK_TYPE_LTE -> "5G NSA"
            else -> Formatters.radioMode(dataType, serviceState)
        }

        val volte = isVolteRegistered(tm)
        val vonr = isVonrRegistered(serviceState)
        val voiceTech = when {
            vonr -> "VoNR"
            volte -> "VoLTE"
            else -> "CS"
        }

        val cachedCa = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            caCache[sub.subscriptionId] else null
        val ca = if (!cachedCa.isNullOrEmpty()) cachedCa
                 else detectCaFromCellInfo(cellInfos)

        return SimSlotData(
            subId = sub.subscriptionId,
            slotIndex = sub.simSlotIndex,
            displayName = sub.displayName?.toString() ?: "SIM ${sub.simSlotIndex + 1}",
            carrierName = sub.carrierName?.toString() ?: "—",
            mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sub.mccString else sub.mcc.toString(),
            mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sub.mncString else sub.mnc.toString(),
            isRoaming = tm.isNetworkRoaming,
            networkType = networkType,
            voiceTech = voiceTech,
            imsRegistered = volte || vonr,
            servingCell = serving,
            carrierAggregation = ca
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

    /* ===== CA detection from allCellInfo (fallback when PhysicalChannelConfig unavailable) ===== */

    private fun detectCaFromCellInfo(cells: List<CellInfo>): List<CarrierComponent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        val lteCells = cells.filterIsInstance<CellInfoLte>()
        val pcell = lteCells.firstOrNull { it.isRegistered } ?: return emptyList()
        val pcellEnb = pcell.cellIdentity.ci.let {
            if (it != CellInfo.UNAVAILABLE && it > 0) it / 256L else return emptyList()
        }
        // Keep only cells from the same eNB (they are CA component carriers)
        val caCells = lteCells.filter { cell ->
            val cid = cell.cellIdentity.ci
            cid != CellInfo.UNAVAILABLE && cid > 0 && cid / 256L == pcellEnb
        }
        if (caCells.size < 2) return emptyList()
        return caCells.mapIndexed { idx, cell ->
            val id = cell.cellIdentity
            val earfcn = id.earfcn.takeIf { it != CellInfo.UNAVAILABLE }
            val bwKhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.bandwidth
                        else CellInfo.UNAVAILABLE
            CarrierComponent(
                index = idx,
                role = if (cell.isRegistered) "PCell" else "SCell",
                band = BandMapper.lteBand(earfcn),
                bandwidthMhz = if (bwKhz > 0 && bwKhz != CellInfo.UNAVAILABLE) bwKhz / 1000.0 else null,
                pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE },
                earfcn = earfcn,
                downlinkFrequencyMhz = null
            )
        }
    }

    /* ===== Carrier aggregation (Android 12+) ===== */

    @SuppressLint("MissingPermission")
    private fun ensureCaListener(subId: Int) {
        if (callbacks.containsKey(subId)) return
        val tm = telephonyManager.createForSubscriptionId(subId)

        val cb = object : TelephonyCallback(), TelephonyCallback.PhysicalChannelConfigListener {
            override fun onPhysicalChannelConfigChanged(configs: MutableList<PhysicalChannelConfig>) {
                val list = configs.mapIndexed { idx, cfg ->
                    val bwKhz = cfg.cellBandwidthDownlinkKhz
                    val role = when (cfg.connectionStatus) {
                        PhysicalChannelConfig.CONNECTION_PRIMARY_SERVING -> "PCell"
                        PhysicalChannelConfig.CONNECTION_SECONDARY_SERVING -> "SCell"
                        else -> "—"
                    }
                    CarrierComponent(
                        index = idx,
                        role = role,
                        band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Band ${cfg.band}" else null,
                        bandwidthMhz = if (bwKhz > 0) bwKhz / 1000.0 else null,
                        pci = cfg.physicalCellId.takeIf { it >= 0 },
                        earfcn = cfg.downlinkChannelNumber.takeIf { it > 0 },
                        downlinkFrequencyMhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            cfg.downlinkFrequencyKhz.takeIf { it > 0 }?.let { it / 1000.0 }
                        } else null
                    )
                }
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

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callbacks.forEach { (subId, cb) ->
                try {
                    telephonyManager.createForSubscriptionId(subId).unregisterTelephonyCallback(cb)
                } catch (e: Exception) { /* ignore */ }
            }
            callbacks.clear()
        }
    }
}
