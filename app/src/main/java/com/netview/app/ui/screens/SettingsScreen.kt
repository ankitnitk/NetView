package com.netview.app.ui.screens

import android.net.Uri
import com.netview.app.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netview.app.data.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentRefreshSeconds: Int,
    onRefreshChange: (Int) -> Unit,
    debugLoggingEnabled: Boolean,
    onDebugLoggingChange: (Boolean) -> Unit,
    onOpenDebugLog: () -> Unit,
    cmExportStatus: String,
    onLoadCmExport: (Uri) -> Unit,
    onClearCmExport: () -> Unit,
    wcdmaCmExportStatus: String,
    onLoadWcdmaCmExport: (Uri) -> Unit,
    onClearWcdmaCmExport: () -> Unit,
    gsmCmExportStatus: String,
    onLoadGsmCmExport: (Uri) -> Unit,
    onClearGsmCmExport: () -> Unit,
    widgetRefreshSeconds: Int,
    onWidgetRefreshChange: (Int) -> Unit,
    backgroundMonitoringEnabled: Boolean,
    onBackgroundMonitoringChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            var seconds by remember(currentRefreshSeconds) { mutableIntStateOf(currentRefreshSeconds) }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Refresh Rate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "How often NetView reads cell info & GPS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "$seconds second${if (seconds == 1) "" else "s"}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = seconds.toFloat(),
                        onValueChange = { seconds = it.toInt() },
                        onValueChangeFinished = { onRefreshChange(seconds) },
                        valueRange = SettingsRepository.MIN_REFRESH.toFloat()..SettingsRepository.MAX_REFRESH.toFloat(),
                        steps = SettingsRepository.MAX_REFRESH - SettingsRepository.MIN_REFRESH - 1
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1s", style = MaterialTheme.typography.labelSmall)
                        Text("60s", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Widget section
            var widgetSecs by remember(widgetRefreshSeconds) { mutableIntStateOf(widgetRefreshSeconds) }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Monitoring", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Keeps the home screen widget updated with live cell data. Shows a persistent notification.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = backgroundMonitoringEnabled, onCheckedChange = onBackgroundMonitoringChange)
                    }
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Widget Refresh Rate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "How often the home screen widget updates when background monitoring is on",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "$widgetSecs second${if (widgetSecs == 1) "" else "s"}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = widgetSecs.toFloat(),
                        onValueChange = { widgetSecs = it.toInt() },
                        onValueChangeFinished = { onWidgetRefreshChange(widgetSecs) },
                        valueRange = SettingsRepository.MIN_REFRESH.toFloat()..SettingsRepository.MAX_REFRESH.toFloat(),
                        steps = SettingsRepository.MAX_REFRESH - SettingsRepository.MIN_REFRESH - 1
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1s", style = MaterialTheme.typography.labelSmall)
                        Text("60s", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Debug Logging", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Capture internal events to diagnose carrier-specific issues. Off by default.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = debugLoggingEnabled, onCheckedChange = onDebugLoggingChange)
                    }
                    if (debugLoggingEnabled) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenDebugLog) { Text("View Debug Log") }
                    }
                }
            }

            CmExportCard(
                title = "4G CMExport File",
                description = "Nokia LTE CMExport Excel file for site/cell parameter lookup",
                status = cmExportStatus,
                onLoad = onLoadCmExport,
                onClear = onClearCmExport,
            )

            CmExportCard(
                title = "3G CMExport File",
                description = "Nokia WCDMA CMExport Excel file for 3G cell parameter lookup",
                status = wcdmaCmExportStatus,
                onLoad = onLoadWcdmaCmExport,
                onClear = onClearWcdmaCmExport,
            )

            CmExportCard(
                title = "2G CMExport File",
                description = "Nokia GSM CMExport Excel file for 2G cell parameter lookup",
                status = gsmCmExportStatus,
                onLoad = onLoadGsmCmExport,
                onClear = onClearGsmCmExport,
            )

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("NetView — Cell Info Viewer", style = MaterialTheme.typography.bodyMedium)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Shows serving-cell parameters (PCI, EARFCN, RSRP, RSRQ, SINR…) " +
                                "and carrier aggregation status for each SIM. GPS shown live.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun CmExportCard(
    title: String,
    description: String,
    status: String,
    onLoad: (Uri) -> Unit,
    onClear: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            val fileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { onLoad(it) } }

            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("Loaded"))
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { fileLauncher.launch(arrayOf("*/*")) }) {
                    Text("Load File")
                }
                if (status.startsWith("Loaded")) {
                    OutlinedButton(onClick = onClear) {
                        Text("Clear")
                    }
                }
            }
        }
    }
}
