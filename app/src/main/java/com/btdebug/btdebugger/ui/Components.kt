package com.btdebug.btdebugger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btdebug.btdebugger.ble.DeviceKind

fun kindIcon(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.PHONE -> Icons.Default.Smartphone
    DeviceKind.COMPUTER -> Icons.Default.Computer
    DeviceKind.WEARABLE -> Icons.Default.Watch
    DeviceKind.AUDIO -> Icons.Default.Headphones
    DeviceKind.SCALE -> Icons.Default.MonitorWeight
    DeviceKind.BEACON -> Icons.Default.PinDrop
    DeviceKind.TRACKER -> Icons.Default.PinDrop
    DeviceKind.SENSOR -> Icons.Default.Thermostat
    DeviceKind.APPLE -> Icons.Default.Devices
    DeviceKind.OTHER -> Icons.Default.Bluetooth
}

fun rssiColor(rssi: Int): Color = when {
    rssi >= -70 -> SignalGood
    rssi >= -85 -> SignalFair
    else -> SignalPoor
}

@Composable
fun KindBadge(kind: DeviceKind) {
    Box(
        Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(kindIcon(kind), kind.label, tint = MaterialTheme.colorScheme.onSurface) }
}

@Composable
fun RssiIndicator(rssi: Int) {
    val color = rssiColor(rssi)
    val level = when { rssi >= -60 -> 4; rssi >= -70 -> 3; rssi >= -80 -> 2; rssi >= -90 -> 1; else -> 0 }
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.End) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(20.dp)) {
            for (i in 1..4) Box(
                Modifier.width(5.dp).height((5 + i * 4).dp)
                    .background(if (i <= level) color else color.copy(alpha = 0.25f), RoundedCornerShape(1.dp)),
            )
        }
        Text("$rssi dBm", color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Mono)
    }
}

@Composable
fun Tag(text: String, container: Color, content: Color) {
    Text(
        text, color = content, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(container, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) { androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), content = content) }
}
