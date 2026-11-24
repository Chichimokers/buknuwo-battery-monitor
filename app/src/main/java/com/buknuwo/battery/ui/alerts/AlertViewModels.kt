package com.buknuwo.battery.ui.alerts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buknuwo.battery.alerts.AlarmController
import com.buknuwo.battery.alerts.AlertManager
import com.buknuwo.battery.alerts.AlertRuleRepository
import com.buknuwo.battery.alerts.model.AlertCondition
import com.buknuwo.battery.alerts.model.AlertPhase
import com.buknuwo.battery.alerts.model.AlertRule
import com.buknuwo.battery.alerts.model.ConditionNode
import com.buknuwo.battery.alerts.model.ComparisonOperator
import com.buknuwo.battery.alerts.model.Logic
import com.buknuwo.battery.alerts.model.NotificationSoundType
import com.buknuwo.battery.alerts.model.Priority
import com.buknuwo.battery.alerts.model.SoundType
import com.buknuwo.battery.alerts.model.VibrationPatternKind
import com.buknuwo.battery.database.AlertEventDao
import com.buknuwo.battery.telemetry.TelemetryCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AlertListViewModel @Inject constructor(
    private val repository: AlertRuleRepository
) : ViewModel() {
    val rules = repository.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggle(rule: AlertRule) = viewModelScope.launch {
        repository.save(rule.copy(enabled = !rule.enabled, updatedAt = System.currentTimeMillis()))
    }

    fun delete(rule: AlertRule) = viewModelScope.launch { repository.delete(rule.id) }
    fun duplicate(rule: AlertRule) = viewModelScope.launch { repository.duplicate(rule.id) }
    fun seedExamples() = viewModelScope.launch { repository.seedExamplesIfEmpty() }
}

@HiltViewModel
class AlertEditorViewModel @Inject constructor(
    private val repository: AlertRuleRepository,
    private val alarmController: AlarmController,
    val catalog: TelemetryCatalog,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val ruleId: String? = savedStateHandle["ruleId"]
    private val _rule = MutableStateFlow(newRule())
    val rule: StateFlow<AlertRule> = _rule.asStateFlow()

    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    /** Plain-language summary of what the rule currently does. */
    val summary: StateFlow<String> = _rule.map { rule ->
        val condition = rule.allConditions().firstOrNull()
        if (condition == null) {
            "Esta regla todavía no tiene condiciones. Añade una para que pueda evaluarse."
        } else {
            val variable = catalog.get(condition.variableId)?.label ?: condition.variableId
            val unit = catalog.get(condition.variableId)?.unit ?: ""
            val threshold = condition.value?.let { "%.3f".format(it) } ?: "—"
            val duration = if (condition.durationMs > 0) " durante ${condition.durationMs / 1000} s" else ""
            val logic = (rule.root as? ConditionNode.Group)?.logic?.name ?: ""
            val extra = if (rule.allConditions().size > 1) " (${rule.allConditions().size} condiciones, $logic)" else ""
            "Cuando «$variable» ${condition.operator.label.lowercase()} $threshold $unit$duration$extra " +
                "→ aviso de prioridad ${rule.priority.name}."
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    init {
        if (ruleId != null) {
            viewModelScope.launch { repository.get(ruleId)?.let { _rule.value = it } }
        }
    }

    private fun newRule() = AlertRule(
        id = UUID.randomUUID().toString(),
        name = "Nueva alerta",
        description = "",
        root = ConditionNode.Group(
            id = UUID.randomUUID().toString(),
            logic = Logic.AND,
            children = listOf(newLeaf())
        )
    )

    private fun newLeaf() = ConditionNode.Leaf(
        AlertCondition(UUID.randomUUID().toString(), "soc", ComparisonOperator.LESS_THAN, 20.0)
    )

    /** Safe, editable starting points. They are examples, not manufacturer limits. */
    fun applyTemplate(template: String) {
        val (name, description, condition, priority, persistent) = when (template) {
            "soc" -> Quad(
                "Batería baja", "Ejemplo editable: SOC por debajo del 20 %.",
                AlertCondition(UUID.randomUUID().toString(), "soc", ComparisonOperator.LESS_THAN, 20.0),
                Priority.HIGH, false
            )
            "temp" -> Quad(
                "Temperatura MOSFET alta", "Ejemplo editable: > 65 °C durante 5 s.",
                AlertCondition(UUID.randomUUID().toString(), "mosfet_temp", ComparisonOperator.GREATER_THAN, 65.0, durationMs = 5_000),
                Priority.CRITICAL, true
            )
            "cell" -> Quad(
                "Celdas desequilibradas", "Ejemplo editable: diferencia > 0.050 V durante 10 s.",
                AlertCondition(UUID.randomUUID().toString(), "cell_delta_v", ComparisonOperator.GREATER_THAN, 0.050, durationMs = 10_000),
                Priority.HIGH, false
            )
            "nodata" -> Quad(
                "Sin telemetría", "Ejemplo editable: sin datos durante 10 s.",
                AlertCondition(UUID.randomUUID().toString(), "seconds_since_data", ComparisonOperator.NO_DATA_FOR, 10.0),
                Priority.CRITICAL, true
            )
            else -> Quad(
                "Nueva alerta", "", AlertCondition(UUID.randomUUID().toString(), "soc", ComparisonOperator.LESS_THAN, 20.0),
                Priority.NORMAL, false
            )
        }
        _rule.value = AlertRule(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            priority = priority,
            root = ConditionNode.Group(UUID.randomUUID().toString(), Logic.AND, listOf(ConditionNode.Leaf(condition))),
            notificationPersistent = persistent
        )
    }

    private data class Quad<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

    private fun modify(transform: (AlertRule) -> AlertRule) {
        _rule.value = transform(_rule.value).copy(updatedAt = System.currentTimeMillis())
    }

    private fun replaceRoot(transform: (ConditionNode) -> ConditionNode) {
        modify { it.copy(root = transform(it.root)) }
    }

    fun setName(value: String) = modify { it.copy(name = value) }
    fun setDescription(value: String) = modify { it.copy(description = value) }
    fun setEnabled(value: Boolean) = modify { it.copy(enabled = value) }
    fun setPriority(value: Priority) = modify { it.copy(priority = value) }
    fun setHysteresis(value: Double) = modify { it.copy(hysteresis = value.coerceAtLeast(0.0)) }
    fun setMinDurationMs(value: Long) = modify { it.copy(minActiveDurationMs = value.coerceAtLeast(0)) }
    fun setMaxDataAgeMs(value: Long) = modify { it.copy(maxDataAgeMs = value.coerceAtLeast(0)) }
    fun setSoundType(value: SoundType) = modify { it.copy(soundType = value) }
    fun setSoundUri(value: String) = modify { it.copy(soundUri = value, soundType = SoundType.CUSTOM_URI) }
    fun setSoundVolume(value: Float) = modify { it.copy(soundVolume = value.coerceIn(0f, 1f)) }
    fun setSoundDurationMs(value: Long) = modify { it.copy(soundDurationMs = value.coerceAtLeast(500)) }
    fun setRepeatSound(value: Boolean) = modify { it.copy(repeatSoundWhileActive = value) }
    fun setSoundRepeatIntervalMs(value: Long) = modify { it.copy(soundRepeatIntervalMs = value.coerceAtLeast(1000)) }
    fun setVibration(value: VibrationPatternKind) = modify { it.copy(vibrationPattern = value) }
    fun setVibrationRepeat(value: Int) = modify { it.copy(vibrationRepeat = value) }
    fun setCustomVibration(pattern: LongArray) = modify {
        it.copy(vibrationPattern = VibrationPatternKind.CUSTOM, customVibrationPattern = pattern)
    }
    fun setNotificationEnabled(value: Boolean) = modify { it.copy(notificationEnabled = value) }
    fun setNotificationSound(type: NotificationSoundType, uri: String? = null) = modify {
        it.copy(
            notificationSoundType = type,
            notificationSoundUri = if (type == NotificationSoundType.CUSTOM) uri else null
        )
    }

    /** Preview uses the same media player as real alarms, without touching telemetry or rules. */
    fun previewNotificationSound() {
        val current = _rule.value
        val preview = when (current.notificationSoundType) {
            NotificationSoundType.CUSTOM -> current.copy(
                id = "preview_notification", soundType = SoundType.CUSTOM_URI,
                soundUri = current.notificationSoundUri, soundDurationMs = 2_000
            )
            NotificationSoundType.SYSTEM_ALARM -> current.copy(
                id = "preview_notification", soundType = SoundType.SYSTEM_ALARM, soundDurationMs = 2_000
            )
            NotificationSoundType.PRIORITY_DEFAULT -> current.copy(
                id = "preview_notification", soundType = SoundType.SYSTEM_NOTIFICATION, soundDurationMs = 2_000
            )
        }
        alarmController.preview(preview)
    }
    fun setPersistent(value: Boolean) = modify { it.copy(notificationPersistent = value) }
    fun setShowOnLockScreen(value: Boolean) = modify { it.copy(showOnLockScreen = value) }
    fun setRepeatNotification(value: Boolean) = modify { it.copy(repeatNotification = value) }
    fun setNotificationRepeatMs(value: Long) = modify { it.copy(notificationRepeatIntervalMs = value.coerceAtLeast(1000)) }
    fun setSchedule(enabled: Boolean, startHour: Int, endHour: Int, days: Set<Int>) = modify {
        it.copy(scheduleEnabled = enabled, startHour = startHour, endHour = endHour, daysOfWeek = days)
    }

    fun setRootLogic(logic: Logic) = replaceRoot { root ->
        when (root) {
            is ConditionNode.Group -> root.copy(logic = logic)
            is ConditionNode.Leaf -> ConditionNode.Group(UUID.randomUUID().toString(), logic, listOf(root))
        }
    }

    fun addCondition() = replaceRoot { root ->
        when (root) {
            is ConditionNode.Group -> root.copy(children = root.children + newLeaf())
            is ConditionNode.Leaf -> ConditionNode.Group(UUID.randomUUID().toString(), Logic.AND, listOf(root, newLeaf()))
        }
    }

    fun addGroup() = replaceRoot { root ->
        val group = ConditionNode.Group(UUID.randomUUID().toString(), Logic.OR, listOf(newLeaf(), newLeaf()))
        when (root) {
            is ConditionNode.Group -> root.copy(children = root.children + group)
            is ConditionNode.Leaf -> ConditionNode.Group(UUID.randomUUID().toString(), Logic.AND, listOf(root, group))
        }
    }

    fun setGroupLogic(groupId: String, logic: Logic) = replaceRoot { transformGroup(it, groupId, logic) }

    private fun transformGroup(node: ConditionNode, groupId: String, logic: Logic): ConditionNode = when (node) {
        is ConditionNode.Leaf -> node
        is ConditionNode.Group -> if (node.id == groupId) node.copy(logic = logic)
        else node.copy(children = node.children.map { transformGroup(it, groupId, logic) })
    }

    fun updateCondition(id: String, transform: (AlertCondition) -> AlertCondition) = replaceRoot { root ->
        fun walk(node: ConditionNode): ConditionNode = when (node) {
            is ConditionNode.Leaf -> if (node.condition.id == id) node.copy(condition = transform(node.condition)) else node
            is ConditionNode.Group -> node.copy(children = node.children.map(::walk))
        }
        walk(root)
    }

    fun removeNode(id: String) = replaceRoot { root ->
        fun walk(node: ConditionNode): ConditionNode? = when (node) {
            is ConditionNode.Leaf -> if (node.condition.id == id) null else node
            is ConditionNode.Group -> node.copy(children = node.children.mapNotNull(::walk))
        }
        if (root is ConditionNode.Leaf && root.condition.id == id) {
            ConditionNode.Group(UUID.randomUUID().toString(), Logic.AND, listOf(newLeaf()))
        } else walk(root) ?: ConditionNode.Group(UUID.randomUUID().toString(), Logic.AND, listOf(newLeaf()))
    }

    fun save() = viewModelScope.launch {
        repository.save(_rule.value)
        _saved.send(Unit)
    }
}

@HiltViewModel
class AlertDetailViewModel @Inject constructor(
    private val repository: AlertRuleRepository,
    private val eventDao: AlertEventDao,
    private val alerts: AlertManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val ruleId: String = savedStateHandle["ruleId"] ?: ""
    val rule = repository.rules
        .map { list -> list.firstOrNull { it.id == ruleId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val events = eventDao.observeForRule(ruleId, 100).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val phase = alerts.activeRuleIds
        .map { ids -> if (ruleId in ids) AlertPhase.ACTIVE else AlertPhase.INACTIVE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlertPhase.INACTIVE)

    fun toggle(current: AlertRule) = viewModelScope.launch {
        repository.save(current.copy(enabled = !current.enabled, updatedAt = System.currentTimeMillis()))
    }
    fun delete(current: AlertRule) = viewModelScope.launch { repository.delete(current.id) }
    fun duplicate(current: AlertRule) = viewModelScope.launch { repository.duplicate(current.id) }
    fun silence() = viewModelScope.launch { alerts.silence(ruleId) }
}

@HiltViewModel
class AlertHistoryViewModel @Inject constructor(eventDao: AlertEventDao) : ViewModel() {
    val events = eventDao.observeRecent(300).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@HiltViewModel
class AlertTestViewModel @Inject constructor(
    private val repository: AlertRuleRepository,
    private val alerts: AlertManager,
    private val alarmController: AlarmController
) : ViewModel() {
    val rules = repository.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun test(rule: AlertRule) = alerts.testPresentation(rule)
    fun stop() {
        alerts.stopTestPresentation()
    }
    fun previewSound(type: SoundType) {
        val demo = AlertRule(
            id = "test_sound", name = "Prueba de sonido", description = "",
            soundType = type, soundDurationMs = 2_000
        )
        alarmController.preview(demo)
    }
    fun previewVibration(kind: VibrationPatternKind) {
        val demo = AlertRule(
            id = "test_vibration", name = "Prueba de vibración", description = "",
            soundType = SoundType.NONE, vibrationPattern = kind, soundDurationMs = 1_500
        )
        alarmController.preview(demo)
    }
}
