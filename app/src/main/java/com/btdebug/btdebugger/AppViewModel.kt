package com.btdebug.btdebugger

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.btdebug.btdebugger.ble.AdPacket
import com.btdebug.btdebugger.ble.AdParser
import com.btdebug.btdebugger.ble.Decoders
import com.btdebug.btdebugger.ble.GattSession
import com.btdebug.btdebugger.ble.ScanController
import com.btdebug.btdebugger.ble.ScanDevice
import com.btdebug.btdebugger.data.AdRecord
import com.btdebug.btdebugger.data.Export
import com.btdebug.btdebugger.data.FilterPreset
import com.btdebug.btdebugger.data.FilterSpec
import com.btdebug.btdebugger.data.Reading
import com.btdebug.btdebugger.data.Store
import com.btdebug.btdebugger.data.WeightApp
import com.btdebug.btdebugger.data.ApiSettings
import com.btdebug.btdebugger.data.WeightApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import kotlin.math.abs

sealed interface Route {
    data object Home : Route
    data class Device(val address: String) : Route
    data class WeightApp(val id: String) : Route
    data class AppSettings(val id: String) : Route
}

data class ApiSyncState(val busy: Boolean = false, val pending: Int = 0, val message: String = "No uploads yet")

enum class SortMode(val label: String) { SIGNAL("Signal"), NAME("Name"), RECENT("Recent") }

private data class DeviceOrder(
    val mode: SortMode = SortMode.SIGNAL,
    val revision: Long = 0,
    val devices: List<ScanDevice> = emptyList(),
)

private fun sortDevices(devices: List<ScanDevice>, mode: SortMode): List<ScanDevice> = when (mode) {
    SortMode.SIGNAL -> devices.sortedWith(compareByDescending<ScanDevice> { it.rssi }.thenBy { it.address })
    SortMode.NAME -> devices.sortedWith(compareBy<ScanDevice>({ it.name == null }, { it.name?.lowercase() }).thenBy { it.address })
    SortMode.RECENT -> devices.sortedWith(compareByDescending<ScanDevice> { it.lastSeen }.thenBy { it.address })
}

data class WeightLive(val kg: Double? = null, val stable: Boolean = false, val lastSeen: Long = 0)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    val scanner = ScanController(app)

    val route = MutableStateFlow<Route>(Route.Home)
    val tab = MutableStateFlow(0)
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(FilterSpec())
    val sort = MutableStateFlow(SortMode.SIGNAL)
    private val sortRevision = MutableStateFlow(0L)
    val presets = MutableStateFlow(store.loadPresets())
    val apps = MutableStateFlow(store.loadApps())
    val readings = MutableStateFlow<List<Reading>>(emptyList())
    val live = MutableStateFlow(WeightLive())
    val apiSettings = MutableStateFlow<Map<String, ApiSettings>>(emptyMap())
    val apiSync = MutableStateFlow<Map<String, ApiSyncState>>(emptyMap())
    private val uploadMutex = Mutex()

    val history = MutableStateFlow<List<AdRecord>>(emptyList())
    val paused = MutableStateFlow(false)
    val gatt = MutableStateFlow<GattSession?>(null)

    // Preserve address order across live updates, including while devices are filtered out.
    private val orderedDevices = combine(scanner.devices, sort, sortRevision) { all, mode, revision ->
        DeviceOrder(mode, revision, all)
    }.scan(DeviceOrder()) { previous, current ->
        val ordered = if (previous.devices.isEmpty() || previous.mode != current.mode || previous.revision != current.revision) {
            sortDevices(current.devices, current.mode)
        } else {
            val latest = current.devices.associateBy { it.address }
            val known = previous.devices.mapTo(HashSet()) { it.address }
            previous.devices.mapNotNull { latest[it.address] } + current.devices.filter { it.address !in known }
        }
        current.copy(devices = ordered)
    }

    val shown: StateFlow<List<ScanDevice>> = combine(orderedDevices, query, filter) { ordered, q, f ->
        val text = q.trim().lowercase()
        ordered.devices.filter { d ->
            (text.isEmpty() || text in d.displayName.lowercase() || text in d.address.lowercase().replace(":", "") || text in d.address.lowercase()) &&
                (!f.connectableOnly || d.connectable) && (!f.namedOnly || d.name != null) &&
                (!f.decodedOnly || d.decoded.isNotEmpty()) && d.rssi >= f.minRssi &&
                (f.kinds.isEmpty() || d.kind in f.kinds)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun reorderDevices() { sortRevision.update { it + 1 } }

    init {
        viewModelScope.launch(Dispatchers.Default) { scanner.packets.collect(::onPacket) }
    }

    // --- scanning ---
    fun startScan() = scanner.start()
    fun toggleScan() = if (scanner.scanning.value) scanner.stop() else scanner.start()

    // --- navigation ---
    fun back() {
        when (route.value) {
            is Route.Device -> closeDevice()
            is Route.WeightApp -> route.value = Route.Home
            is Route.AppSettings -> route.value = Route.WeightApp((route.value as Route.AppSettings).id)
            Route.Home -> Unit
        }
    }

    fun openDevice(address: String) {
        gatt.value?.close(); gatt.value = null
        paused.value = false
        val seed = scanner.find(address)
        history.value = seed?.ad?.raw?.takeIf { it.isNotEmpty() }?.let {
            listOf(AdRecord(System.currentTimeMillis(), seed.rssi, it, List(it.size) { false }, 1, summaryOf(it, address)))
        }.orEmpty()
        route.value = Route.Device(address)
    }

    private fun closeDevice() {
        gatt.value?.close(); gatt.value = null
        route.value = Route.Home
    }

    fun connectGatt(address: String) {
        gatt.value?.close()
        gatt.value = GattSession(getApplication(), address).also { it.connect() }
    }

    fun disconnectGatt() { gatt.value?.disconnect() }

    // --- advertisement capture ---
    private fun summaryOf(raw: ByteArray, address: String): String? =
        Decoders.decode(AdParser.parse(raw), address).joinToString("; ") { "${it.protocol}: ${it.summary}" }.ifEmpty { null }

    private fun onPacket(p: AdPacket) {
        val r = route.value
        if (r is Route.Device && r.address == p.address && !paused.value) recordAd(p)
        if (r is Route.WeightApp || r is Route.AppSettings) handleWeight(p)
    }

    private fun recordAd(p: AdPacket) {
        history.update { cur ->
            val last = cur.firstOrNull()
            if (last != null && last.raw.contentEquals(p.raw)) {
                listOf(AdRecord(p.time, p.rssi, last.raw, last.changed, last.count + 1, last.summary)) + cur.drop(1)
            } else {
                val mask = List(p.raw.size) { i -> last != null && last.raw.getOrNull(i) != p.raw[i] }
                (listOf(AdRecord(p.time, p.rssi, p.raw, mask, 1, summaryOf(p.raw, p.address))) + cur).take(300)
            }
        }
    }

    fun clearHistory() { history.value = history.value.take(1) }

    fun exportFile(address: String, name: String?, format: String): File {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val adv = history.value
        val events = gatt.value?.events?.value.orEmpty()
        val file = File(dir, "btdebug_${address.replace(":", "")}_${System.currentTimeMillis() / 1000}.$format")
        file.writeText(if (format == "json") Export.json(address, name, adv, events) else Export.csv(adv, events))
        return file
    }

    // --- presets ---
    fun savePreset(name: String) {
        val updated = presets.value.filterNot { it.name == name } + FilterPreset(name, filter.value)
        presets.value = updated; store.savePresets(updated)
    }

    fun deletePreset(name: String) {
        val updated = presets.value.filterNot { it.name == name }
        presets.value = updated; store.savePresets(updated)
    }

    // --- weight scale apps ---
    private var lastKg = Double.NaN
    private var sameCount = 0
    private var armed = true
    private var lastPacketAt = 0L
    private var lastSavedKg = Double.NaN
    private var lastSavedAt = 0L

    fun createWeightApp(name: String): WeightApp {
        val app = WeightApp(UUID.randomUUID().toString(), name.ifBlank { "OKOK Weight Scale" }, System.currentTimeMillis())
        apps.value = apps.value + app; store.saveApps(apps.value)
        return app
    }

    fun openWeightApp(id: String) {
        readings.value = store.loadReadings(id)
        live.value = WeightLive(); lastKg = Double.NaN; sameCount = 0; armed = true
        route.value = Route.WeightApp(id)
        scanner.start()
        refreshApiState(id)
        if (store.loadApiSettings(id).enabled) uploadPending(id)
    }

    fun deleteWeightApp(id: String) {
        apps.value = apps.value.filterNot { it.id == id }; store.saveApps(apps.value); store.deleteReadings(id)
        store.deleteApiData(id)
        apiSettings.update { it - id }; apiSync.update { it - id }
    }

    private fun refreshApiState(id: String) {
        apiSettings.update { it + (id to store.loadApiSettings(id)) }
        apiSync.update { it + (id to (it[id] ?: ApiSyncState()).copy(pending = store.pendingUploads(id).size)) }
    }

    fun openAppSettings(id: String) {
        refreshApiState(id)
        route.value = Route.AppSettings(id)
    }

    fun saveApiSettings(id: String, settings: ApiSettings) {
        store.saveApiSettings(id, settings)
        refreshApiState(id)
        apiSync.update { it + (id to (it[id] ?: ApiSyncState()).copy(message = "Settings saved")) }
        if (settings.enabled) uploadPending(id)
    }

    fun syncHistory(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.loadReadings(id).forEach { store.enqueueUpload(id, it) } }
            uploadPending(id, manual = true)
        }
    }

    fun retryUploads(id: String) = uploadPending(id, manual = true)

    private fun uploadPending(id: String, manual: Boolean = false) {
        viewModelScope.launch {
            uploadMutex.withLock {
                val app = apps.value.firstOrNull { it.id == id } ?: return@withLock
                val settings = store.loadApiSettings(id)
                if (!manual && !settings.enabled) return@withLock
                val error = WeightApi.validationError(settings.url, settings.token)
                if (error != null) {
                    apiSync.update { it + (id to ApiSyncState(pending = store.pendingUploads(id).size, message = error)) }
                    return@withLock
                }
                apiSync.update { it + (id to ApiSyncState(true, store.pendingUploads(id).size, "Uploading…")) }
                var sent = 0
                var message = "All queued readings uploaded"
                try {
                    withContext(Dispatchers.IO) {
                        while (apps.value.any { it.id == id }) {
                            if (store.loadApiSettings(id) != settings) { message = "Settings changed; retry remaining readings"; break }
                            val reading = store.pendingUploads(id).firstOrNull() ?: break
                            WeightApi.post(settings, app, reading)
                            store.completeUpload(id, reading.time)
                            sent++
                        }
                    }
                    if (sent > 0) message = "Uploaded $sent reading(s)"
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Do not expose URLs, tokens, or response bodies in the UI.
                    message = if (e is IllegalStateException) e.message ?: "Upload failed" else "Connection failed. Check URL and network; retry queued readings."
                } finally {
                    if (apps.value.any { it.id == id }) apiSync.update {
                        it + (id to ApiSyncState(false, store.pendingUploads(id).size, message))
                    }
                    // Only notify for records acknowledged by the server and marked uploaded.
                    // This coroutine resumes on Main after the IO block, so navigation or
                    // recomposition cannot replay a success toast.
                    if (sent > 0) {
                        val remaining = store.pendingUploads(id).size
                        val count = if (sent == 1) "1 reading" else "$sent readings"
                        val suffix = if (remaining > 0) " · $remaining still queued" else ""
                        Toast.makeText(getApplication(), "$count uploaded to API$suffix", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun setUnit(id: String, lb: Boolean) {
        apps.value = apps.value.map { if (it.id == id) it.copy(useLb = lb) else it }; store.saveApps(apps.value)
    }

    fun deleteReading(id: String, reading: Reading) {
        val updated = readings.value.filterNot { it.time == reading.time }
        readings.value = updated; store.saveReadings(id, updated)
        store.removePendingUpload(id, reading.time)
        refreshApiState(id)
    }

    fun lastReading(id: String): Reading? = store.loadReadings(id).maxByOrNull { it.time }
    fun readingCount(id: String): Int = store.loadReadings(id).size

    /** A reading is "stable" after 3 identical weights in a row; it is saved once per weigh-in. */
    private fun handleWeight(p: AdPacket) {
        val kg = Decoders.decode(AdParser.parse(p.raw), p.address).firstNotNullOfOrNull { it.weightKg } ?: return
        val appId = when (val current = route.value) {
            is Route.WeightApp -> current.id
            is Route.AppSettings -> current.id
            else -> return
        }
        if (p.time - lastPacketAt > 8_000) armed = true
        lastPacketAt = p.time
        if (kg == lastKg) sameCount++ else { lastKg = kg; sameCount = 1; armed = true }
        val stable = sameCount >= 3
        if (stable && armed) {
            armed = false
            val duplicate = !lastSavedKg.isNaN() && abs(kg - lastSavedKg) < 0.3 && p.time - lastSavedAt < 60_000
            if (!duplicate) {
                lastSavedKg = kg; lastSavedAt = p.time
                val reading = Reading(p.time, kg, p.address)
                val updated = (readings.value + reading).sortedBy { it.time }
                readings.value = updated; store.saveReadings(appId, updated)
                if (store.loadApiSettings(appId).enabled) {
                    store.enqueueUpload(appId, reading)
                    uploadPending(appId)
                }
            }
        }
        live.value = WeightLive(kg, stable, p.time)
    }

    override fun onCleared() {
        scanner.stop(); gatt.value?.close()
    }
}
