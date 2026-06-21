package com.netview.app.data

/**
 * One serving-cell change for a SIM (handover / reselection / initial camp).
 * Recorded only when the serving cell key changes, so the log stays compact and
 * meaningful for drive-test analysis. `fromNetworkType` is null for the first
 * cell seen in a session.
 */
data class CellChangeEvent(
    val timestampMillis: Long,
    val slotIndex: Int,
    val simLabel: String,
    val fromNetworkType: String?,   // previous network type, null = initial camp
    val networkType: String,        // 2G/3G/4G/5G NSA/5G SA
    val rat: String,                // LTE/NR/WCDMA/GSM
    val enbId: Long?,
    val cellId: Long?,
    val sectorId: Int?,
    val pci: Int?,
    val tac: Int?,
    val arfcn: Int?,                // EARFCN/UARFCN/ARFCN/NRARFCN as applicable
    val band: String?,
    val mcc: String? = null,        // for CM-dump cell-name lookup
    val mnc: String? = null,
    val rsrp: Int?,                 // dBm (RSRP / SS-RSRP / RSCP for 3G / RSSI for 2G)
    val rsrq: Int?,                 // dB  (RSRQ / SS-RSRQ / Ec/No for 3G)
    val sinr: Int?,                 // dB  (RSSNR / SS-SINR)
    val latitude: Double?,
    val longitude: Double?,
)
