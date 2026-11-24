package com.buknuwo.battery.protocol

/**
 * Reassembles MODBUS RTU frames from an arbitrary stream of BLE notification chunks.
 *
 * The BMS answers only to the four documented read requests. Each response has a
 * distinctive byte count, so the request can be correlated without guessing offsets.
 * Invalid frames are discarded and the parser resynchronises looking for 01 03.
 */
class ModbusFrameParser {
    private val buffer = ArrayDeque<Byte>()
    private val invalidSink = mutableListOf<Pair<String, ByteArray>>()

    val bufferedByteCount: Int get() = buffer.size

    fun feed(chunk: ByteArray, nowMs: Long): List<ModbusResponse> {
        chunk.forEach { buffer.addLast(it) }
        val result = mutableListOf<ModbusResponse>()
        var guard = 0
        while (buffer.isNotEmpty() && guard++ < 512) {
            // 1. Find a plausible MODBUS header (slave 0x01, function 0x03).
            var header = -1
            for (i in 0..(buffer.size - 2)) {
                if ((buffer[i].toInt() and 0xFF) == 0x01 &&
                    (buffer[i + 1].toInt() and 0xFF) == 0x03
                ) {
                    header = i
                    break
                }
            }
            if (header < 0) {
                buffer.clear()
                break
            }
            repeat(header) { buffer.removeFirst() }

            // 2. Byte count must be known and correspond to one of our four requests.
            if (buffer.size < 3) break
            val byteCount = buffer[2].toInt() and 0xFF
            val command = ModbusReadCommand.fromByteCount(byteCount)
            if (command == null) {
                invalidSink += "ByteCount desconocido ($byteCount)" to ByteArray(0)
                buffer.removeFirst() // skip false header, keep searching
                continue
            }

            // 3. Wait for the whole frame before validating anything.
            val frameLength = 3 + byteCount + 2
            if (buffer.size < frameLength) break

            val frame = ByteArray(frameLength) { buffer[it] }
            if (!ModbusCrc.isValid(frame)) {
                invalidSink += "CRC inválido" to frame
                buffer.removeFirst() // resynchronise; the 01 03 may be payload data
                continue
            }
            repeat(frameLength) { buffer.removeFirst() }

            val payload = frame.copyOfRange(3, 3 + byteCount)
            result += ModbusResponse(
                slaveId = 0x01,
                function = 0x03,
                address = command.address,
                quantity = command.quantity,
                payload = payload,
                receivedAtMs = nowMs
            )
        }
        return result
    }

    /** Pure single-frame helper used by tests and by synthetic diagnostics. */
    fun parseFrame(bytes: ByteArray, nowMs: Long): FrameParseResult {
        val before = invalidSink.size
        val responses = feed(bytes, nowMs)
        if (responses.isNotEmpty()) return FrameParseResult.Success(responses.first())
        if (invalidSink.size > before) {
            return FrameParseResult.Invalid(invalidSink.last().first, invalidSink.last().second)
        }
        return FrameParseResult.Incomplete
    }

    fun drainInvalid(): List<Pair<String, ByteArray>> {
        val copy = invalidSink.toList()
        invalidSink.clear()
        return copy
    }

    /** Called when a request times out; partial bytes cannot belong to a valid answer. */
    fun reset() {
        buffer.clear()
        invalidSink.clear()
    }
}
