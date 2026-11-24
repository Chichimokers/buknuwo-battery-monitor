package com.buknuwo.battery.telemetry

import com.buknuwo.battery.protocol.ModbusCrc
import com.buknuwo.battery.protocol.ModbusReadCommand
import com.buknuwo.battery.protocol.ModbusResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryDecoderTest {

    private fun response(command: ModbusReadCommand, registers: IntArray): ModbusResponse {
        val payload = ByteArray(registers.size * 2) { i ->
            val word = registers[i / 2]
            if (i % 2 == 0) (word ushr 8).toByte() else (word and 0xFF).toByte()
        }
        val body = byteArrayOf(0x01, 0x03, payload.size.toByte()) + payload
        // CRC is not re-validated by the decoder, but keep the frame well formed.
        ModbusCrc.appendTo(body)
        return ModbusResponse(1, 3, command.address, command.quantity, payload, 1000)
    }

    @Test
    fun decodesMainStatusWithSignedCurrent() {
        val regs = IntArray(13)
        regs[0] = 0xFF38         // -200 => -2.00 A
        regs[1] = 0x0CFC         // 3324 => 33.24 V
        regs[2] = 42
        regs[3] = 98
        regs[4] = 1234           // 12.34 Ah
        regs[6] = 10000          // 100.00 Ah
        regs[7] = 15
        regs[9] = 0x0003
        val status = TelemetryDecoder.decodeMainStatus(response(ModbusReadCommand.MAIN_STATUS, regs))!!
        assertEquals(-2.0, status.currentA!!, 0.001)
        assertEquals(33.24, status.voltageV!!, 0.001)
        assertEquals(42.0, status.soc!!, 0.001)
        assertEquals(98.0, status.soh!!, 0.001)
        assertEquals(12.34, status.cycleAh!!, 0.001)
        assertEquals(100.0, status.nominalAh!!, 0.001)
        assertEquals(15.0, status.cycles!!, 0.001)
        assertEquals(3, status.protectionWords!![0])
        assertEquals(0x0005, status.unmappedRegisters.keys.first())
    }

    @Test
    fun decodesSignedTemperaturesAndCellVoltages() {
        val mos = response(ModbusReadCommand.MOS_TEMPERATURE, intArrayOf(0xFFC6)) // -58 => -5.8 C
        assertEquals(-5.8, TelemetryDecoder.decodeMosTemperature(mos)!!, 0.001)

        val temps = response(ModbusReadCommand.TEMPERATURE_SENSORS, IntArray(11) { if (it == 0) 0x0106 else 0 })
        val decodedTemps = TelemetryDecoder.decodeTemperatureSensors(temps)
        assertEquals(26.2, decodedTemps[0]!!, 0.001)
        assertEquals(0.0, decodedTemps[1]!!, 0.001)

        val cells = response(ModbusReadCommand.CELL_VOLTAGES, IntArray(16) { if (it == 0) 0x0D48 else 0x0D3A })
        val decodedCells = TelemetryDecoder.decodeCellVoltages(cells)
        assertEquals(3.400, decodedCells[0]!!, 0.0001)
        assertEquals(3.386, decodedCells[1]!!, 0.0001)
    }

    @Test
    fun invalidSocIsNotReportedAsRealValue() {
        val regs = IntArray(13)
        regs[2] = 0xFFFF
        regs[3] = 0xFFFF
        val status = TelemetryDecoder.decodeMainStatus(response(ModbusReadCommand.MAIN_STATUS, regs))!!
        assertNull(status.soc)
        assertNull(status.soh)
    }

    @Test
    fun mergeKeepsUnreceivedFieldsNull() {
        val mainRegs = IntArray(13)
        mainRegs[1] = 0x0CFC
        val snap = TelemetryMerger.merge(
            TelemetrySnapshot(),
            response(ModbusReadCommand.MAIN_STATUS, mainRegs),
            500
        )
        assertEquals(33.24, snap.voltageV!!, 0.001)
        assertNull(snap.mosfetTempC)
        assertNull(snap.cellVoltagesV.firstOrNull())
        // Corriente 0 viene en la propia trama, por eso la potencia es 0.0 y no null.
        assertEquals(0.0, snap.powerW!!, 0.001)
    }
}
