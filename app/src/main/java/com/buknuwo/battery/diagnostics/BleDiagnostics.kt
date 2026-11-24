package com.buknuwo.battery.diagnostics

import com.buknuwo.battery.protocol.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleDiagnostics @Inject constructor() {

    enum class Level { INFO, WARN, ERROR, TX, RX }

    data class Entry(
        val id: Long,
        val timestampMs: Long,
        val level: Level,
        val source: String,
        val message: String
    )

    private var nextId = 0L
    private val maxEntries = 300

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun log(level: Level, source: String, message: String) {
        val entry = Entry(nextId++, System.currentTimeMillis(), level, source, message)
        _entries.value = (_entries.value + entry).takeLast(maxEntries)
    }

    fun tx(frame: ByteArray) = log(Level.TX, "GATT", "TX -> ${frame.toHex()}")
    fun rx(chunk: ByteArray) = log(Level.RX, "GATT", "RX <- ${chunk.toHex()}")
    fun error(source: String, message: String) = log(Level.ERROR, source, message)
    fun clear() {
        _entries.value = emptyList()
    }
}
