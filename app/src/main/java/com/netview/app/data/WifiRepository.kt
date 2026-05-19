package com.netview.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.InetAddress

class WifiRepository(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @SuppressLint("MissingPermission")
    fun read(): WifiState {
        if (!wifiManager.isWifiEnabled)
            return WifiState(isEnabled = false, connection = null, nearbyAps = emptyList())

        val info = try { wifiManager.connectionInfo } catch (_: Exception) { null }
        val connected = info != null && info.networkId != -1
        val scanResults = try { wifiManager.scanResults ?: emptyList() } catch (_: Exception) { emptyList() }

        val connection = if (connected && info != null) buildConnection(info, scanResults) else null
        val nearbyAps = scanResults
            .sortedByDescending { it.level }
            .take(10)
            .map { buildNearbyAp(it) }

        return WifiState(isEnabled = true, connection = connection, nearbyAps = nearbyAps)
    }

    private fun buildConnection(info: WifiInfo, scanResults: List<ScanResult>): WifiConnection {
        val dhcp = try { wifiManager.dhcpInfo } catch (_: Exception) { null }
        val freq = info.frequency.takeIf { it > 0 }
        val scanResult = scanResults.firstOrNull { it.BSSID == info.bssid }

        val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val std = info.wifiStandard
            val is6Ghz = freq != null && freq > 5925
            when {
                std == 8 -> "WiFi 7 (802.11be)"
                std == ScanResult.WIFI_STANDARD_11AX && is6Ghz -> "WiFi 6E (802.11ax)"
                std == ScanResult.WIFI_STANDARD_11AX -> "WiFi 6 (802.11ax)"
                std == ScanResult.WIFI_STANDARD_11AC -> "WiFi 5 (802.11ac)"
                std == ScanResult.WIFI_STANDARD_11N -> "WiFi 4 (802.11n)"
                std == ScanResult.WIFI_STANDARD_LEGACY -> "Legacy (802.11a/b/g)"
                else -> null
            }
        } else null

        val securityType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.currentSecurityType) {
                WifiInfo.SECURITY_TYPE_OPEN -> "Open"
                WifiInfo.SECURITY_TYPE_WEP -> "WEP"
                WifiInfo.SECURITY_TYPE_PSK -> "WPA2-Personal"
                WifiInfo.SECURITY_TYPE_EAP -> "WPA2-Enterprise"
                WifiInfo.SECURITY_TYPE_SAE -> "WPA3-Personal"
                WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3-Enterprise"
                WifiInfo.SECURITY_TYPE_OWE -> "OWE"
                else -> null
            }
        } else null

        val txSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.txLinkSpeedMbps else info.linkSpeed
        val rxSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.rxLinkSpeedMbps.takeIf { it > 0 } else null
        val maxTx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.maxSupportedTxLinkSpeedMbps.takeIf { it > 0 } else null
        val maxRx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.maxSupportedRxLinkSpeedMbps.takeIf { it > 0 } else null

        return WifiConnection(
            ssid = cleanSsid(info.ssid),
            bssid = info.bssid,
            securityType = securityType,
            standard = standard,
            rssi = info.rssi.takeIf { it != 0 && it > -200 },
            frequencyMhz = freq,
            band = freq?.let { bandFromFreq(it) },
            channel = freq?.let { channelFromFreq(it) },
            channelWidthMhz = scanResult?.let { channelWidthMhz(it.channelWidth) },
            txLinkSpeedMbps = txSpeed.takeIf { it > 0 },
            rxLinkSpeedMbps = rxSpeed,
            maxTxLinkSpeedMbps = maxTx,
            maxRxLinkSpeedMbps = maxRx,
            ipAddress = intToIpString(dhcp?.ipAddress ?: 0),
            subnetMask = intToIpString(dhcp?.netmask ?: 0),
            gateway = intToIpString(dhcp?.gateway ?: 0),
            dns1 = intToIpString(dhcp?.dns1 ?: 0),
            dns2 = intToIpString(dhcp?.dns2 ?: 0),
            dhcpServer = intToIpString(dhcp?.serverAddress ?: 0),
        )
    }

    private fun buildNearbyAp(result: ScanResult): NearbyAp {
        val freq = result.frequency.takeIf { it > 0 }
        return NearbyAp(
            ssid = result.SSID.takeIf { it.isNotBlank() },
            bssid = result.BSSID,
            rssi = result.level,
            frequencyMhz = freq,
            band = freq?.let { bandFromFreq(it) },
            channel = freq?.let { channelFromFreq(it) },
        )
    }

    private fun cleanSsid(ssid: String?): String? {
        if (ssid == null || ssid == "<unknown ssid>") return null
        return ssid.removeSurrounding("\"")
    }

    private fun intToIpString(ip: Int): String? {
        if (ip == 0) return null
        return try {
            InetAddress.getByAddress(
                byteArrayOf(
                    (ip and 0xFF).toByte(),
                    ((ip shr 8) and 0xFF).toByte(),
                    ((ip shr 16) and 0xFF).toByte(),
                    ((ip shr 24) and 0xFF).toByte()
                )
            ).hostAddress
        } catch (_: Exception) { null }
    }

    private fun bandFromFreq(freqMhz: Int): String = when {
        freqMhz in 2412..2484 -> "2.4 GHz"
        freqMhz in 5170..5825 -> "5 GHz"
        freqMhz >= 5925 -> "6 GHz"
        else -> "—"
    }

    private fun channelFromFreq(freqMhz: Int): Int? = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2483 -> (freqMhz - 2407) / 5
        freqMhz in 5170..5825 -> (freqMhz - 5000) / 5
        freqMhz >= 5925 -> (freqMhz - 5950) / 5 + 1
        else -> null
    }

    private fun channelWidthMhz(width: Int): Int? = when (width) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> 20
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ -> 160
        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
        5 -> 320  // CHANNEL_WIDTH_320MHZ (API 33+)
        else -> null
    }
}
