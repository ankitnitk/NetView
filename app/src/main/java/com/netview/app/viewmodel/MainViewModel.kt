package com.netview.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netview.app.data.LocationData
import com.netview.app.data.LocationRepository
import com.netview.app.data.SettingsRepository
import com.netview.app.data.SimSlotData
import com.netview.app.data.TelephonyRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val telephonyRepo = TelephonyRepository(app)
    private val locationRepo = LocationRepository(app)
    private val settingsRepo = SettingsRepository(app)

    private val _sims = MutableStateFlow<List<SimSlotData>>(emptyList())
    val sims: StateFlow<List<SimSlotData>> = _sims.asStateFlow()

    private val _location = MutableStateFlow<LocationData?>(null)
    val location: StateFlow<LocationData?> = _location.asStateFlow()

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    val refreshSecondsFlow = settingsRepo.refreshSeconds

    private var refreshSeconds = SettingsRepository.DEFAULT_REFRESH

    init {
        viewModelScope.launch {
            settingsRepo.refreshSeconds.collect { refreshSeconds = it }
        }
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(refreshSeconds * 1000L)
            }
        }
        // Re-read sims immediately when PhysicalChannelConfig callback fires (CA data)
        viewModelScope.launch {
            telephonyRepo.caFlow.collect { if (telephonyRepo.hasPermissions()) refresh() }
        }
    }

    fun onPermissionsGranted() {
        _permissionsGranted.value = true
        locationRepo.start()
        refresh()
    }

    fun refresh() {
        if (!telephonyRepo.hasPermissions()) return
        _sims.value = telephonyRepo.readAllSims()
        _location.value = locationRepo.current()
    }

    fun setRefreshSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepo.setRefreshSeconds(seconds) }
    }

    override fun onCleared() {
        super.onCleared()
        locationRepo.stop()
        telephonyRepo.release()
    }
}
