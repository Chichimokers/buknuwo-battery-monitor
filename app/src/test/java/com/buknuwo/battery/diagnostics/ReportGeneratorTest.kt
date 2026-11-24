package com.buknuwo.battery.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class ReportGeneratorTest {

    @Test
    fun reportContainsAllSectionsAndNeverInventsTelemetry() {
        val report = formatReport(
            ReportData(
                generatedAtMs = 1_700_000_000_000L,
                appVersion = "1.1.0 (2)",
                deviceInfo = "Google Pixel, Android 14 (API 34)",
                permissions = listOf("Escanear BLE: DENEGADO"),
                bluetooth = listOf("Estado GATT actual: DISCONNECTED"),
                configuration = listOf("Batería guardada: CDZG202603260020004255 / AA:BB"),
                telemetry = listOf("Voltaje total: SIN DATOS", "SOC: SIN DATOS"),
                userNote = "No se enlaza, el escaneo no encuentra la batería",
                events = emptyList(),
                logs = listOf("2026-11-23 10:00:00.000 [ERROR] [GATT] GATT status 133"),
                persistedLogTail = "2026-11-23 09:59:00.000 [INFO] [SCAN] Escaneo iniciado"
            )
        )
        assertTrue(report.contains("INFORME DE SOPORTE"))
        assertTrue(report.contains("--- PERMISOS ---"))
        assertTrue(report.contains("--- BLUETOOTH / GATT ---"))
        assertTrue(report.contains("--- CONFIGURACIÓN ---"))
        assertTrue(report.contains("--- TELEMETRÍA"))
        assertTrue(report.contains("--- CUÉNTANOS QUÉ HICISTE ---"))
        assertTrue(report.contains("No se enlaza"))
        assertTrue(report.contains("GATT status 133"))
        assertTrue(report.contains("SIN DATOS"))
        assertTrue(report.contains("FIN DEL INFORME"))
    }
}
