package com.buknuwo.battery.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.buknuwo.battery.R
import com.buknuwo.battery.alerts.model.AlertRule
import com.buknuwo.battery.alerts.model.NotificationSoundType
import com.buknuwo.battery.alerts.model.Priority
import com.buknuwo.battery.bluetooth.ConnectionState
import com.buknuwo.battery.monitoring.MonitoringService
import com.buknuwo.battery.settings.SettingsRepository
import com.buknuwo.battery.telemetry.TelemetryCatalog
import com.buknuwo.battery.telemetry.TelemetrySnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Pure helper: notification channel selection can be unit-tested without Android. */
fun notificationChannelId(rule: AlertRule): String = when (rule.notificationSoundType) {
    NotificationSoundType.PRIORITY_DEFAULT -> "buknuwo_alert_${rule.priority.channelSuffix}"
    NotificationSoundType.SYSTEM_ALARM -> "buknuwo_rule_${rule.id}"
    NotificationSoundType.CUSTOM -> "buknuwo_rule_${rule.id}"
}

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: TelemetryCatalog,
    private val settings: SettingsRepository
) {
    companion object {
        const val CHANNEL_MONITORING = "buknuwo_monitoring"
        const val ACTION_SILENCE = "com.buknuwo.battery.action.SILENCE"
        const val EXTRA_RULE_ID = "rule_id"
        const val MONITORING_ID = 1001
    }

    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val activeAlertIds = mutableSetOf<Int>()
    private val appliedRuleSounds = mutableMapOf<String, String?>()

    /** Priority channels are shared; a rule with its own sound gets a dedicated channel. */
    fun channelIdFor(rule: AlertRule): String = notificationChannelId(rule)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        createChannel(CHANNEL_MONITORING, "Monitorización BLE", NotificationManager.IMPORTANCE_LOW, false)
        Priority.entries.forEach { priority ->
            val importance = when (priority) {
                Priority.LOW -> NotificationManager.IMPORTANCE_LOW
                Priority.NORMAL -> NotificationManager.IMPORTANCE_DEFAULT
                Priority.HIGH -> NotificationManager.IMPORTANCE_HIGH
                Priority.CRITICAL -> NotificationManager.IMPORTANCE_MAX
            }
            createChannel(
                "buknuwo_alert_${priority.channelSuffix}",
                "Alertas - ${priority.name}",
                importance,
                priority == Priority.CRITICAL || priority == Priority.HIGH
            )
        }
    }

    private fun createChannel(id: String, name: String, importance: Int, vibration: Boolean) {
        if (manager.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, name, importance).apply {
            description = "Canal de notificaciones Buknuwo Battery Monitor"
            enableVibration(vibration)
            if (vibration) vibrationPattern = longArrayOf(0, 250, 150, 250)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            if (importance >= NotificationManager.IMPORTANCE_HIGH) {
                setSound(
                    android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
        manager.createNotificationChannel(channel)
    }

    /** Creates or updates the per-rule channel when the user changes its notification sound. */
    fun ensureRuleChannel(rule: AlertRule) {
        createChannels()
        if (rule.notificationSoundType == NotificationSoundType.PRIORITY_DEFAULT) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val soundUri = when (rule.notificationSoundType) {
            NotificationSoundType.SYSTEM_ALARM -> Settings.System.DEFAULT_ALARM_ALERT_URI.toString()
            NotificationSoundType.CUSTOM -> rule.notificationSoundUri
            else -> null
        }
        val id = channelIdFor(rule)
        synchronized(appliedRuleSounds) {
            if (appliedRuleSounds[id] == soundUri && manager.getNotificationChannel(id) != null) return
            manager.deleteNotificationChannel(id)
            val importance = when (rule.priority) {
                Priority.LOW -> NotificationManager.IMPORTANCE_LOW
                Priority.NORMAL -> NotificationManager.IMPORTANCE_DEFAULT
                Priority.HIGH -> NotificationManager.IMPORTANCE_HIGH
                Priority.CRITICAL -> NotificationManager.IMPORTANCE_MAX
            }
            val channel = NotificationChannel(id, rule.name, importance).apply {
                description = "Sonido personalizado de ${rule.name}"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(rule.priority == Priority.CRITICAL || rule.priority == Priority.HIGH)
                val uri = soundUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                if (uri != null) {
                    setSound(
                        uri,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    setSound(null, null)
                }
            }
            manager.createNotificationChannel(channel)
            appliedRuleSounds[id] = soundUri
        }
    }

    suspend fun show(rule: AlertRule, snapshot: TelemetrySnapshot, value: Double?, nowMs: Long) {
        createChannels()
        ensureRuleChannel(rule)
        val prefs = settings.settings.first()
        val condition = rule.allConditions().firstOrNull()
        val variable = condition?.variableId?.let { catalog.get(it) }
        val formattedValue = when {
            value == null || value >= 1_000_000 -> "SIN DATOS"
            variable != null -> "${"%.3f".format(value)} ${variable.unit}".trim()
            else -> "%.3f".format(value)
        }
        val threshold = condition?.value?.let { "%.3f".format(it) } ?: "—"
        val batteryStatus = buildString {
            append("Voltaje: ")
            append(snapshot.voltageV?.let { "%.2f V".format(it) } ?: "SIN DATOS")
            append(" · SOC: ")
            append(snapshot.soc?.let { "%.0f %%".format(it) } ?: "SIN DATOS")
        }
        val bigText = buildString {
            append("${variable?.label ?: "Alerta"}: $formattedValue\n")
            append("Límite: $threshold · ${rule.priority.name}\n")
            append("Batería: $batteryStatus\n")
            append(java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(nowMs)))
        }
        val channel = channelIdFor(rule)
        val visibleOnLock = rule.showOnLockScreen || prefs.showOnLockScreen
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(rule.name)
            .setContentText("$formattedValue · Límite $threshold")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setWhen(nowMs)
            .setShowWhen(true)
            .setPriority(priorityCompat(rule.priority))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(if (visibleOnLock) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(rule.notificationPersistent)
            .setAutoCancel(!rule.notificationPersistent)
            .setContentIntent(openDashboardPendingIntent())
            .addAction(R.drawable.ic_stat_alert, "Abrir", openDashboardPendingIntent())
            .addAction(R.drawable.ic_stat_alert, "Silenciar", silencePendingIntent(rule.id))
            .build()
        manager.notify(rule.id.hashCode(), notification)
        synchronized(activeAlertIds) { activeAlertIds += rule.id.hashCode() }
    }

    fun cancel(ruleId: String) {
        manager.cancel(ruleId.hashCode())
        synchronized(activeAlertIds) { activeAlertIds -= ruleId.hashCode() }
    }

    fun cancelAllAlerts() {
        synchronized(activeAlertIds) {
            activeAlertIds.forEach { manager.cancel(it) }
            activeAlertIds.clear()
        }
    }

    fun monitoringNotification(state: ConnectionState, snapshot: TelemetrySnapshot): Notification {
        createChannels()
        val subtitle = when (state) {
            ConnectionState.DISCONNECTED -> "Desconectado"
            ConnectionState.SCANNING -> "Buscando batería…"
            ConnectionState.CONNECTING -> "Conectando…"
            ConnectionState.DISCOVERING_SERVICES -> "Descubriendo servicios…"
            ConnectionState.SUBSCRIBING -> "Suscribiendo notificaciones…"
            ConnectionState.CONNECTED -> "Conectado, esperando datos"
            ConnectionState.RECEIVING_DATA -> "Recibiendo datos del BMS"
            ConnectionState.ERROR -> "Error de conexión"
        }
        val detail = buildString {
            append(subtitle)
            if (snapshot.hasData) {
                append(" · ")
                append(snapshot.voltageV?.let { "%.2f V".format(it) } ?: "—")
                append(" · SOC ")
                append(snapshot.soc?.let { "%.0f %%".format(it) } ?: "—")
            }
        }
        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("Buknuwo Battery Monitor")
            .setContentText(detail)
            .setOngoing(true)
            .setContentIntent(openDashboardPendingIntent())
            .build()
    }

    private fun priorityCompat(priority: Priority) = when (priority) {
        Priority.LOW -> NotificationCompat.PRIORITY_LOW
        Priority.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
        Priority.HIGH -> NotificationCompat.PRIORITY_HIGH
        Priority.CRITICAL -> NotificationCompat.PRIORITY_MAX
    }

    private fun openDashboardPendingIntent(): PendingIntent {
        val intent = Intent(context, com.buknuwo.battery.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun silencePendingIntent(ruleId: String): PendingIntent {
        val intent = Intent(context, MonitoringService::class.java).setAction(ACTION_SILENCE).apply {
            putExtra(EXTRA_RULE_ID, ruleId)
        }
        return PendingIntent.getService(context, ruleId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
