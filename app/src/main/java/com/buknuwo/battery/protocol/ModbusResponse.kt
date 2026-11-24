package com.buknuwo.battery.protocol

/** A validated MODBUS RTU response coming from a real BLE notification. */
data class ModbusResponse(
    val slaveId: Int,
    val function: Int,
    val address: Int,
    val quantity: Int,
    val payload: ByteArray,
    val receivedAtMs: Long
) {
    fun register(index: Int): Int? {
        val offset = index * 2
        if (offset + 1 >= payload.size) return null
        return (payload[offset].toInt() and 0xFF shl 8) or (payload[offset + 1].toInt() and 0xFF)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ModbusResponse) return false
        return slaveId == other.slaveId && function == other.function &&
            address == other.address && quantity == other.quantity &&
            payload.contentEquals(other.payload) && receivedAtMs == other.receivedAtMs
    }

    override fun hashCode(): Int {
        var result = slaveId
        result = 31 * result + function
        result = 31 * result + address
        result = 31 * result + quantity
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + receivedAtMs.hashCode()
        return result
    }
}

/** Result of parsing one candidate frame from the notification byte stream. */
sealed interface FrameParseResult {
    data class Success(val response: ModbusResponse) : FrameParseResult
    data class Invalid(val reason: String, val raw: ByteArray) : FrameParseResult
    data object Incomplete : FrameParseResult
}
