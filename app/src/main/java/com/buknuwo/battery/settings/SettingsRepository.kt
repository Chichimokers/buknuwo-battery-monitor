package com.buknuwo.battery.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.buknuwo.battery.telemetry.MosBitMapping
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "buknuwo_settings")

private object Keys {
    val TARGET_ADDRESS = stringPreferencesKey("target_address")
    val TARGET_NAME = stringPreferencesKey("target_name")
    val AUTO_START = booleanPreferencesKey("auto_start_on_boot")
    val POLL_INTERVAL = longPreferencesKey("poll_interval_ms")
    val REQUEST_TIMEOUT = longPreferencesKey("request_timeout_ms")
    val RECONNECT = booleanPreferencesKey("reconnect_enabled")
    val MAX_RECONNECT = intPreferencesKey("max_reconnect_attempts")
    val WATCHDOG = longPreferencesKey("watchdog_timeout_ms")
    val MASTER_VOLUME = floatPreferencesKey("master_volume")
    val MUTED = booleanPreferencesKey("alerts_muted")
    val LOCK_SCREEN = booleanPreferencesKey("show_on_lock_screen")
    val CHART_RANGE = longPreferencesKey("chart_range_ms")
    val RETENTION = intPreferencesKey("history_retention_days")
    val MOS_CHARGE_REG = intPreferencesKey("mos_charge_reg")
    val MOS_CHARGE_BIT = intPreferencesKey("mos_charge_bit")
    val MOS_DISCHARGE_REG = intPreferencesKey("mos_discharge_reg")
    val MOS_DISCHARGE_BIT = intPreferencesKey("mos_discharge_bit")
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { exception -> if (exception is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw exception }
        .map { prefs ->
            AppSettings(
                targetAddress = prefs[Keys.TARGET_ADDRESS],
                targetName = prefs[Keys.TARGET_NAME],
                autoStartOnBoot = prefs[Keys.AUTO_START] ?: false,
                pollIntervalMs = prefs[Keys.POLL_INTERVAL] ?: 1_500L,
                requestTimeoutMs = prefs[Keys.REQUEST_TIMEOUT] ?: 2_000L,
                reconnectEnabled = prefs[Keys.RECONNECT] ?: true,
                maxReconnectAttempts = prefs[Keys.MAX_RECONNECT] ?: 8,
                watchdogTimeoutMs = prefs[Keys.WATCHDOG] ?: 12_000L,
                alertMasterVolume = prefs[Keys.MASTER_VOLUME] ?: 0.8f,
                alertsMuted = prefs[Keys.MUTED] ?: false,
                showOnLockScreen = prefs[Keys.LOCK_SCREEN] ?: false,
                chartRangeMs = prefs[Keys.CHART_RANGE] ?: 15 * 60_000L,
                historyRetentionDays = prefs[Keys.RETENTION] ?: 30,
                mosMapping = MosBitMapping(
                    chargeRegisterIndex = prefs[Keys.MOS_CHARGE_REG]?.takeIf { it >= 0 },
                    chargeBit = prefs[Keys.MOS_CHARGE_BIT] ?: 0,
                    dischargeRegisterIndex = prefs[Keys.MOS_DISCHARGE_REG]?.takeIf { it >= 0 },
                    dischargeBit = prefs[Keys.MOS_DISCHARGE_BIT] ?: 0
                )
            )
        }

    private suspend fun edit(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(transform)
    }

    suspend fun saveTarget(address: String, name: String) = edit {
        it[Keys.TARGET_ADDRESS] = address
        it[Keys.TARGET_NAME] = name
    }

    suspend fun clearTarget() = edit {
        it.remove(Keys.TARGET_ADDRESS)
        it.remove(Keys.TARGET_NAME)
    }

    suspend fun setAutoStartOnBoot(value: Boolean) = edit { it[Keys.AUTO_START] = value }
    suspend fun setPollIntervalMs(value: Long) = edit { it[Keys.POLL_INTERVAL] = value }
    suspend fun setRequestTimeoutMs(value: Long) = edit { it[Keys.REQUEST_TIMEOUT] = value }
    suspend fun setReconnect(enabled: Boolean, maxAttempts: Int) = edit {
        it[Keys.RECONNECT] = enabled
        it[Keys.MAX_RECONNECT] = maxAttempts
    }
    suspend fun setWatchdogTimeoutMs(value: Long) = edit { it[Keys.WATCHDOG] = value }
    suspend fun setMasterVolume(value: Float) = edit { it[Keys.MASTER_VOLUME] = value.coerceIn(0f, 1f) }
    suspend fun setMuted(value: Boolean) = edit { it[Keys.MUTED] = value }
    suspend fun setShowOnLockScreen(value: Boolean) = edit { it[Keys.LOCK_SCREEN] = value }
    suspend fun setChartRangeMs(value: Long) = edit { it[Keys.CHART_RANGE] = value }
    suspend fun setRetentionDays(value: Int) = edit { it[Keys.RETENTION] = value }

    suspend fun setMosMapping(mapping: MosBitMapping) = edit {
        it[Keys.MOS_CHARGE_REG] = mapping.chargeRegisterIndex ?: -1
        it[Keys.MOS_CHARGE_BIT] = mapping.chargeBit
        it[Keys.MOS_DISCHARGE_REG] = mapping.dischargeRegisterIndex ?: -1
        it[Keys.MOS_DISCHARGE_BIT] = mapping.dischargeBit
    }
}
