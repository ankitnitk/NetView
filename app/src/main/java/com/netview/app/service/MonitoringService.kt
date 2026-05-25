package com.netview.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import com.netview.app.R
import com.netview.app.data.CmExportRepository
import com.netview.app.data.GsmCmRepository
import com.netview.app.data.SettingsRepository
import com.netview.app.data.TelephonyRepository
import com.netview.app.data.WcdmaCmRepository
import com.netview.app.widget.WidgetWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var telephonyRepo: TelephonyRepository
    private val lteExport = CmExportRepository()
    private val wcdmaExport = WcdmaCmRepository()
    private val gsmExport = GsmCmRepository()
    private lateinit var settingsRepo: SettingsRepository
    private val exportsReady = CompletableDeferred<Unit>()

    private data class TrafficSnapshot(val rx: Long, val tx: Long, val ms: Long, val isWifi: Boolean)
    private var lastTraffic: TrafficSnapshot? = null
    private var pingCount = 0
    private var latencyMs: Long? = null

    companion object {
        private const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "netview_monitoring"
        const val ACTION_REFRESH_NOW = "com.netview.app.action.REFRESH_NOW"

        fun start(context: Context) {
            val intent = Intent(context, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }

        fun refreshNow(context: Context) {
            val intent = Intent(context, MonitoringService::class.java).apply {
                action = ACTION_REFRESH_NOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        telephonyRepo = TelephonyRepository(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        loadExportsAsync()
        startMonitoringLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_NOW) {
            scope.launch {
                exportsReady.await()
                if (telephonyRepo.hasPermissions()) runRefresh()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        scope.cancel()
        telephonyRepo.release()
        super.onDestroy()
    }

    private fun loadExportsAsync() {
        scope.launch(Dispatchers.IO) {
            settingsRepo.cmExportUri.first()?.let {
                lteExport.load(applicationContext, Uri.parse(it))
            }
            settingsRepo.wcdmaCmExportUri.first()?.let {
                wcdmaExport.load(applicationContext, Uri.parse(it))
            }
            settingsRepo.gsmCmExportUri.first()?.let {
                gsmExport.load(applicationContext, Uri.parse(it))
            }
            exportsReady.complete(Unit)
        }
    }

    private fun startMonitoringLoop() {
        scope.launch {
            exportsReady.await()
            var refreshMs = settingsRepo.widgetRefreshSeconds.first() * 1000L
            scope.launch { settingsRepo.widgetRefreshSeconds.collect { refreshMs = it * 1000L } }
            while (isActive) {
                if (telephonyRepo.hasPermissions()) runRefresh()
                delay(refreshMs)
            }
        }
    }

    private suspend fun runRefresh() {
        val sims = telephonyRepo.readAllSims()
        val now = System.currentTimeMillis()
        val isWifi = isWifiActive()

        val rx = if (isWifi) TrafficStats.getTotalRxBytes() else TrafficStats.getMobileRxBytes()
        val tx = if (isWifi) TrafficStats.getTotalTxBytes() else TrafficStats.getMobileTxBytes()
        var dlMbps: Double? = null
        if (rx >= 0 && tx >= 0) {
            val prev = lastTraffic
            if (prev != null && prev.isWifi == isWifi) {
                val dt = (now - prev.ms) / 1000.0
                if (dt > 0) dlMbps = ((rx - prev.rx) * 8.0 / 1_000_000.0 / dt).coerceAtLeast(0.0)
            }
            lastTraffic = TrafficSnapshot(rx, tx, now, isWifi)
        }

        pingCount++
        if (pingCount % 4 == 0) {
            scope.launch(Dispatchers.IO) { latencyMs = measureLatency() }
        }

        val defaultDataSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            SubscriptionManager.getDefaultDataSubscriptionId()
        else SubscriptionManager.INVALID_SUBSCRIPTION_ID

        WidgetWriter.write(
            context = applicationContext,
            sims = sims,
            lteExport = lteExport,
            wcdmaExport = wcdmaExport,
            gsmExport = gsmExport,
            isWifi = isWifi,
            dlMbps = dlMbps,
            latencyMs = latencyMs,
            defaultDataSubId = defaultDataSubId,
            updatedMs = now,
        )
    }

    private fun isWifiActive(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun measureLatency(): Long? = try {
        val start = System.currentTimeMillis()
        Socket().use { it.connect(InetSocketAddress("8.8.8.8", 53), 3000) }
        System.currentTimeMillis() - start
    } catch (_: Exception) { null }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NetView")
            .setContentText("Monitoring cell info for widget")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Background Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the cell widget updated"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
