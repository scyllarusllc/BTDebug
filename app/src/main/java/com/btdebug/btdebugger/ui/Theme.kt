package com.btdebug.btdebugger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

val Mono = FontFamily.Monospace
val SignalGood = Color(0xFF4CAF50)
val SignalFair = Color(0xFFFFA726)
val SignalPoor = Color(0xFFEF5350)

private val Dark = darkColorScheme(
    primary = Color(0xFF4C8DFF), onPrimary = Color(0xFF00184A),
    primaryContainer = Color(0xFF1E3A78), onPrimaryContainer = Color(0xFFD9E3FF),
    secondaryContainer = Color(0xFF2B3550), onSecondaryContainer = Color(0xFFDCE4FF),
    background = Color(0xFF0B0D12), onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF0B0D12), onSurface = Color(0xFFE6E8EF),
    surfaceVariant = Color(0xFF232733), onSurfaceVariant = Color(0xFFA9AEBD),
    surfaceContainer = Color(0xFF14171F), surfaceContainerHigh = Color(0xFF1B1F2A), surfaceContainerHighest = Color(0xFF242938),
    outline = Color(0xFF4A5062), outlineVariant = Color(0xFF2E3342),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1F5FE0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E3FF), onPrimaryContainer = Color(0xFF001A47),
    secondaryContainer = Color(0xFFDCE4FF), onSecondaryContainer = Color(0xFF111B33),
    background = Color(0xFFF6F7FB), surface = Color(0xFFF6F7FB),
    surfaceContainer = Color(0xFFEDEFF6), surfaceContainerHigh = Color(0xFFFFFFFF), surfaceContainerHighest = Color(0xFFE4E7F0),
)

@Composable
fun BTDebugTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
