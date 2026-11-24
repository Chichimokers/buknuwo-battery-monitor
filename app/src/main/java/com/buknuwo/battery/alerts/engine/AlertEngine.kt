package com.buknuwo.battery.alerts.engine

import com.buknuwo.battery.alerts.model.AlertCondition
import com.buknuwo.battery.alerts.model.AlertPhase
import com.buknuwo.battery.alerts.model.AlertRule
import com.buknuwo.battery.alerts.model.AlertRuntimeState
import com.buknuwo.battery.alerts.model.ConditionNode
import com.buknuwo.battery.alerts.model.ComparisonOperator
import com.buknuwo.battery.alerts.model.EvaluationOutcome
import com.buknuwo.battery.alerts.model.Logic
import com.buknuwo.battery.telemetry.TelemetryContext

/**
 * Deterministic state machine: INACTIVE -> PENDING -> ACTIVE -> RECOVERED.
 * No side effects: notifications, sound and persistence are handled by AlertManager.
 */
class AlertEngine @javax.inject.Inject constructor(private val evaluator: ConditionEvaluator) {

    fun evaluate(rule: AlertRule, state: AlertRuntimeState, ctx: TelemetryContext): EvaluationOutcome {
        val now = ctx.nowMs
        if (ctx.snapshot.sequence > 0 && ctx.snapshot.sequence <= state.lastSequence) {
            return EvaluationOutcome(state, reason = "Dato repetido (sección ${ctx.snapshot.sequence})")
        }

        val leaves = rule.allConditions()
        val primary = evaluator.evaluateNode(rule.root, ctx, recovery = false, rule.hysteresis)
        val recovery = evaluator.evaluateNode(rule.root, ctx, recovery = true, rule.hysteresis)

        val hasNoDataOperator = leaves.any { it.operator == ComparisonOperator.NO_DATA_FOR }
        val stale = !hasNoDataOperator && rule.maxDataAgeMs > 0 && ctx.snapshot.hasData &&
            now - ctx.snapshot.lastDataAtMs > rule.maxDataAgeMs
        val allowed = rule.enabled && rule.isWithinSchedule(now) && !stale

        val leafStates = mutableMapOf<String, Boolean>()
        val recoveryLeafStates = mutableMapOf<String, Boolean>()
        leaves.forEach { leaf ->
            leafStates[leaf.id] = evaluator.evaluateLeaf(leaf, ctx, false, rule.hysteresis).met
            recoveryLeafStates[leaf.id] = evaluator.evaluateLeaf(leaf, ctx, true, rule.hysteresis).met
        }
        val starts = mutableMapOf<String, Long>()
        val recoveryStarts = mutableMapOf<String, Long>()
        leafStates.forEach { (id, met) -> if (met) starts[id] = state.conditionStartMs[id] ?: now }
        recoveryLeafStates.forEach { (id, met) -> if (met) recoveryStarts[id] = state.conditionStartMs[id] ?: now }

        fun durationsSatisfied(states: Map<String, Boolean>, origin: Map<String, Long>): Boolean {
            val metLeaves = leaves.filter { states[it.id] == true }
            if (metLeaves.isEmpty()) return false
            val required = when (val root = rule.root) {
                is ConditionNode.Leaf -> true
                is ConditionNode.Group -> root.logic == Logic.AND
            }
            return metLeaves.all { now - (origin[it.id] ?: now) >= it.durationMs } ||
                (!required && metLeaves.any { now - (origin[it.id] ?: now) >= it.durationMs })
        }

        val conditionMet = allowed && primary.met && durationsSatisfied(leafStates, starts)
        // Recovery must be evaluated against its own hysteresis-adjusted leaf states:
        // a value inside the hysteresis band keeps the condition true.
        val recoveryMet = allowed && recovery.met && durationsSatisfied(recoveryLeafStates, recoveryStarts)
        var next = state.copy(
            lastSequence = ctx.snapshot.sequence,
            lastValue = primary.value ?: state.lastValue,
            conditionStartMs = starts
        )
        var triggered = false
        var recovered = false

        when (state.phase) {
            AlertPhase.INACTIVE, AlertPhase.RECOVERED -> {
                if (conditionMet) {
                    val pendingSince = if (state.pendingSinceMs > 0) state.pendingSinceMs else now
                    if (now - pendingSince >= rule.minActiveDurationMs) {
                        next = next.copy(
                            phase = AlertPhase.ACTIVE,
                            sinceMs = now,
                            pendingSinceMs = 0,
                            lastTriggeredAt = now
                        )
                        triggered = true
                    } else {
                        next = next.copy(phase = AlertPhase.PENDING, pendingSinceMs = pendingSince)
                    }
                } else {
                    next = next.copy(phase = AlertPhase.INACTIVE, pendingSinceMs = 0)
                }
            }

            AlertPhase.PENDING -> {
                if (!primary.met || !allowed) {
                    next = next.copy(phase = AlertPhase.INACTIVE, pendingSinceMs = 0)
                } else if (conditionMet && now - state.pendingSinceMs >= rule.minActiveDurationMs) {
                    next = next.copy(
                        phase = AlertPhase.ACTIVE,
                        sinceMs = now,
                        pendingSinceMs = 0,
                        lastTriggeredAt = now
                    )
                    triggered = true
                }
            }

            AlertPhase.ACTIVE -> {
                if (!recoveryMet) {
                    next = next.copy(
                        phase = AlertPhase.RECOVERED,
                        sinceMs = now,
                        lastRecoveredAt = now
                    )
                    recovered = true
                }
            }
        }

        val reason = when {
            !rule.enabled -> "Regla desactivada"
            !rule.isWithinSchedule(now) -> "Fuera del horario configurado"
            stale -> "Datos obsoletos (> ${rule.maxDataAgeMs} ms)"
            triggered -> "Condición cumplida"
            recovered -> "Condición finalizada"
            next.phase == AlertPhase.PENDING -> "Esperando duración mínima"
            conditionMet -> "Condición establecida"
            else -> "Sin condición"
        }
        return EvaluationOutcome(next, triggered, recovered, conditionMet, reason)
    }

    fun initialState(ruleId: String): AlertRuntimeState = AlertRuntimeState(ruleId = ruleId)

    fun needsImmediateEvaluation(rule: AlertRule): Boolean =
        rule.allConditions().any { it.operator == ComparisonOperator.NO_DATA_FOR }
}
