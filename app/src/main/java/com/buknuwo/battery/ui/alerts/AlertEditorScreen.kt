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
import androidx.compose.material3.Checkbox
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
import com.buknuwo.battery.ui.common.SectionTitle
import com.buknuwo.battery.ui.theme.SurfaceVariant

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AlertEditorScreen(
    onSaved: () -> Unit,
    viewModel: AlertEditorViewModel = hiltViewModel()
) {
    val rule by viewModel.rule.collectAsState()
    val context = LocalContext.current
    var pendingSaved by remember { mutableStateOf(false) }
    LaunchedEffectSaved(viewModel.saved, onSaved = { pendingSaved = true })

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setSoundUri(uri.toString())
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("General")
        OutlinedTextField(rule.name, { viewModel.setName(it) }, label = { Text("Nombre de la alerta") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(rule.description, { viewModel.setDescription(it) }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Regla activa")
            Switch(checked = rule.enabled, onCheckedChange = viewModel::setEnabled)
        }
        Text("Prioridad", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Priority.entries.forEach { priority ->
                FilterChip(
                    selected = rule.priority == priority,
                    onClick = { viewModel.setPriority(priority) },
                    label = { Text(priority.name) }
                )
            }
        }

        SectionTitle("Condiciones")
        Text("Las variables se obtienen del catálogo de telemetría (${viewModel.catalog.variables.size} disponibles).", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = (rule.root as? ConditionNode.Group)?.logic == Logic.AND, onClick = { viewModel.setRootLogic(Logic.AND) }, label = { Text("AND") })
            FilterChip(selected = (rule.root as? ConditionNode.Group)?.logic == Logic.OR, onClick = { viewModel.setRootLogic(Logic.OR) }, label = { Text("OR") })
        }
        ConditionNodeUi(rule.root, viewModel, isRoot = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::addCondition) { Text("+ Condición") }
            OutlinedButton(onClick = viewModel::addGroup) { Text("+ Grupo") }
        }

        SectionTitle("Temporización e histéresis")
        NumberField("Histéresis (absoluta)", rule.hysteresis, { viewModel.setHysteresis(it) })
        NumberField("Duración mínima (s)", rule.minActiveDurationMs / 1000.0, { viewModel.setMinDurationMs((it * 1000).toLong()) })
        NumberField("Datos obsoletos tras (s)", rule.maxDataAgeMs / 1000.0, { viewModel.setMaxDataAgeMs((it * 1000).toLong()) })

        SectionTitle("Sonido")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SoundType.entries.forEach { type ->
                FilterChip(selected = rule.soundType == type, onClick = { viewModel.setSoundType(type) }, label = { Text(type.name) })
            }
        }
        if (rule.soundType == SoundType.CUSTOM_URI) {
            OutlinedButton(onClick = { audioLauncher.launch(arrayOf("audio/*")) }) { Text("Seleccionar archivo de audio") }
            Text(rule.soundUri ?: "Sin archivo", style = MaterialTheme.typography.labelSmall)
        }
        Text("Volumen ${(rule.soundVolume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
        Slider(rule.soundVolume, { viewModel.setSoundVolume(it) }, valueRange = 0f..1f)
        NumberField("Duración del sonido (ms)", rule.soundDurationMs.toDouble(), { viewModel.setSoundDurationMs(it.toLong()) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Repetir sonido mientras la condición siga activa")
            Switch(checked = rule.repeatSoundWhileActive, onCheckedChange = viewModel::setRepeatSound)
        }
        if (rule.repeatSoundWhileActive) {
            NumberField("Intervalo de repetición de sonido (s)", rule.soundRepeatIntervalMs / 1000.0) { value ->
                viewModel.setSoundRepeatIntervalMs((value * 1000).toLong())
            }
        }

        SectionTitle("Vibración")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VibrationPatternKind.entries.forEach { kind ->
                FilterChip(selected = rule.vibrationPattern == kind, onClick = { viewModel.setVibration(kind) }, label = { Text(kind.name) })
            }
        }
        NumberField("Repeticiones (-1 = indefinida)", rule.vibrationRepeat.toDouble(), { viewModel.setVibrationRepeat(it.toInt()) })
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
                label = { Text("Patrón personalizado (ms, alternando espera/vibración)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        SectionTitle("Notificación")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Notificación visual")
            Switch(checked = rule.notificationEnabled, onCheckedChange = viewModel::setNotificationEnabled)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Persistente mientras esté activa")
            Switch(checked = rule.notificationPersistent, onCheckedChange = viewModel::setPersistent)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Mostrar en pantalla de bloqueo")
            Switch(checked = rule.showOnLockScreen, onCheckedChange = viewModel::setShowOnLockScreen)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Repetir notificación")
            Switch(checked = rule.repeatNotification, onCheckedChange = viewModel::setRepeatNotification)
        }
        if (rule.repeatNotification) {
            NumberField("Intervalo de repetición (s)", rule.notificationRepeatIntervalMs / 1000.0, { viewModel.setNotificationRepeatMs((it * 1000).toLong()) })
        }

        SectionTitle("Horario")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Limitar a horario")
            Switch(checked = rule.scheduleEnabled, onCheckedChange = { viewModel.setSchedule(it, rule.startHour, rule.endHour, rule.daysOfWeek) })
        }
        if (rule.scheduleEnabled) {
            NumberField("Hora inicio (0-23)", rule.startHour.toDouble(), { viewModel.setSchedule(true, it.toInt(), rule.endHour, rule.daysOfWeek) })
            NumberField("Hora fin (0-23)", rule.endHour.toDouble(), { viewModel.setSchedule(true, rule.startHour, it.toInt(), rule.daysOfWeek) })
            Text("Días activos (1=lunes…7=domingo)", style = MaterialTheme.typography.labelSmall)
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

        Button(onClick = viewModel::save, Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("Guardar alerta") }
    }
    if (pendingSaved) onSaved()
}

@Composable
private fun LaunchedEffectSaved(flow: kotlinx.coroutines.flow.Flow<Unit>, onSaved: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { flow.collect { onSaved() } }
}

@Composable
private fun ConditionNodeUi(node: ConditionNode, viewModel: AlertEditorViewModel, isRoot: Boolean) {
    when (node) {
        is ConditionNode.Group -> {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                Column(Modifier.padding(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = node.logic == Logic.AND, onClick = { viewModel.setGroupLogic(node.id, Logic.AND) }, label = { Text("AND") })
                        FilterChip(selected = node.logic == Logic.OR, onClick = { viewModel.setGroupLogic(node.id, Logic.OR) }, label = { Text("OR") })
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
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(Modifier.padding(10.dp)) {
            Text("Condición", fontWeight = FontWeight.Bold)
            BoxWithDropdown(
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
            BoxWithDropdown(
                label = "Operador",
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
            NumberField("Valor de referencia ${variable?.unit ?: ""}", condition.value ?: 0.0) { value ->
                viewModel.updateCondition(condition.id) { it.copy(value = value) }
            }
            if (condition.operator.needsSecondValue) {
                NumberField("Segundo valor ${variable?.unit ?: ""}", condition.secondValue ?: 0.0) { value ->
                    viewModel.updateCondition(condition.id) { it.copy(secondValue = value) }
                }
            }
            if (condition.operator == ComparisonOperator.RISE_BY ||
                condition.operator == ComparisonOperator.FALL_BY ||
                condition.operator == ComparisonOperator.PERCENT_CHANGE ||
                condition.operator == ComparisonOperator.STAYS_ABOVE ||
                condition.operator == ComparisonOperator.STAYS_BELOW
            ) {
                NumberField("Ventana (s)", condition.windowMs / 1000.0) { value ->
                    viewModel.updateCondition(condition.id) { it.copy(windowMs = (value * 1000).toLong().coerceAtLeast(0)) }
                }
            }
            NumberField("Duración sostenida (s)", condition.durationMs / 1000.0) { value ->
                viewModel.updateCondition(condition.id) { it.copy(durationMs = (value * 1000).toLong().coerceAtLeast(0)) }
            }
            OutlinedButton(onClick = { viewModel.removeNode(condition.id) }) { Text("Quitar condición") }
        }
    }
}

@Composable
private fun BoxWithDropdown(
    label: String,
    text: String,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        androidx.compose.foundation.layout.Box {
            Text(text, Modifier.fillMaxWidth().clickable { onToggle(!expanded) }.padding(10.dp))
            DropdownMenu(expanded = expanded, onDismissRequest = { onToggle(false) }) { content() }
        }
    }
}
