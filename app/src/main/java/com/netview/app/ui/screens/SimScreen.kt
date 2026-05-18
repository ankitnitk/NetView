package com.netview.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
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
import com.netview.app.data.LocationData
import com.netview.app.data.SimSlotData
import com.netview.app.ui.components.InfoCard
import com.netview.app.ui.components.TechBadge
import com.netview.app.utils.Formatters
import com.netview.app.utils.ShareFormatter

@Composable
fun SimScreen(
    sim: SimSlotData,
    location: LocationData?,
    cmExportCell: CmExportCell? = null,
    cmExportLoaded: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        val ctx = LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    rows += "EARFCN" to Formatters.intOrDash(c.earfcn)
                    rows += "Band" to Formatters.stringOrDash(c.band)
                    // Bandwidth: cell-identity bandwidth → PCell from CA list (which on
                    // Samsung comes from ServiceState.mCellBandwidths parse)
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
                    rows += "NRARFCN" to Formatters.intOrDash(c.nrarfcn)
                    rows += "Band" to Formatters.stringOrDash(c.band)
                }
                "WCDMA" -> {
                    rows += "LAC" to Formatters.intOrDash(c.tac)
                    // UMTS Cell Identity = RNC_ID (upper 12 bits) + CID (lower 16 bits)
                    // NetMonster: "CI" = full 28-bit, "CID" = lower 16-bit (within RNC).
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
            InfoCard(title = "Serving Cell", rows = rows)

            // Signal
            val sig = mutableListOf<Pair<String, String>>()
            when (c.rat) {
                "LTE" -> {
                    sig += "RSRP" to Formatters.signedOrDash(c.rsrp, "dBm")
                    sig += "RSRQ" to Formatters.signedOrDash(c.rsrq, "dB")
                    sig += "SINR (RSSNR)" to Formatters.signedOrDash(c.rssnr, "dB")
                    sig += "RSSI" to Formatters.signedOrDash(c.rssi, "dBm")
                    sig += "CQI" to Formatters.intOrDash(c.cqi)
                    sig += "Timing Advance" to Formatters.intOrDash(c.timingAdvance)
                }
                "NR" -> {
                    sig += "SS-RSRP" to Formatters.signedOrDash(c.rsrp, "dBm")
                    sig += "SS-RSRQ" to Formatters.signedOrDash(c.rsrq, "dB")
                    sig += "SS-SINR" to Formatters.signedOrDash(c.ssSinr, "dB")
                    sig += "CSI-RSRP" to Formatters.signedOrDash(c.csiRsrp, "dBm")
                    sig += "CSI-RSRQ" to Formatters.signedOrDash(c.csiRsrq, "dB")
                    sig += "CSI-SINR" to Formatters.signedOrDash(c.csiSinr, "dB")
                }
                "WCDMA" -> {
                    sig += "RSCP" to Formatters.signedOrDash(c.rscp, "dBm")
                    sig += "Ec/No" to Formatters.signedOrDash(c.ecNo, "dB")
                    sig += "RSSI" to Formatters.signedOrDash(c.rssi, "dBm")
                }
                "GSM" -> {
                    sig += "RSSI" to Formatters.signedOrDash(c.rssi, "dBm")
                    sig += "BER" to Formatters.intOrDash(c.ber)
                    sig += "Timing Advance" to Formatters.intOrDash(c.timingAdvance)
                }
            }
            InfoCard(title = "Signal", rows = sig)
        } ?: InfoCard(
            title = "Serving Cell",
            rows = listOf("Status" to "No cell info — check permissions")
        )

        // 5G NR leg (NSA only — companion to the LTE anchor)
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
            InfoCard(title = "5G NR Leg", rows = rows)
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
            InfoCard(title = "Carrier Aggregation", rows = rows)
        } else if (ratIsLteOrNr) {
            InfoCard(
                title = "Carrier Aggregation",
                rows = listOf("CA Status" to "—")
            )
        }
        // No CA card at all when on 2G/3G — concept doesn't apply.

        // Configuration as per CM Dump
        if (cmExportLoaded) {
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
                    "Band Count" to (m.bandCount?.toString() ?: "—"),
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

        Spacer(Modifier.height(24.dp))
    }
}
