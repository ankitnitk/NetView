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
import com.netview.app.utils.DebugLog
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
        // allCellInfo is tied to the active modem, not the SIM — both subs return the same list
        // on a single-modem device. Prefer per-SIM ServiceState.NetworkRegistrationInfo cellIdentity;
        // fall back to allCellInfo only when the SS path yields nothing.
        val serving0 = parseServingFromServiceState(tm, serviceState) ?: parseServingCell(cellInfos)
        // TA from SignalStrength.cellSignalStrengths is unreliable on Samsung — the modem only
        // populates it in the CellInfoLte path. Patch it in when the SS path leaves it null.
        val serving = if (serving0 != null && serving0.rat == "LTE" && serving0.timingAdvance == null) {
            val ta = cellInfos.filterIsInstance<CellInfoLte>()
                .firstOrNull { it.isRegistered }
                ?.cellSignalStrength?.timingAdvance
                ?.takeIf { it != CellInfo.UNAVAILABLE }
            ta?.let { serving0.copy(timingAdvance = it) } ?: serving0
        } else serving0
        val ssObj = try { tm.signalStrength } catch (e: Exception) { null }
        val signalStrengths = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ssObj?.cellSignalStrengths ?: emptyList()
            else emptyList()
        } catch (e: Exception) { emptyList() }
        // Log raw SignalStrength.toString so we can mine for per-SCell RSRP/SINR that
        // NetMonster manages to show — Samsung might surface it via a hidden field.
        ssObj?.let {
            try {
                val s = it.toString()
                if (s.length <= 800) DebugLog.d("SIG", s)
                else s.chunked(800).forEachIndexed { i, chunk ->
                    DebugLog.d("SIG", "[${i + 1}] $chunk")
                }
            } catch (e: Exception) { /* ignore */ }
        }
        logDiagnostics(sub.subscriptionId, tm, serviceState, cellInfos, signalStrengths)

        // NSA primary: CellInfoNr in allCellInfo (may need READ_PRECISE_PHONE_STATE on some builds)
        val nrCellInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            cellInfos.filterIsInstance<CellInfoNr>().firstOrNull() else null
        val hasNrCell = nrCellInfo != null
        // NSA fallback: NR component in SignalStrength (only needs READ_PHONE_STATE — always works)
        val nrSignal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            signalStrengths.filterIsInstance<CellSignalStrengthNr>()
                .firstOrNull { it.ssRsrp != CellInfo.UNAVAILABLE } else null
        val hasNrSignal = nrSignal != null
        // NSA fallback #2 (Qualcomm/non-Samsung): ServiceState.mNrFrequencyRange > 0 means NR active.
        // 0=NONE, 1=mmWave, 2=Sub-6. This catches devices that don't expose CellInfoNr in NSA mode.
        val hasNrFromSs = (parseNrFrequencyRange(serviceState) ?: 0) > 0
        val networkType = when {
            dataType == TelephonyManager.NETWORK_TYPE_NR -> "5G SA"
            (hasNrCell || hasNrSignal || hasNrFromSs) && dataType == TelephonyManager.NETWORK_TYPE_LTE -> "5G NSA"
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

        // Clear stale CA when not on LTE/NR — otherwise old aggregation data
        // from a prior LTE session lingers when the device drops to 3G/2G.
        val isLteOrNr = serving?.rat == "LTE" || serving?.rat == "NR"
        if (!isLteOrNr) {
            caCache.remove(sub.subscriptionId)
        }

        // Parse all per-CC bandwidths from ServiceState.mCellBandwidths — used for both
        // serving-cell BW fallback AND CA detection. First entry is the PCell.
        val ssBws = parseCellBandwidthsFromSs(serviceState)
        val isNtn = parseNtnFromSs(serviceState)

        // Attach duplex mode + bandwidth fallback to LTE serving cell. The first entry
        // of mCellBandwidths is the active carrier BW (works whether or not CA is active).
        val servingEnriched = if (serving?.rat == "LTE") {
            val bwFromSs = ssBws.firstOrNull()?.let { it / 1000.0 }
            serving.copy(
                bandwidthMhz = serving.bandwidthMhz ?: bwFromSs,
                duplexMode = duplexModeName(serviceState)
            )
        } else serving

        // CA: layered fallback. Callback cache → synchronous reflection → ServiceState
        // mCellBandwidths string parse (proven to work on Samsung) → cell info heuristic.
        val cached = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            caCache[sub.subscriptionId] ?: emptyList() else emptyList()
        val direct = if (cached.isEmpty()) parsePhysicalChannelsViaReflection(tm) else emptyList()
        val caActiveFromSs = parseCaActiveFromSs(serviceState)
        val fromSs = if (cached.isEmpty() && direct.isEmpty())
            buildCaFromBandwidths(ssBws, servingEnriched, caActiveFromSs) else emptyList()
        val ca = when {
            cached.isNotEmpty() -> enrichCaWithSignal(cached, cellInfos)
            direct.isNotEmpty() -> enrichCaWithSignal(direct, cellInfos)
            fromSs.isNotEmpty() -> {
                // fromSs has correct per-CC bandwidths from ServiceState but null SCell band/PCI/EARFCN.
                // Enrich SCells from allCellInfo (same-eNB non-registered LTE cells) then add signal.
                val withCellInfo = enrichCaFromCellInfo(fromSs, cellInfos)
                enrichCaWithSignal(withCellInfo, cellInfos)
            }
            else -> detectCaFromCellInfo(cellInfos)
        }

        // Serving network PLMN — use ServiceState.operatorNumeric, not home SIM (critical for roaming)
        val operatorNumeric = serviceState?.operatorNumeric?.takeIf { it.length >= 5 }
        val servingMcc = operatorNumeric?.take(3)
        val servingMnc = operatorNumeric?.drop(3)

        val validCi = cellInfos.filterIsInstance<CellInfoLte>().count {
            it.cellIdentity.ci != CellInfo.UNAVAILABLE && it.cellIdentity.ci > 0
        }
        val caHint = parseServiceStateForCaHint(serviceState)
        val diagnostics = DiagnosticInfo(
            cellInfoTotal = cellInfos.size,
            cellInfoLte = cellInfos.count { it is CellInfoLte },
            cellInfoNr = cellInfos.count { it is CellInfoNr },
            cellsWithValidCi = validCi,
            signalStrengthsTotal = signalStrengths.size,
            signalStrengthsLte = signalStrengths.count { it is CellSignalStrengthLte },
            signalStrengthsNr = signalStrengths.count { it is CellSignalStrengthNr },
            tcRegistered = callbacks.containsKey(sub.subscriptionId),
            tcFires = tcFireCount[sub.subscriptionId] ?: 0,
            pslRegistered = phoneStateListeners.containsKey(sub.subscriptionId),
            pslFires = pslFireCount[sub.subscriptionId] ?: 0,
            serviceStateCaHint = caHint
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
            servingCell = servingEnriched,
            nrCell = nrCellDisplay,
            carrierAggregation = ca,
            isNonTerrestrial = isNtn,
            diagnostics = diagnostics,
            neighborCells = parseNeighborCells(cellInfos, servingEnriched?.earfcn),
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
        val uarfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) id.uarfcn else null
        return ServingCellInfo(
            rat = "WCDMA",
            mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString else id.mcc.toString(),
            mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString else id.mnc.toString(),
            pci = id.psc.takeIf { it != CellInfo.UNAVAILABLE },
            tac = id.lac.takeIf { it != CellInfo.UNAVAILABLE },
            cellId = id.cid.toLong().takeIf { it > 0 },
            enbId = null, gnbId = null, sectorId = null,
            earfcn = null, nrarfcn = null,
            uarfcn = uarfcn,
            arfcn = null, band = BandMapper.wcdmaBand(uarfcn), bandwidthMhz = null,
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
        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString else id.mcc.toString()
        val arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) id.arfcn else null
        return ServingCellInfo(
            rat = "GSM",
            mcc = mcc,
            mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString else id.mnc.toString(),
            pci = null,
            tac = id.lac.takeIf { it != CellInfo.UNAVAILABLE },
            cellId = id.cid.toLong().takeIf { it > 0 },
            enbId = null, gnbId = null, sectorId = null,
            earfcn = null, nrarfcn = null, uarfcn = null,
            arfcn = arfcn,
            band = BandMapper.gsmBandWithMcc(arfcn, mcc), bandwidthMhz = null,
            rsrp = null, rsrq = null, rssnr = null,
            ssSinr = null, csiRsrp = null, csiRsrq = null, csiSinr = null,
            rscp = null, ecNo = null,
            rssi = s.dbm.takeIf { it != CellInfo.UNAVAILABLE },
            cqi = null, timingAdvance = s.timingAdvance.takeIf { it != CellInfo.UNAVAILABLE },
            bsic = id.bsic.takeIf { it != CellInfo.UNAVAILABLE },
            ber = s.bitErrorRate.takeIf { it != CellInfo.UNAVAILABLE }
        )
    }

    /* ===== Per-SIM serving cell from ServiceState (correct for DSDS) ===== */

    @SuppressLint("MissingPermission")
    private fun parseServingFromServiceState(tm: TelephonyManager, ss: ServiceState?): ServingCellInfo? {
        if (ss == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val psInfo = try {
            ss.networkRegistrationInfoList.firstOrNull { reg ->
                reg.domain == NetworkRegistrationInfo.DOMAIN_PS &&
                        reg.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN &&
                        reg.cellIdentity != null
            }
        } catch (e: Exception) { null } ?: return null
        val cellId = psInfo.cellIdentity ?: return null
        // tm.signalStrength is per-SIM (since tm was created with createForSubscriptionId)
        val sigList = try { tm.signalStrength?.cellSignalStrengths ?: emptyList() }
                      catch (e: Exception) { emptyList() }
        return when (cellId) {
            is CellIdentityLte ->
                parseLteIdentity(cellId, sigList.filterIsInstance<CellSignalStrengthLte>().firstOrNull())
            is CellIdentityWcdma ->
                parseWcdmaIdentity(cellId, sigList.filterIsInstance<CellSignalStrengthWcdma>().firstOrNull())
            is CellIdentityGsm ->
                parseGsmIdentity(cellId, sigList.filterIsInstance<CellSignalStrengthGsm>().firstOrNull())
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellId is CellIdentityNr)
                parseNrIdentity(cellId, sigList.filterIsInstance<CellSignalStrengthNr>().firstOrNull())
            else null
        }
    }

    private fun parseWcdmaIdentity(id: CellIdentityWcdma, s: CellSignalStrengthWcdma?): ServingCellInfo {
        val uarfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            id.uarfcn.takeIf { it != CellInfo.UNAVAILABLE } else null
        val ecNo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            try { s?.ecNo?.takeIf { it != CellInfo.UNAVAILABLE } } catch (e: Throwable) { null }
        else null
        return ServingCellInfo(
            rat = "WCDMA",
            mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString else id.mcc.toString(),
            mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString else id.mnc.toString(),
            pci = id.psc.takeIf { it != CellInfo.UNAVAILABLE },
            tac = id.lac.takeIf { it != CellInfo.UNAVAILABLE },
            cellId = id.cid.toLong().takeIf { it > 0 && it != CellInfo.UNAVAILABLE.toLong() },
            enbId = null, gnbId = null, sectorId = null,
            earfcn = null, nrarfcn = null,
            uarfcn = uarfcn, arfcn = null,
            band = BandMapper.wcdmaBand(uarfcn),
            bandwidthMhz = 5.0, // UMTS is always 5 MHz per carrier
            rsrp = null, rsrq = null, rssnr = null,
            ssSinr = null, csiRsrp = null, csiRsrq = null, csiSinr = null,
            rscp = s?.dbm?.takeIf { it != CellInfo.UNAVAILABLE },
            ecNo = ecNo,
            rssi = s?.dbm?.takeIf { it != CellInfo.UNAVAILABLE },
            cqi = null, timingAdvance = null, bsic = null, ber = null
        )
    }

    private fun parseGsmIdentity(id: CellIdentityGsm, s: CellSignalStrengthGsm?): ServingCellInfo {
        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString else id.mcc.toString()
        val arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            id.arfcn.takeIf { it != CellInfo.UNAVAILABLE } else null
        return ServingCellInfo(
            rat = "GSM",
            mcc = mcc,
            mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString else id.mnc.toString(),
            pci = null,
            tac = id.lac.takeIf { it != CellInfo.UNAVAILABLE },
            cellId = id.cid.toLong().takeIf { it > 0 && it != CellInfo.UNAVAILABLE.toLong() },
            enbId = null, gnbId = null, sectorId = null,
            earfcn = null, nrarfcn = null, uarfcn = null,
            arfcn = arfcn,
            band = BandMapper.gsmBandWithMcc(arfcn, mcc),
            bandwidthMhz = 0.2, // GSM 200 kHz
            rsrp = null, rsrq = null, rssnr = null,
            ssSinr = null, csiRsrp = null, csiRsrq = null, csiSinr = null,
            rscp = null, ecNo = null,
            rssi = s?.dbm?.takeIf { it != CellInfo.UNAVAILABLE },
            cqi = null,
            timingAdvance = s?.timingAdvance?.takeIf { it != CellInfo.UNAVAILABLE },
            bsic = id.bsic.takeIf { it != CellInfo.UNAVAILABLE },
            ber = s?.bitErrorRate?.takeIf { it != CellInfo.UNAVAILABLE }
        )
    }

    private fun parseLteIdentity(id: CellIdentityLte, s: CellSignalStrengthLte?): ServingCellInfo {
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
            bandwidthMhz = if (bw > 0 && bw != CellInfo.UNAVAILABLE) bw / 1000.0 else null,
            rsrp = s?.rsrp?.takeIf { it != CellInfo.UNAVAILABLE },
            rsrq = s?.rsrq?.takeIf { it != CellInfo.UNAVAILABLE },
            rssnr = s?.rssnr?.takeIf { it != CellInfo.UNAVAILABLE },
            ssSinr = null, csiRsrp = null, csiRsrq = null, csiSinr = null,
            rscp = null, ecNo = null,
            rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                s?.rssi?.takeIf { it != CellInfo.UNAVAILABLE } else null,
            cqi = s?.cqi?.takeIf { it != CellInfo.UNAVAILABLE },
            timingAdvance = s?.timingAdvance?.takeIf { it != CellInfo.UNAVAILABLE },
            bsic = null, ber = null
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun parseNrIdentity(id: CellIdentityNr, s: CellSignalStrengthNr?): ServingCellInfo {
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
            rsrp = s?.ssRsrp?.takeIf { it != CellInfo.UNAVAILABLE },
            rsrq = s?.ssRsrq?.takeIf { it != CellInfo.UNAVAILABLE },
            rssnr = null,
            ssSinr = s?.ssSinr?.takeIf { it != CellInfo.UNAVAILABLE },
            csiRsrp = s?.csiRsrp?.takeIf { it != CellInfo.UNAVAILABLE },
            csiRsrq = s?.csiRsrq?.takeIf { it != CellInfo.UNAVAILABLE },
            csiSinr = s?.csiSinr?.takeIf { it != CellInfo.UNAVAILABLE },
            rscp = null, ecNo = null, rssi = null, cqi = null,
            timingAdvance = null, bsic = null, ber = null
        )
    }

    /* ===== CA fallbacks when PhysicalChannelConfig callback never fires ===== */

    private fun detectCaFromCellInfo(cells: List<CellInfo>): List<CarrierComponent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()

        // Android 9+ (API 28): getCellConnectionStatus() directly identifies PCell and SCells —
        // no CI/eNB heuristics needed, works even when SCell CI = UNAVAILABLE (common on Samsung).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pcell = cells.filterIsInstance<CellInfoLte>()
                .firstOrNull { it.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING }
                ?: cells.filterIsInstance<CellInfoLte>().firstOrNull { it.isRegistered }
                ?: return emptyList()
            val scells = cells.filterIsInstance<CellInfoLte>()
                .filter { it.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING }
            if (scells.isEmpty()) return emptyList()
            return (listOf(pcell) + scells).mapIndexed { idx, cell ->
                cellInfoLteToCarrier(idx, if (idx == 0) "PCell" else "SCell", cell)
            }
        }

        // Pre-API 28: conservative eNB heuristic — requires valid CI and different EARFCNs.
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
        if (uniqueEarfcns.size < 2) return emptyList()
        return sameEnb.mapIndexed { idx, cell ->
            cellInfoLteToCarrier(idx, if (cell.isRegistered && idx == 0) "PCell" else "SCell", cell)
        }
    }

    /** Build a CarrierComponent from a CellInfoLte entry (band/PCI/EARFCN/BW/signal). */
    private fun cellInfoLteToCarrier(idx: Int, role: String, cell: CellInfoLte): CarrierComponent {
        val id = cell.cellIdentity
        val sig = cell.cellSignalStrength
        val earfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            id.earfcn.takeIf { it != CellInfo.UNAVAILABLE && it > 0 } else null
        val bwKhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.bandwidth else CellInfo.UNAVAILABLE
        val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && id.bands.isNotEmpty())
            "B${id.bands.first()}" else BandMapper.lteBand(earfcn)
        return CarrierComponent(
            index = idx, role = role, band = band,
            bandwidthMhz = if (bwKhz > 0 && bwKhz != CellInfo.UNAVAILABLE) bwKhz / 1000.0 else null,
            pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE },
            earfcn = earfcn, downlinkFrequencyMhz = null,
            rsrp = sig.rsrp.takeIf { it != CellInfo.UNAVAILABLE },
            rsrq = sig.rsrq.takeIf { it != CellInfo.UNAVAILABLE },
            rssnr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                sig.rssnr.takeIf { it != CellInfo.UNAVAILABLE } else null,
            cqi = sig.cqi.takeIf { it != CellInfo.UNAVAILABLE },
            timingAdvance = sig.timingAdvance.takeIf { it != CellInfo.UNAVAILABLE }
        )
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
            // cfg.band == 0 means the modem didn't populate it; leave null so enrichCaWithSignal
            // can fill it from CellIdentityLte.getBands() via PCI matching.
            band = if (cfg.band > 0) "B${cfg.band}" else null,
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
                DebugLog.i("TC", "fire sub=$subId configs=${configs.size}")
                val list = configs.mapIndexed { idx, cfg -> physicalChannelToCarrier(idx, cfg) }
                caCache[subId] = list
                _caFlow.value = caCache.toMap()
            }
        }
        try {
            tm.registerTelephonyCallback(executor, cb)
            callbacks[subId] = cb
            DebugLog.i("TC", "registerTelephonyCallback OK sub=$subId")
        } catch (e: Exception) {
            DebugLog.w("TC", "registerTelephonyCallback FAILED sub=$subId: ${e.javaClass.simpleName}: ${e.message}")
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
                DebugLog.i("PSL", "fire sub=$subId configs=${configs.size}")
                val list = configs.mapIndexed { idx, cfg -> physicalChannelToCarrier(idx, cfg) }
                caCache[subId] = list
                _caFlow.value = caCache.toMap()
            }
        }
        try {
            tm.listen(listener, event)
            phoneStateListeners[subId] = listener
            DebugLog.i("PSL", "tm.listen OK sub=$subId event=0x${event.toString(16)}")
        } catch (e: Throwable) {
            DebugLog.w("PSL", "tm.listen FAILED sub=$subId: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun parseNeighborCells(cellInfos: List<CellInfo>, servingEarfcn: Int?): List<NeighborCell> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        return cellInfos.filterIsInstance<CellInfoLte>()
            .filter { !it.isRegistered }
            .mapNotNull { c ->
                // SCells are not registered but aren't neighbours — exclude them (API 28+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    c.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING) return@mapNotNull null
                val id = c.cellIdentity
                val sig = c.cellSignalStrength
                val pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE } ?: return@mapNotNull null
                val rsrp = sig.rsrp.takeIf { it != CellInfo.UNAVAILABLE } ?: return@mapNotNull null
                // Filter modem N/A markers: RSRP≤-113 is below any real signal
                if (rsrp <= -113) return@mapNotNull null
                val earfcn = id.earfcn.takeIf { it != CellInfo.UNAVAILABLE }
                // On single-modem DSDS, allCellInfo is shared across both SIMs.
                // Filter by serving EARFCN so SIM2's serving cell doesn't appear as SIM1's neighbour.
                if (servingEarfcn != null && earfcn != null && earfcn != servingEarfcn) return@mapNotNull null
                NeighborCell(
                    rat = "LTE",
                    pci = pci,
                    earfcn = earfcn,
                    band = BandMapper.lteBand(earfcn),
                    rsrp = rsrp,
                    rsrq = sig.rsrq.takeIf { it != CellInfo.UNAVAILABLE },
                    rssnr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        sig.rssnr.takeIf { it != CellInfo.UNAVAILABLE } else null,
                )
            }
            .sortedByDescending { it.rsrp }
            .take(10)
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

    /**
     * String-parse ServiceState for diagnostic info (Samsung exposes carrier-aggregation flag,
     * NR frequency range, channel bandwidth array, etc. only in toString output).
     */
    private fun parseServiceStateForCaHint(ss: ServiceState?): String? {
        if (ss == null) return null
        val str = try { ss.toString() } catch (e: Exception) { return null }
        // Log in 800-char chunks so we see ALL the fields, not just the first ones
        if (str.length > 0) {
            val chunks = str.chunked(800)
            chunks.forEachIndexed { i, chunk ->
                DebugLog.d("SS", "[${i + 1}/${chunks.size}] $chunk")
            }
        }
        val ca = Regex("(?:mIsUsingCarrierAggregation|isUsingCarrierAggregation)=(true|false)")
            .find(str)?.groupValues?.get(1)
        val nrFreq = Regex("mNrFrequencyRange=(\\d+)").find(str)?.groupValues?.get(1)
        val bws = Regex("mCellBandwidths=\\[([\\d, ]+)]").find(str)?.groupValues?.get(1)
        val parts = mutableListOf<String>()
        if (bws != null) parts += "BWs=[$bws]"
        if (ca != null) parts += "CA=$ca"
        if (nrFreq != null) parts += "NR-FR=$nrFreq"
        return parts.joinToString(" ").ifBlank { null }
    }

    /**
     * Parse mCellBandwidths array from ServiceState.toString(). Returns per-CC bandwidths
     * in kHz (PCell first). Empty list if not present (e.g. 2G/3G where this field is empty).
     */
    private fun parseCellBandwidthsFromSs(ss: ServiceState?): List<Int> {
        if (ss == null) return emptyList()
        val str = try { ss.toString() } catch (e: Exception) { return emptyList() }
        val match = Regex("mCellBandwidths=\\[([\\d, ]+)]").find(str) ?: return emptyList()
        return match.groupValues[1].split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
    }

    /**
     * Build CarrierComponent list from per-CC bandwidth array (ServiceState fallback).
     * Only used when PhysicalChannelConfig isn't available. We know the CA flag and per-CC
     * bandwidths from ServiceState reliably. We do NOT try to match SCells from allCellInfo
     * because that list contains neighbor cells too — there's no isServing flag on non-registered
     * cells, so any match would be a guess and would frequently pick the wrong cell.
     */
    private fun buildCaFromBandwidths(
        bws: List<Int>,
        serving: ServingCellInfo?,
        isCaActive: Boolean
    ): List<CarrierComponent> {
        // If the modem reports CA is not active, don't show a CA card — mCellBandwidths can
        // reflect configured CA capability even when only one CC is in use.
        if (!isCaActive || bws.size < 2) return emptyList()
        return bws.mapIndexed { idx, bwKhz ->
            if (idx == 0) {
                CarrierComponent(
                    index = 0, role = "PCell",
                    band = serving?.band, bandwidthMhz = bwKhz / 1000.0,
                    pci = serving?.pci, earfcn = serving?.earfcn,
                    downlinkFrequencyMhz = null,
                    rsrp = serving?.rsrp, rsrq = serving?.rsrq, rssnr = serving?.rssnr,
                    cqi = serving?.cqi, timingAdvance = serving?.timingAdvance
                )
            } else {
                // SCell: bandwidth is from ServiceState (reliable). Band/PCI/signal are not
                // available without PhysicalChannelConfig — leave them null rather than guess.
                CarrierComponent(
                    index = idx, role = "SCell",
                    band = null, bandwidthMhz = bwKhz / 1000.0,
                    pci = null, earfcn = null, downlinkFrequencyMhz = null
                )
            }
        }
    }

    /** True if the modem reports carrier aggregation is currently active. */
    private fun parseCaActiveFromSs(ss: ServiceState?): Boolean {
        if (ss == null) return false
        // isUsingCarrierAggregation() is @hide — string-parse the toString() dump instead.
        val str = try { ss.toString() } catch (e: Exception) { return false }
        return str.contains("mIsUsingCarrierAggregation=true") ||
                str.contains("isUsingCarrierAggregation=true")
    }

    /**
     * Parse mNrFrequencyRange from ServiceState.toString(). Returns 0=NONE, 1=FR2, 2=FR1, or null.
     * Present on Android 12+ and many Samsung Android 11 builds.
     */
    private fun parseNrFrequencyRange(ss: ServiceState?): Int? {
        if (ss == null) return null
        val str = try { ss.toString() } catch (e: Exception) { return null }
        return Regex("mNrFrequencyRange=(\\d+)").find(str)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** True if the device is camped on a non-terrestrial (satellite) cell. */
    private fun parseNtnFromSs(ss: ServiceState?): Boolean {
        if (ss == null) return false
        val str = try { ss.toString() } catch (e: Exception) { return false }
        return str.contains("mIsUsingNonTerrestrialNetwork=true") ||
                str.contains("isNonTerrestrialNetwork=NON_TERRESTRIAL")
    }

    /** Map ServiceState.duplexMode() (API 30+) to a human label. */
    private fun duplexModeName(ss: ServiceState?): String? {
        if (ss == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            when (ss.duplexMode) {
                1 -> "FDD"
                2 -> "TDD"
                else -> null
            }
        } catch (e: Throwable) { null }
    }

    /**
     * Enrich CA components (from the ServiceState bandwidth fallback path) with band/PCI/EARFCN
     * sourced from allCellInfo. On API 28+ uses CONNECTION_SECONDARY_SERVING to find SCells
     * directly — no CI/eNB heuristic needed, works even when SCell CI = UNAVAILABLE.
     */
    private fun enrichCaFromCellInfo(
        ca: List<CarrierComponent>,
        cellInfos: List<CellInfo>
    ): List<CarrierComponent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return ca

        val scellCandidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cellInfos.filterIsInstance<CellInfoLte>()
                .filter { it.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING }
                .sortedByDescending { it.cellIdentity.earfcn }
        } else {
            val pcellCi = cellInfos.filterIsInstance<CellInfoLte>()
                .firstOrNull { it.isRegistered }?.cellIdentity?.ci ?: return ca
            val pcellEnb = pcellCi / 256L
            cellInfos.filterIsInstance<CellInfoLte>()
                .filter { !it.isRegistered && it.cellIdentity.ci / 256L == pcellEnb }
                .sortedByDescending { it.cellIdentity.earfcn }
        }

        var idx = 0
        return ca.map { cc ->
            if (cc.band != null || cc.role == "PCell") return@map cc
            val candidate = scellCandidates.getOrNull(idx++) ?: return@map cc
            val id = candidate.cellIdentity
            val earfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                id.earfcn.takeIf { it != CellInfo.UNAVAILABLE && it > 0 } else null
            val bwKhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.bandwidth else CellInfo.UNAVAILABLE
            val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && id.bands.isNotEmpty())
                "B${id.bands.first()}" else BandMapper.lteBand(earfcn)
            cc.copy(
                band = band,
                pci = id.pci.takeIf { it != CellInfo.UNAVAILABLE },
                earfcn = earfcn,
                bandwidthMhz = cc.bandwidthMhz ?: if (bwKhz > 0 && bwKhz != CellInfo.UNAVAILABLE) bwKhz / 1000.0 else null
            )
        }
    }

    /**
     * Enrich CA components from PCC callback / reflection path with signal from allCellInfo,
     * matched by PCI. Also fills band/EARFCN from id.bands when PCC reported null or band=0
     * (some modems don't populate PhysicalChannelConfig.band).
     */
    private fun enrichCaWithSignal(ca: List<CarrierComponent>, cellInfos: List<CellInfo>): List<CarrierComponent> {
        if (ca.isEmpty()) return ca
        val lteByPci = cellInfos.filterIsInstance<CellInfoLte>()
            .filter { it.cellIdentity.pci != CellInfo.UNAVAILABLE }
            .associateBy { it.cellIdentity.pci }
        return ca.map { cc ->
            val cell = cc.pci?.let { lteByPci[it] } ?: return@map cc
            val sig = cell.cellSignalStrength
            val id = cell.cellIdentity
            val earfcnFromCi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                id.earfcn.takeIf { it != CellInfo.UNAVAILABLE && it > 0 } else null
            val bandFromCi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && id.bands.isNotEmpty())
                "B${id.bands.first()}"
            else BandMapper.lteBand(earfcnFromCi)
            cc.copy(
                band = if (cc.band == null || cc.band == "B0") bandFromCi else cc.band,
                earfcn = cc.earfcn ?: earfcnFromCi,
                rsrp = sig.rsrp.takeIf { it != CellInfo.UNAVAILABLE },
                rsrq = sig.rsrq.takeIf { it != CellInfo.UNAVAILABLE },
                rssnr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    sig.rssnr.takeIf { it != CellInfo.UNAVAILABLE } else null,
                cqi = sig.cqi.takeIf { it != CellInfo.UNAVAILABLE },
                timingAdvance = sig.timingAdvance.takeIf { it != CellInfo.UNAVAILABLE }
            )
        }
    }

    /** Dump everything the modem is reporting to the debug log for field analysis. */
    @SuppressLint("MissingPermission")
    private fun logDiagnostics(
        subId: Int,
        tm: TelephonyManager,
        ss: ServiceState?,
        cellInfos: List<CellInfo>,
        signalStrengths: List<CellSignalStrength>
    ) {
        if (!DebugLog.enabled) return

        // Network type summary
        val dataType = try { tm.dataNetworkType } catch (e: Exception) { TelephonyManager.NETWORK_TYPE_UNKNOWN }
        val voiceType = try { tm.voiceNetworkType } catch (e: Exception) { TelephonyManager.NETWORK_TYPE_UNKNOWN }
        DebugLog.d("NET", "sub=$subId data=${networkTypeName(dataType)} voice=${networkTypeName(voiceType)} roaming=${tm.isNetworkRoaming}")

        // All CellInfo — registered AND unregistered neighbors
        DebugLog.d("CI", "sub=$subId total=${cellInfos.size}")
        cellInfos.forEachIndexed { i, cell ->
            when {
                cell is CellInfoLte -> {
                    val id = cell.cellIdentity
                    val sig = cell.cellSignalStrength
                    val earfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) id.earfcn else -1
                    val bw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.bandwidth else -1
                    val bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) id.bands.toString() else "n/a"
                    val rssnr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sig.rssnr else Int.MIN_VALUE
                    val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sig.rssi else Int.MIN_VALUE
                    DebugLog.d("CI", "[$i]LTE reg=${cell.isRegistered} ci=${id.ci} pci=${id.pci} tac=${id.tac} earfcn=$earfcn bw=${bw}kHz bands=$bands")
                    DebugLog.d("CI", "[$i]LTE rsrp=${sig.rsrp} rsrq=${sig.rsrq} rssnr=$rssnr cqi=${sig.cqi} ta=${sig.timingAdvance} rssi=$rssi lvl=${sig.level}")
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr -> {
                    val id = cell.cellIdentity as? CellIdentityNr ?: return@forEachIndexed
                    val sig = cell.cellSignalStrength as? CellSignalStrengthNr ?: return@forEachIndexed
                    val bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.toString() else "n/a"
                    DebugLog.d("CI", "[$i]NR reg=${cell.isRegistered} nci=${id.nci} pci=${id.pci} tac=${id.tac} nrarfcn=${id.nrarfcn} bands=$bands")
                    DebugLog.d("CI", "[$i]NR ssRsrp=${sig.ssRsrp} ssRsrq=${sig.ssRsrq} ssSinr=${sig.ssSinr} csiRsrp=${sig.csiRsrp} csiRsrq=${sig.csiRsrq} csiSinr=${sig.csiSinr}")
                }
                cell is CellInfoWcdma -> {
                    val id = cell.cellIdentity
                    val sig = cell.cellSignalStrength
                    val uarfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) id.uarfcn else -1
                    val ecNo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try { sig.ecNo } catch (e: Throwable) { Int.MIN_VALUE }
                    } else Int.MIN_VALUE
                    DebugLog.d("CI", "[$i]WCDMA reg=${cell.isRegistered} cid=${id.cid} psc=${id.psc} lac=${id.lac} uarfcn=$uarfcn dbm=${sig.dbm} ecNo=$ecNo")
                }
                cell is CellInfoGsm -> {
                    val id = cell.cellIdentity
                    val sig = cell.cellSignalStrength
                    val arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) id.arfcn else -1
                    DebugLog.d("CI", "[$i]GSM reg=${cell.isRegistered} cid=${id.cid} lac=${id.lac} arfcn=$arfcn bsic=${id.bsic} dbm=${sig.dbm} ta=${sig.timingAdvance}")
                }
                else -> DebugLog.d("CI", "[$i]${cell.javaClass.simpleName} reg=${cell.isRegistered}")
            }
        }

        // Structured per-RAT signal strengths
        DebugLog.d("SS2", "sub=$subId count=${signalStrengths.size}")
        signalStrengths.forEachIndexed { i, sig ->
            when {
                sig is CellSignalStrengthLte -> {
                    val rssnr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sig.rssnr else Int.MIN_VALUE
                    val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sig.rssi else Int.MIN_VALUE
                    DebugLog.d("SS2", "[$i]LTE rsrp=${sig.rsrp} rsrq=${sig.rsrq} rssnr=$rssnr cqi=${sig.cqi} ta=${sig.timingAdvance} rssi=$rssi lvl=${sig.level}")
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sig is CellSignalStrengthNr -> {
                    DebugLog.d("SS2", "[$i]NR ssRsrp=${sig.ssRsrp} ssRsrq=${sig.ssRsrq} ssSinr=${sig.ssSinr} csiRsrp=${sig.csiRsrp} csiRsrq=${sig.csiRsrq} csiSinr=${sig.csiSinr} lvl=${sig.level}")
                }
                sig is CellSignalStrengthWcdma -> {
                    val ecNo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try { sig.ecNo } catch (e: Throwable) { Int.MIN_VALUE }
                    } else Int.MIN_VALUE
                    DebugLog.d("SS2", "[$i]WCDMA dbm=${sig.dbm} ecNo=$ecNo lvl=${sig.level}")
                }
                sig is CellSignalStrengthGsm -> {
                    DebugLog.d("SS2", "[$i]GSM dbm=${sig.dbm} ta=${sig.timingAdvance} ber=${sig.bitErrorRate} lvl=${sig.level}")
                }
                else -> DebugLog.d("SS2", "[$i]${sig.javaClass.simpleName} lvl=${sig.level}")
            }
        }

        // NetworkRegistrationInfo — per-domain, per-transport breakdown
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ss != null) {
            try {
                ss.networkRegistrationInfoList.forEachIndexed { i, reg ->
                    val domain = when (reg.domain) {
                        NetworkRegistrationInfo.DOMAIN_CS -> "CS"
                        NetworkRegistrationInfo.DOMAIN_PS -> "PS"
                        else -> "?(${reg.domain})"
                    }
                    val transport = when (reg.transportType) {
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN -> "WWAN"
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN -> "WLAN"
                        else -> "?(${reg.transportType})"
                    }
                    val tech = networkTypeName(reg.accessNetworkTechnology)
                    val state = try {
                        val m = reg.javaClass.getMethod("getRegistrationState")
                        m.invoke(reg)?.toString() ?: "?"
                    } catch (e: Throwable) { "n/a" }
                    val plmn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try { reg.registeredPlmn ?: "null" } catch (e: Throwable) { "err" }
                    } else "n/a"
                    DebugLog.d("NRI", "[$i] domain=$domain transport=$transport tech=$tech state=$state plmn=$plmn")
                    val cellId = reg.cellIdentity
                    when {
                        cellId is CellIdentityLte -> {
                            val earfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cellId.earfcn else -1
                            val bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cellId.bands.toString() else "n/a"
                            DebugLog.d("NRI", "[$i] LTE ci=${cellId.ci} pci=${cellId.pci} tac=${cellId.tac} earfcn=$earfcn bands=$bands")
                        }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellId is CellIdentityNr -> {
                            val bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cellId.bands.toString() else "n/a"
                            DebugLog.d("NRI", "[$i] NR nci=${cellId.nci} pci=${cellId.pci} tac=${cellId.tac} nrarfcn=${cellId.nrarfcn} bands=$bands")
                        }
                        cellId != null -> DebugLog.d("NRI", "[$i] ${cellId.javaClass.simpleName}")
                        else -> DebugLog.d("NRI", "[$i] cellIdentity=null")
                    }
                }
            } catch (e: Exception) { DebugLog.w("NRI", "failed: ${e.message}") }
        }

        // PhysicalChannelConfig — synchronous reflection snapshot
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val m = tm.javaClass.getMethod("getPhysicalChannelConfigs")
                @Suppress("UNCHECKED_CAST")
                val configs = (m.invoke(tm) as? List<PhysicalChannelConfig>) ?: emptyList()
                DebugLog.d("PCC", "sub=$subId reflect count=${configs.size}")
                configs.forEachIndexed { i, cfg ->
                    val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        cfg.downlinkFrequencyKhz else -1
                    DebugLog.d("PCC", "[$i] band=${cfg.band} bw=${cfg.cellBandwidthDownlinkKhz}kHz pci=${cfg.physicalCellId} earfcn=${cfg.downlinkChannelNumber} status=${cfg.connectionStatus} freq=${freq}kHz")
                }
            } catch (e: Throwable) {
                DebugLog.d("PCC", "sub=$subId reflect failed: ${e.message}")
            }
        }
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        else -> "?(${type})"
    }
}
