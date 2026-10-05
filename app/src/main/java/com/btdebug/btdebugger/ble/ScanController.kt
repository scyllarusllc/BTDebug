package com.btdebug.btdebugger.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class ScanController(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val table = ConcurrentHashMap<String, ScanDevice>()
    private var publisher: Job? = null

    private val _packets = MutableSharedFlow<AdPacket>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Every advertisement received, unthrottled. */
    val packets: SharedFlow<AdPacket> = _packets

    private val _devices = MutableStateFlow<List<ScanDevice>>(emptyList())
    /** Snapshot of all known devices, refreshed ~2.5×/s while scanning. */
    val devices: StateFlow<List<ScanDevice>> = _devices

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            _error.value = when (errorCode) {
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Scan failed: too many scans registered. Wait a moment and retry."
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Android is throttling scans (5 starts per 30 s). Try again shortly."
                else -> "Scan failed (error $errorCode)"
            }
            _scanning.value = false
        }
    }

    private fun handle(r: ScanResult) {
        val address = r.device.address
        val record = r.scanRecord
        val ad = AdParser.parse(record?.bytes)
        val prev = table[address]
        val now = System.currentTimeMillis()
        val name = record?.deviceName ?: ad.localName ?: prev?.name
        val decoded = Decoders.decode(ad, address)
        val usedAd = if (ad.isEmpty && prev != null) prev.ad else ad
        val usedDecoded = if (ad.isEmpty && prev != null) prev.decoded else decoded
        val dev = ScanDevice(
            address = address, name = name, rssi = r.rssi, connectable = r.isConnectable,
            firstSeen = prev?.firstSeen ?: now, lastSeen = now, packets = (prev?.packets ?: 0) + 1,
            ad = usedAd, decoded = usedDecoded, kind = classify(usedAd, usedDecoded, name),
            identified = usedDecoded.firstNotNullOfOrNull { it.identified } ?: name?.takeIf { classify(usedAd, usedDecoded, name) != DeviceKind.OTHER },
        )
        table[address] = dev
        _packets.tryEmit(AdPacket(address, name, r.rssi, r.isConnectable, now, usedAd.raw))
    }

    fun start() {
        val ad = adapter
        if (ad == null) { _error.value = "This device has no Bluetooth."; return }
        if (!ad.isEnabled) { _error.value = "Bluetooth is turned off."; return }
        val scanner = ad.bluetoothLeScanner ?: run { _error.value = "Bluetooth LE scanner unavailable."; return }
        if (_scanning.value) return
        _error.value = null
        runCatching {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            _scanning.value = true
        }.onFailure { _error.value = "Could not start scan: ${it.message}" }
        publisher?.cancel()
        publisher = scope.launch {
            while (isActive) {
                val cutoff = System.currentTimeMillis() - 120_000
                table.values.removeIf { it.lastSeen < cutoff }
                _devices.value = table.values.toList()
                delay(400)
            }
        }
    }

    fun stop() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _scanning.value = false
        publisher?.cancel()
    }

    fun clear() { table.clear(); _devices.value = emptyList() }
    fun find(address: String): ScanDevice? = table[address]
}
