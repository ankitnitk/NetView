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
        val context  = LocalContext.current

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(colorBg)
                .clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
        ) {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                if (simCount == 0) {
                    Spacer(GlanceModifier.defaultWeight())
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "No data — open NetView",
                            style = TextStyle(color = colorSubtle, fontSize = 11.sp),
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Text(
                            "↻",
                            style = TextStyle(color = colorPrimary, fontSize = 14.sp),
                            modifier = GlanceModifier.clickable(actionRunCallback<WidgetRefreshCallback>())
                        )
                    }
                    Spacer(GlanceModifier.defaultWeight())
                } else {
                    val compact = simCount > 1
                    for (slot in 0 until simCount) {
                        if (slot > 0) {
                            Spacer(GlanceModifier.fillMaxWidth().height(4.dp))
                            Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(colorSubtle))
                            Spacer(GlanceModifier.fillMaxWidth().height(4.dp))
                        }
                        SimBlock(
                            prefs    = prefs,
                            slot     = slot,
                            showRefresh = slot == 0,
                            compact  = compact
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SimBlock(
        prefs: Preferences,
        slot: Int,
        showRefresh: Boolean,
        compact: Boolean,
    ) {
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

        // Font scale: normal for single SIM, tighter for dual
        val headerSp = if (compact) 10.sp else 11.sp
        val cellSp   = if (compact) 12.sp else 14.sp
        val sigSp    = if (compact) 10.sp else 12.sp
        val metricSp = if (compact) 10.sp else 11.sp

        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxWidth()
        ) {
            // ── Header: carrier  |  RAT  band  ↻ ──────────────────────────
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val headerLabel = if (compact) "$simLabel · $carrier" else carrier
                Text(
                    headerLabel,
                    style = TextStyle(color = colorOnBg, fontWeight = FontWeight.Medium, fontSize = headerSp),
                    modifier = GlanceModifier.defaultWeight()
                )
                if (rat.isNotEmpty()) {
                    Text(rat, style = TextStyle(color = colorPrimary, fontWeight = FontWeight.Bold, fontSize = headerSp))
                }
                if (band.isNotEmpty()) {
                    Text("  $band", style = TextStyle(color = colorSubtle, fontSize = headerSp))
                }
                if (showRefresh) {
                    Text(
                        "  ↻",
                        style = TextStyle(color = colorPrimary, fontSize = if (compact) 12.sp else 14.sp),
                        modifier = GlanceModifier.clickable(actionRunCallback<WidgetRefreshCallback>())
                    )
                }
            }

            // ── Flexible gap: pushes cell name away from top ───────────────
            if (!compact) Spacer(GlanceModifier.defaultWeight())
            else Spacer(GlanceModifier.height(3.dp))

            // ── Cell identity — primary focus ──────────────────────────────
            if (cell.isNotEmpty() || ca.isNotEmpty()) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        cell.ifEmpty { "—" },
                        style = TextStyle(
                            color = colorPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = cellSp
                        ),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    if (ca.isNotEmpty()) {
                        Text(
                            ca,
                            style = TextStyle(color = colorSubtle, fontSize = metricSp)
                        )
                    }
                }
            }

            // ── Signal metrics ─────────────────────────────────────────────
            if (sig.isNotEmpty()) {
                Spacer(GlanceModifier.height(2.dp))
                Text(sig, style = TextStyle(color = colorOnBg, fontSize = sigSp))
            }

            // ── NR secondary row (NSA) ─────────────────────────────────────
            if (!compact && (nrRow.isNotEmpty() || nrSig.isNotEmpty())) {
                Spacer(GlanceModifier.height(1.dp))
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (nrRow.isNotEmpty()) {
                        Text(
                            nrRow,
                            style = TextStyle(color = colorSubtle, fontSize = 10.sp),
                            modifier = GlanceModifier.defaultWeight()
                        )
                    }
                    if (nrSig.isNotEmpty()) {
                        Text(nrSig, style = TextStyle(color = colorOnBg, fontSize = 10.sp))
                    }
                }
            }

            // ── Flexible gap: pushes DL/lat to bottom (single SIM only) ───
            if (!compact) Spacer(GlanceModifier.defaultWeight())

            // ── DL throughput + latency ────────────────────────────────────
            if (!compact && (dl.isNotEmpty() || lat.isNotEmpty())) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dl.isNotEmpty()) {
                        Text(
                            dl,
                            style = TextStyle(color = colorPrimary, fontSize = metricSp),
                            modifier = GlanceModifier.defaultWeight()
                        )
                    } else {
                        Spacer(GlanceModifier.defaultWeight())
                    }
                    if (lat.isNotEmpty()) {
                        Text(lat, style = TextStyle(color = colorSubtle, fontSize = metricSp))
                    }
                }
            }
        }
    }
}
