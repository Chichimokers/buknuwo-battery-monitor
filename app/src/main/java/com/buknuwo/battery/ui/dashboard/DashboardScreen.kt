package com.buknuwo.battery.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.buknuwo.battery.bluetooth.ConnectionState
import com.buknuwo.battery.database.TelemetrySampleEntity
import com.buknuwo.battery.ui.common.LineChart
import com.buknuwo.battery.ui.common.MetricCard
import com.buknuwo.battery.ui.common.SectionTitle
import com.buknuwo.battery.ui.common.SocGauge
import com.buknuwo.battery.ui.common.StatusChip
import com.buknuwo.battery.ui.common.formatValue
import com.buknuwo.battery.ui.theme.Accent
import com.buknuwo.battery.ui.theme.Danger
import com.buknuwo.battery.ui.theme.SurfaceVariant
import com.buknuwo.battery.ui.theme.Warning

private fun List<TelemetrySampleEntity>.points(selector: (TelemetrySampleEntity) -> Double?) =
    mapNotNull { sample -> selector(sample)?.let { sample.timestampMs to it } }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    onOpenDevices: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val snapshot by viewModel.snapshot.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val error by viewModel.lastError.collectAsState()
    val samples by viewModel.samples.collectAsState()
    val range by viewModel.chartRangeMs.collectAsState()
    val activeIds by viewModel.activeAlertIds.collectAsState()
    val ruleCount by viewModel.ruleCount.collectAsState()

    val status = when (state) {
        ConnectionState.DISCONNECTED -> "DESCONECTADO" to Color.Gray
        ConnectionState.SCANNING -> "BUSCANDO" to Accent
        ConnectionState.CONNECTING -> "CONECTANDO" to Accent
        ConnectionState.DISCOVERING_SERVICES -> "DESCUBRIENDO SERVICIOS" to Accent
        ConnectionState.SUBSCRIBING -> "SUSCRIBIENDO" to Accent
        ConnectionState.CONNECTED -> "CONECTADO" to Accent
        ConnectionState.RECEIVING_DATA -> "RECIBIENDO DATOS" to Accent
        ConnectionState.ERROR -> "ERROR DE CONEXIÓN" to Danger
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusChip(status.first, status.second)
            Text("${ruleCount.size} reglas · ${activeIds.size} activas", style = MaterialTheme.typography.labelSmall)
        }
        if (error != null) {
            Text("Error: $error", color = Danger, style = MaterialTheme.typography.bodyMedium)
        }

        if (!snapshot.hasData) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.12f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("SIN DATOS", color = Warning, fontWeight = FontWeight.Bold)
                    Text(
                        "No se ha recibido ninguna trama real del BMS. Conecte la batería y espere a que llegue al menos una respuesta MODBUS válida.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::startMonitoring) { Text("Iniciar monitorización") }
                        OutlinedButton(onClick = onOpenDevices) { Text("Seleccionar batería") }
                    }
                }
            }
        }

        SectionTitle("Información general")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SocGauge(snapshot.soc, Modifier.weight(0.35f))
            Column(Modifier.weight(0.65f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Voltaje total", formatValue(snapshot.voltageV, 2, "V"))
                MetricCard("Corriente", formatValue(snapshot.currentA, 2, "A"), accent = if ((snapshot.currentA ?: 0.0) < 0) Warning else Accent)
                MetricCard("Potencia", formatValue(snapshot.powerW, 1, "W"))
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "SOH" to formatValue(snapshot.soh, 0, "%"),
                "Capacidad" to formatValue(snapshot.nominalAh, 2, "Ah"),
                "Ciclos" to formatValue(snapshot.cycles, 0),
                "Ah del ciclo" to formatValue(snapshot.cycleAh, 2, "Ah")
            ).forEach { (label, value) ->
                MetricCard(label, value, Modifier.weight(1f, fill = false))
            }
        }

        SectionTitle("Información eléctrica")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Celda máx.", formatValue(snapshot.maxCellV, 3, "V"), Modifier.weight(1f))
            MetricCard("Celda mín.", formatValue(snapshot.minCellV, 3, "V"), Modifier.weight(1f))
            MetricCard("Δ celdas", formatValue(snapshot.cellDeltaV, 3, "V"), Modifier.weight(1f))
        }
        if (snapshot.cellVoltagesV.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Voltajes de celdas", style = MaterialTheme.typography.titleMedium)
                    snapshot.cellVoltagesV.forEachIndexed { index, value ->
                        Text("Celda ${index + 1}: ${formatValue(value, 3, "V")}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Text("Temperaturas", style = MaterialTheme.typography.titleMedium)
                Text("MOSFET: ${formatValue(snapshot.mosfetTempC, 1, "°C")}", style = MaterialTheme.typography.bodyMedium)
                snapshot.temperatureSensorsC.forEachIndexed { index, temp ->
                    Text("Sensor ${index + 1}: ${formatValue(temp, 1, "°C")}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        SectionTitle("Protección y MOS")
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                val words = snapshot.protectionWords
                if (words == null) {
                    Text("SIN DATOS de protección", style = MaterialTheme.typography.bodyMedium)
                } else {
                    words.forEachIndexed { index, word ->
                        Text("0x%04X".format(0x0009 + index) + " = 0x%04X".format(word) + " (bits 0–15)", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Los nombres de bits no se asignan sin documentación verificada del BMS.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    "MOS carga/descarga: configurable en Ajustes; sin asignar = desconocido",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        SectionTitle("Gráficos (datos reales)")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5L, 15L, 60L, 360L).forEach { minutes ->
                FilterChip(
                    selected = range == minutes * 60_000L,
                    onClick = { viewModel.setChartRange(minutes * 60_000L) },
                    label = { Text("${minutes} min") }
                )
            }
        }
        LineChart(samples.points { it.voltageV }, color = Accent, label = "Voltaje total (V)")
        LineChart(samples.points { it.currentA }, color = Warning, label = "Corriente (A)")
        LineChart(samples.points { it.soc }, color = Accent, label = "SOC (%)")
        LineChart(samples.points { it.mosfetTempC }, color = Danger, label = "Temperatura MOSFET (°C)")
        LineChart(samples.points { it.cellDeltaV }, color = Warning, label = "Diferencia entre celdas (V)")

        Spacer(Modifier.height(16.dp))
        if (state == ConnectionState.RECEIVING_DATA) {
            OutlinedButton(onClick = viewModel::stopMonitoring, Modifier.fillMaxWidth()) { Text("Detener monitorización") }
        }
    }
}
