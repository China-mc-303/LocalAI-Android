package com.buxingjie.localai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

val GlassBorder = Color(0x30FFFFFF)
val GlassBorderLight = Color(0x33000000)
val GlassFillTop = Color(0x22FFFFFF)
val GlassFillBottom = Color(0x0AFFFFFF)
val GlassTint = Color(0x14FFFFFF)

/** 白天模式时玻璃卡片改用白色底 + 深色描边。 */
val LocalGlassLight = compositionLocalOf { false }

/** 液态玻璃背景：支持自定义主题色、强调色、透明度与壁纸。 */
@Composable
fun LiquidBackground(
    modifier: Modifier = Modifier,
    primaryColor: Color = Color(0xFF2DD4BF),
    accentColor: Color = Color(0xFF7DD3FC),
    opacity: Float = 0.16f,
    wallpaperPath: String? = null,
    wallpaperBlur: Int = 0,
    light: Boolean = LocalGlassLight.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val baseGradient = if (light) {
        listOf(Color(0xFFE8EEF5), Color(0xFFF4F7FB), Color(0xFFE3EBF3))
    } else {
        listOf(Color(0xFF05090E), Color(0xFF0B1822), Color(0xFF081019))
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(baseGradient))
    ) {
        // 壁纸层
        if (!wallpaperPath.isNullOrBlank()) {
            val file = java.io.File(wallpaperPath)
            if (file.exists()) {
                val bitmap = remember(wallpaperPath) {
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                }
                if (bitmap != null) {
                    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        // 光斑层
        Canvas(Modifier.fillMaxSize()) {
            val r1 = size.minDimension * 0.9f
            val c1 = Offset(size.width * 0.86f, -size.height * 0.04f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(primaryColor.copy(alpha = opacity * 1.4f), Color(0x00000000)),
                    center = c1,
                    radius = r1,
                ),
                radius = r1,
                center = c1,
            )
            val r2 = size.minDimension * 0.8f
            val c2 = Offset(-size.width * 0.1f, size.height * 0.62f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accentColor.copy(alpha = opacity * 1.2f), Color(0x00000000)),
                    center = c2,
                    radius = r2,
                ),
                radius = r2,
                center = c2,
            )
            val r3 = size.minDimension * 0.7f
            val c3 = Offset(size.width * 0.55f, size.height * 1.02f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(primaryColor.copy(alpha = opacity), Color(0x00000000)),
                    center = c3,
                    radius = r3,
                ),
                radius = r3,
                center = c3,
            )
        }
        content()
    }
}

/** 半透明磨砂玻璃卡片，透明度可配置。 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    opacity: Float = 0.16f,
    light: Boolean = LocalGlassLight.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val fillTop = if (light) Color.White.copy(alpha = (opacity * 2.4f + 0.3f).coerceAtMost(0.85f))
    else Color.White.copy(alpha = opacity)
    val fillBottom = if (light) Color.White.copy(alpha = (opacity * 1.6f + 0.2f).coerceAtMost(0.7f))
    else Color.White.copy(alpha = opacity * 0.4f)
    val border = if (light) GlassBorderLight else GlassBorder
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(fillTop, fillBottom)))
            .border(1.dp, border, shape)
    ) {
        content()
    }
}
