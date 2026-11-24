package com.buknuwo.battery.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.buknuwo.battery.ui.common.SectionTitle
import com.buknuwo.battery.ui.common.formatTime
import com.buknuwo.battery.ui.theme.Danger
import com.buknuwo.battery.ui.theme.SurfaceVariant
import com.buknuwo.battery.ui.theme.Warning

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsState()
    val state by viewModel.state.collectAsState()
    val error by viewModel.error.collectAsState()
    val probe by viewModel.probeResult.collectAsState()
    val note by viewModel.userNote.collectAsState()
    val reportStatus by viewModel.reportStatus.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Estado GATT: ${state.name}", style = MaterialTheme.typography.titleMedium)
        if (error != null) Text("Último error: $error", color = Danger, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::startMonitoring, Modifier.weight(1f)) { Text("Iniciar") }
            OutlinedButton(onClick = viewModel::stopMonitoring, Modifier.weight(1f)) { Text("Detener") }
        }
        SectionTitle("Sonda de lectura RX")
        Text(
            "Comprueba experimentalmente si el BMS responde a readCharacteristic(). " +
                "Este resultado no se usa como telemetría: las lecturas no sustituyen a las notificaciones.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = viewModel::probeRxRead, Modifier.fillMaxWidth()) { Text("Probar lectura RX") }
        if (probe != null) Text(probe!!, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))

        SectionTitle("Informe para soporte (.log)")
        Text(
            "Si la batería no se enlaza: activa el Bluetooth, acepta los permisos, escanea y selecciona " +
                "la batería desde «Batería BLE», espera 30 segundos y vuelve aquí. Cuenta qué hiciste " +
                "y comparte el informe por WhatsApp. El archivo incluye permisos, estado GATT, " +
                "configuración, telemetría recibida y todos los registros BLE.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = note,
            onValueChange = viewModel::setUserNote,
            label = { Text("¿Qué hiciste y qué falló? (opcional)") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            minLines = 3
        )
        Button(onClick = viewModel::shareReport, Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Generar y compartir informe .log")
        }
        if (reportStatus != null) {
            Text(reportStatus!!, color = com.buknuwo.battery.ui.theme.Accent, style = MaterialTheme.typography.bodyMedium)
        }

        SectionTitle("Registro BLE (${entries.size})")
        OutlinedButton(onClick = viewModel::clearLogs, Modifier.fillMaxWidth()) { Text("Limpiar registro") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(entries, key = { it.id }) { entry ->
                val color = when (entry.level) {
                    com.buknuwo.battery.diagnostics.BleDiagnostics.Level.ERROR -> Danger
                    com.buknuwo.battery.diagnostics.BleDiagnostics.Level.WARN -> Warning
                    com.buknuwo.battery.diagnostics.BleDiagnostics.Level.TX -> Color(0xFF6FB7C7)
                    com.buknuwo.battery.diagnostics.BleDiagnostics.Level.RX -> com.buknuwo.battery.ui.theme.Accent
                    else -> Color.Gray
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                    Column(Modifier.padding(8.dp)) {
                        Text("${formatTime(entry.timestampMs)} · ${entry.level} · ${entry.source}", style = MaterialTheme.typography.labelSmall, color = color)
                        Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
