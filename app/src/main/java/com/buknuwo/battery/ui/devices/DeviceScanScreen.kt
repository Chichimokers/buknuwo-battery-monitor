package com.buknuwo.battery.ui.devices

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.buknuwo.battery.ui.common.SectionTitle
import com.buknuwo.battery.ui.common.StatusChip
import com.buknuwo.battery.ui.theme.Accent
import com.buknuwo.battery.ui.theme.Danger
import com.buknuwo.battery.ui.theme.SurfaceVariant

@Composable
fun DeviceScanScreen(viewModel: DeviceScanViewModel = hiltViewModel()) {
    val devices by viewModel.devices.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val prefs by viewModel.targetAddress.collectAsState()
    val message by viewModel.message.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results are observed through the manager's permission checks */ }

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.permissionRequest.collect { permissionLauncher.launch(it) }
    }

    val status = when (state.name) {
        "SCANNING" -> "BUSCANDO" to Accent
        "CONNECTING" -> "CONECTANDO" to Accent
        "DISCOVERING_SERVICES" -> "DESCUBRIENDO SERVICIOS" to Accent
        "SUBSCRIBING" -> "SUSCRIBIENDO" to Accent
        "RECEIVING_DATA" -> "RECIBIENDO DATOS" to Accent
        "ERROR" -> "ERROR DE CONEXIÓN" to Danger
        else -> "DESCONECTADO" to Accent
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusChip(status.first, status.second)
            Text("Objetivo: ${prefs.targetName ?: "sin seleccionar"}", style = MaterialTheme.typography.labelSmall)
        }
        if (message != null) {
            Text(message!!, color = Accent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        SectionTitle("Batería objetivo")
        Text("Nombre: CDZG202603260020004255", style = MaterialTheme.typography.bodyMedium)
        Text("El dispositivo debe estar encendido y dentro de alcance.", style = MaterialTheme.typography.labelSmall)

        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::startScan, Modifier.weight(1f)) { Text("Escanear") }
            OutlinedButton(onClick = viewModel::stopScan, Modifier.weight(1f)) { Text("Detener escaneo") }
        }
        SectionTitle("Conexión")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::connect, Modifier.weight(1f)) { Text("Conectar batería guardada") }
            OutlinedButton(onClick = viewModel::disconnect, Modifier.weight(1f)) { Text("Desconectar") }
        }

        SectionTitle("Dispositivos BLE encontrados")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.address }) { device ->
                Card(
                    Modifier.fillMaxWidth().clickable { viewModel.select(device.name, device.address) },
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(device.name, fontWeight = FontWeight.Bold)
                        Text("${device.address} · ${device.rssi} dBm", style = MaterialTheme.typography.bodyMedium)
                        if (device.isTarget) Text("DISPOSITIVO OBJETIVO", color = Accent, style = MaterialTheme.typography.labelSmall)
                        if (prefs.targetAddress == device.address) Text("Guardado como batería activa", color = Accent, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
