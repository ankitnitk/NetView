package com.netview.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.netview.app.service.MonitoringService

class WidgetRefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // Delegate to MonitoringService which has a warm TelephonyRepository (callbacks already
        // registered, CA cache populated, CMExport repos loaded). Much better than spinning up
        // a cold repo here. If background monitoring is off the service starts briefly, does one
        // refresh, then the OS will kill it since START_STICKY with no ongoing work.
        MonitoringService.refreshNow(context)
    }
}
