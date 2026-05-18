package com.netview.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "netview_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val REFRESH_SECONDS = intPreferencesKey("refresh_seconds")
        private val DEBUG_LOGGING = booleanPreferencesKey("debug_logging")
        private val CMEXPORT_URI = stringPreferencesKey("cmexport_uri")
        const val DEFAULT_REFRESH = 2
        const val MIN_REFRESH = 1
        const val MAX_REFRESH = 60
    }

    val refreshSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[REFRESH_SECONDS] ?: DEFAULT_REFRESH
    }

    val debugLoggingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DEBUG_LOGGING] ?: false
    }

    val cmExportUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[CMEXPORT_URI]
    }

    suspend fun setRefreshSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(MIN_REFRESH, MAX_REFRESH)
        context.dataStore.edit { it[REFRESH_SECONDS] = clamped }
    }

    suspend fun setDebugLoggingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DEBUG_LOGGING] = enabled }
    }

    suspend fun setCmExportUri(uri: String?) {
        context.dataStore.edit {
            if (uri != null) it[CMEXPORT_URI] = uri else it.remove(CMEXPORT_URI)
        }
    }
}
