package com.netview.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.material3.GlanceTheme
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.netview.app.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NetViewWidget : GlanceAppWidget() {

    companion object {
        val KEY_SIM_COUNT = intPreferencesKey("sim_count")
        val KEY_UPDATED = longPreferencesKey("updated_ms")

        fun keyCarrier(slot: Int) = stringPreferencesKey("carrier_$slot")
        fun keyRat(slot: Int) = stringPreferencesKey("rat_$slot")
        fun keyCellLabel(slot: Int) = stringPreferencesKey("cell_$slot")
        fun keyBand(slot: Int) = stringPreferencesKey("band_$slot")
        fun keySigLine(slot: Int) = stringPreferencesKey("sig_$slot")
        fun keyCa(slot: Int) = stringPreferencesKey("ca_$slot")
        fun keyDl(slot: Int) = stringPreferencesKey("dl_$slot")
        fun keyLat(slot: Int) = stringPreferencesKey("lat_$slot")
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            GlanceTheme {
                Content(prefs)
            }
        }
    }

    @Composable
    private fun Content(prefs: Preferences) {
        val simCount = (prefs[KEY_SIM_COUNT] ?: 0).coerceAtMost(2)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Column(modifier = GlanceModifier.fillMaxSize().padding(8.dp)) {
                if (simCount == 0) {
                    Text(
                        "No data — open NetView",
                        style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 12.sp)
                    )
                } else {
                    for (slot in 0 until simCount) {
                        if (slot > 0) Spacer(GlanceModifier.fillMaxWidth().height(6.dp))
                        SimBlock(prefs, slot)
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                Footer(prefs)
            }
        }
    }

    @Composable
    private fun SimBlock(prefs: Preferences, slot: Int) {
        val carrier = prefs[keyCarrier(slot)] ?: ""
        val rat     = prefs[keyRat(slot)] ?: ""
        val cell    = prefs[keyCellLabel(slot)] ?: ""
        val band    = prefs[keyBand(slot)] ?: ""
        val sig     = prefs[keySigLine(slot)] ?: ""
        val ca      = prefs[keyCa(slot)] ?: ""
        val dl      = prefs[keyDl(slot)] ?: ""
        val lat     = prefs[keyLat(slot)] ?: ""

        Column(modifier = GlanceModifier.fillMaxWidth()) {
            // Carrier  RAT  Band
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    carrier,
                    style = TextStyle(
                        color = GlanceTheme.colors.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                if (rat.isNotEmpty()) {
                    Text(rat, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp))
                }
                if (band.isNotEmpty()) {
                    Text("  $band", style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 11.sp))
                }
            }
            // Cell label  CA
            if (cell.isNotEmpty() || ca.isNotEmpty()) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cell,
                        style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 11.sp),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    if (ca.isNotEmpty()) {
                        Text("CA $ca", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 10.sp))
                    }
                }
            }
            // Signal summary
            if (sig.isNotEmpty()) {
                Text(sig, style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 10.sp))
            }
            // Throughput / latency (background monitoring only)
            if (dl.isNotEmpty() || lat.isNotEmpty()) {
                Row {
                    if (dl.isNotEmpty()) {
                        Text(dl, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 10.sp))
                    }
                    if (dl.isNotEmpty() && lat.isNotEmpty()) {
                        Text("  ", style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 10.sp))
                    }
                    if (lat.isNotEmpty()) {
                        Text(lat, style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 10.sp))
                    }
                }
            }
        }
    }

    @Composable
    private fun Footer(prefs: Preferences) {
        val updatedMs = prefs[KEY_UPDATED] ?: 0L
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (updatedMs > 0L) {
                Text(
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(updatedMs)),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp)
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "↻",
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 16.sp),
                modifier = GlanceModifier.clickable(actionRunCallback<WidgetRefreshCallback>())
            )
        }
    }
}
