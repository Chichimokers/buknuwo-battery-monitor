package com.buknuwo.battery.bluetooth

/** Explicit, user-visible BLE lifecycle states. Never infer "connected" from a scan result. */
enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    DISCOVERING_SERVICES,
    SUBSCRIBING,
    CONNECTED,
    RECEIVING_DATA,
    ERROR
}

data class BleDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int,
    val isTarget: Boolean = false,
    val lastSeenMs: Long = 0
)
