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

    fun lookup(pci: Int, earfcn: Int): CmExportCell? = _cells.value[Pair(pci, earfcn)]

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

            val sheetTarget = findSheetFile(wbBytes, relsBytes, "LNCEL Details")
            val sheetBytes = buffers["xl/$sheetTarget"]
                ?: return@withContext Result.failure(Exception("LNCEL Details sheet not found (expected xl/$sheetTarget)"))

            val strings = parseSharedStrings(ssBytes.inputStream())
            val parsed = parseLncelSheet(sheetBytes.inputStream(), strings)

            // Index by (PCI, EARFCN). Duplicate keys (same PCI+EARFCN across different sectors
            // in the export) keep the last entry — this is rare in practice.
            val map = parsed.associateBy { Pair(it.pci, it.earfcn) }
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

    private fun parseLncelSheet(input: InputStream, strings: List<String>): List<CmExportCell> {
        val result = mutableListOf<CmExportCell>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")

        // col index (0-based) → field name, populated from header row
        var colMap = emptyMap<Int, String>()
        var currentRow = 0
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
                    "row" -> {
                        currentRow = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: 0
                        currentCells = mutableMapOf()
                    }
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
                            val decoded = if (currentType == "s") {
                                raw.toIntOrNull()?.let { strings.getOrNull(it) } ?: ""
                            } else raw
                            currentCells[currentColIdx] = decoded
                        }
                        inValue = false
                    }
                    "c" -> inCell = false
                    "row" -> {
                        when {
                            currentRow == 1 -> colMap = currentCells.mapValues { it.value }
                            currentRow > 1 && colMap.isNotEmpty() -> {
                                decodeRow(currentCells, colMap)?.let { result.add(it) }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun decodeRow(row: Map<Int, String>, colMap: Map<Int, String>): CmExportCell? {
        // Invert colMap to field-name → value for this row
        val fields = row.entries.associate { (col, value) -> (colMap[col] ?: "") to value }

        val pci = fields["PCI"]?.toIntOrNull() ?: return null
        val earfcn = fields["EARFCN DL"]?.toIntOrNull() ?: return null
        val lnbtsName = fields["LNBTS Name"]?.takeIf { it.isNotBlank() } ?: return null
        val lncelName = fields["LNCEL Name"]?.takeIf { it.isNotBlank() } ?: return null

        return CmExportCell(
            lnbtsId = fields["LNBTS ID"]?.toIntOrNull() ?: 0,
            lnbtsName = lnbtsName,
            lncelName = lncelName,
            pci = pci,
            earfcn = earfcn,
            pmaxDbm = fields["PMAX (dBm)"]?.toDoubleOrNull(),
            dlRsBoost = fields["dlRsBoost"]?.toDoubleOrNull(),
            dlMimoMode = fields["DL MIMO Mode"]?.takeIf { it.isNotBlank() },
            tiltTenthDeg = fields["Tilt"]?.toIntOrNull()
        )
    }

    private fun colLetterToIndex(letters: String): Int {
        var result = 0
        for (ch in letters.uppercase()) result = result * 26 + (ch - 'A' + 1)
        return result - 1  // 0-based: A→0, B→1, ..., Z→25, AA→26
    }
}
