package com.buknuwo.battery.telemetry

/** Single source of truth used by the UI alert builder and by the rule engine. */
data class TelemetryVariable(
    val id: String,
    val label: String,
    val unit: String,
    val category: String,
    val kind: Kind = Kind.NUMBER,
    val evaluate: (TelemetryContext) -> VariableValue?
) {
    enum class Kind { NUMBER, BOOLEAN }
}

sealed interface VariableValue {
    data class Number(val value: Double) : VariableValue
    data class BooleanValue(val value: Boolean) : VariableValue

    fun asNumber(): Double? = when (this) {
        is Number -> value
        is BooleanValue -> if (value) 1.0 else 0.0
    }

    fun asBoolean(): Boolean? = when (this) {
        is Number -> value != 0.0
        is BooleanValue -> value
    }
}

data class ValueSample(val timestampMs: Long, val value: Double)

fun interface VariableHistory {
    fun samples(variableId: String, sinceMs: Long, nowMs: Long): List<ValueSample>
}

data class TelemetryContext(
    val snapshot: TelemetrySnapshot,
    val nowMs: Long,
    val connected: Boolean,
    val mosMapping: MosBitMapping = MosBitMapping(),
    val history: VariableHistory = VariableHistory { _, _, _ -> emptyList() }
)

class TelemetryCatalog(extraVariables: List<TelemetryVariable> = emptyList()) {

    private val baseVariables: List<TelemetryVariable> = buildList {
        fun number(id: String, label: String, unit: String, category: String, get: (TelemetryContext) -> Double?) {
            add(TelemetryVariable(id, label, unit, category) { get(it)?.let { v -> VariableValue.Number(v) } })
        }
        fun bool(id: String, label: String, category: String, get: (TelemetryContext) -> Boolean?) {
            add(TelemetryVariable(id, label, "", category, TelemetryVariable.Kind.BOOLEAN) {
                get(it)?.let { v -> VariableValue.BooleanValue(v) }
            })
        }

        val electrical = "Eléctrico"
        val temperatures = "Temperaturas"
        val cells = "Celdas"
        val protection = "Protección"
        val system = "Sistema"

        number("voltage_total", "Voltaje total", "V", electrical) { it.snapshot.voltageV }
        number("current", "Corriente", "A", electrical) { it.snapshot.currentA }
        number("power", "Potencia", "W", electrical) { it.snapshot.powerW }
        number("soc", "SOC", "%", electrical) { it.snapshot.soc }
        number("soh", "SOH", "%", electrical) { it.snapshot.soh }
        number("capacity", "Capacidad nominal", "Ah", electrical) { it.snapshot.nominalAh }
        number("cycle_ah", "Ah del ciclo", "Ah", electrical) { it.snapshot.cycleAh }
        number("cycles", "Ciclos", "", electrical) { it.snapshot.cycles }
        number("cell_max_v", "Voltaje celda máxima", "V", cells) { it.snapshot.maxCellV }
        number("cell_min_v", "Voltaje celda mínima", "V", cells) { it.snapshot.minCellV }
        number("cell_delta_v", "Diferencia entre celdas", "V", cells) { it.snapshot.cellDeltaV }
        number("mosfet_temp", "Temperatura MOSFET", "°C", temperatures) { it.snapshot.mosfetTempC }

        repeat(11) { i ->
            number("temp_${i + 1}", "Temperatura interna ${i + 1}", "°C", temperatures) {
                it.snapshot.temperatureSensorsC.getOrNull(i)
            }
        }
        repeat(16) { i ->
            number("cell_${i + 1}", "Celda ${i + 1}", "V", cells) {
                it.snapshot.cellVoltagesV.getOrNull(i)
            }
        }
        // Protection bits are exposed with raw addresses and indexes only.
        // No meaning is assigned to a bit without a confirmed BMS specification.
        listOf(0x0009, 0x000A, 0x000B).forEach { address ->
            repeat(16) { bit ->
                number("protection_${address}_bit_$bit", "0x${"%04X".format(address)} bit $bit", "", protection) {
                    val words = it.snapshot.protectionWords
                    val index = address - 0x0009
                    if (words == null || index !in words.indices) null
                    else ((words[index] ushr bit) and 1).toDouble()
                }
            }
        }
        bool("charge_mos", "MOS de carga", system) { ctx ->
            ctx.mosMapping.chargeActive(ctx.snapshot.protectionWords)
        }
        bool("discharge_mos", "MOS de descarga", system) { ctx ->
            ctx.mosMapping.dischargeActive(ctx.snapshot.protectionWords)
        }
        number("connection_state", "Estado de conexión", "", system) {
            if (it.connected) 1.0 else 0.0
        }
        number("seconds_since_data", "Tiempo sin datos", "s", system) {
            if (it.snapshot.lastDataAtMs <= 0) Int.MAX_VALUE.toDouble()
            else ((it.nowMs - it.snapshot.lastDataAtMs).coerceAtLeast(0) / 1000.0)
        }
    }

    val variables: List<TelemetryVariable> = baseVariables + extraVariables
    val categories: List<String> = variables.map { it.category }.distinct()

    fun get(id: String): TelemetryVariable? = variables.firstOrNull { it.id == id }
    fun byCategory(category: String): List<TelemetryVariable> = variables.filter { it.category == category }

    fun evaluate(ctx: TelemetryContext, variableId: String): VariableValue? =
        get(variableId)?.evaluate?.invoke(ctx)
}
