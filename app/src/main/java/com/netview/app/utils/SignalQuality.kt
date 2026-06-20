package com.netview.app.utils

import androidx.compose.ui.graphics.Color

/**
 * Maps raw signal metrics to a red/amber/green (RAG) colour so coverage quality
 * can be read at a glance during drive testing. Thresholds follow common
 * industry conventions for each metric. Returns null when the value is null so
 * callers can fall back to the default text colour.
 *
 * Colours are theme-aware: darker, saturated shades on light backgrounds and
 * brighter shades on dark backgrounds for adequate contrast (the green in
 * particular needs to be lighter on dark themes to stay legible).
 */
object SignalQuality {
    private val GREEN_LIGHT = Color(0xFF2E7D32)
    private val GREEN_DARK = Color(0xFF69F0AE) // bright teal-green, high contrast on dark
    private val AMBER_LIGHT = Color(0xFFF9A825)
    private val AMBER_DARK = Color(0xFFFFD54F)
    private val RED_LIGHT = Color(0xFFC62828)
    private val RED_DARK = Color(0xFFFF6E6E)

    fun green(dark: Boolean) = if (dark) GREEN_DARK else GREEN_LIGHT
    fun amber(dark: Boolean) = if (dark) AMBER_DARK else AMBER_LIGHT
    fun red(dark: Boolean) = if (dark) RED_DARK else RED_LIGHT

    /** good = green at/above; fair = amber at/above; below fair = red. */
    private fun rag(value: Int, good: Int, fair: Int, dark: Boolean): Color = when {
        value >= good -> green(dark)
        value >= fair -> amber(dark)
        else -> red(dark)
    }

    // LTE / NR RSRP (dBm) — also used for NR SS-RSRP / CSI-RSRP
    fun rsrp(v: Int?, dark: Boolean): Color? = v?.let { rag(it, good = -95, fair = -110, dark) }

    // LTE / NR RSRQ (dB)
    fun rsrq(v: Int?, dark: Boolean): Color? = v?.let { rag(it, good = -10, fair = -15, dark) }

    // LTE RSSNR / NR SINR (dB)
    fun sinr(v: Int?, dark: Boolean): Color? = v?.let { rag(it, good = 13, fair = 0, dark) }

    // WCDMA RSCP (dBm)
    fun rscp(v: Int?, dark: Boolean): Color? = v?.let { rag(it, good = -85, fair = -100, dark) }

    // WCDMA Ec/No (dB)
    fun ecNo(v: Int?, dark: Boolean): Color? = v?.let { rag(it, good = -9, fair = -13, dark) }
}
