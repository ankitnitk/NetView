package com.netview.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Log of serving-cell changes per SIM. Persisted to an internal TSV file so a
 * long drive survives the process being killed, and reloaded on startup. The
 * live view is a capped in-memory buffer surfaced as a StateFlow.
 */
object CellHistory {
    private const val MAX_EVENTS = 5000
    private const val SEP = "\t"
    private val buffer = ConcurrentLinkedDeque<CellChangeEvent>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null

    @Volatile var enabled: Boolean = false

    private val _events = MutableStateFlow<List<CellChangeEvent>>(emptyList())
    val events: StateFlow<List<CellChangeEvent>> = _events

    /** Bind the persistence file and load any existing events. Call once at startup. */
    fun attach(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "cell_history.tsv")
        file = f
        io.launch {
            if (f.exists()) {
                val loaded = runCatching { f.readLines().mapNotNull { parseLine(it) } }.getOrDefault(emptyList())
                if (loaded.isNotEmpty()) {
                    buffer.clear()
                    buffer.addAll(loaded.takeLast(MAX_EVENTS))
                    _events.value = buffer.toList()
                }
            }
        }
    }

    fun record(event: CellChangeEvent) {
        if (!enabled) return
        buffer.addLast(event)
        while (buffer.size > MAX_EVENTS) buffer.pollFirst()
        _events.value = buffer.toList()
        val f = file ?: return
        io.launch { runCatching { f.appendText(serialize(event) + "\n") } }
    }

    fun clear() {
        buffer.clear()
        _events.value = emptyList()
        val f = file ?: return
        io.launch { runCatching { f.writeText("") } }
    }

    /** CSV for the given slot (or all slots if null), Excel-friendly. */
    fun toCsv(slotIndex: Int? = null): String {
        val header = "Time,SIM,From,Type,RAT,eNB/gNB,CID,Sector,PCI,TAC,ARFCN,Band,RSRP,RSRQ,SINR,Latitude,Longitude"
        val rows = buffer
            .filter { slotIndex == null || it.slotIndex == slotIndex }
            .joinToString("\n") { e ->
                listOf(
                    fmt.format(Date(e.timestampMillis)),
                    e.simLabel,
                    e.fromNetworkType ?: "",
                    e.networkType,
                    e.rat,
                    e.enbId?.toString() ?: "",
                    e.cellId?.toString() ?: "",
                    e.sectorId?.toString() ?: "",
                    e.pci?.toString() ?: "",
                    e.tac?.toString() ?: "",
                    e.arfcn?.toString() ?: "",
                    e.band ?: "",
                    e.rsrp?.toString() ?: "",
                    e.rsrq?.toString() ?: "",
                    e.sinr?.toString() ?: "",
                    e.latitude?.let { "%.6f".format(it) } ?: "",
                    e.longitude?.let { "%.6f".format(it) } ?: "",
                ).joinToString(",")
            }
        return "$header\n$rows"
    }

    // ---- TSV persistence (tab-separated to tolerate commas in carrier names) ----

    private fun serialize(e: CellChangeEvent): String = listOf(
        e.timestampMillis.toString(),
        e.slotIndex.toString(),
        e.simLabel.replace(SEP, " "),
        e.fromNetworkType ?: "",
        e.networkType,
        e.rat,
        e.enbId?.toString() ?: "",
        e.cellId?.toString() ?: "",
        e.sectorId?.toString() ?: "",
        e.pci?.toString() ?: "",
        e.tac?.toString() ?: "",
        e.arfcn?.toString() ?: "",
        e.band ?: "",
        e.rsrp?.toString() ?: "",
        e.rsrq?.toString() ?: "",
        e.sinr?.toString() ?: "",
        e.latitude?.toString() ?: "",
        e.longitude?.toString() ?: "",
    ).joinToString(SEP)

    private fun parseLine(line: String): CellChangeEvent? {
        val p = line.split(SEP)
        if (p.size < 18) return null
        return runCatching {
            CellChangeEvent(
                timestampMillis = p[0].toLong(),
                slotIndex = p[1].toInt(),
                simLabel = p[2],
                fromNetworkType = p[3].ifEmpty { null },
                networkType = p[4],
                rat = p[5],
                enbId = p[6].toLongOrNull(),
                cellId = p[7].toLongOrNull(),
                sectorId = p[8].toIntOrNull(),
                pci = p[9].toIntOrNull(),
                tac = p[10].toIntOrNull(),
                arfcn = p[11].toIntOrNull(),
                band = p[12].ifEmpty { null },
                rsrp = p[13].toIntOrNull(),
                rsrq = p[14].toIntOrNull(),
                sinr = p[15].toIntOrNull(),
                latitude = p[16].toDoubleOrNull(),
                longitude = p[17].toDoubleOrNull(),
            )
        }.getOrNull()
    }
}
