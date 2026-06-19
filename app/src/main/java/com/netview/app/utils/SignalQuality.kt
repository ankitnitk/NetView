package com.netview.app.utils

import androidx.compose.ui.graphics.Color

/**
 * Maps raw signal metrics to a red/amber/green (RAG) colour so coverage quality
 * can be read at a glance during drive testing. Thresholds follow common
 * industry conventions for each metric. Returns null when the value is null so
 * callers can fall back to the default text colour.
 */
object SignalQuality {
    val GREEN = Color(0xFF2E7D32)
    val AMBER = Color(0xFFF9A825)
    val RED = Color(0xFFC62828)

    /** good = green at/above; fair = amber at/above; below fair = red. */
    private fun rag(value: Int, good: Int, fair: Int): Color = when {
        value >= good -> GREEN
        value >= fair -> AMBER
        else -> RED
    }

    // LTE / NR RSRP (dBm) — also used for NR SS-RSRP / CSI-RSRP
    fun rsrp(v: Int?): Color? = v?.let { rag(it, good = -95, fair = -110) }

    // LTE / NR RSRQ (dB)
    fun rsrq(v: Int?): Color? = v?.let { rag(it, good = -10, fair = -15) }

    // LTE RSSNR / NR SINR (dB)
    fun sinr(v: Int?): Color? = v?.let { rag(it, good = 13, fair = 0) }

    // WCDMA RSCP (dBm)
    fun rscp(v: Int?): Color? = v?.let { rag(it, good = -85, fair = -100) }

    // WCDMA Ec/No (dB)
    fun ecNo(v: Int?): Color? = v?.let { rag(it, good = -9, fair = -13) }
}
