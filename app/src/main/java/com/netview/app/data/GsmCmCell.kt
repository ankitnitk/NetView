package com.netview.app.data

data class GsmCmCell(
    // Lookup keys
    val lac: Int,
    val cellId: Int,
    // Identity
    val bcfName: String,
    val cellName: String,
    // RF params
    val bands: String?,
    val bcch: Int?,
    val ncc: Int?,
    val bcc: Int?,
    val masterTiltTenthDeg: Int?,
    val masterTrxPowerW: Double?,
    // PLMN filter
    val mcc: Int?,
    val mnc: Int?,
)
