package com.netview.app.utils

import com.netview.app.BuildConfig
import com.netview.app.data.LocationData
import com.netview.app.data.ServingCellInfo
import com.netview.app.data.SimSlotData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Build a plain-text snapshot of one SIM's cell info for sharing via WhatsApp, email, etc.
 * Includes everything the SIM screen displays (network, serving cell, signal, NR leg,
 * carrier aggregation, location) plus a timestamp.
 */
object ShareFormatter {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

    fun build(sim: SimSlotData, location: LocationData?): String = buildString {
        appendLine("📡 NetView snapshot")
        appendLine("Captured: ${fmt.format(Date())}")
        appendLine()

        // Header
        appendLine("Carrier: ${sim.carrierName}")
        appendLine("SIM: ${sim.displayName} • Slot ${sim.slotIndex + 1}" +
                if (sim.isRoaming) " • Roaming" else "")
        appendLine()

        // Network
        appendLine("── Network ──")
        appendLine("PLMN: ${sim.mcc ?: "—"}/${sim.mnc ?: "—"}")
        appendLine("Network Type: ${sim.networkType}")
        appendLine("Voice: ${sim.voiceTech}")
        appendLine("IMS Registered: ${if (sim.imsRegistered) "Yes" else "No"}")
        appendLine("Roaming: ${if (sim.isRoaming) "Yes" else "No"}")
        appendLine()

        // Serving cell + signal
        sim.servingCell?.let { c ->
            appendLine("── Serving Cell (${c.rat}) ──")
            if (sim.isNonTerrestrial) appendLine("Network Class: Satellite (NTN)")
            when (c.rat) {
                "LTE" -> {
                    appendLine("eNB ID: ${dash(c.enbId)}")
                    appendLine("LCR ID: ${dash(c.sectorId)}")
                    appendLine("Cell ID (CID): ${dash(c.cellId)}")
                    appendLine("PCI: ${dash(c.pci)}")
                    appendLine("TAC: ${dash(c.tac)}")
                    appendLine("EARFCN: ${dash(c.earfcn)}")
                    appendLine("Band: ${c.band ?: "—"}")
                    appendLine("Bandwidth: ${c.bandwidthMhz?.let { "%.1f MHz".format(it) } ?: "—"}")
                    c.duplexMode?.let { appendLine("Duplex Mode: $it") }
                }
                "NR" -> {
                    appendLine("gNB ID: ${dash(c.gnbId)}")
                    appendLine("NCI: ${dash(c.cellId)}")
                    appendLine("PCI: ${dash(c.pci)}")
                    appendLine("TAC: ${dash(c.tac)}")
                    appendLine("NRARFCN: ${dash(c.nrarfcn)}")
                    appendLine("Band: ${c.band ?: "—"}")
                }
                "WCDMA" -> {
                    appendLine("LAC: ${dash(c.tac)}")
                    val ci = c.cellId
                    appendLine("CI: ${dash(ci)}")
                    if (ci != null) {
                        appendLine("RNC ID: ${ci shr 16}")
                        appendLine("CID: ${ci and 0xFFFF}")
                    }
                    appendLine("PSC: ${dash(c.pci)}")
                    appendLine("UARFCN: ${dash(c.uarfcn)}")
                    appendLine("Band: ${c.band ?: "—"}")
                }
                "GSM" -> {
                    appendLine("LAC: ${dash(c.tac)}")
                    appendLine("CID: ${dash(c.cellId)}")
                    appendLine("ARFCN: ${dash(c.arfcn)}")
                    appendLine("BSIC: ${dash(c.bsic)}")
                    appendLine("Band: ${c.band ?: "—"}")
                }
            }
            appendLine()
            appendLine("── Signal ──")
            when (c.rat) {
                "LTE" -> {
                    appendLine("RSRP: ${signed(c.rsrp, "dBm")}")
                    appendLine("RSRQ: ${signed(c.rsrq, "dB")}")
                    appendLine("SINR (RSSNR): ${signed(c.rssnr, "dB")}")
                    appendLine("RSSI: ${signed(c.rssi, "dBm")}")
                    appendLine("CQI: ${dash(c.cqi)}")
                    appendLine("Timing Advance: ${dash(c.timingAdvance)}")
                }
                "NR" -> {
                    appendLine("SS-RSRP: ${signed(c.rsrp, "dBm")}")
                    appendLine("SS-RSRQ: ${signed(c.rsrq, "dB")}")
                    appendLine("SS-SINR: ${signed(c.ssSinr, "dB")}")
                    appendLine("CSI-RSRP: ${signed(c.csiRsrp, "dBm")}")
                    appendLine("CSI-RSRQ: ${signed(c.csiRsrq, "dB")}")
                    appendLine("CSI-SINR: ${signed(c.csiSinr, "dB")}")
                }
                "WCDMA" -> {
                    appendLine("RSCP: ${signed(c.rscp, "dBm")}")
                    appendLine("Ec/No: ${signed(c.ecNo, "dB")}")
                    appendLine("RSSI: ${signed(c.rssi, "dBm")}")
                }
                "GSM" -> {
                    appendLine("RSSI: ${signed(c.rssi, "dBm")}")
                    appendLine("BER: ${dash(c.ber)}")
                    appendLine("Timing Advance: ${dash(c.timingAdvance)}")
                }
            }
            appendLine()
        }

        // 5G NR Leg (NSA)
        sim.nrCell?.let { nr ->
            appendLine("── 5G NR Leg ──")
            nr.pci?.let { appendLine("NR PCI: $it") }
            nr.nrarfcn?.let { appendLine("NR ARFCN: $it") }
            nr.band?.let { appendLine("NR Band: $it") }
            appendLine("SS-RSRP: ${signed(nr.rsrp, "dBm")}")
            appendLine("SS-RSRQ: ${signed(nr.rsrq, "dB")}")
            appendLine("SS-SINR: ${signed(nr.ssSinr, "dB")}")
            appendLine()
        }

        // Carrier Aggregation (LTE/NR only)
        val ratIsLteOrNr = sim.servingCell?.rat == "LTE" || sim.servingCell?.rat == "NR"
        if (ratIsLteOrNr && sim.carrierAggregation.isNotEmpty()) {
            appendLine("── Carrier Aggregation ──")
            val bws = sim.carrierAggregation.mapNotNull { it.bandwidthMhz?.let { v -> "%.0f".format(v) } }
            val totalBw = sim.carrierAggregation.sumOf { it.bandwidthMhz ?: 0.0 }
            appendLine("CA Status: Active • ${sim.carrierAggregation.size}CC")
            if (bws.isNotEmpty()) {
                appendLine("Bandwidths: ${bws.joinToString(" + ")} MHz (Σ ${"%.0f".format(totalBw)} MHz)")
            }
            sim.carrierAggregation.forEach { cc ->
                val bw = cc.bandwidthMhz?.let { " %.1f MHz".format(it) } ?: ""
                val pci = cc.pci?.let { " • PCI $it" } ?: ""
                appendLine("CC${cc.index + 1} (${cc.role}): ${cc.band ?: "—"}$bw$pci")
            }
            appendLine()
        }

        // Location
        location?.let { loc ->
            appendLine("── Location ──")
            appendLine("Lat: %.6f".format(loc.latitude))
            appendLine("Long: %.6f".format(loc.longitude))
            appendLine("Accuracy: ± %.1f m".format(loc.accuracyMeters))
            loc.speedMps?.let { appendLine("Speed: %.1f m/s".format(it)) }
            appendLine("Provider: ${loc.provider}")
        }

        append("— NetView ${BuildConfig.VERSION_NAME}")
    }

    private fun dash(v: Long?): String =
        if (v == null || v <= 0) "—" else v.toString()

    private fun dash(v: Int?): String =
        if (v == null || v < 0) "—" else v.toString()

    private fun signed(v: Int?, unit: String): String =
        if (v == null) "—" else if (unit.isNotEmpty()) "$v $unit" else v.toString()
}
