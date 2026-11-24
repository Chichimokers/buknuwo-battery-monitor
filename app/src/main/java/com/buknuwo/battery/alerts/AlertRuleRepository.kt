package com.buknuwo.battery.alerts

import com.buknuwo.battery.database.AlertRuleDao
import com.buknuwo.battery.database.AlertRuleEntity
import com.buknuwo.battery.database.ConditionEntity
import com.buknuwo.battery.alerts.model.AlertCondition
import com.buknuwo.battery.alerts.model.AlertRule
import com.buknuwo.battery.alerts.model.ConditionNode
import com.buknuwo.battery.alerts.model.ComparisonOperator
import com.buknuwo.battery.alerts.model.Logic
import com.buknuwo.battery.alerts.model.Priority
import com.buknuwo.battery.alerts.model.SoundType
import com.buknuwo.battery.alerts.model.VibrationPatternKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertRuleRepository @Inject constructor(private val dao: AlertRuleDao) {

    val rules: Flow<List<AlertRule>> = dao.observeRules().map { entities ->
        val conditions = dao.allConditions().groupBy { it.ruleId }
        entities.map { entity -> fromEntity(entity, conditions[entity.ruleId].orEmpty()) }
    }

    suspend fun save(rule: AlertRule) {
        dao.upsertRule(rule.toEntity())
        val entities = mutableListOf<ConditionEntity>()
        var order = 0
        fun walk(node: ConditionNode, parentId: String?) {
            when (node) {
                is ConditionNode.Leaf -> {
                    val c = node.condition
                    entities += ConditionEntity(
                        conditionId = c.id, ruleId = rule.id, parentId = parentId,
                        isGroup = false, logic = "AND", orderIndex = order++,
                        variableId = c.variableId, operator = c.operator.name,
                        value = c.value, secondValue = c.secondValue,
                        durationMs = c.durationMs, windowMs = c.windowMs
                    )
                }
                is ConditionNode.Group -> {
                    entities += ConditionEntity(
                        conditionId = node.id, ruleId = rule.id, parentId = parentId,
                        isGroup = true, logic = node.logic.name, orderIndex = order++,
                        variableId = "", operator = "", value = null,
                        secondValue = null, durationMs = 0, windowMs = 0
                    )
                    node.children.forEach { walk(it, node.id) }
                }
            }
        }
        walk(rule.root, null)
        dao.replaceConditions(rule.id, entities)
    }

    suspend fun delete(id: String) {
        dao.deleteConditions(id)
        dao.deleteRule(id)
    }

    suspend fun duplicate(id: String): AlertRule? {
        val original = get(id) ?: return null
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} (copia)",
            enabled = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            root = remapIds(original.root)
        )
        save(copy)
        return copy
    }

    private fun remapIds(node: ConditionNode): ConditionNode = when (node) {
        is ConditionNode.Leaf -> node.copy(
            condition = node.condition.copy(id = UUID.randomUUID().toString())
        )
        is ConditionNode.Group -> node.copy(
            id = UUID.randomUUID().toString(),
            children = node.children.map(::remapIds)
        )
    }

    suspend fun get(id: String): AlertRule? {
        val entity = dao.getRule(id) ?: return null
        return fromEntity(entity, dao.conditionsFor(id))
    }

    suspend fun seedExamplesIfEmpty() {
        if (dao.observeRules().first().isNotEmpty()) return
        listOf(
            AlertRule(
                id = UUID.randomUUID().toString(),
                name = "Batería baja",
                description = "Ejemplo editable. SOC por debajo del 20 %.",
                priority = Priority.HIGH,
                root = ConditionNode.Leaf(
                    AlertCondition(UUID.randomUUID().toString(), "soc", ComparisonOperator.LESS_THAN, 20.0)
                ),
                soundType = SoundType.SYSTEM_ALARM,
                repeatNotification = true,
                notificationRepeatIntervalMs = 60_000
            ),
            AlertRule(
                id = UUID.randomUUID().toString(),
                name = "Temperatura MOSFET elevada",
                description = "Ejemplo editable: > 65 °C durante 5 s.",
                priority = Priority.CRITICAL,
                root = ConditionNode.Leaf(
                    AlertCondition(
                        UUID.randomUUID().toString(), "mosfet_temp", ComparisonOperator.GREATER_THAN,
                        65.0, durationMs = 5_000
                    )
                ),
                soundType = SoundType.SYSTEM_ALARM,
                notificationPersistent = true,
                vibrationPattern = VibrationPatternKind.TRIPLE
            ),
            AlertRule(
                id = UUID.randomUUID().toString(),
                name = "Celdas desequilibradas",
                description = "Ejemplo editable: diferencia > 0.050 V durante 10 s.",
                priority = Priority.HIGH,
                root = ConditionNode.Leaf(
                    AlertCondition(
                        UUID.randomUUID().toString(), "cell_delta_v", ComparisonOperator.GREATER_THAN,
                        0.050, durationMs = 10_000
                    )
                )
            ),
            AlertRule(
                id = UUID.randomUUID().toString(),
                name = "Sin telemetría",
                description = "Ejemplo editable: sin datos durante 10 s.",
                priority = Priority.CRITICAL,
                root = ConditionNode.Leaf(
                    AlertCondition(UUID.randomUUID().toString(), "seconds_since_data", ComparisonOperator.NO_DATA_FOR, 10.0)
                ),
                soundType = SoundType.SYSTEM_ALARM,
                notificationPersistent = true
            ),
            AlertRule(
                id = UUID.randomUUID().toString(),
                name = "Descarga excesiva",
                description = "Ejemplo editable: corriente < -50 A durante 15 s.",
                priority = Priority.HIGH,
                root = ConditionNode.Leaf(
                    AlertCondition(
                        UUID.randomUUID().toString(), "current", ComparisonOperator.LESS_THAN,
                        -50.0, durationMs = 15_000
                    )
                )
            )
        ).forEach { save(it) }
    }

    private fun fromEntity(entity: AlertRuleEntity, conditions: List<ConditionEntity>): AlertRule {
        val byParent = conditions.groupBy { it.parentId }
        fun build(node: ConditionEntity): ConditionNode = if (node.isGroup) {
            ConditionNode.Group(
                id = node.conditionId,
                logic = runCatching { Logic.valueOf(node.logic) }.getOrDefault(Logic.AND),
                children = byParent[node.conditionId].orEmpty().sortedBy { it.orderIndex }.map(::build)
            )
        } else {
            ConditionNode.Leaf(
                AlertCondition(
                    id = node.conditionId,
                    variableId = node.variableId,
                    operator = runCatching { ComparisonOperator.valueOf(node.operator) }
                        .getOrDefault(ComparisonOperator.EQUAL),
                    value = node.value,
                    secondValue = node.secondValue,
                    durationMs = node.durationMs,
                    windowMs = node.windowMs
                )
            )
        }
        val rootEntity = conditions.firstOrNull { it.parentId == null }
            ?: conditions.firstOrNull()
        val root = rootEntity?.let(::build) ?: ConditionNode.Group(
            id = UUID.randomUUID().toString(), logic = Logic.AND, children = emptyList()
        )
        return AlertRule(
            id = entity.ruleId,
            name = entity.name,
            description = entity.description,
            enabled = entity.enabled,
            priority = runCatching { Priority.valueOf(entity.priority) }.getOrDefault(Priority.NORMAL),
            root = root,
            hysteresis = entity.hysteresis,
            minActiveDurationMs = entity.minActiveDurationMs,
            maxDataAgeMs = entity.maxDataAgeMs,
            soundType = runCatching { SoundType.valueOf(entity.soundType) }
                .getOrDefault(SoundType.SYSTEM_NOTIFICATION),
            soundUri = entity.soundUri,
            soundVolume = entity.soundVolume,
            soundDurationMs = entity.soundDurationMs,
            repeatSoundWhileActive = entity.repeatSoundWhileActive,
            soundRepeatIntervalMs = entity.soundRepeatIntervalMs,
            vibrationPattern = runCatching { VibrationPatternKind.valueOf(entity.vibrationPattern) }
                .getOrDefault(VibrationPatternKind.SHORT),
            customVibrationPattern = entity.customVibrationPattern?.split(",")
                ?.mapNotNull { it.trim().toLongOrNull() }?.toLongArray(),
            vibrationRepeat = entity.vibrationRepeat,
            notificationEnabled = entity.notificationEnabled,
            notificationPersistent = entity.notificationPersistent,
            showOnLockScreen = entity.showOnLockScreen,
            repeatNotification = entity.repeatNotification,
            notificationRepeatIntervalMs = entity.notificationRepeatIntervalMs,
            scheduleEnabled = entity.scheduleEnabled,
            startHour = entity.startHour,
            startMinute = entity.startMinute,
            endHour = entity.endHour,
            endMinute = entity.endMinute,
            daysOfWeek = entity.daysOfWeek.split(",").mapNotNull { it.toIntOrNull() }.toSet(),
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }

    private fun AlertRule.toEntity() = AlertRuleEntity(
        ruleId = id,
        name = name,
        description = description,
        enabled = enabled,
        priority = priority.name,
        logic = (root as? ConditionNode.Group)?.logic?.name ?: Logic.AND.name,
        hysteresis = hysteresis,
        minActiveDurationMs = minActiveDurationMs,
        maxDataAgeMs = maxDataAgeMs,
        soundType = soundType.name,
        soundUri = soundUri,
        soundVolume = soundVolume,
        soundDurationMs = soundDurationMs,
        repeatSoundWhileActive = repeatSoundWhileActive,
        soundRepeatIntervalMs = soundRepeatIntervalMs,
        vibrationPattern = vibrationPattern.name,
        customVibrationPattern = customVibrationPattern?.joinToString(","),
        vibrationRepeat = vibrationRepeat,
        notificationEnabled = notificationEnabled,
        notificationPersistent = notificationPersistent,
        showOnLockScreen = showOnLockScreen,
        repeatNotification = repeatNotification,
        notificationRepeatIntervalMs = notificationRepeatIntervalMs,
        scheduleEnabled = scheduleEnabled,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        daysOfWeek = daysOfWeek.sorted().joinToString(","),
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
