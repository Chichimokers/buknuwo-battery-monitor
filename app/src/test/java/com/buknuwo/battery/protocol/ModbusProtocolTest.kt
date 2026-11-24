package com.buknuwo.battery.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModbusProtocolTest {

    private val expectedFrames = listOf(
        "01 03 00 00 00 0D 84 0F",
        "01 03 00 39 00 01 54 07",
        "01 03 00 2E 00 0B 64 04",
        "01 03 00 53 00 10 B4 17"
    )

    @Test
    fun generatedCommandsMatchDocumentedFrames() {
        ModbusReadCommand.entries.forEachIndexed { index, command ->
            assertEquals(expectedFrames[index], command.frame.toHex())
        }
    }

    @Test
    fun crcIsLittleEndianAndValidated() {
        val frame = ModbusReadCommand.MAIN_STATUS.frame
        val crc = ModbusCrc.crc16(frame.copyOfRange(0, 6))
        // 0x0F84 transmitted little-endian as 84 0F (the documented frame).
        assertEquals(0x0F84, crc)
        assertEquals(0x84.toByte(), frame[6]) // low byte first
        assertEquals(0x0F.toByte(), frame[7]) // high byte second
        assertTrue(ModbusCrc.isValid(frame))
        assertFalse(ModbusCrc.isValid(frame.copyOf().also { it[3] = 0x01 }))
    }

    @Test
    fun parserReassemblesFragmentedResponse() {
        val command = ModbusReadCommand.MOS_TEMPERATURE
        // Real-shaped answer: slave, function, byte count, one register, CRC.
        val body = byteArrayOf(0x01, 0x03, 0x02, 0x01, 0x18)
        val frame = ModbusCrc.appendTo(body)
        val parser = ModbusFrameParser()
        val first = parser.feed(frame.copyOfRange(0, 3), 100)
        assertTrue(first.isEmpty())
        val second = parser.feed(frame.copyOfRange(3, frame.size), 110)
        assertEquals(1, second.size)
        assertEquals(command.address, second[0].address)
        assertEquals(1, second[0].quantity) // 1 register, byte count 2
        assertEquals(28.0, second[0].register(0)!! / 10.0, 0.001) // 0x0118 = 280 -> 28.0 °C
    }

    @Test
    fun parserRejectsBadCrcAndResynchronises() {
        val body = byteArrayOf(0x01, 0x03, 0x02, 0x01, 0x18)
        val good = ModbusCrc.appendTo(body)
        val corrupt = good.copyOf().also { it[3] = 0x99.toByte() }
        val parser = ModbusFrameParser()
        val responses = parser.feed(corrupt + byteArrayOf(0x00.toByte(), 0xFF.toByte()) + good, 200)
        assertEquals(1, responses.size)
        assertTrue(parser.drainInvalid().any { it.first.contains("CRC") })
    }
}
