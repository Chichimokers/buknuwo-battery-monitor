package com.buknuwo.battery.diagnostics

import android.content.Context
import androidx.core.content.ContextCompat
import com.buknuwo.battery.protocol.toHex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context
) {

    enum class Level { INFO, WARN, ERROR, TX, RX }

    data class Entry(
        val id: Long,
        val timestampMs: Long,
        val level: Level,
        val source: String,
        val message: String
    ) {
        fun format(): String = "${ts(timestampMs)} [$level] [$source] $message"
    }

    private var nextId = 0L
    private val maxEntries = 500
    private val maxLogBytes = 512 * 1024
    private val logFile = File(context.filesDir, "logs" + File.separator + "buknuwo_ble.log")

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    @Volatile
    private var crashSection: String = ""

    init {
        runCatching {
            logFile.parentFile?.mkdirs()
            if (logFile.exists()) {
                appendLine("----- SESIÓN INICIADA ${ts(System.currentTimeMillis())} -----")
            } else {
                logFile.writeText("Buknuwo Battery Monitor - registro de diagnóstico\n")
                appendLine("----- SESIÓN INICIADA ${ts(System.currentTimeMillis())} -----")
            }
        }
    }

    fun log(level: Level, source: String, message: String) {
        val entry = Entry(nextId++, System.currentTimeMillis(), level, source, message)
        _entries.value = (_entries.value + entry).takeLast(maxEntries)
        appendLine(entry.format())
    }

    fun tx(frame: ByteArray) = log(Level.TX, "GATT", "TX -> ${frame.toHex()}")
    fun rx(chunk: ByteArray) = log(Level.RX, "GATT", "RX <- ${chunk.toHex()}")
    fun error(source: String, message: String) = log(Level.ERROR, source, message)

    /** Called by the global uncaught exception handler; survives process restarts. */
    fun recordCrash(threadName: String, throwable: Throwable) {
        val text = buildString {
            append("EXCEPCIÓN NO CONTROLADA en hilo $threadName a las ${ts(System.currentTimeMillis())}\n")
            append(throwable.stackTraceToString())
        }
        crashSection = text
        appendLine(text)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun allEntries(): List<Entry> = _entries.value

    fun fileText(): String = runCatching { logFile.readText() }.getOrDefault("(no se pudo leer el registro)")

    private fun appendLine(line: String) {
        runCatching {
            if (logFile.exists() && logFile.length() > maxLogBytes) {
                val content = logFile.readText()
                logFile.writeText(content.takeLast(maxLogBytes / 2))
            }
            logFile.appendText(line + "\n")
        }
    }

    companion object {
        private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        @Synchronized
        fun ts(timestampMs: Long): String = timeFormat.format(Date(timestampMs))
    }
}
