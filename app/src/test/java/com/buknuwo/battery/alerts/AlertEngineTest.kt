package com.buknuwo.battery.alerts

import com.buknuwo.battery.alerts.engine.AlertEngine
import com.buknuwo.battery.alerts.engine.ConditionEvaluator
import com.buknuwo.battery.alerts.model.AlertPhase
import com.buknuwo.battery.alerts.model.AlertRule
import com.buknuwo.battery.alerts.model.AlertRuntimeState
import com.buknuwo.battery.alerts.model.ConditionNode
import com.buknuwo.battery.alerts.model.ComparisonOperator
import com.buknuwo.battery.alerts.model.Logic
import com.buknuwo.battery.alerts.model.Priority
import com.buknuwo.battery.telemetry.TelemetryCatalog
import com.buknuwo.battery.telemetry.TelemetryContext
import com.buknuwo.battery.telemetry.MosBitMapping
import com.buknuwo.battery.telemetry.TelemetrySnapshot
import com.buknuwo.battery.telemetry.ValueSample
import com.buknuwo.battery.telemetry.VariableHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {

    private val catalog = TelemetryCatalog()
    private val engine = AlertEngine(ConditionEvaluator(catalog))

    private fun leaf(
        id: String,
        variable: String,
        op: ComparisonOperator,
        value: Double?,
        second: Double? = null,
        durationMs: Long = 0,
        windowMs: Long = 0
    ) = ConditionNode.Leaf(
        com.buknuwo.battery.alerts.model.AlertCondition(
            id, variable, op, value, second, durationMs, windowMs
        )
    )

    private fun rule(
        root: ConditionNode,
        hysteresis: Double = 0.0,
        minDuration: Long = 0,
        maxAge: Long = 15_000
    ) = AlertRule(
        id = "r1",
        name = "Prueba",
        description = "",
        priority = Priority.CRITICAL,
        root = root,
        hysteresis = hysteresis,
        minActiveDurationMs = minDuration,
        maxDataAgeMs = maxAge
    )

    private fun ctx(
        snap: TelemetrySnapshot,
        now: Long = 10_000,
        history: Map<String, List<ValueSample>> = emptyMap(),
        connected: Boolean = true,
        mosMapping: MosBitMapping = MosBitMapping()
    ): TelemetryContext {
        val source = VariableHistory { id, since, until ->
            history[id].orEmpty().filter { it.timestampMs in since..until }
        }
        return TelemetryContext(snap, now, connected, mosMapping = mosMapping, history = source)
    }

    private fun snap(seq: Int, now: Long = 10_000, block: TelemetrySnapshot.() -> TelemetrySnapshot = { this }) =
        TelemetrySnapshot(sequence = seq.toLong(), lastDataAtMs = now).block()

    @Test
    fun socBelowLimitTriggers() {
        val r = rule(leaf("c1", "soc", ComparisonOperator.LESS_THAN, 20.0))
        val outcome = engine.evaluate(r, engine.initialState("r1"), ctx(snap(1) { copy(soc = 15.0) }))
        assertTrue(outcome.triggered)
        assertEquals(AlertPhase.ACTIVE, outcome.state.phase)
    }

    @Test
    fun voltageAboveLimitTriggers() {
        val r = rule(leaf("c1", "voltage_total", ComparisonOperator.GREATER_THAN, 14.6))
        val outcome = engine.evaluate(r, engine.initialState("r1"), ctx(snap(1) { copy(voltageV = 14.8) }))
        assertTrue(outcome.triggered)
    }

    @Test
    fun negativeCurrentTriggers() {
        val r = rule(leaf("c1", "current", ComparisonOperator.LESS_THAN, -50.0))
        val outcome = engine.evaluate(r, engine.initialState("r1"), ctx(snap(1) { copy(currentA = -62.0) }))
        assertTrue(outcome.triggered)
    }

    @Test
    fun criticalTemperatureWithSustainedDuration() {
        val r = rule(leaf("c1", "mosfet_temp", ComparisonOperator.GREATER_THAN, 65.0), minDuration = 5_000)
        var state = engine.initialState("r1")
        var result = engine.evaluate(r, state, ctx(snap(1) { copy(mosfetTempC = 70.0) }, now = 1_000))
        assertEquals(AlertPhase.PENDING, result.state.phase)
        assertFalse(result.triggered)
        state = result.state

        result = engine.evaluate(r, state, ctx(snap(2) { copy(mosfetTempC = 70.0) }, now = 5_000))
        assertEquals(AlertPhase.PENDING, result.state.phase)
        state = result.state

        result = engine.evaluate(r, state, ctx(snap(3) { copy(mosfetTempC = 70.0) }, now = 6_000))
        assertTrue(result.triggered)
        assertEquals(AlertPhase.ACTIVE, result.state.phase)
    }

    @Test
    fun andRequiresBothConditions() {
        val root = ConditionNode.Group(
            "g1",
            Logic.AND,
            listOf(
                leaf("c1", "soc", ComparisonOperator.LESS_THAN, 20.0),
                leaf("c2", "mosfet_temp", ComparisonOperator.GREATER_THAN, 60.0)
            )
        )
        val r = rule(root)
        val onlySoc = engine.evaluate(r, engine.initialState("r1"), ctx(snap(1) { copy(soc = 15.0, mosfetTempC = 30.0) }))
        assertFalse(onlySoc.triggered)
        val both = engine.evaluate(r, onlySoc.state, ctx(snap(2) { copy(soc = 15.0, mosfetTempC = 70.0) }))
        assertTrue(both.triggered)
    }

    @Test
    fun orRequiresOneCondition() {
        val root = ConditionNode.Group(
            "g1",
            Logic.OR,
            listOf(
                leaf("c1", "soc", ComparisonOperator.LESS_THAN, 20.0),
                leaf("c2", "mosfet_temp", ComparisonOperator.GREATER_THAN, 70.0)
            )
        )
        val r = rule(root)
        val result = engine.evaluate(r, engine.initialState("r1"), ctx(snap(1) { copy(soc = 50.0, mosfetTempC = 75.0) }))
        assertTrue(result.triggered)
    }

    @Test
    fun hysteresisPreventsFlappingAndAllowsRecovery() {
        val r = rule(leaf("c1", "mosfet_temp", ComparisonOperator.GREATER_THAN, 65.0), hysteresis = 2.0)
        var state = engine.initialState("r1")
        var result = engine.evaluate(r, state, ctx(snap(1) { copy(mosfetTempC = 66.0) }))
        assertTrue(result.triggered)
        state = result.state

        result = engine.evaluate(r, state, ctx(snap(2) { copy(mosfetTempC = 64.0) }))
        assertFalse(result.recovered)
        assertEquals(AlertPhase.ACTIVE, result.state.phase)
        state = result.state

        result = engine.evaluate(r, state, ctx(snap(3) { copy(mosfetTempC = 62.0) }))
        assertTrue(result.recovered)
        assertEquals(AlertPhase.RECOVERED, result.state.phase)

        result = engine.evaluate(r, result.state, ctx(snap(4) { copy(mosfetTempC = 70.0) }))
        assertTrue(result.triggered)
        assertEquals(AlertPhase.ACTIVE, result.state.phase)
    }

    @Test
    fun staleDataForcesRecovery() {
        val r = rule(leaf("c1", "soc", ComparisonOperator.LESS_THAN, 20.0), maxAge = 5_000)
        var state = engine.initialState("r1")
        var result = engine.evaluate(r, state, ctx(snap(1, 1_000) { copy(soc = 10.0) }, now = 1_000))
        assertTrue(result.triggered)
        state = result.state
        result = engine.evaluate(r, state, ctx(snap(2, 1_000) { copy(soc = 10.0) }, now = 20_000))
        assertTrue(result.recovered)
    }

    @Test
    fun duplicateSequenceIsNotEvaluatedAgain() {
        val r = rule(leaf("c1", "soc", ComparisonOperator.LESS_THAN, 20.0))
        val first = engine.evaluate(r, engine.initialState("r1"), ctx(snap(1) { copy(soc = 10.0) }))
        val second = engine.evaluate(r, first.state, ctx(snap(1) { copy(soc = 5.0) }))
        assertFalse(second.triggered)
        assertEquals("Dato repetido (sección 1)", second.reason)
    }

    @Test
    fun noDataOperatorTriggersWithoutTelemetry() {
        val r = rule(leaf("c1", "seconds_since_data", ComparisonOperator.NO_DATA_FOR, 10.0))
        val empty = TelemetrySnapshot(sequence = 1)
        val result = engine.evaluate(r, engine.initialState("r1"), ctx(empty, now = 11_000))
        assertTrue(result.triggered)
    }

    @Test
    fun betweenAndOutsideOperators() {
        val between = rule(leaf("c1", "soc", ComparisonOperator.BETWEEN, 40.0, 60.0))
        assertTrue(engine.evaluate(between, engine.initialState("r1"), ctx(snap(1) { copy(soc = 50.0) })).triggered)
        val outside = rule(leaf("c1", "soc", ComparisonOperator.OUTSIDE, 40.0, 60.0))
        assertTrue(engine.evaluate(outside, engine.initialState("r1"), ctx(snap(2) { copy(soc = 15.0) })).triggered)
    }

    @Test
    fun booleanAndChangeOperators() {
        val active = rule(leaf("c1", "connection_state", ComparisonOperator.IS_ACTIVE, null))
        assertTrue(engine.evaluate(active, engine.initialState("r1"), ctx(snap(1), connected = true)).triggered)

        // MOS state is unknown until the user maps the bit from real frames.
        val unknownMos = rule(leaf("c1", "charge_mos", ComparisonOperator.IS_INACTIVE, null))
        assertFalse(engine.evaluate(unknownMos, engine.initialState("r2"), ctx(snap(2))).triggered)

        val mapped = MosBitMapping(chargeRegisterIndex = 0, chargeBit = 0)
        val inactive = rule(leaf("c1", "charge_mos", ComparisonOperator.IS_INACTIVE, null))
        assertTrue(
            engine.evaluate(
                inactive, engine.initialState("r3"),
                ctx(snap(3) { copy(protectionWords = listOf(0, 0, 0)) }, mosMapping = mapped)
            ).triggered
        )

        val history = mapOf(
            "soc" to listOf(ValueSample(8_000, 50.0), ValueSample(9_000, 30.0))
        )
        val changed = rule(leaf("c1", "soc", ComparisonOperator.CHANGED, 0.0))
        assertTrue(
            engine.evaluate(
                changed, engine.initialState("r3"),
                ctx(snap(3) { copy(soc = 30.0) }, now = 10_000, history = history)
            ).triggered
        )
    }

    @Test
    fun protectionBitVariablesAreAvailableDynamically() {
        val variable = catalog.get("protection_9_bit_1")
        assertTrue(variable != null)
        val value = variable!!.evaluate(
            ctx(snap(1) { copy(protectionWords = listOf(0x0002, 0, 0)) })
        )
        assertEquals(1.0, value!!.asNumber()!!, 0.001)
    }

    @Test
    fun disabledOrOutOfScheduleRulesDoNotTrigger() {
        val disabled = rule(leaf("c1", "soc", ComparisonOperator.LESS_THAN, 20.0)).copy(enabled = false)
        assertFalse(engine.evaluate(disabled, engine.initialState("r1"), ctx(snap(1) { copy(soc = 5.0) })).triggered)

        val scheduled = rule(leaf("c1", "soc", ComparisonOperator.LESS_THAN, 20.0)).copy(
            scheduleEnabled = true, daysOfWeek = emptySet()
        )
        assertFalse(engine.evaluate(scheduled, engine.initialState("r1"), ctx(snap(2) { copy(soc = 5.0) })).triggered)
    }
}
