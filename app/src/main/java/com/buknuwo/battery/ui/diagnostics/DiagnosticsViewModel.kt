package com.buknuwo.battery.ui.diagnostics

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buknuwo.battery.bluetooth.BleManager
import com.buknuwo.battery.bluetooth.ConnectionState
import com.buknuwo.battery.diagnostics.BleDiagnostics
import com.buknuwo.battery.diagnostics.ReportGenerator
import com.buknuwo.battery.monitoring.MonitoringService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val diagnostics: BleDiagnostics,
    private val ble: BleManager,
    private val reportGenerator: ReportGenerator,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val entries = diagnostics.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val state = ble.connectionState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.DISCONNECTED)
    val error = ble.lastError.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _probeResult = MutableStateFlow<String?>(null)
    val probeResult: StateFlow<String?> = _probeResult.asStateFlow()

    private val _userNote = MutableStateFlow("")
    val userNote: StateFlow<String> = _userNote.asStateFlow()

    private val _reportStatus = MutableStateFlow<String?>(null)
    val reportStatus: StateFlow<String?> = _reportStatus.asStateFlow()

    fun setUserNote(value: String) { _userNote.value = value }

    fun probeRxRead() = viewModelScope.launch {
        _probeResult.value = "Leyendo RX…"
        val result = ble.probeRxRead()
        _probeResult.value = result.fold(
            onSuccess = { bytes ->
                if (bytes.isEmpty()) "Lectura devolvió 0 bytes" else
                    "Lectura devolvió ${bytes.size} bytes: ${bytes.joinToString(" ") { "%02X".format(it) }}"
            },
            onFailure = { "Error: ${it.message}" }
        )
    }

    /** Generates a .log file and opens the Android share sheet with WhatsApp as first option. */
    fun shareReport() = viewModelScope.launch {
        _reportStatus.value = "Generando informe…"
        runCatching {
            val file = reportGenerator.generate(_userNote.value)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Buknuwo Battery Monitor - ${file.name}")
                putExtra(Intent.EXTRA_TEXT, "Informe de diagnóstico adjunto (.log).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Compartir informe con…")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val whatsappInstalled = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("com.whatsapp", 0)
                true
            }.getOrDefault(false)
            if (whatsappInstalled) {
                val whatsapp = Intent(sendIntent).setPackage("com.whatsapp")
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(whatsapp))
            }
            context.startActivity(chooser)
            _reportStatus.value = "Informe listo: ${file.name} (${file.length()} bytes)"
        }.onFailure { error ->
            _reportStatus.value = "No se pudo generar el informe: ${error.message}"
            Toast.makeText(context, "Error al generar el informe", Toast.LENGTH_LONG).show()
        }
    }

    fun clearReportStatus() { _reportStatus.value = null }
    fun clearLogs() {
        diagnostics.clear()
        _reportStatus.value = null
    }
    fun startMonitoring() = MonitoringService.start(context)
    fun stopMonitoring() = MonitoringService.stop(context)
}
