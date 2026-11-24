package com.buknuwo.battery.protocol

/**
 * CRC-16/MODBUS: init 0xFFFF, reflected polynomial 0xA001, transmitted little-endian.
 * Pure Kotlin: no Android dependencies so it can be unit-tested deterministically.
 */
object ModbusCrc {

    fun crc16(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    /** Appends CRC low byte first, as MODBUS RTU requires. */
    fun appendTo(frame: ByteArray): ByteArray {
        val crc = crc16(frame)
        return frame + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte())
    }

    /** Returns true when the 2 trailing bytes match the computed CRC. */
    fun isValid(frame: ByteArray): Boolean {
        if (frame.size < 4) return false
        val body = frame.copyOfRange(0, frame.size - 2)
        val received = (frame[frame.size - 1].toInt() and 0xFF shl 8) or
            (frame[frame.size - 2].toInt() and 0xFF)
        return crc16(body) == received
    }
}
