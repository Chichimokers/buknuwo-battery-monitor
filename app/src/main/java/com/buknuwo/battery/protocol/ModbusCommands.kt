package com.buknuwo.battery.protocol

/**
 * The ONLY write operations this application is allowed to perform on the BMS,
 * besides the BLE CCCD subscription. No configuration/firmware/MOS writes exist.
 */
enum class ModbusReadCommand(
    val address: Int,
    val quantity: Int,
    val description: String,
    val expectedByteCount: Int = quantity * 2
) {
    MAIN_STATUS(0x0000, 0x000D, "Estado principal"),
    MOS_TEMPERATURE(0x0039, 0x0001, "Temperatura MOSFET"),
    TEMPERATURE_SENSORS(0x002E, 0x000B, "Sensores de temperatura"),
    CELL_VOLTAGES(0x0053, 0x0010, "Voltajes de celdas");

    val frame: ByteArray
        get() = ModbusCrc.appendTo(
            byteArrayOf(
                0x01, // slave id
                0x03, // read holding registers
                (address ushr 8).toByte(),
                (address and 0xFF).toByte(),
                (quantity ushr 8).toByte(),
                (quantity and 0xFF).toByte()
            )
        )

    companion object {
        fun fromAddress(address: Int): ModbusReadCommand? =
            entries.firstOrNull { it.address == address }

        fun fromByteCount(byteCount: Int): ModbusReadCommand? =
            entries.firstOrNull { it.expectedByteCount == byteCount }
    }
}

/** Hex string used by diagnostics and by the unit tests. */
fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
