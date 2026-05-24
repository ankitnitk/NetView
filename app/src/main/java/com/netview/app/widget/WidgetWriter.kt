package com.netview.app.widget

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAppWidgetState
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
            updateAppWidgetState(context, id) {
                this[NetViewWidget.KEY_SIM_COUNT] = sims.size.coerceAtMost(2)
                this[NetViewWidget.KEY_UPDATED] = updatedMs

                sims.take(2).forEachIndexed { slot, sim ->
                    val isDataSim = defaultDataSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                            || sim.subId == defaultDataSubId

                    this[NetViewWidget.keyCarrier(slot)] = sim.carrierName
                    this[NetViewWidget.keyRat(slot)] = sim.networkType
                    this[NetViewWidget.keyCellLabel(slot)] = cellLabel(sim, lteExport, wcdmaExport, gsmExport)
                    this[NetViewWidget.keyBand(slot)] = sim.servingCell?.band ?: ""
                    this[NetViewWidget.keySigLine(slot)] = signalLine(sim.servingCell)
                    this[NetViewWidget.keyCa(slot)] =
                        if (sim.carrierAggregation.isNotEmpty()) "${sim.carrierAggregation.size}CC" else ""

                    val showMetrics = !isWifi && isDataSim
                    this[NetViewWidget.keyDl(slot)] = if (showMetrics) dlMbps?.let { "DL %.1f Mbps".format(it) } ?: "" else ""
                    this[NetViewWidget.keyLat(slot)] = if (showMetrics) latencyMs?.let { "$it ms" } ?: "" else ""
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
                name ?: "eNB ${c.enbId ?: "—"} / LCR ${c.sectorId ?: "—"}"
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

    fun signalLine(c: ServingCellInfo?): String {
        if (c == null) return ""
        return when (c.rat) {
            "LTE" -> buildList {
                c.rsrp?.let { add("$it dBm") }
                c.rsrq?.let { add("$it dB") }
                c.rssnr?.let { add("SINR $it dB") }
            }.joinToString("  ")
            "NR" -> buildList {
                c.rsrp?.let { add("$it dBm") }
                c.rsrq?.let { add("$it dB") }
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
