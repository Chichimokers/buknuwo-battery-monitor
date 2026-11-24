package com.buknuwo.battery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buknuwo.battery.settings.AppSettings
import com.buknuwo.battery.settings.SettingsRepository
import com.buknuwo.battery.telemetry.MosBitMapping
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setAutoStart(value: Boolean) = viewModelScope.launch { repository.setAutoStartOnBoot(value) }
    fun setPollInterval(ms: Long) = viewModelScope.launch { repository.setPollIntervalMs(ms) }
    fun setRequestTimeout(ms: Long) = viewModelScope.launch { repository.setRequestTimeoutMs(ms) }
    fun setReconnect(enabled: Boolean, attempts: Int) = viewModelScope.launch { repository.setReconnect(enabled, attempts) }
    fun setWatchdog(ms: Long) = viewModelScope.launch { repository.setWatchdogTimeoutMs(ms) }
    fun setMasterVolume(value: Float) = viewModelScope.launch { repository.setMasterVolume(value) }
    fun setMuted(value: Boolean) = viewModelScope.launch { repository.setMuted(value) }
    fun setShowOnLockScreen(value: Boolean) = viewModelScope.launch { repository.setShowOnLockScreen(value) }
    fun setRetention(days: Int) = viewModelScope.launch { repository.setRetentionDays(days) }
    fun setMosMapping(mapping: MosBitMapping) = viewModelScope.launch { repository.setMosMapping(mapping) }
}
