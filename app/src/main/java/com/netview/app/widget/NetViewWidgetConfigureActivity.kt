package com.netview.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.netview.app.data.SettingsRepository
import com.netview.app.ui.theme.NetViewTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NetViewWidgetConfigureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Default result: canceled (user pressed back without confirming)
        setResult(RESULT_CANCELED)

        val repo = SettingsRepository(applicationContext)

        lifecycleScope.launch {
            if (repo.backgroundMonitoringEnabled.first()) {
                // Already enabled — skip config screen, add widget immediately
                setResult(RESULT_OK, Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                })
                finish()
                return@launch
            }

            // Background monitoring is off — show config screen so user can enable it
            setContent {
                NetViewTheme {
                    var bgEnabled by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        repo.backgroundMonitoringEnabled.collect { bgEnabled = it }
                    }

                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "NetView Widget",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Shows live cell info for each SIM on your home screen.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(32.dp))

                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Background Monitoring",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "Required to keep the widget updated with live data",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Switch(
                                        checked = bgEnabled,
                                        onCheckedChange = { enabled ->
                                            bgEnabled = enabled
                                            lifecycleScope.launch {
                                                repo.setBackgroundMonitoringEnabled(enabled)
                                            }
                                        }
                                    )
                                }
                            }

                            Spacer(Modifier.height(32.dp))

                            Button(
                                onClick = {
                                    val resultIntent = Intent().apply {
                                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                    }
                                    setResult(RESULT_OK, resultIntent)
                                    finish()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Add Widget")
                            }
                        }
                    }
                }
            }
        }
    }
}
