package com.buxingjie.localai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

/**
 * 图1 风格取色器：色板（饱和度/明度）+ 竖向色相条 + 横向透明度条 + 十六进制输入。
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initialColor: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = Color(initialColor)
    var hue by remember { mutableFloatStateOf(start.hue()) }
    var saturation by remember { mutableFloatStateOf(start.saturation()) }
    var value by remember { mutableFloatStateOf(start.value()) }
    var alpha by remember { mutableFloatStateOf(start.alpha) }
    var hexInput by remember {
        mutableStateOf(initialColor.toULong().toString(16).uppercase().padStart(8, '0'))
    }

    val current = Color.hsv(hue, saturation, value, alpha)

    Dialog(onDismissRequest = onDismiss) {
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    // 饱和度/明度色板
                    SaturationValuePanel(
                        hue = hue,
                        saturation = saturation,
                        value = value,
                        modifier = Modifier
                            .weight(1f)
                            .height(180.dp),
                        onChange = { s, v ->
                            saturation = s
                            value = v
                            hexInput = current.copy(alpha = 1f).toHexWith(alpha)
                        },
                    )
                    Spacer(Modifier.width(14.dp))
                    // 竖向色相条
                    HueBar(
                        hue = hue,
                        modifier = Modifier
                            .width(22.dp)
                            .height(180.dp),
                        onChange = {
                            hue = it
                            hexInput = current.copy(alpha = 1f).toHexWith(alpha)
                        },
                    )
                    Spacer(Modifier.width(16.dp))
                    // 旧色 → 新色
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.height(180.dp),
                    ) {
                        Box(
                            Modifier
                                .size(width = 44.dp, height = 34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(start)
                                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        )
                        Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(
                            Modifier
                                .size(width = 44.dp, height = 34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(current)
                                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 十六进制输入
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { text ->
                        val clean = text.trim().removePrefix("#").uppercase()
                        if (clean.length <= 8 && clean.all { it.isDigit() || it in 'A'..'F' }) {
                            hexInput = clean
                            if (clean.length == 8) {
                                runCatching {
                                    val parsed = clean.toULong(16).toLong()
                                    val c = Color(parsed)
                                    hue = c.hue()
                                    saturation = c.saturation()
                                    value = c.value()
                                    alpha = c.alpha
                                }
                            }
                        }
                    },
                    label = { Text("颜色值（AARRGGBB）") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                // 透明度条（棋盘格底 + 渐变）
                AlphaBar(
                    color = current.copy(alpha = 1f),
                    alpha = alpha,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp),
                    onChange = {
                        alpha = it
                        hexInput = current.toHex()
                    },
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onConfirm(current.toHex().toULong(16).toLong()) }) {
                        Text("确认", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SaturationValuePanel(
    hue: Float,
    saturation: Float,
    value: Float,
    modifier: Modifier,
    onChange: (Float, Float) -> Unit,
) {
    val pureHue = Color.hsv(hue, 1f, 1f)
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(hue) {
                detectTapGestures { offset ->
                    onChange(
                        (offset.x / size.width).coerceIn(0f, 1f),
                        (1f - offset.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(
                        (change.position.x / size.width).coerceIn(0f, 1f),
                        (1f - change.position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, pureHue)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val x = saturation * size.width
            val y = (1f - value) * size.height
            drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(x, y), style = Stroke(width = 2.5.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.4f), radius = 10.dp.toPx(), center = Offset(x, y), style = Stroke(width = 1.dp.toPx()))
        }
    }
}

@Composable
private fun HueBar(hue: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    val colors = listOf(0, 60, 120, 180, 240, 300, 360).map { Color.hsv(it.toFloat(), 1f, 1f) }
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange((offset.y / size.height * 360f).coerceIn(0f, 359.9f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange((change.position.y / size.height * 360f).coerceIn(0f, 359.9f))
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.verticalGradient(colors))
            val y = hue / 360f * size.height
            drawRect(
                color = Color.White,
                topLeft = Offset(0f, (y - 2.dp.toPx()).coerceAtLeast(0f)),
                size = Size(size.width, 4.dp.toPx()),
            )
        }
    }
}

@Composable
private fun AlphaBar(color: Color, alpha: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            // 棋盘格底
            val cell = 6.dp.toPx()
            val cols = (size.width / cell).roundToInt() + 1
            val rows = (size.height / cell).roundToInt() + 1
            for (r in 0..rows) {
                for (c in 0..cols) {
                    if ((r + c) % 2 == 0) {
                        drawRect(
                            color = Color(0x33000000),
                            topLeft = Offset(c * cell, r * cell),
                            size = Size(cell, cell),
                        )
                    }
                }
            }
            drawRect(Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color)))
            val x = alpha * size.width
            drawRect(
                color = Color.White,
                topLeft = Offset((x - 2.dp.toPx()).coerceAtLeast(0f), 0f),
                size = Size(4.dp.toPx(), size.height),
            )
        }
    }
}

private fun Color.hue(): Float = toHsv()[0]
private fun Color.saturation(): Float = toHsv()[1]
private fun Color.value(): Float = toHsv()[2]

private fun Color.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
        hsv,
    )
    return hsv
}

private fun Color.toHex(): String {
    val a = (alpha * 255).roundToInt()
    val r = (red * 255).roundToInt()
    val g = (green * 255).roundToInt()
    val b = (blue * 255).roundToInt()
    return "%02X%02X%02X%02X".format(a, r, g, b)
}

private fun Color.toHexWith(alpha: Float): String = copy(alpha = alpha).toHex()
