package com.buknuwo.battery.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.buknuwo.battery.ui.common.DataGrid
import com.buknuwo.battery.ui.common.LineChart
import com.buknuwo.battery.ui.common.MetricGrid
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

    BoxWithConstraints {
        val compact = maxWidth < 600.dp
        val gaugeSize = if (compact) 112.dp else 150.dp
        val smallCards = if (compact) 2 else 4
        val dataColumns = if (compact) 2 else 4

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (compact) 12.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatusChip(status.first, status.second)
                Text("${ruleCount.size} reglas · ${activeIds.size} activas", style = MaterialTheme.typography.labelSmall)
            }
            if (error != null) Text("Error: $error", color = Danger, style = MaterialTheme.typography.bodyMedium)

            if (!snapshot.hasData) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.12f))) {
                    Column(Modifier.padding(14.dp)) {
                        Text("SIN DATOS", color = Warning, fontWeight = FontWeight.Bold)
                        Text(
                            "Aún no llegó ninguna trama real del BMS. Conecte la batería y espere una respuesta MODBUS válida.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::startMonitoring, Modifier.weight(1f)) { Text("Monitorizar") }
                            OutlinedButton(onClick = onOpenDevices, Modifier.weight(1f)) { Text("Batería BLE") }
                        }
                    }
                }
            }

            SectionTitle("Información general")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SocGauge(snapshot.soc, Modifier.size(gaugeSize))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricGrid(
                        listOf(
                            Triple("Voltaje", formatValue(snapshot.voltageV, 2, "V"), Accent),
                            Triple("Corriente", formatValue(snapshot.currentA, 2, "A"),
                                if ((snapshot.currentA ?: 0.0) < 0) Warning else Accent),
                            Triple("Potencia", formatValue(snapshot.powerW, 1, "W"), Accent)
                        ),
                        columns = 1,
                        compact = true
                    )
                }
            }
            MetricGrid(
                listOf(
                    Triple("SOH", formatValue(snapshot.soh, 0, "%"), Accent),
                    Triple("Capacidad", formatValue(snapshot.nominalAh, 0, "Ah"), Accent),
                    Triple("Ciclos", formatValue(snapshot.cycles, 0), Accent),
                    Triple("Ah ciclo", formatValue(snapshot.cycleAh, 1, "Ah"), Accent)
                ),
                columns = smallCards,
                compact = true
            )

            SectionTitle("Información eléctrica")
            MetricGrid(
                listOf(
                    Triple("Celda máx.", formatValue(snapshot.maxCellV, 3, "V"), Accent),
                    Triple("Celda mín.", formatValue(snapshot.minCellV, 3, "V"), Accent),
                    Triple("Δ celdas", formatValue(snapshot.cellDeltaV, 3, "V"), Warning)
                ),
                columns = if (compact) 3 else 3,
                compact = true
            )
            DataGrid(
                "Voltajes de celdas",
                snapshot.cellVoltagesV.mapIndexed { i, v -> "Celda ${i + 1}" to formatValue(v, 3, "V") },
                dataColumns
            )
            DataGrid(
                "Temperaturas",
                buildList {
                    add("MOSFET" to formatValue(snapshot.mosfetTempC, 1, "°C"))
                    snapshot.temperatureSensorsC.forEachIndexed { i, t ->
                        add("Sensor ${i + 1}" to formatValue(t, 1, "°C"))
                    }
                },
                dataColumns
            )

            SectionTitle("Protección")
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    val words = snapshot.protectionWords
                    if (words == null) {
                        Text("SIN DATOS de protección", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        words.forEachIndexed { i, word ->
                            Text(
                                "0x%04X".format(0x0009 + i) + " = 0x%04X".format(word) + "  (bits 0–15)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            "Los nombres de bits no se asignan sin documentación verificada del BMS.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text("MOS carga/descarga: configurable en Ajustes; sin asignar = desconocido",
                        style = MaterialTheme.typography.labelSmall)
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

            if (state == ConnectionState.RECEIVING_DATA) {
                OutlinedButton(onClick = viewModel::stopMonitoring, Modifier.fillMaxWidth()) { Text("Detener monitorización") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
