package com.netview.app.widget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.netview.app.data.CmExportRepository
import com.netview.app.data.GsmCmRepository
import com.netview.app.data.SettingsRepository
import com.netview.app.data.TelephonyRepository
import com.netview.app.data.WcdmaCmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class WidgetRefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val telephonyRepo = TelephonyRepository(context)
        if (!telephonyRepo.hasPermissions()) return
        try {
            val sims = telephonyRepo.readAllSims()
            val defaultDataSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                SubscriptionManager.getDefaultDataSubscriptionId()
            else SubscriptionManager.INVALID_SUBSCRIPTION_ID

            // Load CMExport repos from stored URIs so site names show on manual refresh too
            val settingsRepo = SettingsRepository(context)
            val lteUri   = settingsRepo.cmExportUri.first()
            val wcdmaUri = settingsRepo.wcdmaCmExportUri.first()
            val gsmUri   = settingsRepo.gsmCmExportUri.first()
            val lteExport   = if (lteUri   != null) withContext(Dispatchers.IO) { CmExportRepository().also   { it.load(context, Uri.parse(lteUri)) } }   else null
            val wcdmaExport = if (wcdmaUri != null) withContext(Dispatchers.IO) { WcdmaCmRepository().also    { it.load(context, Uri.parse(wcdmaUri)) } } else null
            val gsmExport   = if (gsmUri   != null) withContext(Dispatchers.IO) { GsmCmRepository().also      { it.load(context, Uri.parse(gsmUri)) } }   else null

            WidgetWriter.write(
                context = context,
                sims = sims,
                lteExport = lteExport,
                wcdmaExport = wcdmaExport,
                gsmExport = gsmExport,
                isWifi = isWifiActive(context),
                dlMbps = null,
                latencyMs = measureLatency(),
                defaultDataSubId = defaultDataSubId,
                updatedMs = System.currentTimeMillis(),
            )
        } finally {
            telephonyRepo.release()
        }
    }

    private fun isWifiActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun measureLatency(): Long? = try {
        val start = System.currentTimeMillis()
        Socket().use { it.connect(InetSocketAddress("8.8.8.8", 53), 3000) }
        System.currentTimeMillis() - start
    } catch (_: Exception) { null }
}
