package com.netview.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

internal object XlsxSheetParser {

    fun parseSharedStrings(input: InputStream): List<String> {
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

    /**
     * Returns list of rows; each row is Map<colIndex, cellValue>.
     * Row 0 is the header row (column names).
     */
    fun parseSheetRows(input: InputStream, strings: List<String>): List<Map<Int, String>> {
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

    fun colLetterToIndex(letters: String): Int {
        var result = 0
        for (ch in letters.uppercase()) result = result * 26 + (ch - 'A' + 1)
        return result - 1
    }

    /**
     * Returns a Map<colName, cellValue> for each data row, using row 0 as header.
     * Keys are case-insensitive so minor header re-casing in the export
     * (e.g. "LNCEL Name" -> "LNCEL name") doesn't break column lookups.
     */
    fun rowsToFieldMaps(rows: List<Map<Int, String>>): List<Map<String, String>> {
        if (rows.isEmpty()) return emptyList()
        val colMap = rows[0]
        return (1 until rows.size).map { i ->
            val m = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
            rows[i].forEach { (col, value) -> m[colMap[col] ?: ""] = value }
            m
        }
    }
}
