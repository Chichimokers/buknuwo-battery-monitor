package com.buknuwo.battery.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.buknuwo.battery.diagnostics.BleDiagnostics
import com.buknuwo.battery.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var diagnostics: BleDiagnostics

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.LOCKED_BOOT_COMPLETED")) return
        val pending = goAsync()
        Thread {
            try {
                val prefs = runBlocking { settings.settings.first() }
                if (prefs.autoStartOnBoot && !prefs.targetAddress.isNullOrBlank()) {
                    // Android 12+ may block background FGS starts. Failures are logged,
                    // never presented as a successful monitoring session.
                    try {
                        MonitoringService.start(context)
                        diagnostics.log(BleDiagnostics.Level.INFO, "BOOT", "Monitorización solicitada tras reinicio")
                    } catch (security: SecurityException) {
                        diagnostics.error("BOOT", "El sistema impidió iniciar el servicio: ${security.message}")
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
