package com.buknuwo.battery.telemetry

import com.buknuwo.battery.protocol.ModbusReadCommand

/**
 * A snapshot assembled only from real decoded BMS frames.
 * Every field is nullable: if the corresponding frame was never received, or is stale,
 * the UI MUST show "SIN DATOS" instead of a fabricated value.
 */
data class TelemetrySnapshot(
    val sequence: Long = 0,
    val firstReceivedAtMs: Long = 0,
    val lastDataAtMs: Long = 0,
    val sourceTimesMs: Map<ModbusReadCommand, Long> = emptyMap(),

    val currentA: Double? = null,
    val voltageV: Double? = null,
    val soc: Double? = null,
    val soh: Double? = null,
    val cycleAh: Double? = null,
    val nominalAh: Double? = null,
    val cycles: Double? = null,

    /** Raw 16-bit protection words at 0x0009, 0x000A and 0x000B. Bit meanings are NOT assumed. */
    val protectionWords: List<Int>? = null,

    val mosfetTempC: Double? = null,
    val temperatureSensorsC: List<Double?> = emptyList(),
    val cellVoltagesV: List<Double?> = emptyList(),

    /** Registers present in a frame but not documented: kept for diagnostics, never renamed. */
    val unmappedRegisters: Map<Int, Int> = emptyMap()
) {
    val powerW: Double?
        get() = if (voltageV != null && currentA != null) voltageV!! * currentA!! else null

    val maxCellV: Double? get() = cellVoltagesV.filterNotNull().maxOrNull()
    val minCellV: Double? get() = cellVoltagesV.filterNotNull().minOrNull()
    val cellDeltaV: Double?
        get() = if (maxCellV != null && minCellV != null) maxCellV!! - minCellV!! else null

    /** True only while at least one real frame has arrived. */
    val hasData: Boolean get() = lastDataAtMs > 0
}

data class MainStatus(
    val currentA: Double?,
    val voltageV: Double?,
    val soc: Double?,
    val soh: Double?,
    val cycleAh: Double?,
    val nominalAh: Double?,
    val cycles: Double?,
    val protectionWords: List<Int>?,
    val unmappedRegisters: Map<Int, Int>
)

/** User-defined bit mapping because the BMS MOS-bit semantics are not confirmed. */
data class MosBitMapping(
    val chargeRegisterIndex: Int? = null,
    val chargeBit: Int = 0,
    val dischargeRegisterIndex: Int? = null,
    val dischargeBit: Int = 0
) {
    val isConfigured: Boolean
        get() = chargeRegisterIndex != null || dischargeRegisterIndex != null

    fun chargeActive(words: List<Int>?): Boolean? = bitValue(chargeRegisterIndex, chargeBit, words)
    fun dischargeActive(words: List<Int>?): Boolean? = bitValue(dischargeRegisterIndex, dischargeBit, words)

    private fun bitValue(index: Int?, bit: Int, words: List<Int>?): Boolean? {
        if (index == null || words == null || index !in words.indices || bit !in 0..15) return null
        return (words[index] ushr bit) and 1 == 1
    }
}
