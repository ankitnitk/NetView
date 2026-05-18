package com.netview.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.netview.app.data.CmExportCell
import com.netview.app.data.GsmCmCell
import com.netview.app.data.LocationData
import com.netview.app.data.SimSlotData
import com.netview.app.data.WcdmaCmCell
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    sims: List<SimSlotData>,
    location: LocationData?,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    cmExportLookup: ((Long?, Int?, String?, String?) -> CmExportCell?)? = null,
    cmExportLoaded: Boolean = false,
    cmNeighborLookup: ((Int, Int) -> CmExportCell?)? = null,
    wcdmaCmLookup: ((Int?, Int?, Int?, String?, String?) -> WcdmaCmCell?)? = null,
    wcdmaCmLoaded: Boolean = false,
    gsmCmLookup: ((Int?, Int?, String?, String?) -> GsmCmCell?)? = null,
    gsmCmLoaded: Boolean = false,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NetView") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {

            if (!permissionsGranted) {
                PermissionPrompt(onRequestPermissions)
                return@Column
            }
            if (sims.isEmpty()) {
                EmptyState()
                return@Column
            }

            val pagerState = rememberPagerState(pageCount = { sims.size })
            val scope = rememberCoroutineScope()

            if (sims.size > 1) {
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    sims.forEachIndexed { index, sim ->
                        SimTab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            label = "SIM ${sim.slotIndex + 1}",
                            sublabel = sim.carrierName
                        )
                    }
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val sim = sims[page]
                val cell = sim.servingCell
                val cmCell = cell?.let {
                    cmExportLookup?.invoke(it.enbId, it.sectorId, sim.mcc, sim.mnc)
                }
                val wcdmaCell = if (cell?.rat == "WCDMA") {
                    val rncId = cell.cellId?.let { (it shr 16).toInt() }
                    val wcelId = cell.cellId?.let { (it and 0xFFFF).toInt() }
                    wcdmaCmLookup?.invoke(rncId, wcelId, cell.uarfcn, sim.mcc, sim.mnc)
                } else null
                val gsmCell = if (cell?.rat == "GSM") {
                    gsmCmLookup?.invoke(cell.tac, cell.cellId?.toInt(), sim.mcc, sim.mnc)
                } else null
                SimScreen(
                    sim = sim,
                    location = location,
                    cmExportCell = cmCell,
                    cmExportLoaded = cmExportLoaded,
                    cmNeighborLookup = cmNeighborLookup,
                    wcdmaCmCell = wcdmaCell,
                    wcdmaCmLoaded = wcdmaCmLoaded,
                    gsmCmCell = gsmCell,
                    gsmCmLoaded = gsmCmLoaded,
                )
            }
        }
    }
}

@Composable
private fun SimTab(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    sublabel: String
) {
    androidx.compose.material3.Tab(selected = selected, onClick = onClick) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(sublabel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "NetView needs permissions",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "• Phone state — to read SIM & cell info\n" +
                    "• Location — required by Android to expose cell IDs and for GPS coordinates",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant permissions") }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No active SIM detected", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Make sure at least one SIM is inserted and active.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
