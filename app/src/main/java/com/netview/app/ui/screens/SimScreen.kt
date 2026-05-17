package com.netview.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netview.app.data.LocationData
import com.netview.app.data.SimSlotData
import com.netview.app.ui.components.InfoCard
import com.netview.app.ui.components.TechBadge
import com.netview.app.utils.Formatters

@Composable
fun SimScreen(
    sim: SimSlotData,
    location: LocationData?,
    hasPrecisePermission: Boolean = true,
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
            when (c.rat) {
                "LTE" -> {
                    rows += "eNB ID" to Formatters.longOrDash(c.enbId)
                    rows += "LCR ID" to Formatters.intOrDash(c.sectorId)
                    rows += "Cell ID (CID)" to Formatters.longOrDash(c.cellId)
                    rows += "PCI" to Formatters.intOrDash(c.pci)
                    rows += "TAC" to Formatters.intOrDash(c.tac)
                    rows += "EARFCN" to Formatters.intOrDash(c.earfcn)
                    rows += "Band" to Formatters.stringOrDash(c.band)
                    val bw = c.bandwidthMhz
                        ?: sim.carrierAggregation.firstOrNull { it.role == "PCell" }?.bandwidthMhz
                    rows += "Bandwidth" to (bw?.let { "%.1f MHz".format(it) } ?: "—")
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
                    rows += "CID" to Formatters.longOrDash(c.cellId)
                    rows += "PSC" to Formatters.intOrDash(c.pci)
                    rows += "UARFCN" to Formatters.intOrDash(c.uarfcn)
                }
                "GSM" -> {
                    rows += "LAC" to Formatters.intOrDash(c.tac)
                    rows += "CID" to Formatters.longOrDash(c.cellId)
                    rows += "ARFCN" to Formatters.intOrDash(c.arfcn)
                    rows += "BSIC" to Formatters.intOrDash(c.bsic)
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

        // Carrier Aggregation
        if (sim.carrierAggregation.isNotEmpty()) {
            val rows = mutableListOf<Pair<String, String>>()
            rows += "CA Status" to "Active (${sim.carrierAggregation.size} CC)"
            sim.carrierAggregation.forEach { cc ->
                val freq = cc.downlinkFrequencyMhz?.let { " @ %.1f MHz".format(it) } ?: ""
                val bw = cc.bandwidthMhz?.let { " ${it} MHz" } ?: ""
                rows += "CC${cc.index + 1} (${cc.role})" to
                        "${cc.band ?: "—"}$bw • PCI ${Formatters.intOrDash(cc.pci)}$freq"
            }
            InfoCard(title = "Carrier Aggregation", rows = rows)
        } else {
            val caMsg = if (!hasPrecisePermission)
                "Grant 'Precise phone state' permission"
            else
                "None detected"
            InfoCard(
                title = "Carrier Aggregation",
                rows = listOf("CA Status" to caMsg)
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
