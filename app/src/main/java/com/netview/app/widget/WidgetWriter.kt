package com.netview.app.widget

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.netview.app.data.CmExportRepository
import com.netview.app.data.GsmCmRepository
import com.netview.app.data.ServingCellInfo
import com.netview.app.data.SimSlotData
import com.netview.app.data.WcdmaCmRepository

object WidgetWriter {

    suspend fun write(
        context: Context,
        sims: List<SimSlotData>,
        lteExport: CmExportRepository?,
        wcdmaExport: WcdmaCmRepository?,
        gsmExport: GsmCmRepository?,
        isWifi: Boolean,
        dlMbps: Double?,
        latencyMs: Long?,
        defaultDataSubId: Int,
        updatedMs: Long,
    ) {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(NetViewWidget::class.java)
        if (ids.isEmpty()) return

        for (id in ids) {
            updateAppWidgetState(context, id) { prefs ->
                prefs[NetViewWidget.KEY_SIM_COUNT] = sims.size.coerceAtMost(2)
                prefs[NetViewWidget.KEY_UPDATED] = updatedMs

                sims.take(2).forEachIndexed { slot, sim ->
                    val isDataSim = defaultDataSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                            || sim.subId == defaultDataSubId

                    prefs[NetViewWidget.keySim(slot)] = "SIM ${sim.slotIndex + 1}"
                    prefs[NetViewWidget.keyCarrier(slot)] = sim.carrierName
                    prefs[NetViewWidget.keyRat(slot)] = sim.networkType
                    // Samsung returns CI=UNAVAILABLE from background services; skip the write so
                    // the last good value (written by the foreground app) is preserved in the store.
                    if (hasCellId(sim.servingCell)) {
                        prefs[NetViewWidget.keyCellLabel(slot)] = cellLabel(sim, lteExport, wcdmaExport, gsmExport)
                    }
                    prefs[NetViewWidget.keyBand(slot)] = sim.servingCell?.band ?: ""
                    prefs[NetViewWidget.keySigLine(slot)] = signalLine(sim.servingCell)
                    prefs[NetViewWidget.keyCa(slot)] =
                        if (sim.carrierAggregation.isNotEmpty()) "${sim.carrierAggregation.size}CC" else ""

                    // NR secondary cell (NSA mode)
                    val nr = sim.nrCell
                    if (nr == null) {
                        prefs[NetViewWidget.keyNrRow(slot)] = ""
                        prefs[NetViewWidget.keyNrSig(slot)] = ""
                    } else {
                        // Signal always available; NR identity (gnbId/band) may be null in background —
                        // keep previous row content rather than overwriting with blank.
                        prefs[NetViewWidget.keyNrSig(slot)] = signalLine(nr)
                        val nrRowContent = buildString {
                            nr.band?.let { append(it) }
                            nr.gnbId?.let { if (isNotEmpty()) append(" · "); append("gNB $it") }
                        }
                        if (nrRowContent.isNotEmpty()) {
                            prefs[NetViewWidget.keyNrRow(slot)] = nrRowContent
                        }
                    }

                    val showMetrics = !isWifi && isDataSim
                    prefs[NetViewWidget.keyDl(slot)] = if (showMetrics) dlMbps?.let { "DL %.1f Mbps".format(it) } ?: "" else ""
                    prefs[NetViewWidget.keyLat(slot)] = if (showMetrics) latencyMs?.let { "$it ms" } ?: "" else ""
                }
            }
            NetViewWidget().update(context, id)
        }
    }

    private fun cellLabel(
        sim: SimSlotData,
        lteExport: CmExportRepository?,
        wcdmaExport: WcdmaCmRepository?,
        gsmExport: GsmCmRepository?,
    ): String {
        val c = sim.servingCell ?: return "—"
        return when (c.rat) {
            "LTE" -> {
                val enbId = c.enbId?.toInt()
                val sectorId = c.sectorId
                val name = if (lteExport != null && enbId != null && sectorId != null)
                    lteExport.lookup(enbId, sectorId, c.mcc?.toIntOrNull(), c.mnc?.toIntOrNull())?.lncelName
                else null
                when {
                    name != null -> name
                    enbId != null && sectorId != null -> "eNB $enbId / LCR $sectorId"
                    else -> buildString {
                        // CI unavailable — show physical cell identifiers as fallback
                        c.pci?.let { append("PCI $it") }
                        c.earfcn?.let { if (isNotEmpty()) append("  "); append("EARFCN $it") }
                    }.ifBlank { "LTE" }
                }
            }
            "NR" -> "gNB ${c.gnbId ?: "—"}"
            "WCDMA" -> {
                val ci = c.cellId
                val rncId = ci?.let { (it shr 16).toInt() }
                val wcelId = ci?.let { (it and 0xFFFFL).toInt() }
                val name = if (wcdmaExport != null && rncId != null && wcelId != null && c.uarfcn != null)
                    wcdmaExport.lookup(rncId, wcelId, c.uarfcn, c.mcc?.toIntOrNull(), c.mnc?.toIntOrNull())?.wcelName
                else null
                name ?: "RNC ${rncId ?: "—"} / WCEL ${wcelId ?: "—"}"
            }
            "GSM" -> {
                val name = if (gsmExport != null && c.tac != null && c.cellId != null)
                    gsmExport.lookup(c.tac, c.cellId.toInt(), c.mcc?.toIntOrNull(), c.mnc?.toIntOrNull())?.cellName
                else null
                name ?: "LAC ${c.tac ?: "—"} / CI ${c.cellId ?: "—"}"
            }
            else -> c.rat
        }
    }

    private fun hasCellId(c: ServingCellInfo?): Boolean {
        if (c == null) return false
        return when (c.rat) {
            "LTE" -> c.enbId != null
            "NR" -> c.gnbId != null
            "WCDMA" -> c.cellId != null
            "GSM" -> c.cellId != null
            else -> false
        }
    }

    fun signalLine(c: ServingCellInfo?): String {
        if (c == null) return ""
        return when (c.rat) {
            "LTE" -> buildList {
                c.rsrp?.let { add("RSRP $it") }
                c.rsrq?.let { add("RSRQ $it") }
                c.rssnr?.let { add("SINR $it dB") }
            }.joinToString("  ")
            "NR" -> buildList {
                c.rsrp?.let { add("RSRP $it") }
                c.rsrq?.let { add("RSRQ $it") }
                (c.ssSinr ?: c.csiSinr)?.let { add("SINR $it dB") }
            }.joinToString("  ")
            "WCDMA" -> buildList {
                c.rscp?.let { add("RSCP $it dBm") }
                c.ecNo?.let { add("Ec/No $it dB") }
            }.joinToString("  ")
            "GSM" -> c.rssi?.let { "RSSI $it dBm" } ?: ""
            else -> ""
        }
    }
}
