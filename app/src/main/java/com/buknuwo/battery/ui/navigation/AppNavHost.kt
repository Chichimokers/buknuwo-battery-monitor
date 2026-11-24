package com.buknuwo.battery.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.buknuwo.battery.ui.alerts.AlertDetailScreen
import com.buknuwo.battery.ui.alerts.AlertEditorScreen
import com.buknuwo.battery.ui.alerts.AlertHistoryScreen
import com.buknuwo.battery.ui.alerts.AlertListScreen
import com.buknuwo.battery.ui.alerts.AlertTestScreen
import com.buknuwo.battery.ui.dashboard.DashboardScreen
import com.buknuwo.battery.ui.devices.DeviceScanScreen
import com.buknuwo.battery.ui.diagnostics.DiagnosticsScreen
import com.buknuwo.battery.ui.history.TelemetryHistoryScreen
import com.buknuwo.battery.ui.settings.GeneralSettingsScreen
import com.buknuwo.battery.ui.settings.SoundSettingsScreen
import com.buknuwo.battery.ui.settings.VibrationSettingsScreen

@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController, startDestination = Screen.Dashboard.route, modifier = modifier) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(onOpenDevices = { navController.navigate(Screen.Devices.route) })
        }
        composable(Screen.Devices.route) { DeviceScanScreen() }
        composable(Screen.Alerts.route) {
            AlertListScreen(
                onAdd = { navController.navigate(Screen.AlertEditor.route("new")) },
                onEdit = { navController.navigate(Screen.AlertEditor.route(it)) },
                onDetail = { navController.navigate(Screen.AlertDetail.route(it)) }
            )
        }
        composable(
            Screen.AlertEditor.route,
            arguments = listOf(navArgument(Screen.AlertEditor.ARG) { type = NavType.StringType })
        ) {
            AlertEditorScreen(onSaved = { navController.popBackStack() })
        }
        composable(
            Screen.AlertDetail.route,
            arguments = listOf(navArgument(Screen.AlertDetail.ARG) { type = NavType.StringType })
        ) {
            AlertDetailScreen(onBack = { navController.popBackStack() }, onEdit = { navController.navigate(Screen.AlertEditor.route(it)) })
        }
        composable(Screen.AlertHistory.route) { AlertHistoryScreen() }
        composable(Screen.AlertTest.route) { AlertTestScreen() }
        composable(Screen.TelemetryHistory.route) { TelemetryHistoryScreen() }
        composable(Screen.Diagnostics.route) { DiagnosticsScreen() }
        composable(Screen.SoundSettings.route) { SoundSettingsScreen() }
        composable(Screen.VibrationSettings.route) { VibrationSettingsScreen() }
        composable(Screen.Settings.route) { GeneralSettingsScreen() }
    }
}

@Composable
fun PlaceholderScreen(text: String) {
    Box(Modifier.padding(16.dp)) { Text(text) }
}
