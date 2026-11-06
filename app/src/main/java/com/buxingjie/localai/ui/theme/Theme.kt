package com.buxingjie.localai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.buxingjie.localai.ui.LocalGlassLight

@Composable
fun LocalAiTheme(
    primary: Long = 0xFF2DD4BF,
    accent: Long = 0xFF7DD3FC,
    opacity: Float = 0.16f,
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val primaryColor = Color(primary)
    val accentColor = Color(accent)

    val colors = if (darkTheme) {
        darkColorScheme(
            primary = primaryColor,
            onPrimary = Color(0xFF00201C),
            primaryContainer = primaryColor.copy(alpha = 0.3f),
            onPrimaryContainer = primaryColor,
            secondary = accentColor,
            onSecondary = Color(0xFF00202F),
            secondaryContainer = accentColor.copy(alpha = 0.3f),
            onSecondaryContainer = accentColor,
            background = Color(0xFF05090E),
            onBackground = Color(0xFFE6EDF3),
            surface = Color.White.copy(alpha = opacity),
            onSurface = Color(0xFFE6EDF3),
            surfaceVariant = Color.White.copy(alpha = (opacity * 1.5f).coerceAtMost(0.35f)),
            onSurfaceVariant = Color(0xFFB9C6D3),
            surfaceContainer = Color.White.copy(alpha = (opacity * 0.8f).coerceAtMost(0.28f)),
            surfaceContainerHigh = Color.White.copy(alpha = (opacity * 1.2f).coerceAtMost(0.32f)),
            outline = Color(0x40FFFFFF),
            error = Color(0xFFFF8A80),
        )
    } else {
        lightColorScheme(
            primary = primaryColor,
            onPrimary = Color.White,
            primaryContainer = primaryColor.copy(alpha = 0.22f),
            onPrimaryContainer = primaryColor.darken(),
            secondary = accentColor,
            onSecondary = Color.White,
            secondaryContainer = accentColor.copy(alpha = 0.22f),
            onSecondaryContainer = accentColor.darken(),
            background = Color(0xFFF2F5F9),
            onBackground = Color(0xFF1B2833),
            surface = Color.White.copy(alpha = 0.45f + opacity),
            onSurface = Color(0xFF1B2833),
            surfaceVariant = Color.White.copy(alpha = 0.35f + opacity),
            onSurfaceVariant = Color(0xFF54646F),
            surfaceContainer = Color.White.copy(alpha = 0.4f + opacity),
            surfaceContainerHigh = Color.White.copy(alpha = 0.45f + opacity),
            outline = Color(0x33000000),
            error = Color(0xFFD32F2F),
        )
    }

    CompositionLocalProvider(LocalGlassLight provides !darkTheme) {
        MaterialTheme(
            colorScheme = colors,
            content = content,
        )
    }
}

/** 让主题色在白天模式下作为文字颜色更深一些。 */
private fun Color.darken(): Color = Color(
    red * 0.75f,
    green * 0.75f,
    blue * 0.75f,
    alpha,
)
