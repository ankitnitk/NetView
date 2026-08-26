package com.netview.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

class CmExportRepository {

    private val _cells = MutableStateFlow<Map<Pair<Int, Int>, CmExportCell>>(emptyMap())
    val cells: StateFlow<Map<Pair<Int, Int>, CmExportCell>> = _cells

    /** Match on eNB ID + LCR/sector ID, then confirm MCC/MNC when both sides have the value. */
    fun lookup(enbId: Int, lncelId: Int, mcc: Int?, mnc: Int?): CmExportCell? {
        val cell = _cells.value[Pair(enbId, lncelId)] ?: return null
        if (mcc != null && cell.mcc != null && cell.mcc != mcc) return null
        if (mnc != null && cell.mnc != null && cell.mnc != mnc) return null
        return cell
    }

    /** Lookup by PCI + EARFCN for neighbour cross-reference. Returns null if ambiguous (>1 match). */
    fun lookupByPciEarfcn(pci: Int, earfcn: Int): CmExportCell? {
        val matches = _cells.value.values.filter { it.pci == pci && it.earfcn == earfcn }
        return if (matches.size == 1) matches[0] else null
    }

    fun clear() { _cells.value = emptyMap() }

    val size: Int get() = _cells.value.size

    suspend fun load(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // Read all needed zip entries in one pass (sharedStrings.xml comes after
            // the worksheet XMLs in the zip, so we buffer everything we need).
            val buffers = mutableMapOf<String, ByteArray>()
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name
                            if (name == "xl/workbook.xml" ||
                                name == "xl/_rels/workbook.xml.rels" ||
                                name == "xl/sharedStrings.xml" ||
                                name.startsWith("xl/worksheets/")
                            ) {
                                buffers[name] = zip.readBytes()
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return@withContext Result.failure(Exception("Cannot open file"))

            val wbBytes = buffers["xl/workbook.xml"]
                ?: return@withContext Result.failure(Exception("workbook.xml not found"))
            val relsBytes = buffers["xl/_rels/workbook.xml.rels"]
                ?: return@withContext Result.failure(Exception("workbook.xml.rels not found"))
            val ssBytes = buffers["xl/sharedStrings.xml"]
                ?: return@withContext Result.failure(Exception("sharedStrings.xml not found"))

            val lncelTarget = findSheetFile(wbBytes, relsBytes, "LNCEL Details")
            val lncelBytes = buffers["xl/$lncelTarget"]
                ?: return@withContext Result.failure(Exception("LNCEL Details sheet not found (expected xl/$lncelTarget)"))

            val lnbtsTarget = findSheetFile(wbBytes, relsBytes, "LNBTS Details")
            val lnbtsBytes = buffers["xl/$lnbtsTarget"]
                ?: return@withContext Result.failure(Exception("LNBTS Details sheet not found (expected xl/$lnbtsTarget)"))

            val strings = XlsxSheetParser.parseSharedStrings(ssBytes.inputStream())
            val siteMap = parseLnbtsSheet(lnbtsBytes.inputStream(), strings)
            val parsed = parseLncelSheet(lncelBytes.inputStream(), strings, siteMap)

            val map = parsed.associateBy { Pair(it.lnbtsId, it.lncelId) }
            _cells.value = map
            Result.success(map.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findSheetFile(workbookBytes: ByteArray, relsBytes: ByteArray, sheetName: String): String {
        val workbookXml = String(workbookBytes)
        val relsXml = String(relsBytes)
        val ridRegex = Regex("""<sheet\b[^>]*name="${Regex.escape(sheetName)}"[^>]*>""")
        val sheetTag = ridRegex.find(workbookXml)?.value
            ?: throw IllegalArgumentException("Sheet '$sheetName' not found in workbook.xml")
        val rid = Regex("""r:id="([^"]+)"""").find(sheetTag)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("r:id not found in sheet tag: $sheetTag")
        val relTag = Regex("""<Relationship\b[^>]*Id="${Regex.escape(rid)}"[^>]*>""").find(relsXml)?.value
            ?: throw IllegalArgumentException("Relationship $rid not found in rels")
        return Regex("""Target="([^"]+)"""").find(relTag)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Target not found in relationship: $relTag")
    }

    private fun parseLnbtsSheet(input: java.io.InputStream, strings: List<String>): Map<Int, Map<String, String>> {
        val result = mutableMapOf<Int, Map<String, String>>()
        val fieldMaps = XlsxSheetParser.rowsToFieldMaps(XlsxSheetParser.parseSheetRows(input, strings))
        for (fields in fieldMaps) {
            val lnbtsId = fields["LNBTS ID"]?.toIntOrNull() ?: continue
            result[lnbtsId] = fields
        }
        return result
    }

    private fun parseLncelSheet(
        input: java.io.InputStream,
        strings: List<String>,
        siteMap: Map<Int, Map<String, String>>
    ): List<CmExportCell> {
        val result = mutableListOf<CmExportCell>()
        val fieldMaps = XlsxSheetParser.rowsToFieldMaps(XlsxSheetParser.parseSheetRows(input, strings))
        for (fields in fieldMaps) {
            val lnbtsId = fields["LNBTS ID"]?.toIntOrNull() ?: continue
            val lncelId = fields["LNCEL ID"]?.toIntOrNull() ?: continue
            val lnbtsName = fields["LNBTS Name"]?.takeIf { it.isNotBlank() } ?: continue
            // Newer exports renamed this column "LNCEL name" (band+sector, e.g.
            // NAIROBI_KAWANGWARE_L8_A). Case-insensitive maps also match the older
            // "LNCEL Name". The separate "Name" column (…_1 style) is intentionally not used.
            val lncelName = fields["LNCEL name"]?.takeIf { it.isNotBlank() } ?: continue
            val site = siteMap[lnbtsId]
            result.add(CmExportCell(
                lnbtsId = lnbtsId,
                lncelId = lncelId,
                lnbtsName = lnbtsName,
                lncelName = lncelName,
                pci = fields["PCI"]?.toIntOrNull(),
                earfcn = fields["EARFCN DL"]?.toIntOrNull(),
                pmaxDbm = fields["PMAX (dBm)"]?.toDoubleOrNull(),
                dlRsBoost = fields["dlRsBoost"]?.toDoubleOrNull(),
                rsPowerDbm = fields["RS Power (dBm)"]?.toDoubleOrNull(),
                dlMimoMode = fields["DL MIMO Mode"]?.takeIf { it.isNotBlank() },
                tiltTenthDeg = fields["Tilt"]?.toIntOrNull(),
                sibPriority = fields["SIB Priority"]?.toIntOrNull(),
                irfimList = fields["IRFIM {Prio} List"]?.takeIf { it.isNotBlank() },
                lnhoifList = fields["LNHOIF List"]?.takeIf { it.isNotBlank() },
                caprList = fields["CAPR {Prio} List"]?.takeIf { it.isNotBlank() },
                mcc = fields["MCC"]?.toIntOrNull(),
                mnc = fields["MNC"]?.toIntOrNull(),
                lncelCount = site?.get("LNCEL Count")?.toIntOrNull(),
                bandCount = site?.get("Band Count")?.toIntOrNull(),
                bandList = site?.get("Band List")?.takeIf { it.isNotBlank() },
                lteMode = site?.get("LTE Mode")?.takeIf { it.isNotBlank() }
            ))
        }
        return result
    }
}
