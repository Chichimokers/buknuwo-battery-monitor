package com.buknuwo.battery.ui.navigation

/** Navigation destinations. Icons are intentionally not used: the drawer is text-only,
 * which avoids shipping the large material-icons-extended artifact. */
sealed class Screen(val route: String, val title: String) {
    data object Dashboard : Screen("dashboard", "Dashboard")
    data object Devices : Screen("devices", "Batería BLE")
    data object Alerts : Screen("alerts", "Alertas")
    data object AlertHistory : Screen("alert_history", "Historial de alertas")
    data object AlertTest : Screen("alert_test", "Prueba de alertas")
    data object SoundSettings : Screen("sound_settings", "Sonidos")
    data object VibrationSettings : Screen("vibration_settings", "Vibración")
    data object TelemetryHistory : Screen("telemetry_history", "Historial de telemetría")
    data object Diagnostics : Screen("diagnostics", "Diagnóstico BLE")
    data object Settings : Screen("settings", "Ajustes generales")

    data object AlertEditor : Screen("alert_editor/{ruleId}", "Editor de alerta") {
        fun route(ruleId: String) = "alert_editor/$ruleId"
        const val ARG = "ruleId"
    }

    data object AlertDetail : Screen("alert_detail/{ruleId}", "Detalle de alerta") {
        fun route(ruleId: String) = "alert_detail/$ruleId"
        const val ARG = "ruleId"
    }

    companion object {
        val drawerItems = listOf(
            Dashboard, Devices, Alerts, AlertHistory, AlertTest,
            TelemetryHistory, Diagnostics, SoundSettings, VibrationSettings, Settings
        )
    }
}
