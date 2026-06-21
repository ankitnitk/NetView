package com.netview.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.netview.app.data.SettingsRepository
import com.netview.app.ui.screens.CellHistoryScreen
import com.netview.app.ui.screens.DebugLogScreen
import com.netview.app.ui.screens.MainScreen
import com.netview.app.ui.screens.SettingsScreen
import com.netview.app.ui.theme.NetViewTheme
import com.netview.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.READ_PHONE_STATE] == true &&
                results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) viewModel.onPermissionsGranted()
    }

    private val precisePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetViewTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
        if (!hasAllPermissions()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        } else {
            viewModel.onPermissionsGranted()
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.READ_PRECISE_PHONE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            precisePermissionLauncher.launch(Manifest.permission.READ_PRECISE_PHONE_STATE)
        }
    }

    override fun onResume() {
        super.onResume()
        // Start foreground polling (also picks up grants made via the system Settings screen).
        if (hasAllPermissions()) viewModel.start()
    }

    override fun onStop() {
        super.onStop()
        // Stop polling telephony + GPS while the app is in the background.
        viewModel.stop()
    }

    private fun hasAllPermissions(): Boolean {
        val phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        return phone && loc
    }

    private fun isPermPermanentlyDenied(): Boolean {
        if (hasAllPermissions()) return false
        return !shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE) &&
               !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    @androidx.compose.runtime.Composable
    private fun AppNav() {
        val nav = rememberNavController()
        val sims by viewModel.sims.collectAsState()
        val location by viewModel.location.collectAsState()
        val wifiState by viewModel.wifiState.collectAsState()
        val permissionsGranted by viewModel.permissionsGranted.collectAsState()
        val refresh by viewModel.refreshSecondsFlow.collectAsState(initial = SettingsRepository.DEFAULT_REFRESH)
        val debugLogging by viewModel.debugLoggingEnabledFlow.collectAsState(initial = false)
        val cellChangeLogging by viewModel.cellChangeLoggingEnabledFlow.collectAsState(initial = false)
        val keepScreenOn by viewModel.keepScreenOnFlow.collectAsState(initial = false)
        val statusNotification by viewModel.statusNotificationEnabledFlow.collectAsState(initial = false)
        val cmExportStatus by viewModel.cmExportStatus.collectAsState()
        val cmExportLoaded by viewModel.cmExportLoaded.collectAsState()
        val wcdmaCmExportStatus by viewModel.wcdmaCmExportStatus.collectAsState()
        val wcdmaCmExportLoaded by viewModel.wcdmaCmExportLoaded.collectAsState()
        val gsmCmExportStatus by viewModel.gsmCmExportStatus.collectAsState()
        val gsmCmExportLoaded by viewModel.gsmCmExportLoaded.collectAsState()

        // Recomputed each recomposition — cheap and always current.
        // true only after at least one prior denial (onCreate already attempted the dialog).
        val permanentlyDenied = !permissionsGranted && isPermPermanentlyDenied()

        // Keep the screen awake while the app is foreground, when enabled.
        androidx.compose.runtime.LaunchedEffect(keepScreenOn) {
            if (keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        NavHost(navController = nav, startDestination = "main") {
            composable("main") {
                MainScreen(
                    sims = sims,
                    location = location,
                    permissionsGranted = permissionsGranted,
                    permanentlyDenied = permanentlyDenied,
                    onRequestPermissions = {
                        if (permanentlyDenied) openAppSettings()
                        else permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        )
                    },
                    onOpenSettings = { nav.navigate("settings") },
                    cellLoggingEnabled = cellChangeLogging,
                    onOpenHistory = { nav.navigate("cell_history") },
                    cmExportLookup = { enbId, sectorId, mcc, mnc ->
                        viewModel.lookupCmExport(enbId, sectorId, mcc, mnc)
                    },
                    cmExportLoaded = cmExportLoaded,
                    cmNeighborLookup = { pci, earfcn ->
                        viewModel.lookupCmExportByPciEarfcn(pci, earfcn)
                    },
                    wcdmaCmLookup = { rncId, wcelId, uarfcn, mcc, mnc ->
                        viewModel.lookupWcdmaCmExport(rncId, wcelId, uarfcn, mcc, mnc)
                    },
                    wcdmaCmLoaded = wcdmaCmExportLoaded,
                    gsmCmLookup = { lac, cellId, mcc, mnc ->
                        viewModel.lookupGsmCmExport(lac, cellId, mcc, mnc)
                    },
                    gsmCmLoaded = gsmCmExportLoaded,
                    wifiState = wifiState,
                )
            }
            composable("settings") {
                SettingsScreen(
                    currentRefreshSeconds = refresh,
                    onRefreshChange = { viewModel.setRefreshSeconds(it) },
                    debugLoggingEnabled = debugLogging,
                    onDebugLoggingChange = { viewModel.setDebugLoggingEnabled(it) },
                    onOpenDebugLog = { nav.navigate("debug_log") },
                    cellChangeLoggingEnabled = cellChangeLogging,
                    onCellChangeLoggingChange = { viewModel.setCellChangeLoggingEnabled(it) },
                    keepScreenOn = keepScreenOn,
                    onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
                    statusNotificationEnabled = statusNotification,
                    onStatusNotificationChange = { viewModel.setStatusNotificationEnabled(it) },
                    cmExportStatus = cmExportStatus,
                    onLoadCmExport = { uri -> viewModel.loadCmExport(uri) },
                    onClearCmExport = { viewModel.clearCmExport() },
                    wcdmaCmExportStatus = wcdmaCmExportStatus,
                    onLoadWcdmaCmExport = { uri -> viewModel.loadWcdmaCmExport(uri) },
                    onClearWcdmaCmExport = { viewModel.clearWcdmaCmExport() },
                    gsmCmExportStatus = gsmCmExportStatus,
                    onLoadGsmCmExport = { uri -> viewModel.loadGsmCmExport(uri) },
                    onClearGsmCmExport = { viewModel.clearGsmCmExport() },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("debug_log") {
                DebugLogScreen(onBack = { nav.popBackStack() })
            }
            composable("cell_history") {
                CellHistoryScreen(
                    sims = sims,
                    initialSlot = sims.firstOrNull()?.slotIndex ?: 0,
                    cmName = { ev -> viewModel.cellNameFor(ev) },
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }
}
