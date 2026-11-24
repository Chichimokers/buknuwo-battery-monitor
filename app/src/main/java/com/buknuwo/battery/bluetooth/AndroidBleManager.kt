package com.buknuwo.battery.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.buknuwo.battery.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.buknuwo.battery.diagnostics.BleDiagnostics
import com.buknuwo.battery.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("MissingPermission")
@Singleton
class AndroidBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val diagnostics: BleDiagnostics,
    @ApplicationScope private val scope: CoroutineScope
) : BleManager {

    companion object {
        const val TARGET_NAME = "CDZG202603260020004255"
        private val SERVICE_UUID: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e1001")
        private val TX_UUID: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0001")
        private val RX_UUID: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0002")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SCAN_DURATION_MS = 15_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _devices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<BleDeviceInfo>> = _devices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val notifications: SharedFlow<ByteArray> = _notifications.asSharedFlow()

    private val _disconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val disconnected: SharedFlow<Unit> = _disconnected.asSharedFlow()

    private val gattMutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var cccd: BluetoothGattDescriptor? = null

    @Volatile private var pending: PendingOperation? = null
    @Volatile private var connectionDeferred: CompletableDeferred<Result<Unit>>? = null
    @Volatile private var servicesDeferred: CompletableDeferred<Result<Unit>>? = null
    @Volatile private var scanning = false
    @Volatile private var released = false
    @Volatile private var targetAddress: String? = null
    @Volatile private var targetFoundLogged = false
    @Volatile private var gattStage = "inicio"

    init {
        scope.launch {
            settings.settings.collect { targetAddress = it.targetAddress }
        }
    }

    private data class PendingOperation(
        val kind: Kind,
        val deferred: CompletableDeferred<Result<ByteArray>>
    )

    private enum class Kind { WRITE_FRAME, CCCD_WRITE, READ_RX }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!BluetoothPermissions.hasBlePermissions(context)) return
            val device = result.device
            val name = try { device.name ?: "(sin nombre)" } catch (_: SecurityException) { "(sin nombre)" }
            val address = try { device.address } catch (_: SecurityException) { return }
            val info = BleDeviceInfo(
                name = name,
                address = address,
                rssi = result.rssi,
                isTarget = name.equals(TARGET_NAME, true) ||
                    address.equals(scopeTargetAddress(), true),
                lastSeenMs = System.currentTimeMillis()
            )
            _devices.value = (_devices.value.filterNot { it.address == address } + info)
                .sortedByDescending { it.rssi }
                .take(40)
            if (info.isTarget && !targetFoundLogged) {
                targetFoundLogged = true
                diagnostics.log(
                    BleDiagnostics.Level.INFO, "SCAN",
                    "Dispositivo objetivo encontrado: $name ($address, ${result.rssi} dBm)"
                )
                if (result.rssi < -85) {
                    diagnostics.log(
                        BleDiagnostics.Level.WARN, "SCAN",
                        "Señal BLE muy débil (${result.rssi} dBm). Acerque la batería al teléfono " +
                            "(1-2 m, sin paredes metálicas) antes de conectar."
                    )
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _connectionState.value = ConnectionState.ERROR
            setError("Fallo de escaneo BLE (código $errorCode)")
            diagnostics.error("SCAN", "onScanFailed errorCode=$errorCode")
        }
    }

    private fun scopeTargetAddress(): String? = targetAddress

    override fun startScan() {
        if (released) return
        if (!BluetoothPermissions.hasBlePermissions(context)) {
            _connectionState.value = ConnectionState.ERROR
            setError("Permisos Bluetooth insuficientes")
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            _connectionState.value = ConnectionState.ERROR
            setError("Bluetooth desactivado o no disponible")
            return
        }
        if (scanning) return
        _devices.value = emptyList()
        targetFoundLogged = false
        setError(null)
        diagnostics.log(
            BleDiagnostics.Level.INFO, "SCAN",
            "Escaneo iniciado. Permisos BLE=${BluetoothPermissions.hasBlePermissions(context)}, " +
                "Bluetooth=${adapter?.isEnabled == true}, objetivo=${targetAddress ?: TARGET_NAME}"
        )
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback
            )
            scanning = true
            _connectionState.value = ConnectionState.SCANNING
            diagnostics.log(BleDiagnostics.Level.INFO, "SCAN", "Escaneo BLE iniciado")
            scope.launch {
                delay(SCAN_DURATION_MS)
                if (scanning) stopScan()
            }
        } catch (security: SecurityException) {
            _connectionState.value = ConnectionState.ERROR
            setError("Permiso de escaneo denegado")
            diagnostics.error("SCAN", security.message ?: "SecurityException")
        }
    }

    override fun stopScan() {
        if (!scanning) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Nothing else to do; scan ends by system policy.
        }
        scanning = false
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        diagnostics.log(BleDiagnostics.Level.INFO, "SCAN", "Escaneo BLE detenido")
    }

    override suspend fun connect(device: BleDeviceInfo): Result<Unit> {
        if (!BluetoothPermissions.hasBlePermissions(context)) {
            return Result.failure(IllegalStateException("Permisos Bluetooth insuficientes"))
        }
        if (adapter?.isEnabled != true) {
            return Result.failure(IllegalStateException("Bluetooth desactivado"))
        }
        stopScan()
        gattMutex.withLock {
            val existing = gatt
            if (existing != null) {
                val alreadyConnected = try {
                    bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
                        ?.any { it.address == device.address } == true
                } catch (_: SecurityException) { false }
                if (alreadyConnected && rxCharacteristic != null && txCharacteristic != null) {
                    _connectionState.value = ConnectionState.CONNECTED
                    return Result.success(Unit)
                }
                cleanupGatt(existing)
            }
            return connectLocked(device)
        }
    }

    private suspend fun connectLocked(device: BleDeviceInfo): Result<Unit> {
        _connectionState.value = ConnectionState.CONNECTING
        gattStage = "conexión GATT"
        setError(null)
        val remote: BluetoothDevice = try {
            adapter?.getRemoteDevice(device.address)
                ?: return Result.failure(IllegalStateException("Adaptador Bluetooth no disponible"))
        } catch (_: IllegalArgumentException) {
            return Result.failure(IllegalArgumentException("Dirección BLE inválida"))
        }
        val connDeferred = CompletableDeferred<Result<Unit>>()
        connectionDeferred = connDeferred
        val svcDeferred = CompletableDeferred<Result<Unit>>()
        servicesDeferred = svcDeferred

        return try {
            diagnostics.log(BleDiagnostics.Level.INFO, "GATT", "Conectando a ${device.address}")
            val newGatt = remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (newGatt == null) {
                return Result.failure(IllegalStateException("connectGatt devolvió null"))
            }
            gatt = newGatt
            val connected = withTimeout(CONNECT_TIMEOUT_MS) { connDeferred.await() }
            connected.getOrThrow()

            gattStage = "descubrimiento de servicios"
            _connectionState.value = ConnectionState.DISCOVERING_SERVICES
            val discovered = withTimeout(CONNECT_TIMEOUT_MS) { svcDeferred.await() }
            discovered.getOrThrow()

            if (rxCharacteristic == null || txCharacteristic == null || cccd == null) {
                return Result.failure(IllegalStateException("Servicio/características GATT no encontrados"))
            }
            gattStage = "suscripción CCCD"
            // connectLocked is already inside gattMutex: use the non-locking variant
            // to avoid a deadlock on Kotlin's non-reentrant Mutex.
            val subscribed = subscribeNotificationsUnlocked()
            subscribed.getOrThrow()
            gattStage = "listo"
            _connectionState.value = ConnectionState.CONNECTED
            Result.success(Unit)
        } catch (timeout: TimeoutCancellationException) {
            val message = "Timeout en etapa: $gattStage"
            setError(message)
            diagnostics.error("GATT", message)
            releaseConnection()
            Result.failure(IllegalStateException(message))
        } catch (exception: Exception) {
            val message = exception.message ?: "Error de conexión GATT"
            setError(message)
            diagnostics.error("GATT", message)
            releaseConnection()
            Result.failure(exception)
        }
    }

    override suspend fun subscribeNotifications(): Result<Unit> = gattMutex.withLock {
        subscribeNotificationsUnlocked()
    }

    /** Must be called while holding [gattMutex]. */
    private suspend fun subscribeNotificationsUnlocked(): Result<Unit> {
        val currentGatt = gatt ?: return Result.failure(IllegalStateException("Sin conexión GATT"))
        val rx = rxCharacteristic ?: return Result.failure(IllegalStateException("RX no disponible"))
        val descriptor = cccd ?: return Result.failure(IllegalStateException("CCCD no disponible"))
        _connectionState.value = ConnectionState.SUBSCRIBING
        // Local notification switch MUST be enabled before writing the CCCD.
        if (!currentGatt.setCharacteristicNotification(rx, true)) {
            return Result.failure(IllegalStateException("setCharacteristicNotification falló"))
        }
        // Reuse the already-held lock: runGattOperation assumes the caller owns gattMutex.
        val result = runGattOperationLocked(Kind.CCCD_WRITE) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            currentGatt.writeDescriptor(descriptor)
        }
        if (result.isSuccess) diagnostics.log(BleDiagnostics.Level.INFO, "GATT", "Notificaciones RX activadas")
        else diagnostics.error("GATT", "Fallo al activar notificaciones: ${result.exceptionOrNull()?.message}")
        return result.map { Unit }
    }

    override suspend fun writeFrame(frame: ByteArray): Result<Unit> {
        val currentGatt = gatt ?: return Result.failure(IllegalStateException("Sin conexión GATT"))
        val tx = txCharacteristic ?: return Result.failure(IllegalStateException("TX no disponible"))
        return runGattOperation(Kind.WRITE_FRAME) {
            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            tx.value = frame
            currentGatt.writeCharacteristic(tx)
        }.map { Unit }.also {
            if (it.isSuccess) diagnostics.tx(frame)
        }
    }

    override suspend fun probeRxRead(): Result<ByteArray> {
        val currentGatt = gatt ?: return Result.failure(IllegalStateException("Sin conexión GATT"))
        val rx = rxCharacteristic ?: return Result.failure(IllegalStateException("RX no disponible"))
        diagnostics.log(BleDiagnostics.Level.INFO, "GATT", "Sonda de lectura RX (diagnóstico)")
        return runGattOperation(Kind.READ_RX) { currentGatt.readCharacteristic(rx) }
    }

    private suspend fun runGattOperation(kind: Kind, start: () -> Boolean): Result<ByteArray> =
        gattMutex.withLock { runGattOperationLocked(kind, start) }

    /** Caller must already hold [gattMutex]. */
    private suspend fun runGattOperationLocked(
        kind: Kind,
        start: () -> Boolean
    ): Result<ByteArray> {
        val currentGatt = gatt ?: return Result.failure(IllegalStateException("Sin conexión GATT"))
        val deferred = CompletableDeferred<Result<ByteArray>>()
        pending = PendingOperation(kind, deferred)
        return try {
            val started = try {
                start()
            } catch (security: SecurityException) {
                return Result.failure(security)
            }
            if (!started) {
                return Result.failure(IllegalStateException("Operación GATT $kind rechazada"))
            }
            val timeout = settings.settings.first().requestTimeoutMs
            withTimeout(timeout) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            diagnostics.error("GATT", "Timeout en operación $kind")
            Result.failure(IllegalStateException("Timeout en operación $kind"))
        } catch (exception: Exception) {
            Result.failure(exception)
        } finally {
            pending = null
        }
    }

    @Synchronized
    private fun completePending(result: Result<ByteArray>, expected: Kind) {
        val op = pending
        if (op?.kind == expected) op.deferred.complete(result)
    }

    @Synchronized
    private fun failAllPending(message: String) {
        pending?.deferred?.complete(Result.failure(IllegalStateException(message)))
        pending = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    diagnostics.error("GATT", "onConnectionStateChange status=$status state=$newState")
                    connectionDeferred?.complete(Result.failure(IllegalStateException("GATT status $status")))
                    servicesDeferred?.complete(Result.failure(IllegalStateException("GATT status $status")))
                    onDisconnected(status)
                }
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    // CRITICAL: release the coroutine waiting in connectLocked(). Without this,
                    // connectGatt succeeds and services are discovered but connect() still waits
                    // 15 s and then disconnects, so notifications are never enabled.
                    connectionDeferred?.complete(Result.success(Unit))
                    _connectionState.value = ConnectionState.DISCOVERING_SERVICES
                    diagnostics.log(BleDiagnostics.Level.INFO, "GATT", "Conectado; descubriendo servicios")
                    scope.launch {
                        delay(300) // some BMS firmware needs a short gap before discovery
                        try {
                            if (!gatt.discoverServices()) {
                                servicesDeferred?.complete(Result.failure(IllegalStateException("discoverServices devolvió false")))
                            }
                        } catch (security: SecurityException) {
                            servicesDeferred?.complete(Result.failure(security))
                        }
                    }
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    diagnostics.log(BleDiagnostics.Level.WARN, "GATT", "Desconectado por el dispositivo")
                    connectionDeferred?.complete(Result.failure(IllegalStateException("Desconectado")))
                    servicesDeferred?.complete(Result.failure(IllegalStateException("Desconectado")))
                    onDisconnected(0)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val message = "Descubrimiento de servicios falló (status=$status)"
                diagnostics.error("GATT", message)
                servicesDeferred?.complete(Result.failure(IllegalStateException(message)))
                return
            }
            val service: BluetoothGattService? = gatt.getService(SERVICE_UUID)
            val rx = service?.getCharacteristic(RX_UUID)
            val tx = service?.getCharacteristic(TX_UUID)
            val descriptor = rx?.getDescriptor(CCCD_UUID)
            if (service == null || rx == null || tx == null || descriptor == null) {
                val message = "No se encontraron UUIDs GATT esperados"
                diagnostics.error("GATT", message)
                servicesDeferred?.complete(Result.failure(IllegalStateException(message)))
                return
            }
            rxCharacteristic = rx
            txCharacteristic = tx
            cccd = descriptor
            diagnostics.log(
                BleDiagnostics.Level.INFO, "GATT",
                "Servicios descubiertos: RX=${rx.uuid} TX=${tx.uuid}"
            )
            servicesDeferred?.complete(Result.success(Unit))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != RX_UUID) return
            val value = characteristic.value?.copyOf() ?: return
            handleRx(value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != RX_UUID) return
            handleRx(value.copyOf())
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            completePending(
                if (status == BluetoothGatt.GATT_SUCCESS) Result.success(ByteArray(0))
                else Result.failure(IllegalStateException("Escritura TX status=$status")),
                Kind.WRITE_FRAME
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            completePending(
                if (status == BluetoothGatt.GATT_SUCCESS) Result.success(ByteArray(0))
                else Result.failure(IllegalStateException("CCCD status=$status")),
                Kind.CCCD_WRITE
            )
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val value = characteristic.value?.copyOf() ?: ByteArray(0)
            completePending(
                if (status == BluetoothGatt.GATT_SUCCESS) Result.success(value)
                else Result.failure(IllegalStateException("Lectura RX status=$status")),
                Kind.READ_RX
            )
        }
    }

    private fun handleRx(value: ByteArray) {
        diagnostics.rx(value)
        _notifications.tryEmit(value)
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.SUBSCRIBING
        ) {
            _connectionState.value = ConnectionState.RECEIVING_DATA
        }
    }

    private fun onDisconnected(status: Int) {
        failAllPending("GATT desconectado")
        _connectionState.value = if (status == 0) ConnectionState.DISCONNECTED else ConnectionState.ERROR
        _disconnected.tryEmit(Unit)
        val current = gatt ?: return
        scope.launch {
            delay(150)
            cleanupGatt(current)
        }
    }

    @Synchronized
    private fun cleanupGatt(current: BluetoothGatt) {
        try {
            current.close()
        } catch (_: Exception) {
            // Closing an already-dead GATT is harmless.
        }
        if (gatt === current) {
            gatt = null
            rxCharacteristic = null
            txCharacteristic = null
            cccd = null
        }
    }

    override fun disconnect() {
        stopScan()
        val current = gatt
        gatt = null
        rxCharacteristic = null
        txCharacteristic = null
        cccd = null
        if (current != null) {
            try {
                current.disconnect()
            } catch (_: SecurityException) {
                // Nothing else to do.
            }
            scope.launch { delay(150); cleanupGatt(current) }
        }
        _connectionState.value = ConnectionState.DISCONNECTED
        setError(null)
    }

    private fun releaseConnection() {
        connectionDeferred = null
        servicesDeferred = null
        val current = gatt ?: return
        try {
            current.disconnect()
        } catch (_: Exception) {
            // ignore
        }
        cleanupGatt(current)
        _connectionState.value = ConnectionState.ERROR
    }

    override fun release() {
        released = true
        stopScan()
        disconnect()
    }

    override fun setError(message: String?) {
        _lastError.value = message
    }
}
