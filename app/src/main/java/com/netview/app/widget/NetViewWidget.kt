package com.netview.app.widget

import android.content.Context
import android.content.Intent
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
import androidx.glance.LocalContext
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.netview.app.MainActivity
import com.netview.app.R

class NetViewWidget : GlanceAppWidget() {

    companion object {
        val KEY_SIM_COUNT = intPreferencesKey("sim_count")
        val KEY_UPDATED   = longPreferencesKey("updated_ms")

        fun keySim(slot: Int)       = stringPreferencesKey("sim_$slot")
        fun keyCarrier(slot: Int)   = stringPreferencesKey("carrier_$slot")
        fun keyRat(slot: Int)       = stringPreferencesKey("rat_$slot")
        fun keyCellLabel(slot: Int) = stringPreferencesKey("cell_$slot")
        fun keyBand(slot: Int)      = stringPreferencesKey("band_$slot")
        fun keySigLine(slot: Int)   = stringPreferencesKey("sig_$slot")
        fun keyCa(slot: Int)        = stringPreferencesKey("ca_$slot")
        fun keyDl(slot: Int)        = stringPreferencesKey("dl_$slot")
        fun keyLat(slot: Int)       = stringPreferencesKey("lat_$slot")
        fun keyNrRow(slot: Int)     = stringPreferencesKey("nr_row_$slot")
        fun keyNrSig(slot: Int)     = stringPreferencesKey("nr_sig_$slot")

        // Resource-based day/night colours (values/colors.xml + values-night/colors.xml)
        private val colorBg      = ColorProvider(R.color.widget_bg)
        private val colorPrimary = ColorProvider(R.color.widget_primary)
        private val colorOnBg    = ColorProvider(R.color.widget_on_bg)
        private val colorSubtle  = ColorProvider(R.color.widget_subtle)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            Content(prefs)
        }
    }

    @Composable
    private fun Content(prefs: Preferences) {
        val simCount = (prefs[KEY_SIM_COUNT] ?: 0).coerceAtMost(2)
        val context = LocalContext.current

        // Box fills the whole widget area (makes the entire area tappable to open the app).
        // Column has background + wraps to content height so there's no empty dark space
        // below the content when the widget is placed in a larger cell area.
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
        ) {
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(colorBg)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // Header
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "NetView",
                        style = TextStyle(color = colorPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        "↻",
                        style = TextStyle(color = colorPrimary, fontSize = 14.sp),
                        modifier = GlanceModifier.clickable(actionRunCallback<WidgetRefreshCallback>())
                    )
                }
                Spacer(GlanceModifier.fillMaxWidth().height(4.dp))

                if (simCount == 0) {
                    Text(
                        "No data — open NetView",
                        style = TextStyle(color = colorSubtle, fontSize = 11.sp)
                    )
                } else {
                    for (slot in 0 until simCount) {
                        if (slot > 0) {
                            Spacer(GlanceModifier.fillMaxWidth().height(4.dp))
                            Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(colorSubtle))
                            Spacer(GlanceModifier.fillMaxWidth().height(4.dp))
                        }
                        SimBlock(prefs, slot)
                    }
                }
            }
        }
    }

    @Composable
    private fun SimBlock(prefs: Preferences, slot: Int) {
        val simLabel = prefs[keySim(slot)] ?: "SIM ${slot + 1}"
        val carrier  = prefs[keyCarrier(slot)] ?: ""
        val rat      = prefs[keyRat(slot)] ?: ""
        val cell     = prefs[keyCellLabel(slot)] ?: ""
        val band     = prefs[keyBand(slot)] ?: ""
        val sig      = prefs[keySigLine(slot)] ?: ""
        val ca       = prefs[keyCa(slot)] ?: ""
        val dl       = prefs[keyDl(slot)] ?: ""
        val lat      = prefs[keyLat(slot)] ?: ""
        val nrRow    = prefs[keyNrRow(slot)] ?: ""
        val nrSig    = prefs[keyNrSig(slot)] ?: ""

        Column(modifier = GlanceModifier.fillMaxWidth()) {
            // Row 1: SIM label · carrier  |  RAT  band
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$simLabel · $carrier",
                    style = TextStyle(color = colorOnBg, fontWeight = FontWeight.Bold, fontSize = 11.sp),
                    modifier = GlanceModifier.defaultWeight()
                )
                if (rat.isNotEmpty()) {
                    Text(rat, style = TextStyle(color = colorPrimary, fontSize = 10.sp))
                }
                if (band.isNotEmpty()) {
                    Text("  $band", style = TextStyle(color = colorSubtle, fontSize = 10.sp))
                }
            }
            // Row 2: cell/site name  |  CA
            if (cell.isNotEmpty() || ca.isNotEmpty()) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cell,
                        style = TextStyle(color = colorOnBg, fontSize = 10.sp),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    if (ca.isNotEmpty()) {
                        Text("CA $ca", style = TextStyle(color = colorPrimary, fontSize = 10.sp))
                    }
                }
            }
            // Row 3: signal
            if (sig.isNotEmpty()) {
                Text(sig, style = TextStyle(color = colorOnBg, fontSize = 10.sp))
            }
            // Row 4: NR secondary cell (NSA)
            if (nrRow.isNotEmpty() || nrSig.isNotEmpty()) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (nrRow.isNotEmpty()) {
                        Text(
                            nrRow,
                            style = TextStyle(color = colorOnBg, fontSize = 10.sp),
                            modifier = GlanceModifier.defaultWeight()
                        )
                    }
                    if (nrSig.isNotEmpty()) {
                        Text(nrSig, style = TextStyle(color = colorPrimary, fontSize = 10.sp))
                    }
                }
            }
            // Row 5: DL + latency
            if (dl.isNotEmpty() || lat.isNotEmpty()) {
                Row {
                    if (dl.isNotEmpty()) {
                        Text(dl, style = TextStyle(color = colorPrimary, fontSize = 10.sp))
                    }
                    if (dl.isNotEmpty() && lat.isNotEmpty()) {
                        Text("  ", style = TextStyle(color = colorOnBg, fontSize = 10.sp))
                    }
                    if (lat.isNotEmpty()) {
                        Text(lat, style = TextStyle(color = colorOnBg, fontSize = 10.sp))
                    }
                }
            }
        }
    }
}
