package com.buknuwo.battery.ui.alerts

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.buknuwo.battery.alerts.model.AlertCondition
import com.buknuwo.battery.alerts.model.ConditionNode
import com.buknuwo.battery.alerts.model.ComparisonOperator
import com.buknuwo.battery.alerts.model.Logic
import com.buknuwo.battery.alerts.model.Priority
import com.buknuwo.battery.alerts.model.SoundType
import com.buknuwo.battery.alerts.model.VibrationPatternKind
import com.buknuwo.battery.telemetry.TelemetryVariable
import com.buknuwo.battery.ui.common.NumberField
import com.buknuwo.battery.ui.theme.Accent
import com.buknuwo.battery.ui.theme.SurfaceVariant
import com.buknuwo.battery.ui.theme.Warning

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AlertEditorScreen(
    onSaved: () -> Unit,
    viewModel: AlertEditorViewModel = hiltViewModel()
) {
    val rule by viewModel.rule.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val context = LocalContext.current
    var showAdvanced by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.saved.collect { onSaved() } }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.setSoundUri(uri.toString())
        }
    }
    val notificationAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.setNotificationSound(
                com.buknuwo.battery.alerts.model.NotificationSoundType.CUSTOM,
                uri.toString()
            )
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Accent.copy(alpha = 0.12f))) {
            Column(Modifier.padding(14.dp)) {
                Text("ASÍ QUEDARÁ TU ALERTA", style = MaterialTheme.typography.labelSmall, color = Accent)
                Text(summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
        }

        SectionCard("0", "Empezar desde una plantilla", "Opcional. Después puedes cambiar todo.") {
            Text("Estos valores son ejemplos editables, no límites de seguridad del fabricante.", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("soc" to "Batería baja", "temp" to "Temperatura", "cell" to "Celdas", "nodata" to "Sin datos").forEach { (id, label) ->
                    OutlinedButton(onClick = { viewModel.applyTemplate(id) }) { Text(label) }
                }
            }
        }

        SectionCard("1", "Identifica la alerta", "Un nombre claro te ayudará a entenderla después.") {
            OutlinedTextField(rule.name, { viewModel.setName(it) }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(rule.description, { viewModel.setDescription(it) }, label = { Text("Descripción (opcional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            SwitchRow("Alerta activa", rule.enabled, viewModel::setEnabled)
            Text("Prioridad", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Priority.entries.forEach { priority ->
                    FilterChip(selected = rule.priority == priority, onClick = { viewModel.setPriority(priority) }, label = { Text(priority.name) })
                }
            }
        }

        SectionCard("2", "¿Qué quieres vigilar?", "Elige una variable del BMS y cuándo debe dispararse.") {
            Text("Variables disponibles: ${viewModel.catalog.variables.size}. Todas salen del catálogo de telemetría real.", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = (rule.root as? ConditionNode.Group)?.logic == Logic.AND, onClick = { viewModel.setRootLogic(Logic.AND) }, label = { Text("TODAS (AND)") })
                FilterChip(selected = (rule.root as? ConditionNode.Group)?.logic == Logic.OR, onClick = { viewModel.setRootLogic(Logic.OR) }, label = { Text("ALGUNA (OR)") })
            }
            ConditionNodeUi(rule.root, viewModel, isRoot = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::addCondition) { Text("+ Condición") }
                OutlinedButton(onClick = viewModel::addGroup) { Text("+ Grupo") }
            }
        }

        SectionCard("3", "¿Cómo quieres que avise?", "Sonido, vibración y notificación.") {
            Text("Sonido", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SoundType.entries.forEach { type ->
                    FilterChip(selected = rule.soundType == type, onClick = { viewModel.setSoundType(type) }, label = { Text(type.name) })
                }
            }
            if (rule.soundType == SoundType.CUSTOM_URI) {
                OutlinedButton(onClick = { audioLauncher.launch(arrayOf("audio/*")) }) { Text("Elegir archivo de audio") }
                Text(rule.soundUri ?: "Sin archivo seleccionado", style = MaterialTheme.typography.labelSmall)
            }
            Text("Volumen ${(rule.soundVolume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            Slider(rule.soundVolume, { viewModel.setSoundVolume(it) }, valueRange = 0f..1f)
            SwitchRow("Repetir sonido mientras siga activa", rule.repeatSoundWhileActive, viewModel::setRepeatSound)
            if (rule.repeatSoundWhileActive) {
                NumberField("Cada cuántos segundos", rule.soundRepeatIntervalMs / 1000.0) { viewModel.setSoundRepeatIntervalMs((it * 1000).toLong()) }
            }

            Text("Vibración", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VibrationPatternKind.entries.forEach { kind ->
                    FilterChip(selected = rule.vibrationPattern == kind, onClick = { viewModel.setVibration(kind) }, label = { Text(kind.name) })
                }
            }
            NumberField("Repeticiones (-1 = hasta silenciar)", rule.vibrationRepeat.toDouble(), { viewModel.setVibrationRepeat(it.toInt()) })
            if (rule.vibrationPattern == VibrationPatternKind.CUSTOM) {
                var patternText by remember(rule.customVibrationPattern) {
                    mutableStateOf(rule.customVibrationPattern?.joinToString(",") ?: "0,200,100,200")
                }
                OutlinedTextField(
                    value = patternText,
                    onValueChange = { text ->
                        patternText = text
                        val parsed = text.split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
                        if (parsed.size >= 2) viewModel.setCustomVibration(parsed)
                    },
                    label = { Text("Patrón en ms: espera,vibra,espera,vibra…") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text("Notificación", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            SwitchRow("Mostrar notificación", rule.notificationEnabled, viewModel::setNotificationEnabled)
            Text("Sonido de la notificación", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = rule.notificationSoundType == com.buknuwo.battery.alerts.model.NotificationSoundType.PRIORITY_DEFAULT,
                    onClick = { viewModel.setNotificationSound(com.buknuwo.battery.alerts.model.NotificationSoundType.PRIORITY_DEFAULT) },
                    label = { Text("Según prioridad") }
                )
                FilterChip(
                    selected = rule.notificationSoundType == com.buknuwo.battery.alerts.model.NotificationSoundType.SYSTEM_ALARM,
                    onClick = { viewModel.setNotificationSound(com.buknuwo.battery.alerts.model.NotificationSoundType.SYSTEM_ALARM) },
                    label = { Text("Alarma sistema") }
                )
                FilterChip(
                    selected = rule.notificationSoundType == com.buknuwo.battery.alerts.model.NotificationSoundType.CUSTOM,
                    onClick = { viewModel.setNotificationSound(com.buknuwo.battery.alerts.model.NotificationSoundType.CUSTOM, rule.notificationSoundUri) },
                    label = { Text("Personalizado") }
                )
            }
            if (rule.notificationSoundType == com.buknuwo.battery.alerts.model.NotificationSoundType.CUSTOM) {
                OutlinedButton(onClick = { notificationAudioLauncher.launch(arrayOf("audio/*")) }) {
                    Text("Elegir audio para la notificación")
                }
                Text(rule.notificationSoundUri ?: "Sin archivo seleccionado", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = viewModel::previewNotificationSound) { Text("Probar sonido") }
            SwitchRow("Mantenerla while la condición siga activa", rule.notificationPersistent, viewModel::setPersistent)
            SwitchRow("Mostrar en pantalla de bloqueo", rule.showOnLockScreen, viewModel::setShowOnLockScreen)
            SwitchRow("Repetir aviso cada cierto tiempo", rule.repeatNotification, viewModel::setRepeatNotification)
            if (rule.repeatNotification) {
                NumberField("Cada cuántos segundos", rule.notificationRepeatIntervalMs / 1000.0) { viewModel.setNotificationRepeatMs((it * 1000).toLong()) }
            }
        }

        SectionCard("4", "Solo en ciertos horarios", "Opcional. Por defecto avisa a cualquier hora.") {
            SwitchRow("Limitar a un horario", rule.scheduleEnabled, { viewModel.setSchedule(it, rule.startHour, rule.endHour, rule.daysOfWeek) })
            if (rule.scheduleEnabled) {
                NumberField("Desde la hora (0-23)", rule.startHour.toDouble()) { viewModel.setSchedule(true, it.toInt(), rule.endHour, rule.daysOfWeek) }
                NumberField("Hasta la hora (0-23)", rule.endHour.toDouble()) { viewModel.setSchedule(true, rule.startHour, it.toInt(), rule.daysOfWeek) }
                Text("Días (1=lunes … 7=domingo)", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { day ->
                        FilterChip(
                            selected = day in rule.daysOfWeek,
                            onClick = {
                                val days = if (day in rule.daysOfWeek) rule.daysOfWeek - day else rule.daysOfWeek + day
                                viewModel.setSchedule(true, rule.startHour, rule.endHour, days)
                            },
                            label = { Text(day.toString()) }
                        )
                    }
                }
            }
        }

        OutlinedButton(onClick = { showAdvanced = !showAdvanced }, Modifier.fillMaxWidth()) {
            Text(if (showAdvanced) "Ocultar opciones avanzadas" else "Ver opciones avanzadas")
        }
        if (showAdvanced) {
            SectionCard("+", "Ajustes finos", "Solo si sabes lo que hacen. Puedes dejarlos como están.") {
                NumberField("Histéresis (evita parpadeos)", rule.hysteresis) { viewModel.setHysteresis(it) }
                NumberField("Duración mínima de la condición (s)", rule.minActiveDurationMs / 1000.0) { viewModel.setMinDurationMs((it * 1000).toLong()) }
                NumberField("Ignorar datos con más de (s)", rule.maxDataAgeMs / 1000.0) { viewModel.setMaxDataAgeMs((it * 1000).toLong()) }
                NumberField("Duración del sonido (ms)", rule.soundDurationMs.toDouble()) { viewModel.setSoundDurationMs(it.toLong()) }
            }
        }

        Button(onClick = viewModel::save, Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Guardar alerta") }
    }
}

@Composable
private fun SectionCard(step: String, title: String, description: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(step, color = Accent, fontWeight = FontWeight.Bold)
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(description, style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.padding(top = 10.dp)) { content() }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ConditionNodeUi(node: ConditionNode, viewModel: AlertEditorViewModel, isRoot: Boolean) {
    when (node) {
        is ConditionNode.Group -> {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = node.logic == Logic.AND, onClick = { viewModel.setGroupLogic(node.id, Logic.AND) }, label = { Text("TODAS") })
                        FilterChip(selected = node.logic == Logic.OR, onClick = { viewModel.setGroupLogic(node.id, Logic.OR) }, label = { Text("ALGUNA") })
                        if (!isRoot) OutlinedButton(onClick = { viewModel.removeNode(node.id) }) { Text("Quitar grupo") }
                    }
                    node.children.forEach { ConditionNodeUi(it, viewModel, false) }
                }
            }
        }
        is ConditionNode.Leaf -> LeafUi(node.condition, viewModel)
    }
}

@Composable
private fun LeafUi(condition: AlertCondition, viewModel: AlertEditorViewModel) {
    var variableExpanded by remember { mutableStateOf(false) }
    var operatorExpanded by remember { mutableStateOf(false) }
    val variable = viewModel.catalog.get(condition.variableId)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(10.dp)) {
            Text("Condición", fontWeight = FontWeight.Bold)
            PickerField(
                label = "Variable",
                text = variable?.label ?: condition.variableId,
                expanded = variableExpanded,
                onToggle = { variableExpanded = it }
            ) {
                viewModel.catalog.variables.forEach { item: TelemetryVariable ->
                    DropdownMenuItem(
                        text = { Text("${item.category} · ${item.label}") },
                        onClick = {
                            viewModel.updateCondition(condition.id) { it.copy(variableId = item.id, value = null) }
                            variableExpanded = false
                        }
                    )
                }
            }
            PickerField(
                label = "Regla",
                text = condition.operator.label,
                expanded = operatorExpanded,
                onToggle = { operatorExpanded = it }
            ) {
                ComparisonOperator.entries.forEach { op ->
                    DropdownMenuItem(
                        text = { Text(op.label) },
                        onClick = {
                            viewModel.updateCondition(condition.id) { it.copy(operator = op) }
                            operatorExpanded = false
                        }
                    )
                }
            }
            NumberField("Valor (${variable?.unit ?: "según variable"})", condition.value ?: 0.0) { value ->
                viewModel.updateCondition(condition.id) { it.copy(value = value) }
            }
            if (condition.operator.needsSecondValue) {
                NumberField("Segundo valor", condition.secondValue ?: 0.0) { value ->
                    viewModel.updateCondition(condition.id) { it.copy(secondValue = value) }
                }
            }
            if (condition.operator in setOf(
                    ComparisonOperator.RISE_BY, ComparisonOperator.FALL_BY, ComparisonOperator.PERCENT_CHANGE,
                    ComparisonOperator.STAYS_ABOVE, ComparisonOperator.STAYS_BELOW
                )
            ) {
                NumberField("Ventana de tiempo (s)", condition.windowMs / 1000.0) { value ->
                    viewModel.updateCondition(condition.id) { it.copy(windowMs = (value * 1000).toLong().coerceAtLeast(0)) }
                }
            }
            NumberField("Debe cumplirse durante (s)", condition.durationMs / 1000.0) { value ->
                viewModel.updateCondition(condition.id) { it.copy(durationMs = (value * 1000).toLong().coerceAtLeast(0)) }
            }
            OutlinedButton(onClick = { viewModel.removeNode(condition.id) }) { Text("Quitar condición") }
        }
    }
}

@Composable
private fun PickerField(
    label: String,
    text: String,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        androidx.compose.foundation.layout.Box {
            Text(
                text,
                Modifier.fillMaxWidth().clickable { onToggle(!expanded) }.padding(10.dp),
                color = if (text.isBlank()) Warning else MaterialTheme.colorScheme.onSurface
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { onToggle(false) }) { content() }
        }
    }
}
