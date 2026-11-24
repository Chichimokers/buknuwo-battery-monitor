package com.buknuwo.battery.telemetry

import com.buknuwo.battery.bluetooth.ConnectionState
import com.buknuwo.battery.database.TelemetrySampleDao
import com.buknuwo.battery.database.TelemetrySampleEntity
import com.buknuwo.battery.di.ApplicationScope
import com.buknuwo.battery.protocol.ModbusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelemetryRepository @Inject constructor(
    private val sampleDao: TelemetrySampleDao,
    private val catalog: TelemetryCatalog,
    @ApplicationScope private val scope: CoroutineScope
) : VariableHistory {

    private val mutex = Mutex()
    private var snapshot = TelemetrySnapshot()

    private val _latest = MutableStateFlow(snapshot)
    val latest: StateFlow<TelemetrySnapshot> = _latest.asStateFlow()

    private val _updates = MutableSharedFlow<TelemetrySnapshot>(extraBufferCapacity = 64)
    val updates: SharedFlow<TelemetrySnapshot> = _updates.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val variableHistory = mutableMapOf<String, ArrayDeque<ValueSample>>()
    private val maxHistorySamples = 2048

    suspend fun onResponse(response: ModbusResponse) {
        mutex.withLock {
            val now = response.receivedAtMs
            val next = TelemetryMerger.merge(snapshot, response, now)
            if (next.sequence == snapshot.sequence) return@withLock
            snapshot = next
            _latest.value = next
            _updates.tryEmit(next)
            recordHistory(next, now)
            sampleDao.insert(
                TelemetrySampleEntity(
                    timestampMs = now,
                    voltageV = next.voltageV,
                    currentA = next.currentA,
                    powerW = next.powerW,
                    soc = next.soc,
                    soh = next.soh,
                    mosfetTempC = next.mosfetTempC,
                    cellDeltaV = next.cellDeltaV,
                    cellMinV = next.minCellV,
                    cellMaxV = next.maxCellV
                )
            )
        }
    }

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun current(): TelemetrySnapshot = snapshot

    fun clearData() {
        scope.launch {
            mutex.withLock {
                snapshot = TelemetrySnapshot()
                _latest.value = snapshot
                variableHistory.clear()
            }
        }
    }

    private fun recordHistory(snap: TelemetrySnapshot, now: Long) {
        val ctx = TelemetryContext(
            snapshot = snap,
            nowMs = now,
            connected = _connectionState.value == ConnectionState.RECEIVING_DATA ||
                _connectionState.value == ConnectionState.CONNECTED
        )
        synchronized(variableHistory) {
            catalog.variables.forEach { variable ->
                val value = variable.evaluate(ctx)?.asNumber() ?: return@forEach
                val queue = variableHistory.getOrPut(variable.id) { ArrayDeque() }
                queue.addLast(ValueSample(now, value))
                while (queue.size > maxHistorySamples) queue.removeFirst()
            }
        }
    }

    override fun samples(variableId: String, sinceMs: Long, nowMs: Long): List<ValueSample> {
        synchronized(variableHistory) {
            return variableHistory[variableId]
                ?.filter { it.timestampMs in sinceMs..nowMs }
                ?: emptyList()
        }
    }

    suspend fun storedSamples(sinceMs: Long, limit: Int = 5000) = sampleDao.since(sinceMs, limit)

    suspend fun deleteSamplesOlderThan(before: Long) = sampleDao.deleteOlderThan(before)
}
