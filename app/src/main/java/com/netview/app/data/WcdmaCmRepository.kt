package com.netview.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

class WcdmaCmRepository {

    private val _cells = MutableStateFlow<Map<Triple<Int, Int, Int>, WcdmaCmCell>>(emptyMap())
    val cells: StateFlow<Map<Triple<Int, Int, Int>, WcdmaCmCell>> = _cells

    /** Match on RNC ID + WCEL ID + UARFCN, confirm MCC/MNC when both sides have the value. */
    fun lookup(rncId: Int, wcelId: Int, uarfcn: Int, mcc: Int?, mnc: Int?): WcdmaCmCell? {
        val cell = _cells.value[Triple(rncId, wcelId, uarfcn)] ?: return null
        if (mcc != null && cell.mcc != null && cell.mcc != mcc) return null
        if (mnc != null && cell.mnc != null && cell.mnc != mnc) return null
        return cell
    }

    fun clear() { _cells.value = emptyMap() }

    val size: Int get() = _cells.value.size

    suspend fun load(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val buffers = mutableMapOf<String, ByteArray>()
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name
                            if (name == "xl/sharedStrings.xml" || name.startsWith("xl/worksheets/")) {
                                buffers[name] = zip.readBytes()
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return@withContext Result.failure(Exception("Cannot open file"))

            val sheetKey = buffers.keys.firstOrNull { it.startsWith("xl/worksheets/") }
                ?: return@withContext Result.failure(Exception("No worksheets found in file"))

            val strings = buffers["xl/sharedStrings.xml"]
                ?.let { XlsxSheetParser.parseSharedStrings(it.inputStream()) }
                ?: emptyList()

            val rows = XlsxSheetParser.parseSheetRows(buffers[sheetKey]!!.inputStream(), strings)
            val fieldMaps = XlsxSheetParser.rowsToFieldMaps(rows)

            val parsed = mutableListOf<WcdmaCmCell>()
            for (fields in fieldMaps) {
                val rncId = fields["RNC ID"]?.toIntOrNull() ?: continue
                val wcelId = fields["WCEL ID"]?.toIntOrNull() ?: continue
                val uarfcn = fields["UARFCN"]?.toIntOrNull() ?: continue
                val wbtsName = fields["WBTS Name"]?.takeIf { it.isNotBlank() } ?: continue
                val wcelName = fields["WCEL Name"]?.takeIf { it.isNotBlank() } ?: continue
                parsed.add(WcdmaCmCell(
                    rncId = rncId,
                    wcelId = wcelId,
                    uarfcn = uarfcn,
                    wbtsName = wbtsName,
                    wcelName = wcelName,
                    psc = fields["PSC"]?.toIntOrNull(),
                    tiltTenthDeg = fields["Tilt"]?.toIntOrNull(),
                    cpichDbm = fields["CPICH"]?.toIntOrNull(),
                    pmaxDbm = fields["PMAX"]?.toIntOrNull(),
                    mcc = fields["MCC"]?.toIntOrNull(),
                    mnc = fields["MNC"]?.toIntOrNull(),
                ))
            }

            val map = parsed.associateBy { Triple(it.rncId, it.wcelId, it.uarfcn) }
            _cells.value = map
            Result.success(map.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
