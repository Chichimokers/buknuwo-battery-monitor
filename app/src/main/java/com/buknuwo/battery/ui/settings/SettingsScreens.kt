package com.buknuwo.battery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.buknuwo.battery.ui.common.NumberField
import com.buknuwo.battery.ui.common.SectionTitle

@Composable
fun GeneralSettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.settings.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Monitorización")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Iniciar monitorización al arrancar el teléfono")
            Switch(checked = prefs.autoStartOnBoot, onCheckedChange = viewModel::setAutoStart)
        }
        Text("Android puede impedir el inicio en segundo plano tras el arranque; consulte Diagnóstico.", style = MaterialTheme.typography.labelSmall)
        NumberField("Intervalo de consulta (ms)", prefs.pollIntervalMs.toDouble(), { viewModel.setPollInterval(it.toLong()) })
        NumberField("Timeout de solicitud (ms)", prefs.requestTimeoutMs.toDouble(), { viewModel.setRequestTimeout(it.toLong()) })
        NumberField("Watchdog sin datos (ms)", prefs.watchdogTimeoutMs.toDouble(), { viewModel.setWatchdog(it.toLong()) })
        NumberField("Máximo de reconexiones", prefs.maxReconnectAttempts.toDouble(), { viewModel.setReconnect(prefs.reconnectEnabled, it.toInt()) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Reconexión automática")
            Switch(checked = prefs.reconnectEnabled, onCheckedChange = { viewModel.setReconnect(it, prefs.maxReconnectAttempts) })
        }

        SectionTitle("Historial y privacidad")
        NumberField("Conservación de historial (días)", prefs.historyRetentionDays.toDouble(), { viewModel.setRetention(it.toInt()) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Mostrar notificaciones en pantalla de bloqueo")
            Switch(checked = prefs.showOnLockScreen, onCheckedChange = viewModel::setShowOnLockScreen)
        }

        SectionTitle("Mapa de bits MOS (no confirmado)")
        Text("Los bits de carga/descarga del BMS no están documentados. Configure aquí el índice de palabra (0=0x0009, 1=0x000A, 2=0x000B) y el bit solo si los ha verificado con tramas reales. -1 = sin asignar.", style = MaterialTheme.typography.bodyMedium)
        NumberField("Palabra MOS carga (-1..2)", prefs.mosMapping.chargeRegisterIndex?.toDouble() ?: -1.0) { value ->
            viewModel.setMosMapping(prefs.mosMapping.copy(chargeRegisterIndex = value.toInt().takeIf { it >= 0 }))
        }
        NumberField("Bit MOS carga (0..15)", prefs.mosMapping.chargeBit.toDouble()) { value ->
            viewModel.setMosMapping(prefs.mosMapping.copy(chargeBit = value.toInt().coerceIn(0, 15)))
        }
        NumberField("Palabra MOS descarga (-1..2)", prefs.mosMapping.dischargeRegisterIndex?.toDouble() ?: -1.0) { value ->
            viewModel.setMosMapping(prefs.mosMapping.copy(dischargeRegisterIndex = value.toInt().takeIf { it >= 0 }))
        }
        NumberField("Bit MOS descarga (0..15)", prefs.mosMapping.dischargeBit.toDouble()) { value ->
            viewModel.setMosMapping(prefs.mosMapping.copy(dischargeBit = value.toInt().coerceIn(0, 15)))
        }
    }
}

@Composable
fun SoundSettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.settings.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("Sonido global de alertas")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Silenciar todas las alertas")
            Switch(checked = prefs.alertsMuted, onCheckedChange = viewModel::setMuted)
        }
        Text("Volumen maestro ${(prefs.alertMasterVolume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
        Slider(prefs.alertMasterVolume, { viewModel.setMasterVolume(it) }, valueRange = 0f..1f)
        Text("El sonido específico, la duración, la repetición y el archivo personalizado se configuran en cada regla (Editar alerta).", style = MaterialTheme.typography.bodyMedium)
        Text("Se usan tonos del sistema y AudioAttributes de alarma. Las repeticiones dependen del gestor de alarmas propio, no solo de nuevas notificaciones.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun VibrationSettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("Vibración")
        Text("Cada alerta permite elegir vibración corta, larga, doble, triple, patrón personalizado y número de repeticiones.", style = MaterialTheme.typography.bodyMedium)
        Text("El patrón se aplica con VibratorManager (Android 12+) o Vibrator en versiones anteriores, respetando las capacidades del dispositivo.", style = MaterialTheme.typography.bodyMedium)
        Text("Use la pantalla «Prueba de alertas» para comprobar la vibración sin conectar la batería.", style = MaterialTheme.typography.labelSmall)
    }
}
