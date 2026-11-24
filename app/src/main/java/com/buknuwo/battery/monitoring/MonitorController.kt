package com.buknuwo.battery.monitoring

import com.buknuwo.battery.bluetooth.BleDeviceInfo
import com.buknuwo.battery.bluetooth.BleManager
import com.buknuwo.battery.bluetooth.ConnectionState
import com.buknuwo.battery.diagnostics.BleDiagnostics
import com.buknuwo.battery.protocol.ModbusFrameParser
import com.buknuwo.battery.protocol.ModbusReadCommand
import com.buknuwo.battery.protocol.ModbusResponse
import com.buknuwo.battery.settings.SettingsRepository
import com.buknuwo.battery.telemetry.TelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serialised GATT polling: one request at a time, response correlated by address,
 * CRC validated by the parser, with timeout and watchdog for missing telemetry.
 */
@Singleton
class MonitorController @Inject constructor(
    private val ble: BleManager,
    private val telemetry: TelemetryRepository,
    private val settings: SettingsRepository,
    private val diagnostics: BleDiagnostics
) {
    @Volatile private var running = false
    private var jobs = mutableListOf<Job>()
    private val parser = ModbusFrameParser()
    private val responses = MutableSharedFlow<ModbusResponse>(extraBufferCapacity = 32)

    fun isRunning(): Boolean = running

    fun start(scope: CoroutineScope, device: BleDeviceInfo) {
        if (running) return
        running = true
        jobs = mutableListOf()

        jobs += scope.launch {
            ble.notifications.onEach { chunk ->
                parser.feed(chunk, System.currentTimeMillis()).forEach { response ->
                    responses.tryEmit(response)
                }
                parser.drainInvalid().forEach { (reason, _) ->
                    diagnostics.log(BleDiagnostics.Level.WARN, "MODBUS", "Trama descartada: $reason")
                }
            }.launchIn(this)
        }

        jobs += scope.launch {
            while (running) {
                val state = ble.connectionState.value
                if (state != ConnectionState.CONNECTED && state != ConnectionState.RECEIVING_DATA) {
                    delay(500)
                    continue
                }
                val prefs = settings.settings.first()
                for (command in ModbusReadCommand.entries) {
                    if (!running) break
                    val sentAt = System.currentTimeMillis()
                    val result = ble.writeFrame(command.frame)
                    if (result.isFailure) {
                        diagnostics.error("MODBUS", "Fallo al enviar ${command.name}: ${result.exceptionOrNull()?.message}")
                        break
                    }
                    try {
                        val response = withTimeout(prefs.requestTimeoutMs) {
                            responses.first {
                                it.address == command.address && it.receivedAtMs >= sentAt
                            }
                        }
                        telemetry.onResponse(response)
                    } catch (_: Exception) {
                        parser.reset()
                        diagnostics.log(
                            BleDiagnostics.Level.WARN, "MODBUS",
                            "Sin respuesta para ${command.name} en ${prefs.requestTimeoutMs} ms"
                        )
                    }
                    delay(120)
                }
                delay(prefs.pollIntervalMs)
            }
        }

        jobs += scope.launch {
            ble.disconnected.onEach {
                if (!running) return@onEach
                diagnostics.log(BleDiagnostics.Level.WARN, "MONITOR", "Desconexión detectada; reconectando")
                reconnect(device)
            }.launchIn(this)
        }

        jobs += scope.launch {
            while (running) {
                delay(1_000)
                val prefs = settings.settings.first()
                val last = telemetry.current().lastDataAtMs
                if (last > 0 && System.currentTimeMillis() - last > prefs.watchdogTimeoutMs &&
                    ble.connectionState.value == ConnectionState.RECEIVING_DATA
                ) {
                    diagnostics.error("WATCHDOG", "Ausencia de telemetría > ${prefs.watchdogTimeoutMs} ms")
                    ble.disconnect()
                }
            }
        }
    }

    private suspend fun reconnect(device: BleDeviceInfo) {
        val prefs = settings.settings.first()
        if (!prefs.reconnectEnabled) return
        var attempt = 1
        while (running && attempt <= prefs.maxReconnectAttempts) {
            delay(minOf(1_000L * attempt, 10_000L))
            diagnostics.log(BleDiagnostics.Level.INFO, "MONITOR", "Reconexión $attempt/${prefs.maxReconnectAttempts}")
            val result = ble.connect(device)
            if (result.isSuccess) return
            attempt++
        }
        diagnostics.error("MONITOR", "Se agotaron los intentos de reconexión")
    }

    fun stop() {
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        parser.reset()
    }
}
