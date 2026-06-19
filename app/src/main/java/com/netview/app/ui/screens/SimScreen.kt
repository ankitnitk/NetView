package com.netview.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netview.app.data.CmExportCell
import com.netview.app.data.GsmCmCell
import com.netview.app.data.LocationData
import com.netview.app.data.SimSlotData
import com.netview.app.data.WcdmaCmCell
import com.netview.app.ui.components.InfoCard
import com.netview.app.ui.components.TechBadge
import com.netview.app.utils.EarfcnUtils
import com.netview.app.utils.Formatters
import com.netview.app.utils.ShareFormatter
import com.netview.app.utils.SignalQuality
import kotlin.math.log10

@Composable
fun SimScreen(
    sim: SimSlotData,
    location: LocationData?,
    cmExportCell: CmExportCell? = null,
    cmExportLoaded: Boolean = false,
    cmNeighborLookup: ((Int, Int) -> CmExportCell?)? = null,
    wcdmaCmCell: WcdmaCmCell? = null,
    wcdmaCmLoaded: Boolean = false,
    gsmCmCell: GsmCmCell? = null,
    gsmCmLoaded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    Column(modifier = modifier.fillMaxSize()) {
        // Pinned header — always visible
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sim.carrierName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${sim.displayName} • Slot ${sim.slotIndex + 1}" +
                            if (sim.isRoaming) " • Roaming" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TechBadge(networkType = sim.networkType)
            IconButton(onClick = {
                val text = ShareFormatter.build(sim, location)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "NetView • ${sim.carrierName}")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                ctx.startActivity(Intent.createChooser(intent, "Share cell info"))
            }) {
                Icon(Icons.Default.Share, contentDescription = "Share")
            }
        }
        HorizontalDivider()

        // Scrollable cards
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

        // SIM / network identity
        InfoCard(
            title = "Network",
            rows = listOf(
                "MCC" to Formatters.stringOrDash(sim.mcc),
                "MNC" to Formatters.stringOrDash(sim.mnc),
                "Network Type" to sim.networkType,
                "Voice" to sim.voiceTech,
                "IMS Registered" to if (sim.imsRegistered) "Yes" else "No",
                "Roaming" to if (sim.isRoaming) "Yes" else "No"
            )
        )

        // Serving cell
        sim.servingCell?.let { c ->
            val rows = mutableListOf<Pair<String, String>>()
            rows += "RAT" to c.rat
            if (sim.isNonTerrestrial) {
                rows += "Network Class" to "Satellite (NTN)"
            }
            when (c.rat) {
                "LTE" -> {
                    rows += "eNB ID" to Formatters.longOrDash(c.enbId)
                    rows += "LCR ID" to Formatters.intOrDash(c.sectorId)
                    rows += "Cell ID (CID)" to Formatters.longOrDash(c.cellId)
                    rows += "PCI" to Formatters.intOrDash(c.pci)
                    rows += "TAC" to Formatters.intOrDash(c.tac)
                    rows += "EARFCN" to earfcnWithFreq(c.earfcn)
                    rows += "Band" to Formatters.stringOrDash(c.band)
                    val bw = c.bandwidthMhz
                        ?: sim.carrierAggregation.firstOrNull()?.bandwidthMhz
                    rows += "Bandwidth" to (bw?.let { "%.1f MHz".format(it) } ?: "—")
                    c.duplexMode?.let { rows += "Duplex Mode" to it }
                }
                "NR" -> {
                    rows += "gNB ID" to Formatters.longOrDash(c.gnbId)
                    rows += "NCI" to Formatters.longOrDash(c.cellId)
                    rows += "PCI" to Formatters.intOrDash(c.pci)
                    rows += "TAC" to Formatters.intOrDash(c.tac)
                    rows += "NRARFCN" to nrArfcnWithFreq(c.nrarfcn)
                    rows += "Band" to Formatters.stringOrDash(c.band)
                }
                "WCDMA" -> {
                    rows += "LAC" to Formatters.intOrDash(c.tac)
                    val ci = c.cellId
                    rows += "CI" to Formatters.longOrDash(ci)
                    if (ci != null) {
                        rows += "RNC ID" to (ci shr 16).toString()
                        rows += "CID" to (ci and 0xFFFF).toString()
                    }
                    rows += "PSC" to Formatters.intOrDash(c.pci)
                    rows += "UARFCN" to Formatters.intOrDash(c.uarfcn)
                    rows += "Band" to Formatters.stringOrDash(c.band)
                }
                "GSM" -> {
                    rows += "LAC" to Formatters.intOrDash(c.tac)
                    rows += "CID" to Formatters.longOrDash(c.cellId)
                    rows += "ARFCN" to Formatters.intOrDash(c.arfcn)
                    rows += "BSIC" to Formatters.intOrDash(c.bsic)
                    rows += "Band" to Formatters.stringOrDash(c.band)
                }
            }
            // Throughput and cell time (all RATs)
            sim.dlThroughputMbps?.let { rows += "DL Speed" to formatMbps(it) }
            sim.ulThroughputMbps?.let { rows += "UL Speed" to formatMbps(it) }
            sim.latencyMs?.let { rows += "Latency" to "$it ms" }
            sim.timeOnCellSeconds?.let { rows += "Time on Cell" to formatDuration(it) }

            InfoCard(title = "Serving Cell", rows = rows)

            // Signal
            val sig = mutableListOf<Pair<String, String>>()
            val sigColors = mutableMapOf<String, androidx.compose.ui.graphics.Color>()
            when (c.rat) {
                "LTE" -> {
                    sig += "RSRP" to Formatters.signedOrDash(c.rsrp, "dBm")
                    sig += "RSRQ" to Formatters.signedOrDash(c.rsrq, "dB")
                    sig += "SINR (RSSNR)" to Formatters.signedOrDash(c.rssnr, "dB")
                    sig += "RSSI" to Formatters.signedOrDash(c.rssi, "dBm")
                    sig += "CQI" to Formatters.intOrDash(c.cqi)
                    sig += "Timing Advance" to Formatters.intOrDash(c.timingAdvance)
                    SignalQuality.rsrp(c.rsrp)?.let { sigColors["RSRP"] = it }
                    SignalQuality.rsrq(c.rsrq)?.let { sigColors["RSRQ"] = it }
                    SignalQuality.sinr(c.rssnr)?.let { sigColors["SINR (RSSNR)"] = it }
                }
                "NR" -> {
                    sig += "SS-RSRP" to Formatters.signedOrDash(c.rsrp, "dBm")
                    sig += "SS-RSRQ" to Formatters.signedOrDash(c.rsrq, "dB")
                    sig += "SS-SINR" to Formatters.signedOrDash(c.ssSinr, "dB")
                    sig += "CSI-RSRP" to Formatters.signedOrDash(c.csiRsrp, "dBm")
                    sig += "CSI-RSRQ" to Formatters.signedOrDash(c.csiRsrq, "dB")
                    sig += "CSI-SINR" to Formatters.signedOrDash(c.csiSinr, "dB")
                    SignalQuality.rsrp(c.rsrp)?.let { sigColors["SS-RSRP"] = it }
                    SignalQuality.rsrq(c.rsrq)?.let { sigColors["SS-RSRQ"] = it }
                    SignalQuality.sinr(c.ssSinr)?.let { sigColors["SS-SINR"] = it }
                    SignalQuality.rsrp(c.csiRsrp)?.let { sigColors["CSI-RSRP"] = it }
                    SignalQuality.rsrq(c.csiRsrq)?.let { sigColors["CSI-RSRQ"] = it }
                    SignalQuality.sinr(c.csiSinr)?.let { sigColors["CSI-SINR"] = it }
                }
                "WCDMA" -> {
                    sig += "RSCP" to Formatters.signedOrDash(c.rscp, "dBm")
                    sig += "Ec/No" to Formatters.signedOrDash(c.ecNo, "dB")
                    sig += "RSSI" to Formatters.signedOrDash(c.rssi, "dBm")
                    SignalQuality.rscp(c.rscp)?.let { sigColors["RSCP"] = it }
                    SignalQuality.ecNo(c.ecNo)?.let { sigColors["Ec/No"] = it }
                }
                "GSM" -> {
                    sig += "RSSI" to Formatters.signedOrDash(c.rssi, "dBm")
                    sig += "BER" to Formatters.intOrDash(c.ber)
                    sig += "Timing Advance" to Formatters.intOrDash(c.timingAdvance)
                }
            }
            InfoCard(title = "Signal", rows = sig, valueColors = sigColors)
        } ?: InfoCard(
            title = "Serving Cell",
            rows = listOf("Status" to "No cell info — check permissions")
        )

        // 5G NR leg (NSA only)
        sim.nrCell?.let { nr ->
            val rows = mutableListOf<Pair<String, String>>()
            rows += "RAT" to "NR (NSA secondary)"
            nr.gnbId?.let { rows += "gNB ID" to Formatters.longOrDash(it) }
            nr.cellId?.let { rows += "NCI" to Formatters.longOrDash(it) }
            nr.pci?.let { rows += "NR PCI" to Formatters.intOrDash(it) }
            nr.tac?.let { rows += "NR TAC" to Formatters.intOrDash(it) }
            nr.nrarfcn?.let { rows += "NR ARFCN" to Formatters.intOrDash(it) }
            nr.band?.let { rows += "NR Band" to it }
            rows += "SS-RSRP" to Formatters.signedOrDash(nr.rsrp, "dBm")
            rows += "SS-RSRQ" to Formatters.signedOrDash(nr.rsrq, "dB")
            rows += "SS-SINR" to Formatters.signedOrDash(nr.ssSinr, "dB")
            nr.csiRsrp?.let { rows += "CSI-RSRP" to Formatters.signedOrDash(it, "dBm") }
            nr.csiRsrq?.let { rows += "CSI-RSRQ" to Formatters.signedOrDash(it, "dB") }
            nr.csiSinr?.let { rows += "CSI-SINR" to Formatters.signedOrDash(it, "dB") }
            val nrColors = mutableMapOf<String, androidx.compose.ui.graphics.Color>()
            SignalQuality.rsrp(nr.rsrp)?.let { nrColors["SS-RSRP"] = it }
            SignalQuality.rsrq(nr.rsrq)?.let { nrColors["SS-RSRQ"] = it }
            SignalQuality.sinr(nr.ssSinr)?.let { nrColors["SS-SINR"] = it }
            SignalQuality.rsrp(nr.csiRsrp)?.let { nrColors["CSI-RSRP"] = it }
            SignalQuality.rsrq(nr.csiRsrq)?.let { nrColors["CSI-RSRQ"] = it }
            SignalQuality.sinr(nr.csiSinr)?.let { nrColors["CSI-SINR"] = it }
            InfoCard(title = "5G NR Leg", rows = rows, valueColors = nrColors)
        }

        // Carrier Aggregation (LTE / NR only)
        val ratIsLteOrNr = sim.servingCell?.rat == "LTE" || sim.servingCell?.rat == "NR"
        if (ratIsLteOrNr && sim.carrierAggregation.isNotEmpty()) {
            val rows = mutableListOf<Pair<String, String>>()
            val bwSummary = sim.carrierAggregation
                .mapNotNull { it.bandwidthMhz?.let { v -> "%.0f".format(v) } }
                .joinToString(" + ")
            val totalBw = sim.carrierAggregation.sumOf { it.bandwidthMhz ?: 0.0 }
            val bandSummary = sim.carrierAggregation
                .mapNotNull { it.band }
                .distinct()
                .joinToString(" + ")
            rows += "CA Status" to "Active • ${sim.carrierAggregation.size}CC"
            if (bwSummary.isNotBlank()) {
                rows += "Bandwidths" to "$bwSummary MHz  (Σ ${"%.0f".format(totalBw)} MHz)"
            }
            if (bandSummary.isNotBlank()) {
                rows += "Bands" to bandSummary
            }
            sim.carrierAggregation.forEach { cc ->
                val freq = cc.downlinkFrequencyMhz?.let { " @ %.1f MHz".format(it) } ?: ""
                val bw = cc.bandwidthMhz?.let { " %.1f MHz".format(it) } ?: ""
                val mimo = cc.mimoLayers?.let { " • ${it}L MIMO" } ?: ""
                rows += "CC${cc.index + 1} (${cc.role})" to
                        "${cc.band ?: "—"}$bw • PCI ${Formatters.intOrDash(cc.pci)}$mimo$freq"
                val sigParts = buildList {
                    cc.rsrp?.let { add("${it} dBm") }
                    cc.rsrq?.let { add("RSRQ $it dB") }
                    cc.rssnr?.let { add("SNR $it dB") }
                }
                if (sigParts.isNotEmpty()) rows += "  Signal" to sigParts.joinToString("  ")
            }
            InfoCard(
                title = "Carrier Aggregation • ${sim.carrierAggregation.size}CC",
                rows = rows,
                collapsible = true
            )
        } else if (ratIsLteOrNr) {
            InfoCard(
                title = "Carrier Aggregation",
                rows = listOf("CA Status" to "—")
            )
        }

        // Configuration as per CM Dump — RAT-specific cards
        val rat = sim.servingCell?.rat
        if (rat == "LTE" && cmExportLoaded) {
            val cmRows = if (cmExportCell != null) {
                val m = cmExportCell
                listOf(
                    "Site" to m.lnbtsName,
                    "Cell" to m.lncelName,
                    "Tilt" to (m.tiltTenthDeg?.let { "%.1f°".format(it / 10.0) } ?: "—"),
                    "RS Boost" to (m.dlRsBoost?.let { "$it dB" } ?: "—"),
                    "PMAX" to (m.pmaxDbm?.let { "$it dBm" } ?: "—"),
                    "RS Power" to (m.rsPowerDbm?.let { "$it dBm" } ?: "—"),
                    "MIMO Mode" to (m.dlMimoMode ?: "—"),
                    "SIB Priority" to (m.sibPriority?.toString() ?: "—"),
                    "IRFIM List" to (m.irfimList ?: "—"),
                    "LNHOIF List" to (m.lnhoifList ?: "—"),
                    "CAPR List" to (m.caprList ?: "—"),
                    "LNCEL Count" to (m.lncelCount?.toString() ?: "—"),
                    "Band List" to (m.bandList ?: "—"),
                    "LTE Mode" to (m.lteMode ?: "—")
                )
            } else {
                listOf("Status" to "Cell not found in Configuration Dump")
            }
            InfoCard(
                title = "Configuration as per CM Dump",
                rows = cmRows,
                collapsible = true
            )
        }

        if (rat == "WCDMA" && wcdmaCmLoaded) {
            val cmRows = if (wcdmaCmCell != null) {
                val m = wcdmaCmCell
                listOf(
                    "Site" to m.wbtsName,
                    "Cell" to m.wcelName,
                    "PSC" to (m.psc?.toString() ?: "—"),
                    "Tilt" to (m.tiltTenthDeg?.let { "%.1f°".format(it / 10.0) } ?: "—"),
                    "CPICH" to (m.cpichDbm?.let { "$it dBm" } ?: "—"),
                    "PMAX" to (m.pmaxDbm?.let { "$it dBm" } ?: "—"),
                )
            } else {
                listOf("Status" to "Cell not found in Configuration Dump")
            }
            InfoCard(
                title = "Configuration as per CM Dump",
                rows = cmRows,
                collapsible = true
            )
        }

        if (rat == "GSM" && gsmCmLoaded) {
            val cmRows = if (gsmCmCell != null) {
                val m = gsmCmCell
                val trxPowerDbm = m.masterTrxPowerW
                    ?.takeIf { it > 0 }
                    ?.let { "%.0f dBm".format(10.0 * log10(it) + 30.0) }
                    ?: "—"
                listOf(
                    "Site" to m.bcfName,
                    "Cell" to m.cellName,
                    "Bands" to (m.bands ?: "—"),
                    "BCCH" to (m.bcch?.toString() ?: "—"),
                    "NCC / BCC" to "${m.ncc ?: "—"} / ${m.bcc ?: "—"}",
                    "Tilt" to (m.masterTiltTenthDeg?.let { "%.1f°".format(it / 10.0) } ?: "—"),
                    "TRX Power" to trxPowerDbm,
                )
            } else {
                listOf("Status" to "Cell not found in Configuration Dump")
            }
            InfoCard(
                title = "Configuration as per CM Dump",
                rows = cmRows,
                collapsible = true
            )
        }

        // Location
        location?.let { loc ->
            InfoCard(
                title = "Location",
                rows = listOf(
                    "Latitude" to "%.6f".format(loc.latitude),
                    "Longitude" to "%.6f".format(loc.longitude),
                    "Accuracy" to "± %.1f m".format(loc.accuracyMeters),
                    "Altitude" to (loc.altitudeMeters?.let { "%.1f m".format(it) } ?: "—"),
                    "Speed" to (loc.speedMps?.let { "%.1f m/s".format(it) } ?: "—"),
                    "Provider" to loc.provider
                )
            )
        } ?: InfoCard(
            title = "Location",
            rows = listOf("Status" to "Acquiring GPS fix…")
        )

        // Same-RAT neighbours — intra-frequency, top 5, collapsed by default
        if (sim.neighborCells.isNotEmpty()) {
            InfoCard(
                title = "Neighbour Cells (${sim.neighborCells.size})",
                rows = neighbourRows(sim.neighborCells, cmNeighborLookup),
                collapsible = true,
                initiallyExpanded = false
            )
        }
        // Inter-RAT neighbours — different technology, top 5, collapsed by default
        if (sim.interRatNeighborCells.isNotEmpty()) {
            InfoCard(
                title = "Inter-RAT Cells (${sim.interRatNeighborCells.size})",
                rows = neighbourRows(sim.interRatNeighborCells, null),
                collapsible = true,
                initiallyExpanded = false
            )
        }

        Spacer(Modifier.height(24.dp))
        } // end scrollable Column
    } // end outer Column
}

private fun earfcnWithFreq(earfcn: Int?): String {
    if (earfcn == null) return "—"
    val freq = EarfcnUtils.lteDlFreqMhz(earfcn)?.let { " (%.1f MHz)".format(it) } ?: ""
    return "$earfcn$freq"
}

private fun nrArfcnWithFreq(nrarfcn: Int?): String {
    if (nrarfcn == null) return "—"
    val freq = EarfcnUtils.nrDlFreqMhz(nrarfcn)?.let { " (%.0f MHz)".format(it) } ?: ""
    return "$nrarfcn$freq"
}

private fun formatMbps(mbps: Double): String {
    return if (mbps < 0.01) "< 0.01 Mbps" else "%.2f Mbps".format(mbps)
}

private fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private fun neighbourRows(
    cells: List<com.netview.app.data.NeighborCell>,
    cmNeighborLookup: ((Int, Int) -> com.netview.app.data.CmExportCell?)?
): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    cells.forEach { n ->
        when (n.rat) {
            "WCDMA" -> {
                rows += "WCDMA  PSC ${n.psc ?: "—"}" to "${n.band ?: "—"} • UARFCN ${n.uarfcn?.toString() ?: "—"}"
                val sig = buildList {
                    n.rscp?.let { add("RSCP $it dBm") }
                    n.ecNo?.let { add("Ec/No $it dB") }
                }
                if (sig.isNotEmpty()) rows += "  Signal" to sig.joinToString("  ")
            }
            else -> {
                rows += "LTE  PCI ${n.pci ?: "—"}" to "${n.band ?: "—"} • EARFCN ${n.earfcn?.toString() ?: "—"}"
                val sig = buildList {
                    n.rsrp?.let { add("RSRP $it dBm") }
                    n.rsrq?.let { add("RSRQ $it dB") }
                }
                if (sig.isNotEmpty()) rows += "  Signal" to sig.joinToString("  ")
                if (n.pci != null && n.earfcn != null && cmNeighborLookup != null) {
                    cmNeighborLookup(n.pci, n.earfcn)?.let { cm ->
                        val cfg = buildList {
                            cm.rsPowerDbm?.let { add("RS Power $it dBm") }
                            cm.tiltTenthDeg?.let { add("Tilt ${"%.1f".format(it / 10.0)}°") }
                            cm.dlRsBoost?.let { add("RS Boost $it dB") }
                        }
                        if (cfg.isNotEmpty()) rows += "  Config" to cfg.joinToString("  ")
                    }
                }
            }
        }
    }
    return rows
}
