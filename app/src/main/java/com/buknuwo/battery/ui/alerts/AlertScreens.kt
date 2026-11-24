package com.buknuwo.battery.ui.alerts

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.buknuwo.battery.alerts.model.AlertPhase
import com.buknuwo.battery.alerts.model.AlertRule
import com.buknuwo.battery.alerts.model.Priority
import com.buknuwo.battery.alerts.model.SoundType
import com.buknuwo.battery.alerts.model.VibrationPatternKind
import com.buknuwo.battery.ui.common.SectionTitle
import com.buknuwo.battery.ui.common.formatDateTime
import com.buknuwo.battery.ui.theme.Accent
import com.buknuwo.battery.ui.theme.Danger
import com.buknuwo.battery.ui.theme.SurfaceVariant
import com.buknuwo.battery.ui.theme.Warning

fun priorityColor(priority: Priority): Color = when (priority) {
    Priority.LOW -> Color.Gray
    Priority.NORMAL -> Accent
    Priority.HIGH -> Warning
    Priority.CRITICAL -> Danger
}

@Composable
fun AlertListScreen(
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDetail: (String) -> Unit,
    viewModel: AlertListViewModel = hiltViewModel()
) {
    val rules by viewModel.rules.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, Modifier.weight(1f)) { Text("Nueva alerta") }
            OutlinedButton(onClick = viewModel::seedExamples, Modifier.weight(1f)) { Text("Cargar ejemplos") }
        }
        if (rules.isEmpty()) {
            Text("No hay reglas. Cree una o cargue los ejemplos editables.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
            items(rules, key = { it.id }) { rule ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(rule.name, fontWeight = FontWeight.Bold)
                            Switch(checked = rule.enabled, onCheckedChange = { viewModel.toggle(rule) })
                        }
                        Text("${rule.priority.name} · ${rule.allConditions().size} condición(es)", color = priorityColor(rule.priority), style = MaterialTheme.typography.labelSmall)
                        if (rule.description.isNotBlank()) Text(rule.description, style = MaterialTheme.typography.bodyMedium)
                        rule.allConditions().take(3).forEach { condition ->
                            Text("${condition.variableId} ${condition.operator.label} ${condition.value ?: "—"}", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onDetail(rule.id) }, Modifier.weight(1f)) { Text("Detalle") }
                            OutlinedButton(onClick = { onEdit(rule.id) }, Modifier.weight(1f)) { Text("Editar") }
                            OutlinedButton(onClick = { viewModel.duplicate(rule) }, Modifier.weight(1f)) { Text("Duplicar") }
                            OutlinedButton(onClick = { viewModel.delete(rule) }, Modifier.weight(1f)) { Text("Borrar") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlertDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: AlertDetailViewModel = hiltViewModel()
) {
    val rule by viewModel.rule.collectAsState()
    val events by viewModel.events.collectAsState()
    val phase by viewModel.phase.collectAsState()
    if (rule == null) {
        Text("Alerta no encontrada", modifier = Modifier.padding(16.dp))
        return
    }
    val current = rule!!
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(current.name, style = MaterialTheme.typography.headlineMedium)
        Text("Estado: ${phase.name} · Prioridad: ${current.priority.name}", color = priorityColor(current.priority))
        Text(current.description, style = MaterialTheme.typography.bodyMedium)
        SectionTitle("Condiciones")
        current.allConditions().forEach { condition ->
            Text("${condition.variableId} · ${condition.operator.label} ${condition.value ?: "—"} " +
                "· duración ${condition.durationMs / 1000}s · ventana ${condition.windowMs / 1000}s",
                style = MaterialTheme.typography.bodyMedium)
        }
        SectionTitle("Configuración de aviso")
        Text("Sonido: ${current.soundType.name} · Volumen: ${(current.soundVolume * 100).toInt()}%")
        Text("Vibración: ${current.vibrationPattern.name} · Repeticiones: ${current.vibrationRepeat}")
        Text("Notificación: ${if (current.notificationEnabled) "activada" else "desactivada"} · Persistente: ${current.notificationPersistent}")
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.toggle(current) }, Modifier.weight(1f)) { Text(if (current.enabled) "Desactivar" else "Activar") }
            OutlinedButton(onClick = { onEdit(current.id) }, Modifier.weight(1f)) { Text("Editar") }
            OutlinedButton(onClick = { viewModel.silence() }, Modifier.weight(1f)) { Text("Silenciar") }
        }
        OutlinedButton(onClick = { viewModel.duplicate(current); onBack() }, Modifier.fillMaxWidth()) { Text("Duplicar") }
        SectionTitle("Historial de esta alerta")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(events, key = { it.eventId }) { event ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${event.eventType} · ${formatDateTime(event.timestampMs)}", fontWeight = FontWeight.Bold)
                        Text("Valor ${event.valueText} · Límite ${event.thresholdText}", style = MaterialTheme.typography.bodyMedium)
                        Text(event.snapshotSummary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        OutlinedButton(onClick = { viewModel.delete(current); onBack() }, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Eliminar alerta") }
    }
}

@Composable
fun AlertHistoryScreen(viewModel: AlertHistoryViewModel = hiltViewModel()) {
    val events by viewModel.events.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (events.isEmpty()) Text("Sin eventos registrados.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events, key = { it.eventId }) { event ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${event.ruleName} · ${event.eventType}", fontWeight = FontWeight.Bold,
                            color = if (event.eventType == "TRIGGERED") Danger else Accent)
                        Text(formatDateTime(event.timestampMs), style = MaterialTheme.typography.labelSmall)
                        Text("Valor ${event.valueText} · Límite ${event.thresholdText} · ${event.priority}", style = MaterialTheme.typography.bodyMedium)
                        Text(event.snapshotSummary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun AlertTestScreen(viewModel: AlertTestViewModel = hiltViewModel()) {
    val rules by viewModel.rules.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("PRUEBA DE MECANISMOS", color = Warning, fontWeight = FontWeight.Bold)
        Text("Estas pruebas NO son telemetría de la batería. Solo verifican sonido, vibración y notificaciones.", style = MaterialTheme.typography.bodyMedium)
        SectionTitle("Sonidos del sistema")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { viewModel.previewSound(SoundType.SYSTEM_NOTIFICATION) }) { Text("Notificación") }
            Button(onClick = { viewModel.previewSound(SoundType.SYSTEM_ALARM) }) { Text("Alarma") }
            Button(onClick = { viewModel.previewSound(SoundType.SYSTEM_DEFAULT) }) { Text("Predeterminado") }
        }
        SectionTitle("Vibración")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(VibrationPatternKind.SHORT, VibrationPatternKind.LONG, VibrationPatternKind.DOUBLE, VibrationPatternKind.TRIPLE).forEach {
                OutlinedButton(onClick = { viewModel.previewVibration(it) }) { Text(it.name) }
            }
        }
        SectionTitle("Notificación completa de una regla")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rules, key = { it.id }) { rule ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(rule.name)
                        Button(onClick = { viewModel.test(rule) }) { Text("Disparar") }
                    }
                }
            }
        }
        OutlinedButton(onClick = viewModel::stop, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Detener todas las pruebas") }
    }
}

@Composable
fun AlertPhaseChip(phase: AlertPhase) {
    Text(phase.name, color = if (phase == AlertPhase.ACTIVE) Danger else Accent, fontWeight = FontWeight.Bold)
}
