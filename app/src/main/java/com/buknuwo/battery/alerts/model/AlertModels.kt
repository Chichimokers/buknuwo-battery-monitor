package com.buknuwo.battery.alerts.model

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

enum class Priority(val channelSuffix: String, val rank: Int) {
    LOW("low", 0),
    NORMAL("normal", 1),
    HIGH("high", 2),
    CRITICAL("critical", 3)
}

enum class Logic { AND, OR }

enum class ComparisonOperator(val label: String, val needsSecondValue: Boolean = false) {
    GREATER_THAN("Mayor que"),
    GREATER_OR_EQUAL("Mayor o igual que"),
    LESS_THAN("Menor que"),
    LESS_OR_EQUAL("Menor o igual que"),
    EQUAL("Igual a"),
    NOT_EQUAL("Diferente de"),
    BETWEEN("Entre dos valores", true),
    OUTSIDE("Fuera de un rango", true),
    RISE_BY("Aumenta más de X en un intervalo"),
    FALL_BY("Disminuye más de X en un intervalo"),
    PERCENT_CHANGE("Cambio porcentual"),
    STAYS_ABOVE("Permanece por encima durante", needsSecondValue = false),
    STAYS_BELOW("Permanece por debajo durante", needsSecondValue = false),
    IS_ACTIVE("Está activo"),
    IS_INACTIVE("Está inactivo"),
    CHANGED("Cambia de estado"),
    NO_DATA_FOR("No recibe datos durante (s)")
}

data class AlertCondition(
    val id: String,
    val variableId: String,
    val operator: ComparisonOperator,
    val value: Double?,
    val secondValue: Double? = null,
    val durationMs: Long = 0,
    val windowMs: Long = 0,
    val hysteresis: Double = 0.0
)

sealed interface ConditionNode {
    data class Leaf(val condition: AlertCondition) : ConditionNode
    data class Group(val id: String, val logic: Logic, val children: List<ConditionNode>) : ConditionNode
}

enum class SoundType { NONE, SYSTEM_DEFAULT, SYSTEM_ALARM, SYSTEM_NOTIFICATION, CUSTOM_URI }
enum class VibrationPatternKind { NONE, SHORT, LONG, DOUBLE, TRIPLE, CUSTOM }

data class AlertRule(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    val priority: Priority = Priority.NORMAL,
    val root: ConditionNode = ConditionNode.Group(UUID.randomUUID().toString(), Logic.AND, emptyList()),
    val hysteresis: Double = 0.0,
    val minActiveDurationMs: Long = 0,
    val maxDataAgeMs: Long = 15_000,
    val soundType: SoundType = SoundType.SYSTEM_NOTIFICATION,
    val soundUri: String? = null,
    val soundVolume: Float = 1.0f,
    val soundDurationMs: Long = 3_000,
    val repeatSoundWhileActive: Boolean = false,
    val soundRepeatIntervalMs: Long = 10_000,
    val vibrationPattern: VibrationPatternKind = VibrationPatternKind.SHORT,
    val customVibrationPattern: LongArray? = null,
    val vibrationRepeat: Int = 0,
    val notificationEnabled: Boolean = true,
    val notificationPersistent: Boolean = false,
    val showOnLockScreen: Boolean = false,
    val repeatNotification: Boolean = false,
    val notificationRepeatIntervalMs: Long = 60_000,
    val scheduleEnabled: Boolean = false,
    val startHour: Int = 0,
    val startMinute: Int = 0,
    val endHour: Int = 23,
    val endMinute: Int = 59,
    val daysOfWeek: Set<Int> = (1..7).toSet(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun isWithinSchedule(nowMs: Long): Boolean {
        if (!scheduleEnabled) return true
        val time = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
        if (time.dayOfWeek.value !in daysOfWeek) return false
        val minutes = time.hour * 60 + time.minute
        val start = startHour * 60 + startMinute
        val end = endHour * 60 + endMinute
        return if (start <= end) minutes in start..end else minutes >= start || minutes <= end
    }

    fun allConditions(): List<AlertCondition> {
        fun walk(node: ConditionNode): List<AlertCondition> = when (node) {
            is ConditionNode.Leaf -> listOf(node.condition)
            is ConditionNode.Group -> node.children.flatMap(::walk)
        }
        return walk(root)
    }
}

enum class AlertPhase { INACTIVE, PENDING, ACTIVE, RECOVERED }

data class AlertRuntimeState(
    val ruleId: String,
    val phase: AlertPhase = AlertPhase.INACTIVE,
    val sinceMs: Long = 0,
    val pendingSinceMs: Long = 0,
    val lastTriggeredAt: Long = 0,
    val lastRecoveredAt: Long = 0,
    val lastNotifiedAt: Long = 0,
    val lastSoundAt: Long = 0,
    val lastValue: Double? = null,
    val lastSequence: Long = -1,
    val conditionStartMs: Map<String, Long> = emptyMap()
)

data class EvaluationOutcome(
    val state: AlertRuntimeState,
    val triggered: Boolean = false,
    val recovered: Boolean = false,
    val conditionMet: Boolean = false,
    val reason: String = ""
)
