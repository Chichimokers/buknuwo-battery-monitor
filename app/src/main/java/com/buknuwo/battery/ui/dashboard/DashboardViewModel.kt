package com.buknuwo.battery.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buknuwo.battery.alerts.AlertManager
import com.buknuwo.battery.alerts.AlertRuleRepository
import com.buknuwo.battery.bluetooth.BleManager
import com.buknuwo.battery.database.TelemetrySampleEntity
import com.buknuwo.battery.monitoring.MonitoringService
import com.buknuwo.battery.settings.SettingsRepository
import com.buknuwo.battery.telemetry.TelemetryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val telemetry: TelemetryRepository,
    private val ble: BleManager,
    private val settings: SettingsRepository,
    private val alerts: AlertManager,
    private val rules: AlertRuleRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val snapshot = telemetry.latest
    val connectionState = ble.connectionState
    val lastError = ble.lastError
    val activeAlertIds = alerts.activeRuleIds
    val ruleCount = rules.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _chartRangeMs = MutableStateFlow(15 * 60_000L)
    val chartRangeMs: StateFlow<Long> = _chartRangeMs.asStateFlow()

    private val _samples = MutableStateFlow<List<TelemetrySampleEntity>>(emptyList())
    val samples: StateFlow<List<TelemetrySampleEntity>> = _samples.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { _chartRangeMs.value = it.chartRangeMs }
        }
        viewModelScope.launch {
            combine(telemetry.latest, _chartRangeMs) { snap, range -> snap to range }
                .debounce(400)
                .collect { (_, range) ->
                    _samples.value = telemetry.storedSamples(System.currentTimeMillis() - range)
                }
        }
    }

    fun setChartRange(rangeMs: Long) {
        _chartRangeMs.value = rangeMs
        viewModelScope.launch { settings.setChartRangeMs(rangeMs) }
    }

    fun startMonitoring() = MonitoringService.start(context)
    fun stopMonitoring() = MonitoringService.stop(context)
}
