package com.buxingjie.localai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0F4F48),
    onPrimaryContainer = Color(0xFFC6FFF7),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF00202F),
    secondaryContainer = Color(0xFF134E63),
    onSecondaryContainer = Color(0xFFD9F3FF),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF111820),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B2530),
    onSurfaceVariant = Color(0xFFB9C6D3),
    surfaceContainer = Color(0xFF141D26),
    surfaceContainerHigh = Color(0xFF1B2530),
    outline = Color(0xFF3A4756),
    error = Color(0xFFFF8A80),
)

@Composable
fun LocalAiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
