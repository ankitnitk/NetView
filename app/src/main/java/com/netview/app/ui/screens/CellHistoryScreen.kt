package com.netview.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netview.app.data.CellChangeEvent
import com.netview.app.data.CellHistory
import com.netview.app.data.SimSlotData
import com.netview.app.utils.SignalQuality
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellHistoryScreen(
    sims: List<SimSlotData>,
    initialSlot: Int,
    onBack: () -> Unit
) {
    val events by CellHistory.events.collectAsState()
    val ctx = LocalContext.current

    // Tabs come from the active SIMs; fall back to slots seen in the log.
    val slots = remember(sims, events) {
        (sims.map { it.slotIndex } + events.map { it.slotIndex }).distinct().sorted()
    }
    var selectedSlot by remember(slots) {
        mutableStateOf(if (slots.contains(initialSlot)) initialSlot else slots.firstOrNull() ?: 0)
    }
    val slotLabel: (Int) -> String = { slot ->
        sims.firstOrNull { it.slotIndex == slot }?.carrierName ?: "SIM ${slot + 1}"
    }
    val shown = events.filter { it.slotIndex == selectedSlot }.asReversed() // newest first

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cell History (${shown.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { shareCsv(ctx, CellHistory.toCsv(selectedSlot)) }) {
                        Icon(Icons.Default.Share, contentDescription = "Export CSV")
                    }
                    TextButton(onClick = { CellHistory.clear() }) { Text("Clear") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (slots.size > 1) {
                TabRow(selectedTabIndex = slots.indexOf(selectedSlot).coerceAtLeast(0)) {
                    slots.forEach { slot ->
                        Tab(
                            selected = slot == selectedSlot,
                            onClick = { selectedSlot = slot },
                            text = { Text("SIM ${slot + 1}") }
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No cell changes recorded yet.\nDrive around with logging on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(shown) { e -> EventCard(e, slotLabel(e.slotIndex)) }
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun EventCard(e: CellChangeEvent, label: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            // Line 1: time • transition • identity
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timeFmt.format(Date(e.timestampMillis)),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                val transition = if (e.fromNetworkType != null && e.fromNetworkType != e.networkType)
                    "${e.fromNetworkType} → ${e.networkType}" else e.networkType
                Text(
                    transition,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            // Line 2: cell identity
            val idText = buildList {
                e.enbId?.let { add(if (e.rat == "NR") "gNB $it" else "eNB $it") }
                e.sectorId?.let { add("S$it") }
                e.cellId?.takeIf { e.enbId == null }?.let { add("CID $it") }
                e.pci?.let { add("PCI $it") }
            }.joinToString("  ·  ")
            if (idText.isNotEmpty()) {
                Text(
                    idText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
            // Line 3: band / arfcn
            val cfgText = buildList {
                e.band?.let { add(it) }
                e.arfcn?.let { add("ARFCN $it") }
                e.tac?.let { add("TAC $it") }
            }.joinToString("  ·  ")
            if (cfgText.isNotEmpty()) {
                Text(
                    cfgText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Line 4: RAG-coloured signal
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Metric("RSRP", e.rsrp, SignalQuality.rsrp(e.rsrp))
                Metric("RSRQ", e.rsrq, SignalQuality.rsrq(e.rsrq))
                Metric("SINR", e.sinr, SignalQuality.sinr(e.sinr))
            }
        }
    }
}

@Composable
private fun Metric(name: String, value: Int?, color: Color?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$name ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value?.toString() ?: "—",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = color ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun shareCsv(ctx: Context, csv: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_SUBJECT, "NetView Cell History")
        putExtra(Intent.EXTRA_TEXT, csv)
    }
    ctx.startActivity(Intent.createChooser(intent, "Export cell history (CSV)"))
}
