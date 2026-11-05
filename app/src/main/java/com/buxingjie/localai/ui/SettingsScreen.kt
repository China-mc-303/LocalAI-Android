package com.buxingjie.localai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.buxingjie.localai.BuildConfig
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
    onOpenModels: () -> Unit,
) {
    val maxThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        SettingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("办公助手模式", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "更擅长周报、会议纪要、邮件和翻译；切换后下一句生效",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.workMode,
                    onCheckedChange = { viewModel.toggleWorkMode(it) },
                )
            }
        }

        SettingCard {
            SliderSetting(
                title = "温度",
                valueText = String.format("%.1f", state.temperature),
                hint = "数值越高回答越发散，越低越稳定",
            ) {
                Slider(
                    value = state.temperature,
                    onValueChange = { viewModel.setTemperature((it * 10).roundToInt() / 10f) },
                    valueRange = 0.1f..1.2f,
                )
            }
            HorizontalDivider()
            SliderSetting(
                title = "最大回复长度",
                valueText = "${state.maxTokens} tokens",
                hint = "越大回答越长，生成时间也越久",
            ) {
                Slider(
                    value = state.maxTokens.toFloat(),
                    onValueChange = { viewModel.setMaxTokens(alignTokens(it.roundToInt())) },
                    valueRange = 256f..2048f,
                )
            }
            HorizontalDivider()
            SliderSetting(
                title = "线程数",
                valueText = "${state.threads}",
                hint = "线程越多速度越快，但更耗电和发热",
            ) {
                Slider(
                    value = state.threads.toFloat(),
                    onValueChange = { viewModel.setThreads(it.roundToInt()) },
                    valueRange = 2f..maxThreads.toFloat(),
                    steps = (maxThreads - 3).coerceAtLeast(0),
                )
            }
        }

        SettingCard {
            Text("上下文长度", style = MaterialTheme.typography.titleSmall)
            Text(
                "需要重新加载模型才生效，越大越占内存",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4096, 8192, 16384).forEach { size ->
                    FilterChip(
                        selected = state.contextSize == size,
                        onClick = { viewModel.setContextSize(size) },
                        label = { Text(size.toString()) },
                    )
                }
            }
        }

        SettingCard {
            Text("模型", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    state.modelLoading -> "正在加载…"
                    state.modelLoaded -> "${state.modelName} 已加载"
                    else -> "尚未加载模型"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (state.modelInfo.isNotBlank()) {
                Text(
                    state.modelInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenModels) { Text("模型管理") }
                if (state.modelLoaded) {
                    OutlinedButton(onClick = { viewModel.unloadModel() }) { Text("卸载模型") }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "关于",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "本地AI v${BuildConfig.VERSION_NAME}\n" +
                "步行街工作室\n\n" +
                "• 模型推理、文件解析、聊天记录全部在手机本地完成，不上传服务器。\n" +
                "• 首次使用需要联网下载模型或自行导入 GGUF 文件。\n" +
                "• 语音输入调用手机自带的识别服务，部分机型可能需要联网。\n" +
                "• 小模型能力有限，复杂问题请配合文件资料使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    valueText: String,
    hint: String,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    content()
}

private fun alignTokens(value: Int): Int {
    return ((value / 128) * 128).coerceIn(256, 2048)
}
