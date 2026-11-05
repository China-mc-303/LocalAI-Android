package com.buxingjie.localai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

val GlassBorder = Color(0x30FFFFFF)
val GlassFillTop = Color(0x22FFFFFF)
val GlassFillBottom = Color(0x0AFFFFFF)
val GlassTint = Color(0x14FFFFFF)

/** 液态玻璃背景：深色渐变 + 柔和的彩色光斑。 */
@Composable
fun LiquidBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF05090E), Color(0xFF0B1822), Color(0xFF081019)),
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r1 = size.minDimension * 0.9f
            val c1 = Offset(size.width * 0.86f, -size.height * 0.04f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x662DD4BF), Color(0x00000000)),
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
                    listOf(Color(0x557DD3FC), Color(0x00000000)),
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
                    listOf(Color(0x448A7DD3), Color(0x00000000)),
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

/** 半透明磨砂玻璃卡片。 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(GlassFillTop, GlassFillBottom))
            )
            .border(1.dp, GlassBorder, shape)
    ) {
        content()
    }
}
