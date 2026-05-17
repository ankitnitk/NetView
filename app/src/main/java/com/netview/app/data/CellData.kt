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
    val nrCell: ServingCellInfo? = null,   // NR leg on NSA — shown alongside LTE anchor
    val carrierAggregation: List<CarrierComponent> = emptyList(),
    val isNonTerrestrial: Boolean = false, // true when device is on a satellite (NTN) cell
    val diagnostics: DiagnosticInfo = DiagnosticInfo()
)

/** Diagnostic counters surfaced in the UI so we can see what Samsung is actually giving us. */
data class DiagnosticInfo(
    val cellInfoTotal: Int = 0,
    val cellInfoLte: Int = 0,
    val cellInfoNr: Int = 0,
    val cellsWithValidCi: Int = 0,       // how many of cellInfoLte have non-sanitized CI
    val signalStrengthsTotal: Int = 0,
    val signalStrengthsLte: Int = 0,
    val signalStrengthsNr: Int = 0,
    val tcRegistered: Boolean = false,
    val tcFires: Int = 0,
    val pslRegistered: Boolean = false,
    val pslFires: Int = 0,
    val serviceStateCaHint: String? = null   // parsed from ServiceState.toString()
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
    val ber: Int?,             // GSM
    val duplexMode: String? = null // FDD / TDD (LTE serving cell only)
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
    val downlinkFrequencyMhz: Double?,
    val mimoLayers: Int? = null, // MIMO rank from PhysicalChannelConfig
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val rssnr: Int? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null
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
