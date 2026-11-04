package com.sharefiles.app.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Recent events, shown on the settings screen so problems can be read on the device itself. */
object AppLog {
    private const val MAX_LINES = 300
    private val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val state = MutableStateFlow<List<String>>(emptyList())

    val lines: StateFlow<List<String>> = state

    fun add(message: String) {
        Log.i("ShareFiles", message)
        val stamp = synchronized(time) { time.format(Date()) }
        state.update { (it + "$stamp  $message").takeLast(MAX_LINES) }
    }
}
