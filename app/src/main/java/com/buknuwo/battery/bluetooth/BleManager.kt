package com.buknuwo.battery.bluetooth

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BleManager {
    val devices: StateFlow<List<BleDeviceInfo>>
    val connectionState: StateFlow<ConnectionState>
    val lastError: StateFlow<String?>
    val notifications: SharedFlow<ByteArray>
    val disconnected: SharedFlow<Unit>

    fun startScan()
    fun stopScan()
    suspend fun connect(device: BleDeviceInfo): Result<Unit>
    fun disconnect()
    suspend fun subscribeNotifications(): Result<Unit>
    suspend fun writeFrame(frame: ByteArray): Result<Unit>

    /** Diagnostic probe only: never used as a telemetry source. */
    suspend fun probeRxRead(): Result<ByteArray>
    fun release()
    fun setError(message: String?)
}
