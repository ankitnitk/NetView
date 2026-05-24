package com.netview.app.widget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.netview.app.data.TelephonyRepository
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

            WidgetWriter.write(
                context = context,
                sims = sims,
                lteExport = null,
                wcdmaExport = null,
                gsmExport = null,
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
