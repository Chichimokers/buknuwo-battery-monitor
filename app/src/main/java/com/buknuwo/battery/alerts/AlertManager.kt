package com.buknuwo.battery.alerts

import com.buknuwo.battery.alerts.engine.AlertEngine
import com.buknuwo.battery.alerts.model.AlertPhase
import com.buknuwo.battery.alerts.model.AlertRule
import com.buknuwo.battery.alerts.model.AlertRuntimeState
import com.buknuwo.battery.alerts.model.Priority
import com.buknuwo.battery.database.AlertEventDao
import com.buknuwo.battery.database.AlertEventEntity
import com.buknuwo.battery.database.AlertStateDao
import com.buknuwo.battery.database.AlertStateEntity
import com.buknuwo.battery.di.ApplicationScope
import com.buknuwo.battery.settings.SettingsRepository
import com.buknuwo.battery.telemetry.TelemetryContext
import com.buknuwo.battery.telemetry.TelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertManager @Inject constructor(
    private val rulesRepository: AlertRuleRepository,
    private val telemetry: TelemetryRepository,
    private val settings: SettingsRepository,
    private val stateDao: AlertStateDao,
    private val eventDao: AlertEventDao,
    private val engine: AlertEngine,
    private val alarmController: AlarmController,
    private val notifications: NotificationHelper,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    private val runtime = mutableMapOf<String, AlertRuntimeState>()
    private var currentRules: List<AlertRule> = emptyList()

    private val _activeRuleIds = MutableStateFlow<Set<String>>(emptySet())
    val activeRuleIds: StateFlow<Set<String>> = _activeRuleIds.asStateFlow()

    init {
        notifications.createChannels()
        scope.launch {
            stateDao.all().forEach { runtime[it.ruleId] = it.toRuntime() }
            rulesRepository.rules.collect { rules ->
                currentRules = rules
                evaluateAll(System.currentTimeMillis())
            }
        }
        scope.launch {
            telemetry.updates.collect { evaluateAll(System.currentTimeMillis()) }
        }
        scope.launch {
            while (true) {
                delay(1_000)
                evaluateAll(System.currentTimeMillis())
            }
        }
    }

    private suspend fun evaluateAll(now: Long) = mutex.withLock {
        val prefs = settings.settings.first()
        val snapshot = telemetry.current()
        val connected = telemetry.connectionState.value.name in setOf("CONNECTED", "RECEIVING_DATA")
        val ctx = TelemetryContext(
            snapshot = snapshot,
            nowMs = now,
            connected = connected,
            mosMapping = prefs.mosMapping,
            history = telemetry
        )
        currentRules.forEach { rule ->
            val state = runtime.getOrPut(rule.id) {
                stateDao.get(rule.id)?.toRuntime() ?: engine.initialState(rule.id)
            }
            val outcome = engine.evaluate(rule, state, ctx)
            runtime[rule.id] = outcome.state
            stateDao.save(outcome.state.toEntity())

            if (outcome.triggered) {
                val notified = outcome.state.copy(lastNotifiedAt = now, lastSoundAt = now)
                runtime[rule.id] = notified
                stateDao.save(notified.toEntity())
                if (rule.notificationEnabled && !prefs.alertsMuted) {
                    notifications.show(rule, snapshot, outcome.state.lastValue, now)
                }
                alarmController.start(rule)
                recordEvent(rule, "TRIGGERED", outcome.state.lastValue, now, snapshot)
            }
            if (outcome.recovered) {
                notifications.cancel(rule.id)
                alarmController.stop(rule.id)
                recordEvent(rule, "RECOVERED", outcome.state.lastValue, now, snapshot)
            }
            var currentState = outcome.state
            val shouldRepeat = currentState.phase == AlertPhase.ACTIVE && rule.repeatNotification &&
                now - currentState.lastNotifiedAt >= rule.notificationRepeatIntervalMs
            if (shouldRepeat && !prefs.alertsMuted) {
                notifications.show(rule, snapshot, currentState.lastValue, now)
                currentState = currentState.copy(lastNotifiedAt = now)
            }
            val shouldRepeatSound = currentState.phase == AlertPhase.ACTIVE && rule.repeatSoundWhileActive &&
                now - currentState.lastSoundAt >= rule.soundRepeatIntervalMs
            if (shouldRepeatSound && !prefs.alertsMuted) {
                alarmController.start(rule)
                currentState = currentState.copy(lastSoundAt = now)
            }
            if (currentState !== outcome.state) {
                runtime[rule.id] = currentState
                stateDao.save(currentState.toEntity())
            }
        }
        _activeRuleIds.value = runtime.filter { it.value.phase == AlertPhase.ACTIVE }.keys
    }

    suspend fun silence(ruleId: String) = mutex.withLock {
        val now = System.currentTimeMillis()
        runtime[ruleId]?.let {
            val updated = it.copy(lastNotifiedAt = now, lastSoundAt = now)
            runtime[ruleId] = updated
            stateDao.save(updated.toEntity())
        }
        alarmController.stop(ruleId)
        notifications.cancel(ruleId)
    }

    suspend fun silenceAll() = mutex.withLock {
        alarmController.stopAll()
        runtime.keys.forEach { notifications.cancel(it) }
    }

    /** Manual presentation test: never modifies telemetry or alert state. */
    fun testPresentation(rule: AlertRule) {
        scope.launch {
            notifications.show(rule, telemetry.current(), rule.allConditions().firstOrNull()?.value, System.currentTimeMillis())
        }
        alarmController.preview(rule)
    }

    fun stopTestPresentation() {
        alarmController.stopAll()
    }

    private suspend fun recordEvent(
        rule: AlertRule,
        type: String,
        value: Double?,
        now: Long,
        snapshot: com.buknuwo.battery.telemetry.TelemetrySnapshot
    ) {
        val condition = rule.allConditions().firstOrNull()
        eventDao.insert(
            AlertEventEntity(
                ruleId = rule.id,
                ruleName = rule.name,
                eventType = type,
                timestampMs = now,
                valueText = when {
                    value == null -> "SIN DATOS"
                    value >= 1_000_000 -> "SIN DATOS" // seconds_since_data sentinel when no frame ever arrived
                    else -> "%.3f".format(Locale.US, value)
                },
                thresholdText = condition?.value?.let { "%.3f".format(Locale.US, it) } ?: "—",
                priority = rule.priority.name,
                snapshotSummary = "V=${snapshot.voltageV?.let { "%.2f".format(it) } ?: "—"} V " +
                    "I=${snapshot.currentA?.let { "%.2f".format(it) } ?: "—"} A " +
                    "SOC=${snapshot.soc?.let { "%.0f".format(it) } ?: "—"} %"
            )
        )
    }
}

private fun AlertStateEntity.toRuntime() = AlertRuntimeState(
    ruleId = ruleId,
    phase = runCatching { AlertPhase.valueOf(state) }.getOrDefault(AlertPhase.INACTIVE),
    sinceMs = sinceMs,
    pendingSinceMs = pendingSinceMs,
    lastTriggeredAt = lastTriggeredAt,
    lastRecoveredAt = lastRecoveredAt,
    lastNotifiedAt = lastNotifiedAt,
    lastValue = lastValue,
    lastSequence = lastSequence
)

private fun AlertRuntimeState.toEntity() = AlertStateEntity(
    ruleId = ruleId,
    state = phase.name,
    sinceMs = sinceMs,
    pendingSinceMs = pendingSinceMs,
    lastTriggeredAt = lastTriggeredAt,
    lastRecoveredAt = lastRecoveredAt,
    lastNotifiedAt = lastNotifiedAt,
    lastValue = lastValue,
    lastSequence = lastSequence
)
