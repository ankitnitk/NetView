package com.netview.app.data

data class CmExportCell(
    val lnbtsId: Int,           // = eNB ID (CID / 256)
    val lncelId: Int,           // = LCR ID / sector ID (CID % 256)
    val lnbtsName: String,      // Site name
    val lncelName: String,      // Cell name
    val pci: Int?,
    val earfcn: Int?,
    val pmaxDbm: Double?,
    val dlRsBoost: Double?,
    val dlMimoMode: String?,
    val tiltTenthDeg: Int?      // Nokia stores in 0.1° units; divide by 10 to display
)
