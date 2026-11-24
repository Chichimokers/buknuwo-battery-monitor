package com.buknuwo.battery.telemetry

import com.buknuwo.battery.protocol.ModbusResponse

/**
 * Decodes ONLY documented registers. Registers not present in the provided BMS
 * documentation (0x0005, 0x0008, 0x000C) are returned as raw values for diagnostics.
 *
 * Offsets below follow the register table supplied for this BMS. They must be
 * confirmed against real frames; values that cannot be decoded remain null.
 */
object TelemetryDecoder {

    private fun u16(payload: ByteArray, index: Int): Int? {
        val offset = index * 2
        if (offset + 1 >= payload.size) return null
        return (payload[offset].toInt() and 0xFF shl 8) or (payload[offset + 1].toInt() and 0xFF)
    }

    private fun i16(payload: ByteArray, index: Int): Int? {
        val value = u16(payload, index) ?: return null
        return if (value >= 0x8000) value - 0x10000 else value
    }

    fun decodeMainStatus(response: ModbusResponse): MainStatus? {
        if (response.payload.size < 26) return null
        val raw5 = u16(response.payload, 5)
        val raw8 = u16(response.payload, 8)
        val raw12 = u16(response.payload, 12)
        val unmapped = buildMap {
            if (raw5 != null) put(0x0005, raw5)
            if (raw8 != null) put(0x0008, raw8)
            if (raw12 != null) put(0x000C, raw12)
        }
        return MainStatus(
            currentA = i16(response.payload, 0)?.let { it / 100.0 },
            voltageV = u16(response.payload, 1)?.let { it / 100.0 },
            // Out-of-range values are treated as invalid instead of being clipped into
            // a plausible-looking SOC/SOH that the BMS never reported.
            soc = u16(response.payload, 2)?.toDouble()?.takeIf { it in 0.0..100.0 },
            soh = u16(response.payload, 3)?.toDouble()?.takeIf { it in 0.0..100.0 },
            cycleAh = u16(response.payload, 4)?.let { it / 100.0 },
            nominalAh = u16(response.payload, 6)?.let { it / 100.0 },
            cycles = u16(response.payload, 7)?.toDouble(),
            protectionWords = listOfNotNull(
                u16(response.payload, 9),
                u16(response.payload, 10),
                u16(response.payload, 11)
            ).takeIf { it.size == 3 },
            unmappedRegisters = unmapped
        )
    }

    /** Signed int16 / 10 => degrees Celsius. */
    fun decodeMosTemperature(response: ModbusResponse): Double? =
        i16(response.payload, 0)?.let { it / 10.0 }

    /** 11 signed int16 / 10 values; missing sensors remain null. */
    fun decodeTemperatureSensors(response: ModbusResponse): List<Double?> =
        (0 until 11).map { index -> i16(response.payload, index)?.let { it / 10.0 } }

    /** 16 unsigned uint16 / 1000 values; missing cells remain null. */
    fun decodeCellVoltages(response: ModbusResponse): List<Double?> =
        (0 until 16).map { index -> u16(response.payload, index)?.let { it / 1000.0 } }
}
