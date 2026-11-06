package com.buxingjie.localai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun LocalAiTheme(
    primary: Long = 0xFF2DD4BF,
    accent: Long = 0xFF7DD3FC,
    opacity: Float = 0.16f,
    content: @Composable () -> Unit,
) {
    val primaryColor = Color(primary)
    val accentColor = Color(accent)
    val onPrimary = Color(0xFF00201C)
    val onSecondary = Color(0xFF00202F)
    val surface = Color.White.copy(alpha = opacity)
    val surfaceVariant = Color.White.copy(alpha = (opacity * 1.5f).coerceAtMost(0.35f))
    val surfaceContainer = Color.White.copy(alpha = (opacity * 0.8f).coerceAtMost(0.28f))
    val surfaceContainerHigh = Color.White.copy(alpha = (opacity * 1.2f).coerceAtMost(0.32f))

    val colors = darkColorScheme(
        primary = primaryColor,
        onPrimary = onPrimary,
        primaryContainer = primaryColor.copy(alpha = 0.3f),
        onPrimaryContainer = primaryColor,
        secondary = accentColor,
        onSecondary = onSecondary,
        secondaryContainer = accentColor.copy(alpha = 0.3f),
        onSecondaryContainer = accentColor,
        background = Color(0xFF05090E),
        onBackground = Color(0xFFE6EDF3),
        surface = surface,
        onSurface = Color(0xFFE6EDF3),
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = Color(0xFFB9C6D3),
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        outline = Color(0x40FFFFFF),
        error = Color(0xFFFF8A80),
    )

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
