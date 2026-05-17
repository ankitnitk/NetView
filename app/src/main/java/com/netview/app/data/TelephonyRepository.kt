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
    // Band history per subId — accumulates bands seen on serving cell + visible neighbors over
    // the session so SCells (which have no per-CC band data in public APIs) can show something.
    private val recentBandsBySubId = mutableMapOf<Int, MutableSet<String>>()

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
        val serving = parseServingFromServiceState(tm, serviceState) ?: parseServingCell(cellInfos)
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

        // Clear stale CA / band-history when not on LTE/NR — otherwise old aggregation data
        // from a prior LTE session lingers when the device drops to 3G/2G.
        val isLteOrNr = serving?.rat == "LTE" || serving?.rat == "NR"
        if (!isLteOrNr) {
            caCache.remove(sub.subscriptionId)
            recentBandsBySubId.remove(sub.subscriptionId)
        }
        // Accumulate bands seen in this and prior reads — used to fill SCell band labels
        // since per-CC band info isn't in any public API.
        val bandHistory = if (isLteOrNr)
            recentBandsBySubId.getOrPut(sub.subscriptionId) { mutableSetOf() }
        else mutableSetOf()
        if (isLteOrNr) {
            serving?.band?.let { bandHistory.add(it) }
            extractBandsFromCellInfo(cellInfos).forEach { bandHistory.add(it) }
        }

        // Attach duplex mode + capability info to the LTE serving cell
        val servingEnriched = if (serving?.rat == "LTE")
            serving.copy(duplexMode = duplexModeName(serviceState)) else serving

        // CA: layered fallback. Callback cache → synchronous reflection → ServiceState
        // mCellBandwidths string parse (proven to work on Samsung) → cell info heuristic.
        val cached = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            caCache[sub.subscriptionId] ?: emptyList() else emptyList()
        val direct = if (cached.isEmpty()) parsePhysicalChannelsViaReflection(tm) else emptyList()
        val fromSs = if (cached.isEmpty() && direct.isEmpty())
            buildCaFromServiceStateBandwidths(serviceState, servingEnriched, bandHistory.toList())
            else emptyList()
        val ca = when {
            cached.isNotEmpty() -> cached
            direct.isNotEmpty() -> direct
            fromSs.isNotEmpty() -> fromSs
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
        val arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            id.arfcn.takeIf { it != CellInfo.UNAVAILABLE } else null
        return ServingCellInfo(
            rat = "GSM",
            mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString else id.mcc.toString(),
            mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString else id.mnc.toString(),
            pci = null,
            tac = id.lac.takeIf { it != CellInfo.UNAVAILABLE },
            cellId = id.cid.toLong().takeIf { it > 0 && it != CellInfo.UNAVAILABLE.toLong() },
            enbId = null, gnbId = null, sectorId = null,
            earfcn = null, nrarfcn = null, uarfcn = null,
            arfcn = arfcn,
            band = BandMapper.gsmBand(arfcn),
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
     * Build CarrierComponent list from ServiceState.mCellBandwidths — Samsung populates this
     * with one entry per active component carrier (PCell first, then SCells). When the
     * PhysicalChannelConfig path is blocked, this is our actual data source for CA.
     * SCell bands are assigned from the accumulated band history (excluding PCell's band).
     */
    private fun buildCaFromServiceStateBandwidths(
        ss: ServiceState?,
        serving: ServingCellInfo?,
        bandHistory: List<String>
    ): List<CarrierComponent> {
        if (ss == null) return emptyList()
        val str = try { ss.toString() } catch (e: Exception) { return emptyList() }
        val match = Regex("mCellBandwidths=\\[([\\d, ]+)]").find(str) ?: return emptyList()
        val bws = match.groupValues[1].split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
        if (bws.size < 2) return emptyList()  // only show as CA when >1 carrier
        val pcellBand = serving?.band
        val scellBandPool = bandHistory.filter { it != pcellBand }
        return bws.mapIndexed { idx, bwKhz ->
            val band = if (idx == 0) pcellBand else scellBandPool.getOrNull(idx - 1)
            CarrierComponent(
                index = idx,
                role = if (idx == 0) "PCell" else "SCell",
                band = band,
                bandwidthMhz = bwKhz / 1000.0,
                pci = if (idx == 0) serving?.pci else null,
                earfcn = if (idx == 0) serving?.earfcn else null,
                downlinkFrequencyMhz = null,
                mimoLayers = null
            )
        }
    }

    /** Pull bands out of allCellInfo. CellIdentityLte.bands is API 30+; safe to ignore on older. */
    private fun extractBandsFromCellInfo(cells: List<CellInfo>): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
        return cells.filterIsInstance<CellInfoLte>()
            .flatMap { cell ->
                try { cell.cellIdentity.bands.map { "B$it" } }
                catch (e: Throwable) { emptyList() }
            }
            .toSet()
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
}
