@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.btdebug.btdebugger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.btdebug.btdebugger.AppViewModel
import com.btdebug.btdebugger.SortMode
import com.btdebug.btdebugger.ble.DeviceKind
import com.btdebug.btdebugger.ble.ScanDevice
import com.btdebug.btdebugger.data.FilterSpec

@Composable
fun HomeScreen(vm: AppViewModel) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val scanning by vm.scanner.scanning.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.startScan() }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { vm.tab.value = 0 }, { Icon(Icons.Default.Build, null) }, label = { Text("Debug") })
                NavigationBarItem(tab == 1, { vm.tab.value = 1 }, { Icon(Icons.Default.Apps, null) }, label = { Text("Apps") })
            }
        },
        floatingActionButton = {
            if (tab == 0) ExtendedFloatingActionButton(
                onClick = { vm.toggleScan() },
                icon = { Icon(if (scanning) Icons.Default.Stop else Icons.Default.BluetoothSearching, null) },
                text = { Text(if (scanning) "Stop scanning" else "Start scanning") },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) { if (tab == 0) ScanScreen(vm) else AppsScreens.AppsList(vm) }
    }
}

@Composable
private fun ScanScreen(vm: AppViewModel) {
    val devices by vm.shown.collectAsStateWithLifecycle()
    val pins by vm.pinnedDevices.collectAsStateWithLifecycle()
    val all by vm.scanner.devices.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val error by vm.scanner.error.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    var showFilter by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("BTDebug", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            val identified = all.count { it.identified != null }
            val shownNote = if (devices.size != all.size) " · showing ${devices.size}" else ""
            Text("${all.size} devices found · $identified identified$shownNote", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it }, singleLine = true,
                placeholder = { Text("Search name or MAC") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ vm.query.value = "" }) { Icon(Icons.Default.Close, "Clear") } },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showFilter = true }) {
                Icon(Icons.Default.FilterList, null)
                Spacer(Modifier.width(6.dp))
                Text(if (filter.isActive) "Filter •" else "Filter")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${sort.label} · Order held", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { vm.reorderDevices() }, enabled = devices.isNotEmpty()) { Text("Re-sort") }
        }
        error?.let {
            Card(
                Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 100.dp)) {
            items(devices, key = { it.address }) {
                DeviceRow(it, it.address in pins, { vm.toggleDevicePin(it.address) }) { vm.openDevice(it.address) }
            }
            if (devices.isEmpty()) item {
                Text(
                    if (all.isEmpty()) "Scanning… devices will appear here." else "No devices match the current filter.",
                    Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showFilter) FilterSheet(vm) { showFilter = false }
}

@Composable
private fun DeviceRow(d: ScanDevice, pinned: Boolean, onPin: () -> Unit, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            KindBadge(d.kind)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (pinned) Tag("Pinned", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    if (d.connectable) Tag("Connectable", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    else Tag("Broadcast only", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    d.decoded.firstOrNull()?.let { Tag(it.protocol, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) }
                    if (d.isScale) Tag("App-creatable", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(d.address, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                d.identified?.let { Text("Identified: $it", color = SignalGood, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Column(horizontalAlignment = Alignment.End) {
                RssiIndicator(d.rssi)
                IconToggleButton(checked = pinned, onCheckedChange = { onPin() }) {
                    Icon(
                        if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = "${if (pinned) "Unpin" else "Pin"} device ${d.address}",
                        tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sort by", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortMode.entries.forEach { m -> FilterChip(sort == m, { vm.sort.value = m }, { Text(m.label) }) }
            }
            SwitchRow("Connectable only", filter.connectableOnly) { vm.filter.value = filter.copy(connectableOnly = it) }
            SwitchRow("Named devices only", filter.namedOnly) { vm.filter.value = filter.copy(namedOnly = it) }
            SwitchRow("Decodable broadcasts only", filter.decodedOnly) { vm.filter.value = filter.copy(decodedOnly = it) }
            Text(if (filter.minRssi <= -100) "Minimum signal: any" else "Minimum signal: ${filter.minRssi} dBm", fontWeight = FontWeight.SemiBold)
            Slider(
                value = filter.minRssi.toFloat(), onValueChange = { vm.filter.value = filter.copy(minRssi = it.toInt()) },
                valueRange = -100f..-30f,
            )
            Text("Device types", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceKind.entries.forEach { k ->
                    FilterChip(k in filter.kinds, {
                        vm.filter.value = filter.copy(kinds = if (k in filter.kinds) filter.kinds - k else filter.kinds + k)
                    }, { Text(k.label) })
                }
            }
            Text("Presets", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    InputChip(false, { vm.filter.value = p.spec }, { Text(p.name) },
                        trailingIcon = { Icon(Icons.Default.Delete, "Delete preset", Modifier.clickable { vm.deletePreset(p.name) }) })
                }
                if (presets.isEmpty()) Text("No saved presets yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.filter.value = FilterSpec() }, enabled = filter.isActive) { Text("Reset") }
                OutlinedButton({ saving = true }, enabled = filter.isActive) { Text("Save as preset") }
            }
        }
    }
    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save filter preset") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Preset name") }, singleLine = true) },
            confirmButton = { TextButton({ if (name.isNotBlank()) vm.savePreset(name.trim()); saving = false }) { Text("Save") } },
            dismissButton = { TextButton({ saving = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked, onChange)
    }
}
