package com.buknuwo.battery.diagnostics

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.buknuwo.battery.database.AlertEventDao
import com.buknuwo.battery.database.TelemetrySampleDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import com.buknuwo.battery.settings.SettingsRepository

/** Persistent, non-BLE maintenance work: prunes history according to user retention. */
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sampleDao: TelemetrySampleDao,
    private val eventDao: AlertEventDao,
    private val settings: SettingsRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val days = settings.settings.first().historyRetentionDays.coerceIn(1, 365)
        val cutoff = System.currentTimeMillis() - days * 24 * 60 * 60_000L
        sampleDao.deleteOlderThan(cutoff)
        eventDao.deleteOlderThan(cutoff)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "buknuwo_history_cleanup"
    }
}
