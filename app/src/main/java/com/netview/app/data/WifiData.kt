package com.netview.app.data

data class WifiState(
    val isEnabled: Boolean,
    val connection: WifiConnection?,
    val nearbyAps: List<NearbyAp>
)

data class WifiConnection(
    val ssid: String?,
    val bssid: String?,
    val securityType: String?,
    val standard: String?,
    val rssi: Int?,
    val frequencyMhz: Int?,
    val band: String?,
    val channel: Int?,
    val channelWidthMhz: Int?,
    val txLinkSpeedMbps: Int?,
    val rxLinkSpeedMbps: Int?,
    val maxTxLinkSpeedMbps: Int?,
    val maxRxLinkSpeedMbps: Int?,
    val ipAddress: String?,
    val subnetMask: String?,
    val gateway: String?,
    val dns1: String?,
    val dns2: String?,
    val dhcpServer: String?,
)

data class NearbyAp(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int?,
    val frequencyMhz: Int?,
    val band: String?,
    val channel: Int?,
)
