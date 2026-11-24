package com.buknuwo.battery.ui.diagnostics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buknuwo.battery.bluetooth.BleManager
import com.buknuwo.battery.diagnostics.BleDiagnostics
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
    @ApplicationContext private val context: Context
) : ViewModel() {
    val entries = diagnostics.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val state = ble.connectionState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.buknuwo.battery.bluetooth.ConnectionState.DISCONNECTED)
    val error = ble.lastError.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _probeResult = MutableStateFlow<String?>(null)
    val probeResult: StateFlow<String?> = _probeResult.asStateFlow()

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

    fun clearLogs() = diagnostics.clear()
    fun startMonitoring() = MonitoringService.start(context)
    fun stopMonitoring() = MonitoringService.stop(context)
}
