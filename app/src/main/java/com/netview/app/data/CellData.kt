package com.netview.app.data

/**
 * Container for all per-SIM telephony info shown in NetView.
 */
data class SimSlotData(
    val subId: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String,
    val mcc: String?,
    val mnc: String?,
    val isRoaming: Boolean,
    val networkType: String,       // 2G/3G/4G/5G NSA/5G SA
    val voiceTech: String,         // VoLTE / VoNR / CS
    val imsRegistered: Boolean,
    val servingCell: ServingCellInfo?,
    val carrierAggregation: List<CarrierComponent> = emptyList()
)

/**
 * Holds the parameters of the currently registered serving cell.
 * Some fields are null depending on the radio access tech (RAT).
 */
data class ServingCellInfo(
    val rat: String,           // LTE, NR, WCDMA, GSM
    // Common
    val mcc: String?,
    val mnc: String?,
    val pci: Int?,
    val tac: Int?,
    val cellId: Long?,
    val enbId: Long?,          // LTE eNB derived from CID
    val gnbId: Long?,          // 5G gNB derived from NCI
    val sectorId: Int?,        // LTE sector
    val earfcn: Int?,          // LTE
    val nrarfcn: Int?,         // NR
    val uarfcn: Int?,          // WCDMA
    val arfcn: Int?,           // GSM
    val band: String?,
    val bandwidthMhz: Double?,
    // Signal
    val rsrp: Int?,            // LTE / NR (SS-RSRP)
    val rsrq: Int?,            // LTE / NR (SS-RSRQ)
    val rssnr: Int?,           // LTE SINR
    val ssSinr: Int?,          // NR SS-SINR
    val csiRsrp: Int?,
    val csiRsrq: Int?,
    val csiSinr: Int?,
    val rscp: Int?,            // WCDMA
    val ecNo: Int?,            // WCDMA
    val rssi: Int?,            // Universal
    val cqi: Int?,
    val timingAdvance: Int?,
    val bsic: Int?,            // GSM
    val ber: Int?              // GSM
)

/**
 * One component carrier in carrier aggregation.
 */
data class CarrierComponent(
    val index: Int,
    val role: String,           // PCell / SCell / SS-SCell
    val band: String?,
    val bandwidthMhz: Double?,
    val pci: Int?,
    val earfcn: Int?,
    val downlinkFrequencyMhz: Double?
)

/** GPS / location snapshot. */
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val provider: String,
    val timestampMillis: Long
)
