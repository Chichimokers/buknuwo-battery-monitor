package com.buknuwo.battery.monitoring

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.buknuwo.battery.alerts.AlertManager
import com.buknuwo.battery.alerts.NotificationHelper
import com.buknuwo.battery.bluetooth.AndroidBleManager
import com.buknuwo.battery.bluetooth.BleDeviceInfo
import com.buknuwo.battery.bluetooth.BleManager
import com.buknuwo.battery.bluetooth.ConnectionState
import com.buknuwo.battery.diagnostics.BleDiagnostics
import com.buknuwo.battery.settings.SettingsRepository
import com.buknuwo.battery.telemetry.TelemetryRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringService : Service() {

    companion object {
        const val ACTION_START = "com.buknuwo.battery.action.START_MONITORING"
        const val ACTION_STOP = "com.buknuwo.battery.action.STOP_MONITORING"

        fun start(context: Context) {
            val intent = Intent(context, MonitoringService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }
    }

    @Inject lateinit var ble: BleManager
    @Inject lateinit var telemetry: TelemetryRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var controller: MonitorController
    @Inject lateinit var notifications: NotificationHelper
    @Inject lateinit var alerts: AlertManager
    @Inject lateinit var diagnostics: BleDiagnostics

    private lateinit var scope: CoroutineScope
    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        notifications.createChannels()
        startForeground(
            NotificationHelper.MONITORING_ID,
            notifications.monitoringNotification(ble.connectionState.value, telemetry.current())
        )
        scope.launch {
            combine(ble.connectionState, telemetry.latest) { state, snapshot -> state to snapshot }
                .collect { (state, snapshot) ->
                    telemetry.setConnectionState(state)
                    notificationManager.notify(
                        NotificationHelper.MONITORING_ID,
                        notifications.monitoringNotification(state, snapshot)
                    )
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> scope.launch { startMonitoring() }
            ACTION_STOP -> {
                controller.stop()
                ble.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            NotificationHelper.ACTION_SILENCE -> scope.launch {
                intent.getStringExtra(NotificationHelper.EXTRA_RULE_ID)?.let { alerts.silence(it) }
            }
            else -> if (intent == null) {
                // START_STICKY delivery after the process is killed: restore from settings.
                scope.launch { startMonitoring() }
            }
        }
        return START_STICKY
    }

    private suspend fun startMonitoring() {
        val prefs = settings.settings.first()
        val address = prefs.targetAddress
        if (address.isNullOrBlank()) {
            diagnostics.error("SERVICE", "No hay batería seleccionada")
            stopSelf()
            return
        }
        val device = BleDeviceInfo(
            name = prefs.targetName ?: AndroidBleManager.TARGET_NAME,
            address = address,
            rssi = 0,
            isTarget = true
        )
        // Start the notification collector before connecting so no real frame is lost.
        controller.start(scope, device)
        if (ble.connectionState.value !in setOf(ConnectionState.CONNECTED, ConnectionState.RECEIVING_DATA)) {
            val result = ble.connect(device)
            if (result.isFailure) {
                diagnostics.error("SERVICE", "No se pudo conectar: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller.stop()
        ble.release()
        scope.cancel()
        super.onDestroy()
    }
}
