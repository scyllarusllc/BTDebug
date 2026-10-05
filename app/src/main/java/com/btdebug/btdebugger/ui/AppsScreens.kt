@file:OptIn(ExperimentalMaterial3Api::class)

package com.btdebug.btdebugger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.btdebug.btdebugger.AppViewModel
import com.btdebug.btdebugger.ApiSyncState
import com.btdebug.btdebugger.data.ApiSettings
import com.btdebug.btdebugger.data.Reading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val KG_TO_LB = 2.2046226218

object AppsScreens {
    @Composable
    fun AppsList(vm: AppViewModel) {
        val apps by vm.apps.collectAsStateWithLifecycle()
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Apps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Local dashboards built from live BLE broadcasts", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (apps.isEmpty()) {
                Text(
                    "No apps yet. Open a scale in the Debug tab (look for the \"App-creatable\" tag) and tap \"Create app\".",
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(apps, key = { it.id }) { app ->
                    val last = remember(app.id, apps) { vm.lastReading(app.id) }
                    val count = remember(app.id, apps) { vm.readingCount(app.id) }
                    Card(
                        onClick = { vm.openWeightApp(app.id) }, shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (last == null) "No readings yet" else "${formatWeight(last.kg, app.useLb)} · $count readings",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton({ vm.deleteWeightApp(app.id) }) { Icon(Icons.Default.Delete, "Delete app") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun WeightAppScreen(vm: AppViewModel, id: String) {
        val apps by vm.apps.collectAsStateWithLifecycle()
        val app = apps.firstOrNull { it.id == id }
        val readings by vm.readings.collectAsStateWithLifecycle()
        val live by vm.live.collectAsStateWithLifecycle()
        val scanning by vm.scanner.scanning.collectAsStateWithLifecycle()
        val syncMap by vm.apiSync.collectAsStateWithLifecycle()
        val settingsMap by vm.apiSettings.collectAsStateWithLifecycle()
        val sync = syncMap[id] ?: ApiSyncState()
        val settings = settingsMap[id] ?: ApiSettings()
        val lb = app?.useLb == true

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(app?.name ?: "Weight app", fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    !scanning -> "Scanner stopped"
                                    live.kg == null -> "Waiting for a matching scale to advertise — step on it"
                                    else -> "Receiving broadcasts"
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = { IconButton({ vm.openAppSettings(id) }) { Icon(Icons.Default.Settings, "App settings") } },
                )
            },
        ) { pad ->
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SectionCard {
                        val shown = live.kg ?: readings.lastOrNull()?.kg
                        Text(
                            shown?.let { "%.2f".format(if (lb) it * KG_TO_LB else it) } ?: "—.——",
                            Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                            fontSize = 72.sp, fontWeight = FontWeight.Medium, fontFamily = Mono, color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            when {
                                live.kg == null -> "Last saved reading"
                                live.stable -> "Reading stabilized · saved automatically"
                                else -> "Measuring…"
                            },
                            Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            FilterChip(!lb, { vm.setUnit(id, false) }, { Text("kg") })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(lb, { vm.setUnit(id, true) }, { Text("lb") })
                        }
                    }
                }
                item {
                    SectionCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("API uploads", fontWeight = FontWeight.Bold)
                            TextButton({ vm.openAppSettings(id) }) { Text("Settings") }
                        }
                        Text(
                            if (settings.url.isBlank()) "Set an API URL in App settings to upload your readings." else sync.message,
                            color = if (sync.busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (settings.url.isNotBlank()) {
                            Text(
                                "${if (settings.enabled) "Auto-upload on" else "Auto-upload off"} · ${sync.pending} queued",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { TrendCard(readings, lb) }
                item { StatsRow(readings, lb) }
                item { Text("History · ${readings.size} entries", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(readings.asReversed(), key = { it.time }) { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatWeight(r.kg, lb), fontFamily = Mono, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        Text(SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(r.time)), style = MaterialTheme.typography.bodySmall)
                        IconButton({ vm.deleteReading(id, r) }) { Icon(Icons.Default.Close, "Delete reading") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private fun formatWeight(kg: Double, lb: Boolean) = if (lb) "%.2f lb".format(kg * KG_TO_LB) else "%.2f kg".format(kg)

@Composable
private fun StatsRow(readings: List<Reading>, lb: Boolean) {
    if (readings.size < 2) return
    val f = if (lb) KG_TO_LB else 1.0
    val unit = if (lb) "lb" else "kg"
    val change = (readings.last().kg - readings.first().kg) * f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "Min" to "%.1f $unit".format(readings.minOf { it.kg } * f),
            "Max" to "%.1f $unit".format(readings.maxOf { it.kg } * f),
            "Average" to "%.1f $unit".format(readings.map { it.kg }.average() * f),
            "Change" to "%+.1f $unit".format(change),
        ).forEach { (label, value) ->
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(10.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono)
                }
            }
        }
    }
}

@Composable
private fun TrendCard(readings: List<Reading>, lb: Boolean) {
    SectionCard {
        Text("Trend", fontWeight = FontWeight.Bold)
        if (readings.size < 2) {
            Text(
                "The trend appears after at least two records", Modifier.fillMaxWidth().height(140.dp).padding(top = 48.dp),
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val f = if (lb) KG_TO_LB else 1.0
        val pts = readings.takeLast(40).map { it.kg * f }
        val lo = pts.min(); val hi = pts.max()
        val span = (hi - lo).coerceAtLeast(0.5)
        val line = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(160.dp).padding(top = 12.dp)) {
            val pad = 8.dp.toPx()
            fun x(i: Int) = pad + (size.width - 2 * pad) * i / (pts.size - 1)
            fun y(v: Double) = (pad + (size.height - 2 * pad) * (1 - (v - lo) / span)).toFloat()
            val path = Path().apply { pts.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            val fill = Path().apply {
                addPath(path); lineTo(x(pts.size - 1), size.height); lineTo(x(0), size.height); close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            pts.forEachIndexed { i, v -> drawCircle(line, 4.dp.toPx(), Offset(x(i), y(v))) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("%.1f".format(lo), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.1f".format(hi), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
