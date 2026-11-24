package com.buknuwo.battery.ui.devices

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buknuwo.battery.bluetooth.BleManager
import com.buknuwo.battery.bluetooth.BluetoothPermissions
import com.buknuwo.battery.monitoring.MonitoringService
import com.buknuwo.battery.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceScanViewModel @Inject constructor(
    private val ble: BleManager,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val devices = ble.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val connectionState = ble.connectionState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.buknuwo.battery.bluetooth.ConnectionState.DISCONNECTED)
    val targetAddress = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.buknuwo.battery.settings.AppSettings())

    private val _permissionRequest = Channel<Array<String>>(Channel.BUFFERED)
    val permissionRequest = _permissionRequest.receiveAsFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        if (!BluetoothPermissions.hasBlePermissions(context)) {
            viewModelScope.launch { _permissionRequest.send(BluetoothPermissions.bleRequired()) }
        }
    }

    fun startScan() {
        if (!BluetoothPermissions.hasBlePermissions(context)) {
            refresh()
            return
        }
        ble.startScan()
    }

    fun stopScan() = ble.stopScan()

    fun select(name: String, address: String) {
        viewModelScope.launch {
            settings.saveTarget(address, name)
            MonitoringService.start(context)
            _message.value = "Batería guardada. Iniciando monitorización…"
        }
    }

    fun connect() {
        MonitoringService.start(context)
        _message.value = "Solicitada conexión en segundo plano"
    }

    fun disconnect() {
        MonitoringService.stop(context)
        _message.value = "Monitorización detenida"
    }

    fun clearMessage() { _message.value = null }
}
