package com.buknuwo.battery.diagnostics

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.buknuwo.battery.BuildConfig
import com.buknuwo.battery.bluetooth.BleManager
import com.buknuwo.battery.bluetooth.BluetoothPermissions
import com.buknuwo.battery.database.AlertEventDao
import com.buknuwo.battery.settings.SettingsRepository
import com.buknuwo.battery.telemetry.TelemetryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Plain data so report formatting can be unit-tested without Android. */
data class ReportData(
    val generatedAtMs: Long,
    val appVersion: String,
    val deviceInfo: String,
    val permissions: List<String>,
    val bluetooth: List<String>,
    val configuration: List<String>,
    val telemetry: List<String>,
    val userNote: String,
    val events: List<String>,
    val logs: List<String>,
    val persistedLogTail: String
)

fun formatReport(data: ReportData): String = buildString {
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(data.generatedAtMs))
    appendLine("============================================================")
    appendLine("BUKNUWO BATTERY MONITOR - INFORME DE SOPORTE")
    appendLine("============================================================")
    appendLine("Generado: $ts")
    appendLine("App: ${data.appVersion}  |  ${data.deviceInfo}")
    appendLine()
    appendLine("--- PERMISOS ---")
    data.permissions.forEach { appendLine("- $it") }
    appendLine()
    appendLine("--- BLUETOOTH / GATT ---")
    data.bluetooth.forEach { appendLine("- $it") }
    appendLine()
    appendLine("--- CONFIGURACIÓN ---")
    data.configuration.forEach { appendLine("- $it") }
    appendLine()
    appendLine("--- TELEMETRÍA (SIN DATOS = ninguna trama real recibida) ---")
    data.telemetry.forEach { appendLine("- $it") }
    appendLine()
    appendLine("--- CUÉNTANOS QUÉ HICISTE ---")
    appendLine(data.userNote.ifBlank { "(sin comentario del usuario)" })
    appendLine()
    appendLine("--- ÚLTIMOS EVENTOS DE ALERTAS ---")
    if (data.events.isEmpty()) appendLine("(ninguno)") else data.events.forEach { appendLine("- $it") }
    appendLine()
    appendLine("--- REGISTRO BLE (memoria) ---")
    if (data.logs.isEmpty()) appendLine("(vacío)") else data.logs.forEach(::appendLine)
    appendLine()
    appendLine("--- REGISTRO PERSISTENTE (últimas líneas) ---")
    appendLine(data.persistedLogTail.ifBlank { "(vacío)" })
    appendLine()
    appendLine("============================================================")
    appendLine("FIN DEL INFORME")
}

@Singleton
class ReportGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: BleDiagnostics,
    private val settings: SettingsRepository,
    private val ble: BleManager,
    private val telemetry: TelemetryRepository,
    private val eventDao: AlertEventDao
) {

    suspend fun generate(userNote: String): File {
        val prefs = settings.settings.first()
        val snap = telemetry.current()
        val now = System.currentTimeMillis()

        val permissions = buildList {
            fun state(permission: String, label: String) {
                val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                add("$label: ${if (granted) "CONCEDIDO" else "DENEGADO"} ($permission)")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                state(Manifest.permission.BLUETOOTH_SCAN, "Escanear BLE")
                state(Manifest.permission.BLUETOOTH_CONNECT, "Conectar BLE")
            } else {
                state(Manifest.permission.ACCESS_FINE_LOCATION, "Ubicación exacta (requerida por BLE en Android ≤ 10)")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                state(Manifest.permission.POST_NOTIFICATIONS, "Notificaciones")
            }
        }

        val bluetooth = buildList {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter? = manager?.adapter
            add("Adaptador presente: ${adapter != null}")
            add("Bluetooth activado: ${adapter?.isEnabled == true}")
            add("Estado GATT actual: ${ble.connectionState.value.name}")
            add("Último error: ${ble.lastError.value ?: "ninguno"}")
            add("Dispositivos vistos en el último escaneo: ${ble.devices.value.size}")
            add("Dispositivo objetivo visto: ${ble.devices.value.any { it.isTarget }}")
            try {
                @Suppress("MissingPermission")
                add("Dirección del adaptador: ${adapter?.address ?: "no disponible"}")
                @Suppress("MissingPermission")
                add("Dispositivos vinculados: ${adapter?.bondedDevices?.size ?: 0}")
            } catch (_: SecurityException) {
                add("Datos del adaptador: no disponibles sin permiso BLUETOOTH_CONNECT")
            }
        }

        val configuration = listOf(
            "Batería guardada: ${prefs.targetName ?: "ninguna"} / ${prefs.targetAddress ?: "ninguna"}",
            "Intervalo de consulta: ${prefs.pollIntervalMs} ms",
            "Timeout de solicitud: ${prefs.requestTimeoutMs} ms",
            "Reconexión: ${prefs.reconnectEnabled} (máx. ${prefs.maxReconnectAttempts})",
            "Watchdog sin datos: ${prefs.watchdogTimeoutMs} ms",
            "Alertas silenciadas globalmente: ${prefs.alertsMuted}",
            "Mapeo MOS configurado: ${prefs.mosMapping.isConfigured}"
        )

        fun value(v: Double?, digits: Int = 2, unit: String = "") =
            if (v == null) "SIN DATOS" else "${String.format(Locale.US, "%,.${digits}f", v)}${if (unit.isBlank()) "" else " $unit"}"

        val age = if (snap.lastDataAtMs <= 0) "nunca" else "${(now - snap.lastDataAtMs) / 1000} s"
        val telemetryLines = listOf(
            "Secuencia de telemetría: ${snap.sequence}",
            "Último dato recibido hace: $age",
            "Voltaje total: ${value(snap.voltageV, 2, "V")}",
            "Corriente: ${value(snap.currentA, 2, "A")}",
            "Potencia: ${value(snap.powerW, 1, "W")}",
            "SOC: ${value(snap.soc, 0, "%")}",
            "SOH: ${value(snap.soh, 0, "%")}",
            "Ciclos: ${value(snap.cycles, 0)}",
            "Temperatura MOSFET: ${value(snap.mosfetTempC, 1, "°C")}",
            "Celdas recibidas: ${snap.cellVoltagesV.count { it != null }}",
            "Sensores de temperatura recibidos: ${snap.temperatureSensorsC.count { it != null }}",
            "Palabras de protección: ${snap.protectionWords?.joinToString(", ") { "0x%04X".format(it) } ?: "SIN DATOS"}"
        )

        val events = eventDao.observeRecent(20).first().map { event ->
            "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(event.timestampMs))} " +
                "${event.eventType} ${event.ruleName} valor=${event.valueText} límite=${event.thresholdText} (${event.priority})"
        }

        val data = ReportData(
            generatedAtMs = now,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            permissions = permissions,
            bluetooth = bluetooth,
            configuration = configuration,
            telemetry = telemetryLines,
            userNote = userNote,
            events = events,
            logs = diagnostics.allEntries().map { it.format() },
            persistedLogTail = diagnostics.fileText().takeLast(12_000)
        )

        val reportDir = File(context.filesDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val file = File(reportDir, "buknuwo_report_$stamp.log")
        file.writeText(formatReport(data))
        diagnostics.log(BleDiagnostics.Level.INFO, "REPORT", "Informe generado: ${file.name}")
        return file
    }
}
