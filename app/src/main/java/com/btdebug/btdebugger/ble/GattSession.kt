package com.btdebug.btdebugger.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

enum class GattKind { INFO, READ, WRITE, NOTIFY, INDICATE, ERROR }

class GattEvent(
    val time: Long, val kind: GattKind, val uuid: String?, val value: ByteArray?, val status: Int, val note: String? = null,
)

class LastValue(val kind: GattKind, val value: ByteArray?, val status: Int, val time: Long)

sealed interface GattState {
    data object Idle : GattState
    data class Connecting(val attempt: Int) : GattState
    data object Discovering : GattState
    data object Ready : GattState
    data class Disconnected(val status: Int) : GattState
}

fun charKey(c: BluetoothGattCharacteristic): String = "${c.service.uuid}|${c.uuid}|${c.instanceId}"

/**
 * GATT client with a serialized operation queue: only one read/write/descriptor operation is in
 * flight at a time (Android silently drops overlapping requests), each with a timeout, and the
 * connection is retried automatically on the notorious status 133 and friends.
 */
@SuppressLint("MissingPermission")
class GattSession(private val context: Context, private val address: String) {
    private class OpResult(val status: Int, val value: ByteArray?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<OpResult>? = null
    private var gatt: BluetoothGatt? = null
    private var attempt = 0
    @Volatile private var userDisconnect = false

    private val _state = MutableStateFlow<GattState>(GattState.Idle)
    val state: StateFlow<GattState> = _state
    private val _services = MutableStateFlow<List<BluetoothGattService>>(emptyList())
    val services: StateFlow<List<BluetoothGattService>> = _services
    private val _events = MutableStateFlow<List<GattEvent>>(emptyList())
    val events: StateFlow<List<GattEvent>> = _events
    private val _values = MutableStateFlow<Map<String, LastValue>>(emptyMap())
    val values: StateFlow<Map<String, LastValue>> = _values
    private val _notifying = MutableStateFlow<Set<String>>(emptySet())
    val notifying: StateFlow<Set<String>> = _notifying
    private val _mtu = MutableStateFlow(23)
    val mtu: StateFlow<Int> = _mtu

    private fun log(kind: GattKind, uuid: String?, value: ByteArray?, status: Int, note: String? = null) {
        _events.update { (it + GattEvent(System.currentTimeMillis(), kind, uuid, value, status, note)).takeLast(1000) }
    }

    fun connect() {
        userDisconnect = false
        attempt = 0
        open()
    }

    private fun open() {
        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address) ?: return
        attempt++
        _state.value = GattState.Connecting(attempt)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        userDisconnect = true
        gatt?.disconnect()
    }

    fun close() {
        userDisconnect = true
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        pending?.complete(OpResult(-3, null))
        scope.cancel()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log(GattKind.ERROR, null, null, status, "Connection state change failed (status $status)")
                g.close(); gatt = null
                pending?.complete(OpResult(-3, null))
                if (!userDisconnect && attempt < MAX_ATTEMPTS) {
                    log(GattKind.INFO, null, null, 0, "Retrying connection (attempt ${attempt + 1}/$MAX_ATTEMPTS)")
                    scope.launch { delay(600L * attempt); open() }
                } else _state.value = GattState.Disconnected(status)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log(GattKind.INFO, null, null, 0, "Connected")
                    _state.value = GattState.Discovering
                    scope.launch { delay(400); g.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log(GattKind.INFO, null, null, 0, "Disconnected")
                    g.close(); gatt = null
                    pending?.complete(OpResult(-3, null))
                    _state.value = GattState.Disconnected(0)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            _services.value = g.services.toList()
            log(GattKind.INFO, null, null, status, "Discovered ${g.services.size} services")
            _state.value = GattState.Ready
            scope.launch { exec { it.requestMtu(247) } }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == 0) _mtu.value = mtu
            log(GattKind.INFO, null, null, status, "MTU = $mtu")
            pending?.complete(OpResult(status, null))
        }

        @Deprecated("API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) pending?.complete(OpResult(status, c.value))
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(OpResult(status, value))
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(OpResult(status, null))
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(OpResult(status, null))
        }
        @Deprecated("API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) changed(c, c.value ?: ByteArray(0))
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            changed(c, value)
        }
    }

    private fun changed(c: BluetoothGattCharacteristic, value: ByteArray) {
        val kind = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) GattKind.INDICATE else GattKind.NOTIFY
        record(c, kind, value, 0)
    }

    private fun record(c: BluetoothGattCharacteristic, kind: GattKind, value: ByteArray?, status: Int) {
        log(kind, c.uuid.toString(), value, status)
        _values.update { it + (charKey(c) to LastValue(kind, value, status, System.currentTimeMillis())) }
    }

    /** Runs one GATT operation at a time. Status: -1 could not start, -2 timeout, -3 link lost. */
    private suspend fun exec(timeoutMs: Long = 10_000, start: (BluetoothGatt) -> Boolean): OpResult = opLock.withLock {
        val g = gatt ?: return@withLock OpResult(-1, null)
        val d = CompletableDeferred<OpResult>()
        pending = d
        if (!start(g)) { pending = null; return@withLock OpResult(-1, null) }
        try {
            withTimeout(timeoutMs) { d.await() }
        } catch (_: TimeoutCancellationException) {
            OpResult(-2, null)
        } finally {
            pending = null
        }
    }

    suspend fun read(c: BluetoothGattCharacteristic) {
        val r = exec { it.readCharacteristic(c) }
        record(c, if (r.status == 0) GattKind.READ else GattKind.ERROR, r.value, r.status)
    }

    suspend fun write(c: BluetoothGattCharacteristic, data: ByteArray, withResponse: Boolean) {
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val r = exec {
            if (Build.VERSION.SDK_INT >= 33) it.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") run { c.writeType = type; c.value = data; it.writeCharacteristic(c) } }
        }
        record(c, if (r.status == 0) GattKind.WRITE else GattKind.ERROR, data, r.status)
    }

    suspend fun setNotify(c: BluetoothGattCharacteristic, enable: Boolean) {
        val cccd = c.getDescriptor(CCCD) ?: return
        val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            indicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        gatt?.setCharacteristicNotification(c, enable)
        val r = exec {
            if (Build.VERSION.SDK_INT >= 33) it.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") run { cccd.value = value; it.writeDescriptor(cccd) } }
        }
        if (r.status == 0) {
            val key = charKey(c)
            _notifying.update { if (enable) it + key else it - key }
            log(GattKind.INFO, c.uuid.toString(), null, 0, if (enable) "Notifications enabled" else "Notifications disabled")
        } else log(GattKind.ERROR, c.uuid.toString(), null, r.status, "Could not change notification state")
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
