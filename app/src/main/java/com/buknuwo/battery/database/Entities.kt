package com.buknuwo.battery.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alert_rules")
data class AlertRuleEntity(
    @PrimaryKey val ruleId: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val priority: String,
    val logic: String,
    val hysteresis: Double,
    val minActiveDurationMs: Long,
    val maxDataAgeMs: Long,
    val soundType: String,
    val soundUri: String?,
    val soundVolume: Float,
    val soundDurationMs: Long,
    val repeatSoundWhileActive: Boolean,
    val soundRepeatIntervalMs: Long,
    val vibrationPattern: String,
    val customVibrationPattern: String?,
    val vibrationRepeat: Int,
    val notificationEnabled: Boolean,
    val notificationPersistent: Boolean,
    val showOnLockScreen: Boolean,
    val repeatNotification: Boolean,
    val notificationRepeatIntervalMs: Long,
    val scheduleEnabled: Boolean,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val daysOfWeek: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "alert_conditions", primaryKeys = ["conditionId"])
data class ConditionEntity(
    val conditionId: String,
    val ruleId: String,
    val parentId: String?,
    val isGroup: Boolean,
    val logic: String,
    val orderIndex: Int,
    val variableId: String,
    val operator: String,
    val value: Double?,
    val secondValue: Double?,
    val durationMs: Long,
    val windowMs: Long
)

@Entity(tableName = "alert_states")
data class AlertStateEntity(
    @PrimaryKey val ruleId: String,
    val state: String,
    val sinceMs: Long,
    val pendingSinceMs: Long,
    val lastTriggeredAt: Long,
    val lastRecoveredAt: Long,
    val lastNotifiedAt: Long,
    val lastValue: Double?,
    val lastSequence: Long
)

@Entity(tableName = "alert_events")
data class AlertEventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val ruleId: String,
    val ruleName: String,
    val eventType: String,
    val timestampMs: Long,
    val valueText: String,
    val thresholdText: String,
    val priority: String,
    val snapshotSummary: String
)

@Entity(tableName = "telemetry_samples")
data class TelemetrySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val voltageV: Double?,
    val currentA: Double?,
    val powerW: Double?,
    val soc: Double?,
    val soh: Double?,
    val mosfetTempC: Double?,
    val cellDeltaV: Double?,
    val cellMinV: Double?,
    val cellMaxV: Double?
)
