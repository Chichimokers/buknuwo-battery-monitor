package com.buknuwo.battery.alerts.engine

import com.buknuwo.battery.alerts.model.AlertCondition
import com.buknuwo.battery.alerts.model.ConditionNode
import com.buknuwo.battery.alerts.model.ComparisonOperator
import com.buknuwo.battery.alerts.model.Logic
import com.buknuwo.battery.telemetry.TelemetryCatalog
import com.buknuwo.battery.telemetry.TelemetryContext
import com.buknuwo.battery.telemetry.VariableValue

data class LeafEvaluation(val met: Boolean, val value: Double?, val available: Boolean)

/**
 * Pure, deterministic condition evaluation. `recovery = true` applies hysteresis in the
 * direction that makes the condition harder to leave than to enter.
 */
class ConditionEvaluator @javax.inject.Inject constructor(private val catalog: TelemetryCatalog) {

    fun evaluateNode(
        node: ConditionNode,
        ctx: TelemetryContext,
        recovery: Boolean,
        hysteresis: Double = 0.0
    ): LeafEvaluation = when (node) {
        is ConditionNode.Leaf -> evaluateLeaf(node.condition, ctx, recovery, hysteresis)
        is ConditionNode.Group -> {
            val results = node.children.map { evaluateNode(it, ctx, recovery, hysteresis) }
            val met = if (node.logic == Logic.AND) results.all { it.met } else results.any { it.met }
            LeafEvaluation(met, results.firstOrNull { it.value != null }?.value, results.any { it.available })
        }
    }

    fun evaluateLeaf(
        condition: AlertCondition,
        ctx: TelemetryContext,
        recovery: Boolean,
        hysteresis: Double = 0.0
    ): LeafEvaluation {
        val raw: VariableValue? = catalog.evaluate(ctx, condition.variableId)
        val value = raw?.asNumber()
        val h = if (recovery) hysteresis.coerceAtLeast(0.0) else 0.0
        val target = condition.value
        val second = condition.secondValue

        val met = when (condition.operator) {
            ComparisonOperator.GREATER_THAN -> value != null && target != null && value > target - h
            ComparisonOperator.GREATER_OR_EQUAL -> value != null && target != null && value >= target - h
            ComparisonOperator.LESS_THAN -> value != null && target != null && value < target + h
            ComparisonOperator.LESS_OR_EQUAL -> value != null && target != null && value <= target + h
            ComparisonOperator.EQUAL -> value != null && target != null && kotlin.math.abs(value - target) <= h
            ComparisonOperator.NOT_EQUAL -> value != null && target != null && kotlin.math.abs(value - target) > h
            ComparisonOperator.BETWEEN -> value != null && target != null && second != null &&
                value >= target - h && value <= second + h
            ComparisonOperator.OUTSIDE -> value != null && target != null && second != null &&
                (value < target + h || value > second - h)
            ComparisonOperator.RISE_BY -> {
                val first = firstInWindow(condition, ctx)
                value != null && first != null && target != null && value - first >= target
            }
            ComparisonOperator.FALL_BY -> {
                val first = firstInWindow(condition, ctx)
                value != null && first != null && target != null && first - value >= target
            }
            ComparisonOperator.PERCENT_CHANGE -> {
                val first = firstInWindow(condition, ctx)
                value != null && first != null && first != 0.0 && target != null &&
                    kotlin.math.abs((value - first) / first * 100.0) >= target
            }
            ComparisonOperator.STAYS_ABOVE -> {
                val window = windowOf(condition)
                if (value == null || target == null) false
                else if (window <= 0) value >= target
                else {
                    val samples = ctx.history.samples(condition.variableId, ctx.nowMs - window, ctx.nowMs)
                    samples.isNotEmpty() && samples.all { it.value >= target } && value >= target
                }
            }
            ComparisonOperator.STAYS_BELOW -> {
                val window = windowOf(condition)
                if (value == null || target == null) false
                else if (window <= 0) value <= target
                else {
                    val samples = ctx.history.samples(condition.variableId, ctx.nowMs - window, ctx.nowMs)
                    samples.isNotEmpty() && samples.all { it.value <= target } && value <= target
                }
            }
            ComparisonOperator.IS_ACTIVE -> raw?.asBoolean() == true
            ComparisonOperator.IS_INACTIVE -> raw?.asBoolean() == false
            ComparisonOperator.CHANGED -> {
                val samples = ctx.history.samples(condition.variableId, 0, ctx.nowMs)
                value != null && samples.size >= 2 && samples[samples.size - 2].value != value
            }
            ComparisonOperator.NO_DATA_FOR -> {
                val elapsed = if (ctx.snapshot.lastDataAtMs <= 0) Long.MAX_VALUE
                else (ctx.nowMs - ctx.snapshot.lastDataAtMs).coerceAtLeast(0)
                target != null && elapsed >= (target * 1000).toLong()
            }
        }
        return LeafEvaluation(met, value, raw != null)
    }

    private fun windowOf(condition: AlertCondition): Long =
        if (condition.windowMs > 0) condition.windowMs else condition.durationMs

    private fun firstInWindow(condition: AlertCondition, ctx: TelemetryContext): Double? {
        val window = windowOf(condition)
        if (window <= 0) return null
        return ctx.history.samples(condition.variableId, ctx.nowMs - window, ctx.nowMs)
            .minByOrNull { it.timestampMs }?.value
    }
}
