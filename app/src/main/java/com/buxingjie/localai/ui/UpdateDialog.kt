package com.buxingjie.localai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.buxingjie.localai.BuildConfig
import kotlin.math.roundToInt

/** 检测到 GitHub 新版本时弹出的玻璃风格更新弹窗。 */
@Composable
fun UpdateDialog(
    state: ChatUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val info = state.updateInfo ?: return
    Dialog(onDismissRequest = onDismiss) {
        GlassPanel(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("发现新版本", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "  →  v${info.version}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (info.notes.isNotBlank()) {
                    Text(
                        info.notes.take(1200),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 190.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                if (state.updateDownloading) {
                    LinearProgressIndicator(
                        progress = { state.updateProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "正在下载 ${(state.updateProgress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (state.updateApkPath != null) {
                    Text(
                        "安装包已下载完成，点击安装会覆盖升级并保留当前数据与登录配置。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                state.updateError?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF8A80),
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("稍后") }
                    val readyToInstall = state.updateApkPath != null && !state.updateDownloading
                    Button(
                        onClick = { if (readyToInstall) onInstall() else onDownload() },
                        enabled = !state.updateDownloading,
                    ) {
                        Text(
                            when {
                                state.updateDownloading -> "下载中…"
                                readyToInstall -> "立即安装"
                                else -> "下载最新版"
                            }
                        )
                    }
                }
            }
        }
    }
}
