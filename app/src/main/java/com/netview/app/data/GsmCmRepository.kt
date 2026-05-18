package com.netview.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

class GsmCmRepository {

    private val _cells = MutableStateFlow<Map<Pair<Int, Int>, GsmCmCell>>(emptyMap())
    val cells: StateFlow<Map<Pair<Int, Int>, GsmCmCell>> = _cells

    /** Match on LAC + Cell ID, confirm MCC/MNC when both sides have the value. */
    fun lookup(lac: Int, cellId: Int, mcc: Int?, mnc: Int?): GsmCmCell? {
        val cell = _cells.value[Pair(lac, cellId)] ?: return null
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

            val parsed = mutableListOf<GsmCmCell>()
            for (fields in fieldMaps) {
                val lac = fields["LAC"]?.toIntOrNull() ?: continue
                val cellId = fields["Cell ID"]?.toIntOrNull() ?: continue
                val bcfName = fields["BCF Name"]?.takeIf { it.isNotBlank() } ?: continue
                val cellName = fields["Cell Name"]?.takeIf { it.isNotBlank() } ?: continue
                parsed.add(GsmCmCell(
                    lac = lac,
                    cellId = cellId,
                    bcfName = bcfName,
                    cellName = cellName,
                    bands = fields["Bands"]?.takeIf { it.isNotBlank() },
                    bcch = fields["BCCH"]?.toIntOrNull(),
                    ncc = fields["NCC"]?.toIntOrNull(),
                    bcc = fields["BCC"]?.toIntOrNull(),
                    masterTiltTenthDeg = fields["Master Tilt"]?.toIntOrNull(),
                    masterTrxPowerW = fields["Master TRX Power (W)"]?.toDoubleOrNull(),
                    mcc = fields["MCC"]?.toIntOrNull(),
                    mnc = fields["MNC"]?.toIntOrNull(),
                ))
            }

            val map = parsed.associateBy { Pair(it.lac, it.cellId) }
            _cells.value = map
            Result.success(map.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
