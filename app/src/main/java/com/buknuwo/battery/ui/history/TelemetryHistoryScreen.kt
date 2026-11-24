package com.buknuwo.battery.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.buknuwo.battery.ui.common.LineChart
import com.buknuwo.battery.ui.common.SectionTitle
import com.buknuwo.battery.ui.theme.Accent
import com.buknuwo.battery.ui.theme.Danger
import com.buknuwo.battery.ui.theme.Warning

@Composable
fun TelemetryHistoryScreen(viewModel: TelemetryHistoryViewModel = hiltViewModel()) {
    val samples by viewModel.samples.collectAsState()
    val range by viewModel.rangeMs.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Historial de telemetría real", style = MaterialTheme.typography.titleLarge)
        Text("Solo se muestran muestras recibidas del BMS y validadas por CRC.", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            listOf(5L, 15L, 60L, 360L, 1440L).forEach { minutes ->
                FilterChip(
                    selected = range == minutes * 60_000L,
                    onClick = { viewModel.setRange(minutes * 60_000L) },
                    label = { Text("${minutes} min") }
                )
            }
        }
        SectionTitle("${samples.size} muestras almacenadas")
        LineChart(samples.mapNotNull { s -> s.voltageV?.let { s.timestampMs to it } }, color = Accent, label = "Voltaje (V)")
        LineChart(samples.mapNotNull { s -> s.currentA?.let { s.timestampMs to it } }, color = Warning, label = "Corriente (A)")
        LineChart(samples.mapNotNull { s -> s.soc?.let { s.timestampMs to it } }, color = Accent, label = "SOC (%)")
        LineChart(samples.mapNotNull { s -> s.mosfetTempC?.let { s.timestampMs to it } }, color = Danger, label = "Temperatura MOSFET (°C)")
        LineChart(samples.mapNotNull { s -> s.cellDeltaV?.let { s.timestampMs to it } }, color = Warning, label = "Δ celdas (V)")
    }
}
