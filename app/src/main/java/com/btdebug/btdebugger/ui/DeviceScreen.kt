@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.btdebug.btdebugger.ui

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.btdebug.btdebugger.AppViewModel
import com.btdebug.btdebugger.ble.AdParser
import com.btdebug.btdebugger.ble.Appearance
import com.btdebug.btdebugger.ble.Decoders
import com.btdebug.btdebugger.ble.GattKind
import com.btdebug.btdebugger.ble.GattSession
import com.btdebug.btdebugger.ble.GattState
import com.btdebug.btdebugger.ble.Uuids
import com.btdebug.btdebugger.ble.charKey
import com.btdebug.btdebugger.ble.parseHex
import com.btdebug.btdebugger.ble.toAscii
import com.btdebug.btdebugger.ble.toHex
import com.btdebug.btdebugger.data.AdRecord
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
fun DeviceScreen(vm: AppViewModel, address: String) {
    val all by vm.scanner.devices.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val session by vm.gatt.collectAsStateWithLifecycle()
    val device = all.firstOrNull { it.address == address }
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var createApp by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun share(format: String) {
        val file = vm.exportFile(address, device?.name, format)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (format == "json") "application/json" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Export capture"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(device?.displayName ?: "Device", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(address, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (device?.isScale == true) TextButton({ createApp = true }) { Text("Create app") }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.Share, "Export") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Export JSON") }, { menu = false; share("json") })
                            DropdownMenuItem({ Text("Export CSV") }, { menu = false; share("csv") })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Advertisement", "GATT", "Timeline").forEachIndexed { i, t ->
                    Tab(tab == i, { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> AdvertisementTab(vm, address)
                1 -> GattTab(vm, address, device?.connectable != false, session)
                else -> TimelineTab(history, session)
            }
        }
    }

    if (createApp) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createApp = false },
            title = { Text("Create weight scale app") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Protocol: OKOK advertisement-based weight scale. Open the app and step on the scale; stable readings are saved automatically.")
                    OutlinedTextField(name, { name = it }, label = { Text("App name (default: OKOK Weight Scale)") })
                }
            },
            confirmButton = {
                TextButton({
                    createApp = false
                    val app = vm.createWeightApp(name.trim())
                    vm.route.value = com.btdebug.btdebugger.Route.Home
                    vm.tab.value = 1
                    vm.openWeightApp(app.id)
                }) { Text("Create") }
            },
            dismissButton = { TextButton({ createApp = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- advertisement

@Composable
private fun AdvertisementTab(vm: AppViewModel, address: String) {
    val history by vm.history.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val all by vm.scanner.devices.collectAsStateWithLifecycle()
    val device = all.firstOrNull { it.address == address }
    var ascii by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val clipboard = LocalClipboardManager.current
    val record: AdRecord? = selected?.let { history.getOrNull(it) } ?: history.firstOrNull()
    val ad = remember(record?.raw?.toHex("")) { AdParser.parse(record?.raw ?: device?.ad?.raw) }
    val decoded = remember(ad) { Decoders.decode(ad, address) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        decoded.forEach { d ->
            item {
                SectionCard {
                    if (d.weightKg != null) {
                        Text("%.2f kg".format(d.weightKg), fontSize = 44.sp, fontWeight = FontWeight.Medium, fontFamily = Mono)
                        Text(d.protocol, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(d.protocol, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(d.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (d.fields.isNotEmpty()) Spacer(Modifier.height(8.dp))
                    d.fields.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(k, Modifier.width(130.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text(v, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ vm.paused.value = !paused }) { Text(if (paused) "Resume capture" else "Pause capture") }
                OutlinedButton({ ascii = !ascii }) { Text(if (ascii) "Show HEX" else "Show ASCII") }
                OutlinedButton({
                    val text = record?.raw?.let { if (ascii) it.toAscii() else it.toHex() }.orEmpty()
                    clipboard.setText(AnnotatedString(text))
                }) { Icon(Icons.Default.ContentCopy, "Copy", Modifier.width(18.dp)) }
            }
        }
        if (selected != null) item {
            FilledTonalButton({ selected = null }) { Text("Viewing capture #${history.size - selected!!} — back to live") }
        }
        item {
            SectionCard {
                Text("Raw Advertising Packet", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                val raw = record?.raw ?: ad.raw
                Text(highlighted(raw, record?.changed.orEmpty(), ascii), fontFamily = Mono, fontSize = 16.sp, lineHeight = 24.sp)
                if (record != null && record.changed.any { it }) {
                    Text("Highlighted bytes changed since the previous packet", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            SectionCard {
                Text("Advertisement Metadata", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                MetaRow("RSSI", record?.let { "${it.rssi} dBm" } ?: "—")
                MetaRow("Local name", ad.localName ?: "Not declared")
                MetaRow("TX Power", ad.txPower?.let { "$it dBm" } ?: "Not declared")
                MetaRow("Appearance", ad.appearance?.let { Appearance.name(it) } ?: "Not declared")
                MetaRow("Flags", ad.flags?.let { "0x%02X · %s".format(it, AdParser.flagsText(it)) } ?: "Not declared")
                MetaRow("Service UUIDs", if (ad.serviceUuids.isEmpty()) "None" else ad.serviceUuids.joinToString("\n") { u ->
                    Uuids.short(u) + (Uuids.serviceName(u)?.let { " · $it" } ?: "")
                })
                MetaRow("Connectable", if (device?.connectable == true) "Yes" else "No (broadcast only)")
                MetaRow("Packets seen", (device?.packets ?: 0).toString())
            }
        }
        ad.manufacturer.forEach { (id, bytes) ->
            item {
                SectionCard {
                    Text("Manufacturer 0x%04X · %s".format(id, Decoders.companyName(id)), fontWeight = FontWeight.Bold)
                    Text(if (ascii) bytes.toAscii() else bytes.toHex(), fontFamily = Mono, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        ad.serviceData.forEach { (uuid, bytes) ->
            item {
                SectionCard {
                    Text("Service data ${Uuids.short(uuid)}" + (Uuids.serviceName(uuid)?.let { " · $it" } ?: ""), fontWeight = FontWeight.Bold)
                    Text(if (ascii) bytes.toAscii() else bytes.toHex(), fontFamily = Mono, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            SectionCard {
                Text("AD structures", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                ad.structures.forEach { s ->
                    Text("%02X  %s".format(s.type, AdParser.typeName(s.type)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(s.data.toHex(), fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Capture history · ${history.size}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                TextButton({ vm.clearHistory(); selected = null }) { Text("Clear") }
            }
        }
        items(history.size.coerceAtMost(100)) { i ->
            val r = history[i]
            val isSel = selected == i
            Card(
                Modifier.fillMaxWidth().clickable { selected = if (isSel) null else i },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row {
                        Text("#${history.size - i}  ${timeFmt.format(Date(r.time))}", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        Text("${r.rssi} dBm" + if (r.count > 1) " ×${r.count}" else "", style = MaterialTheme.typography.labelMedium, color = rssiColor(r.rssi))
                    }
                    Text(r.raw.toHex(), fontFamily = Mono, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    r.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun highlighted(raw: ByteArray, changed: List<Boolean>, ascii: Boolean): AnnotatedString {
    val hi = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    return buildAnnotatedString {
        raw.forEachIndexed { i, b ->
            val text = if (ascii) b.toAscii() else "%02X".format(b)
            if (changed.getOrNull(i) == true) withStyle(hi) { append(text) } else append(text)
            if (i != raw.lastIndex) append(if (ascii) "" else " ")
        }
    }
}

private fun Byte.toAscii(): String = byteArrayOf(this).toAscii()

// ---------------------------------------------------------------- GATT

@Composable
private fun GattTab(vm: AppViewModel, address: String, connectable: Boolean, session: GattSession?) {
    if (session == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (connectable) "Connect to discover services and read, write or subscribe to characteristics."
                else "This device only broadcasts (it is not connectable), so there is no GATT server to explore.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button({ vm.connectGatt(address) }, enabled = connectable) { Text("Connect") }
        }
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    val services by session.services.collectAsStateWithLifecycle()
    val values by session.values.collectAsStateWithLifecycle()
    val notifying by session.notifying.collectAsStateWithLifecycle()
    val mtu by session.mtu.collectAsStateWithLifecycle()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    var writeTarget by remember { mutableStateOf<BluetoothGattCharacteristic?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (val s = state) {
                                GattState.Idle -> "Idle"
                                is GattState.Connecting -> "Connecting… (attempt ${s.attempt})"
                                GattState.Discovering -> "Connected · discovering services…"
                                GattState.Ready -> "Connected · service discovery complete"
                                is GattState.Disconnected -> if (s.status == 0) "Disconnected" else "Disconnected (GATT status ${s.status})"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state == GattState.Ready) Text("MTU $mtu · ${services.size} services", style = MaterialTheme.typography.bodySmall)
                    }
                    when (state) {
                        is GattState.Disconnected -> TextButton({ vm.connectGatt(address) }) { Text("Reconnect") }
                        else -> TextButton({ vm.disconnectGatt() }) { Text("Disconnect") }
                    }
                }
            }
        }
        items(services, key = { "${it.uuid}${it.instanceId}" }) { svc ->
            val key = "${svc.uuid}${svc.instanceId}"
            val open = expanded[key] ?: (services.size <= 3)
            SectionCard {
                Row(Modifier.fillMaxWidth().clickable { expanded[key] = !open }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (svc.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) "Primary Service" else "Secondary Service",
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                        Text(Uuids.serviceName(svc.uuid.toString()) ?: "Unknown Service", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(svc.uuid.toString().uppercase(), fontFamily = Mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${svc.characteristics.size} characteristics", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                if (open) svc.characteristics.forEach { c ->
                    Spacer(Modifier.height(8.dp))
                    CharacteristicRow(c, values[charKey(c)], charKey(c) in notifying,
                        onRead = { scope.launch { session.read(c) } },
                        onWrite = { writeTarget = c },
                        onNotify = { on -> scope.launch { session.setNotify(c, on) } })
                }
            }
        }
    }

    writeTarget?.let { c ->
        WriteDialog(c, onDismiss = { writeTarget = null }) { bytes, withResponse ->
            writeTarget = null
            scope.launch { session.write(c, bytes, withResponse) }
        }
    }
}

@Composable
private fun CharacteristicRow(
    c: BluetoothGattCharacteristic, last: com.btdebug.btdebugger.ble.LastValue?, notifying: Boolean,
    onRead: () -> Unit, onWrite: () -> Unit, onNotify: (Boolean) -> Unit,
) {
    val p = c.properties
    val canRead = p and BluetoothGattCharacteristic.PROPERTY_READ != 0
    val canWrite = p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    val canNotify = p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    Column(Modifier.fillMaxWidth()) {
        HorizontalLine()
        val uuid = c.uuid.toString()
        Text(
            (Uuids.characteristicName(uuid) ?: "Characteristic") + if (uuid.startsWith("0000") && uuid.endsWith("-0000-1000-8000-00805f9b34fb")) " (${Uuids.short(uuid)})" else "",
            fontWeight = FontWeight.SemiBold,
        )
        Text(uuid.uppercase(), fontFamily = Mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            if (canRead) PropTag("READ")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) PropTag("WRITE")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) PropTag("WRITE NO RESP")
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) PropTag("NOTIFY")
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) PropTag("INDICATE")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRead) OutlinedButton(onRead) { Text("Read") }
            if (canWrite) OutlinedButton(onWrite) { Text("Write") }
            if (canNotify) {
                if (notifying) Button({ onNotify(false) }) { Text("Stop") } else OutlinedButton({ onNotify(true) }) { Text("Subscribe") }
            }
        }
        if (last != null) {
            val v = last.value
            Text(
                buildString {
                    append(last.kind.name.lowercase().replaceFirstChar { it.uppercase() })
                    if (v != null) append(" · ${v.toHex()}")
                    if (last.status != 0) append(" · status=${last.status}")
                },
                fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                color = if (last.kind == GattKind.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (v != null && v.isNotEmpty()) {
                Text("ASCII: ${v.toAscii()}", fontFamily = Mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Uuids.describeValue(uuid, v)?.let { Text("= $it", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun HorizontalLine() {
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun PropTag(text: String) = Tag(text, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)

@Composable
private fun WriteDialog(c: BluetoothGattCharacteristic, onDismiss: () -> Unit, onSend: (ByteArray, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var hex by remember { mutableStateOf(true) }
    val canResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    val canNoResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    var withResponse by remember { mutableStateOf(canResponse) }
    val bytes = if (hex) text.parseHex() else text.toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write ${Uuids.characteristicName(c.uuid.toString()) ?: Uuids.short(c.uuid.toString())}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(hex, { hex = true }, { Text("HEX") })
                    FilterChip(!hex, { hex = false }, { Text("Text") })
                }
                OutlinedTextField(
                    text, { text = it }, label = { Text(if (hex) "e.g. 01 A0 FF" else "UTF-8 text") },
                    isError = text.isNotEmpty() && bytes == null, supportingText = { bytes?.let { Text("${it.size} bytes · ${it.toHex()}") } },
                )
                if (canResponse && canNoResponse) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(withResponse, { withResponse = true }, { Text("With response") })
                    FilterChip(!withResponse, { withResponse = false }, { Text("No response") })
                }
            }
        },
        confirmButton = { TextButton({ bytes?.let { onSend(it, withResponse) } }, enabled = bytes != null) { Text("Send") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- timeline

private class TimelineItem(val time: Long, val label: String, val color: Color, val text: String, val sub: String? = null)

@Composable
private fun TimelineTab(history: List<AdRecord>, session: GattSession?) {
    val events by (session?.events ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsStateWithLifecycle()
    val primary = MaterialTheme.colorScheme.primary
    val items = remember(history, events) {
        (history.map { TimelineItem(it.time, "ADV", primary, it.raw.toHex(), "${it.rssi} dBm" + if (it.count > 1) " ×${it.count}" else "") } +
            events.map {
                val color = when (it.kind) {
                    GattKind.ERROR -> SignalPoor; GattKind.WRITE -> SignalFair; GattKind.NOTIFY, GattKind.INDICATE -> SignalGood
                    else -> Color(0xFF9AA0B5)
                }
                TimelineItem(it.time, it.kind.name, color, it.value?.toHex() ?: it.note.orEmpty(),
                    listOfNotNull(it.uuid?.let { u -> Uuids.characteristicName(u) ?: Uuids.short(u) }, it.takeIf { e -> e.status != 0 }?.let { e -> "status=${e.status}" },
                        it.note.takeIf { _ -> it.value != null }).joinToString(" · ").ifEmpty { null })
            }).sortedByDescending { it.time }
    }
    if (items.isEmpty()) {
        Text("No packets yet.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items.take(500)) { e ->
            Row(verticalAlignment = Alignment.Top) {
                Text(timeFmt.format(Date(e.time)), fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.width(92.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Tag(e.label, e.color.copy(alpha = 0.2f), e.color)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(e.text, fontFamily = Mono, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    e.sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
