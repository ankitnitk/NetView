package com.netview.app.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Thread-safe circular log buffer. Surfaced in the in-app Debug Log screen so the user
 * can debug without ADB. Also mirrored to logcat at INFO level for ADB sessions.
 */
object DebugLog {
    private const val TAG = "NetView"
    private const val MAX_LINES = 500
    private val buffer = ConcurrentLinkedDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var enabled: Boolean = false

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun d(category: String, msg: String) { if (enabled) append("D", category, msg) }
    fun i(category: String, msg: String) { if (enabled) append("I", category, msg) }
    fun w(category: String, msg: String) { if (enabled) append("W", category, msg) }

    private fun append(level: String, category: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$category: $msg"
        Log.i(TAG, "$category: $msg")
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.pollFirst()
        _lines.value = buffer.toList()
    }

    fun clear() {
        buffer.clear()
        _lines.value = emptyList()
    }

    fun snapshot(): String = buffer.joinToString("\n")
}
