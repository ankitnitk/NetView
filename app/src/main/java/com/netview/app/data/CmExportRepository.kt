package com.netview.app.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
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

            val strings = parseSharedStrings(ssBytes.inputStream())
            // Parse site-level data first so it can be joined into each cell row
            val siteMap = parseLnbtsSheet(lnbtsBytes.inputStream(), strings)
            val parsed = parseLncelSheet(lncelBytes.inputStream(), strings, siteMap)

            // Index by (LNBTS ID, LNCEL ID) = (eNB ID, sector/LCR ID). Unique per cell.
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

        // Find r:id for the named sheet (attribute order can vary)
        val ridRegex = Regex("""<sheet\b[^>]*name="${Regex.escape(sheetName)}"[^>]*>""")
        val sheetTag = ridRegex.find(workbookXml)?.value
            ?: throw IllegalArgumentException("Sheet '$sheetName' not found in workbook.xml")
        val rid = Regex("""r:id="([^"]+)"""").find(sheetTag)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("r:id not found in sheet tag: $sheetTag")

        // Find Target for this r:id
        val relTag = Regex("""<Relationship\b[^>]*Id="${Regex.escape(rid)}"[^>]*>""").find(relsXml)?.value
            ?: throw IllegalArgumentException("Relationship $rid not found in rels")
        return Regex("""Target="([^"]+)"""").find(relTag)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Target not found in relationship: $relTag")
    }

    private fun parseSharedStrings(input: InputStream): List<String> {
        val strings = mutableListOf<String>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")

        var inSi = false
        var inT = false
        val buf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inSi = true; buf.clear() }
                    "t" -> if (inSi) inT = true
                }
                XmlPullParser.TEXT -> if (inT) buf.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inT = false
                    "si" -> { strings.add(buf.toString()); inSi = false }
                }
            }
            event = parser.next()
        }
        return strings
    }

    /** Parse LNBTS Details sheet → Map<LNBTS ID, field map> for site-level join. */
    private fun parseLnbtsSheet(input: InputStream, strings: List<String>): Map<Int, Map<String, String>> {
        val result = mutableMapOf<Int, Map<String, String>>()
        val rows = parseSheetRows(input, strings)
        if (rows.isEmpty()) return result
        val colMap = rows[0]
        for (i in 1 until rows.size) {
            val fields = rows[i].entries.associate { (col, value) -> (colMap[col] ?: "") to value }
            val lnbtsId = fields["LNBTS ID"]?.toIntOrNull() ?: continue
            result[lnbtsId] = fields
        }
        return result
    }

    private fun parseLncelSheet(
        input: InputStream,
        strings: List<String>,
        siteMap: Map<Int, Map<String, String>>
    ): List<CmExportCell> {
        val result = mutableListOf<CmExportCell>()
        val rows = parseSheetRows(input, strings)
        if (rows.isEmpty()) return result
        val colMap = rows[0]
        for (i in 1 until rows.size) {
            val fields = rows[i].entries.associate { (col, value) -> (colMap[col] ?: "") to value }
            val lnbtsId = fields["LNBTS ID"]?.toIntOrNull() ?: continue
            val lncelId = fields["LNCEL ID"]?.toIntOrNull() ?: continue
            val lnbtsName = fields["LNBTS Name"]?.takeIf { it.isNotBlank() } ?: continue
            val lncelName = fields["LNCEL Name"]?.takeIf { it.isNotBlank() } ?: continue
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

    /**
     * Generic sheet parser — returns a list of rows where each row is
     * Map<colIndex, cellValue>. Row 0 is the header row (values are column names).
     */
    private fun parseSheetRows(input: InputStream, strings: List<String>): List<Map<Int, String>> {
        val rows = mutableListOf<Map<Int, String>>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")

        var currentCells = mutableMapOf<Int, String>()
        var inCell = false
        var inValue = false
        var currentColIdx = 0
        var currentType = ""
        val valueBuf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentCells = mutableMapOf()
                    "c" -> {
                        val ref = parser.getAttributeValue(null, "r") ?: ""
                        currentColIdx = colLetterToIndex(ref.takeWhile { it.isLetter() })
                        currentType = parser.getAttributeValue(null, "t") ?: ""
                        inCell = true
                    }
                    "v" -> if (inCell) { inValue = true; valueBuf.clear() }
                }
                XmlPullParser.TEXT -> if (inValue) valueBuf.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> {
                        if (inValue) {
                            val raw = valueBuf.toString()
                            val decoded = if (currentType == "s")
                                raw.toIntOrNull()?.let { strings.getOrNull(it) } ?: "" else raw
                            currentCells[currentColIdx] = decoded
                        }
                        inValue = false
                    }
                    "c" -> inCell = false
                    "row" -> rows.add(currentCells.toMap())
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun colLetterToIndex(letters: String): Int {
        var result = 0
        for (ch in letters.uppercase()) result = result * 26 + (ch - 'A' + 1)
        return result - 1  // 0-based: A→0, B→1, ..., Z→25, AA→26
    }
}
