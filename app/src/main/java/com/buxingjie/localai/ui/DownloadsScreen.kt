package com.buxingjie.localai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.buxingjie.localai.data.DownloadRecord
import com.buxingjie.localai.data.ModelRepository
import com.buxingjie.localai.data.RemoteModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadsScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("下载列表", style = MaterialTheme.typography.titleLarge)
        Text(
            "模型下载、导入记录都收在这里；同一时间只会下载一个模型",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val active = state.downloads.values.filter { it.active }
        if (active.isNotEmpty()) {
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("正在下载", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    active.forEach { download ->
                        val remote = ModelRepository.REMOTE_MODELS.firstOrNull { it.id == download.id }
                        val label = remote?.name ?: if (download.id == "import") "导入模型" else download.id
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(
                                    if (download.total > 0) {
                                        "${formatSize(download.received)} / ${formatSize(download.total)}"
                                    } else {
                                        formatSize(download.received)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            LinearProgressIndicator(
                                progress = {
                                    if (download.total > 0) (download.received.toFloat() / download.total).coerceIn(0f, 1f)
                                    else 0f
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("可下载模型", style = MaterialTheme.typography.titleSmall)
                ModelRepository.REMOTE_MODELS.forEach { remote ->
                    DownloadRow(
                        remote = remote,
                        download = state.downloads[remote.id],
                        installed = state.installedModels.any { it.file.name == remote.fileName },
                        onDownload = { viewModel.downloadModel(remote) },
                    )
                }
            }
        }

        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已完成", style = MaterialTheme.typography.titleSmall)
                if (state.downloadHistory.isEmpty()) {
                    Text(
                        "还没有下载记录。下载完成的模型会出现在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.downloadHistory.sortedByDescending { it.time }.forEach { record ->
                        HistoryRow(
                            record = record,
                            exists = java.io.File(record.path).exists(),
                            onLoad = { viewModel.loadDownloaded(record) },
                            onDelete = { viewModel.deleteDownloaded(record) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DownloadRow(
    remote: RemoteModel,
    download: DownloadState?,
    installed: Boolean,
    onDownload: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(remote.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${remote.description} · ${formatSize(remote.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (download != null) {
                LinearProgressIndicator(
                    progress = {
                        if (download.total > 0) (download.received.toFloat() / download.total).coerceIn(0f, 1f) else 0f
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            download != null -> OutlinedButton(onClick = {}, enabled = false) { Text("下载中") }
            installed -> OutlinedButton(onClick = {}, enabled = false) { Text("已下载") }
            else -> Button(onClick = onDownload) { Text("下载") }
        }
    }
}

@Composable
private fun HistoryRow(
    record: DownloadRecord,
    exists: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(record.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatSize(record.sizeBytes)} · ${dateText(record.time)}" +
                    if (exists) "" else " · 文件已删除",
                style = MaterialTheme.typography.labelSmall,
                color = if (exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        if (exists) {
            TextButton(onClick = onLoad) { Text("加载") }
        }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

private fun dateText(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f)
}
