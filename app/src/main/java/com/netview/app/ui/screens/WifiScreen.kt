package com.netview.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netview.app.data.WifiState
import com.netview.app.ui.components.InfoCard

@Composable
fun WifiScreen(
    wifiState: WifiState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Wi-Fi",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val conn = wifiState.connection

            if (conn == null) {
                InfoCard(
                    title = "Connection",
                    rows = listOf("Status" to "Wi-Fi on — not connected")
                )
            } else {
                InfoCard(
                    title = "Connection",
                    rows = buildList {
                        conn.ssid?.let { add("SSID" to it) }
                        conn.bssid?.let { add("BSSID" to it) }
                        conn.standard?.let { add("Standard" to it) }
                        conn.securityType?.let { add("Security" to it) }
                    }
                )

                InfoCard(
                    title = "Radio",
                    rows = buildList {
                        conn.rssi?.let { add("RSSI" to "$it dBm") }
                        conn.frequencyMhz?.let { add("Frequency" to "$it MHz") }
                        conn.band?.let { add("Band" to it) }
                        conn.channel?.let { add("Channel" to it.toString()) }
                        conn.channelWidthMhz?.let { add("Channel Width" to "$it MHz") }
                        conn.txLinkSpeedMbps?.let { add("TX Speed" to "$it Mbps") }
                        conn.rxLinkSpeedMbps?.let { add("RX Speed" to "$it Mbps") }
                        conn.maxTxLinkSpeedMbps?.let { add("Max TX Speed" to "$it Mbps") }
                        conn.maxRxLinkSpeedMbps?.let { add("Max RX Speed" to "$it Mbps") }
                    }
                )

                InfoCard(
                    title = "IP / Network",
                    rows = buildList {
                        conn.ipAddress?.let { add("IP Address" to it) }
                        conn.subnetMask?.let { add("Subnet Mask" to it) }
                        conn.gateway?.let { add("Gateway" to it) }
                        conn.dns1?.let { add("DNS 1" to it) }
                        conn.dns2?.let { add("DNS 2" to it) }
                        conn.dhcpServer?.let { add("DHCP Server" to it) }
                    }
                )
            }

            val perfRows = buildList {
                wifiState.dlThroughputMbps?.let { add("DL Throughput" to "%.2f Mbps".format(it)) }
                wifiState.ulThroughputMbps?.let { add("UL Throughput" to "%.2f Mbps".format(it)) }
                wifiState.latencyMs?.let { add("Latency" to "$it ms") }
            }
            if (perfRows.isNotEmpty()) {
                InfoCard(title = "Performance", rows = perfRows)
            }

            if (wifiState.nearbyAps.isNotEmpty()) {
                val apRows = buildList {
                    wifiState.nearbyAps.forEach { ap ->
                        val label = ap.ssid?.let { "\"$it\"" } ?: "(hidden)"
                        val detail = "${ap.band ?: "—"} • Ch ${ap.channel ?: "—"}"
                        add(label to detail)
                        ap.rssi?.let { add("  Signal" to "$it dBm") }
                    }
                }
                InfoCard(
                    title = "Nearby APs (${wifiState.nearbyAps.size})",
                    rows = apRows,
                    collapsible = true,
                    initiallyExpanded = false
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
