package com.netview.app.data

data class CmExportCell(
    val lnbtsId: Int,
    val lnbtsName: String,      // Site name
    val lncelName: String,      // Cell name
    val pci: Int,
    val earfcn: Int,
    val pmaxDbm: Double?,
    val dlRsBoost: Double?,
    val dlMimoMode: String?,
    val tiltTenthDeg: Int?      // Nokia stores in 0.1° units; divide by 10 to display
)
