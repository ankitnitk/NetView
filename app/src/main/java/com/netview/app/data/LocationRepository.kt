package com.netview.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Lightweight GPS wrapper using LocationManager so we don't depend on Play Services.
 */
class LocationRepository(private val context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null
    private var lastFix: Location? = null

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) return
        if (listener != null) return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) { lastFix = location }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listener = l
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
            }
            // Seed lastFix from cached
            lastFix = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun stop() {
        listener?.let { lm.removeUpdates(it) }
        listener = null
    }

    fun current(): LocationData? {
        val l = lastFix ?: return null
        return LocationData(
            latitude = l.latitude,
            longitude = l.longitude,
            accuracyMeters = l.accuracy,
            altitudeMeters = if (l.hasAltitude()) l.altitude else null,
            speedMps = if (l.hasSpeed()) l.speed else null,
            bearingDeg = if (l.hasBearing()) l.bearing else null,
            provider = l.provider ?: "—",
            timestampMillis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                l.elapsedRealtimeNanos / 1_000_000 else l.time
        )
    }
}
