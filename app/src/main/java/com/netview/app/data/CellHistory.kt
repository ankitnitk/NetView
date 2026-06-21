package com.netview.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Session-scoped log of serving-cell changes per SIM. In-memory only — it is NOT
 * persisted across app restarts (keeping a multi-day log proved unhelpful). Holds
 * the most recent [MAX_EVENTS] changes and is surfaced as a StateFlow for the
 * Cell History screen. Export to CSV before closing the app to keep a record.
 */
object CellHistory {
    private const val MAX_EVENTS = 200
    private val buffer = ConcurrentLinkedDeque<CellChangeEvent>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var enabled: Boolean = false

    private val _events = MutableStateFlow<List<CellChangeEvent>>(emptyList())
    val events: StateFlow<List<CellChangeEvent>> = _events

    fun record(event: CellChangeEvent) {
        if (!enabled) return
        buffer.addLast(event)
        while (buffer.size > MAX_EVENTS) buffer.pollFirst()
        _events.value = buffer.toList()
    }

    fun clear() {
        buffer.clear()
        _events.value = emptyList()
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
}
