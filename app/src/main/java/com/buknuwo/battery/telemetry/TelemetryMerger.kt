package com.buknuwo.battery.telemetry

import com.buknuwo.battery.protocol.ModbusReadCommand
import com.buknuwo.battery.protocol.ModbusResponse

/** Pure merge logic: a frame only overwrites fields it actually contains. */
object TelemetryMerger {

    fun merge(
        current: TelemetrySnapshot,
        response: ModbusResponse,
        nowMs: Long
    ): TelemetrySnapshot {
        val command = ModbusReadCommand.fromAddress(response.address) ?: return current
        val next = when (command) {
            ModbusReadCommand.MAIN_STATUS -> {
                val status = TelemetryDecoder.decodeMainStatus(response) ?: return current
                current.copy(
                    currentA = status.currentA,
                    voltageV = status.voltageV,
                    soc = status.soc,
                    soh = status.soh,
                    cycleAh = status.cycleAh,
                    nominalAh = status.nominalAh,
                    cycles = status.cycles,
                    protectionWords = status.protectionWords,
                    unmappedRegisters = status.unmappedRegisters
                )
            }

            ModbusReadCommand.MOS_TEMPERATURE -> {
                val temp = TelemetryDecoder.decodeMosTemperature(response) ?: return current
                current.copy(mosfetTempC = temp)
            }

            ModbusReadCommand.TEMPERATURE_SENSORS ->
                current.copy(temperatureSensorsC = TelemetryDecoder.decodeTemperatureSensors(response))

            ModbusReadCommand.CELL_VOLTAGES ->
                current.copy(cellVoltagesV = TelemetryDecoder.decodeCellVoltages(response))
        }
        return next.copy(
            sequence = current.sequence + 1,
            firstReceivedAtMs = if (current.firstReceivedAtMs == 0L) nowMs else current.firstReceivedAtMs,
            lastDataAtMs = nowMs,
            sourceTimesMs = current.sourceTimesMs + (command to nowMs)
        )
    }
}
