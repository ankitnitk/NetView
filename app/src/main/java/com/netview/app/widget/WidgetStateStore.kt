package com.netview.app.widget

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

internal val Context.widgetPrefsDataStore by preferencesDataStore("netview_widget")
