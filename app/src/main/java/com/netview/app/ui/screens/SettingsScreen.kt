package com.netview.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    val fileLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri -> uri?.let { onLoadCmExport(it) } }

                    Text("CMExport File", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Nokia 4G CMExport Excel file for site/cell parameter lookup",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        cmExportStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (cmExportStatus.startsWith("Loaded"))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { fileLauncher.launch(arrayOf("*/*")) }) {
                            Text("Load File")
                        }
                        if (cmExportStatus.startsWith("Loaded")) {
                            OutlinedButton(onClick = onClearCmExport) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("NetView — Cell Info Viewer", style = MaterialTheme.typography.bodyMedium)
                    Text("Version 1.1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
