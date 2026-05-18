package com.netview.app.data

data class WcdmaCmCell(
    // Lookup keys
    val rncId: Int,
    val wcelId: Int,
    val uarfcn: Int,
    // Identity
    val wbtsName: String,
    val wcelName: String,
    // RF params
    val psc: Int?,
    val tiltTenthDeg: Int?,
    val cpichDbm: Int?,
    val pmaxDbm: Int?,
    // PLMN filter
    val mcc: Int?,
    val mnc: Int?,
)
