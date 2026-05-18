package com.netview.app.data

data class CmExportCell(
    // Match keys
    val lnbtsId: Int,           // = eNB ID (CID / 256)
    val lncelId: Int,           // = LCR ID / sector ID (CID % 256)
    // Cell identity
    val lnbtsName: String,      // Site name
    val lncelName: String,      // Cell name
    val pci: Int?,
    val earfcn: Int?,
    // Cell-level RF params
    val pmaxDbm: Double?,
    val dlRsBoost: Double?,
    val rsPowerDbm: Double?,
    val dlMimoMode: String?,
    val tiltTenthDeg: Int?,     // Nokia stores in 0.1° units; divide by 10 to display
    // Cell-level config
    val sibPriority: Int?,
    val irfimList: String?,
    val lnhoifList: String?,
    val caprList: String?,
    // Site-level params (from LNBTS Details sheet, joined by LNBTS ID)
    val lncelCount: Int?,
    val bandCount: Int?,
    val bandList: String?,
    val lteMode: String?
)
