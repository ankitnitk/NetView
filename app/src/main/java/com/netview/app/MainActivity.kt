package com.netview.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    // Request READ_PRECISE_PHONE_STATE separately — needed for PhysicalChannelConfig (CA/BW)
    private val precisePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun hasAllPermissions(): Boolean {
        val phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        return phone && loc
    }

    @androidx.compose.runtime.Composable
    private fun AppNav() {
        val nav = rememberNavController()
        val sims by viewModel.sims.collectAsState()
        val location by viewModel.location.collectAsState()
        val permissionsGranted by viewModel.permissionsGranted.collectAsState()
        val refresh by viewModel.refreshSecondsFlow.collectAsState(initial = SettingsRepository.DEFAULT_REFRESH)
        val debugLogging by viewModel.debugLoggingEnabledFlow.collectAsState(initial = false)
        val cmExportStatus by viewModel.cmExportStatus.collectAsState()
        val cmExportLoaded by viewModel.cmExportLoaded.collectAsState()

        NavHost(navController = nav, startDestination = "main") {
            composable("main") {
                MainScreen(
                    sims = sims,
                    location = location,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        )
                    },
                    onOpenSettings = { nav.navigate("settings") },
                    cmExportLookup = { enbId, sectorId -> viewModel.lookupCmExport(enbId, sectorId) },
                    cmExportLoaded = cmExportLoaded
                )
            }
            composable("settings") {
                SettingsScreen(
                    currentRefreshSeconds = refresh,
                    onRefreshChange = { viewModel.setRefreshSeconds(it) },
                    debugLoggingEnabled = debugLogging,
                    onDebugLoggingChange = { viewModel.setDebugLoggingEnabled(it) },
                    onOpenDebugLog = { nav.navigate("debug_log") },
                    cmExportStatus = cmExportStatus,
                    onLoadCmExport = { uri -> viewModel.loadCmExport(uri) },
                    onClearCmExport = { viewModel.clearCmExport() },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("debug_log") {
                DebugLogScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
