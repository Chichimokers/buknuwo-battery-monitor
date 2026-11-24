package com.buknuwo.battery.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buknuwo.battery.database.TelemetrySampleEntity
import com.buknuwo.battery.settings.SettingsRepository
import com.buknuwo.battery.telemetry.TelemetryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class TelemetryHistoryViewModel @Inject constructor(
    private val telemetry: TelemetryRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    val rangeMs: StateFlow<Long> = settings.settings
        .map { it.chartRangeMs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15 * 60_000L)

    private val _samples = MutableStateFlow<List<TelemetrySampleEntity>>(emptyList())
    val samples: StateFlow<List<TelemetrySampleEntity>> = _samples.asStateFlow()

    init {
        viewModelScope.launch {
            rangeMs.debounce(300).collect { range ->
                _samples.value = telemetry.storedSamples(System.currentTimeMillis() - range)
            }
        }
    }

    fun setRange(range: Long) {
        viewModelScope.launch { settings.setChartRangeMs(range) }
    }
}
