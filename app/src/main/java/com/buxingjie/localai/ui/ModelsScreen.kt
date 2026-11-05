package com.buxingjie.localai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buxingjie.localai.data.LocalModel
import com.buxingjie.localai.data.ModelRepository
import com.buxingjie.localai.data.RemoteModel

@Composable
fun ModelsScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
) {
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importModel(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("本地模型", style = MaterialTheme.typography.titleLarge)
                Text(
                    "模型完全存放在手机里，不会上传任何对话数据",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(4.dp))
                        Text(
                            if (state.modelLoaded) state.modelName else "当前未加载模型",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (state.modelInfo.isNotBlank()) {
                        Text(
                            state.modelInfo,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (state.modelLoading) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "首次加载需要一些时间…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.modelLoaded) {
                            OutlinedButton(onClick = { viewModel.unloadModel() }) { Text("卸载") }
                        }
                        FilledTonalButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Outlined.Upload, contentDescription = null)
                            Text(" 导入 GGUF")
                        }
                    }
                    state.downloads["import"]?.let { progress ->
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { ratio(progress.received, progress.total) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "正在导入 ${formatBytes(progress.received)}" +
                                if (progress.total > 0) " / ${formatBytes(progress.total)}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.installedModels.isNotEmpty()) {
            item {
                Text("已安装", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(state.installedModels, key = { it.path }) { model ->
                InstalledRow(
                    model = model,
                    selected = state.selectedModelPath == model.path && state.modelLoaded,
                    onSelect = { viewModel.selectModel(model) },
                    onDelete = { viewModel.deleteModel(model) },
                )
            }
        }

        item {
            Text(
                "在线下载（免费）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "从 ModelScope / HuggingFace 下载到手机，首次使用需联网",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(ModelRepository.REMOTE_MODELS, key = { it.id }) { remote ->
            RemoteRow(
                remote = remote,
                download = state.downloads[remote.id],
                onDownload = { viewModel.downloadModel(remote) },
            )
        }

        item {
            Text(
                "推荐：12GB 内存选 1.5B 或 3B；8GB 内存选 1.5B；6GB 以下选 0.5B。\n" +
                    "也可以把自己下载的 .gguf 模型文件直接导入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun InstalledRow(
    model: LocalModel,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatBytes(model.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSelect) { Text(if (selected) "已加载" else "加载") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun RemoteRow(
    remote: RemoteModel,
    download: DownloadState?,
    onDownload: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(remote.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${remote.params} · ${formatBytes(remote.sizeBytes)} · ${remote.description}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onDownload, enabled = download == null) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Text(if (download == null) " 下载" else " 下载中")
                }
            }
            download?.let { progress ->
                LinearProgressIndicator(
                    progress = { ratio(progress.received, progress.total) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    "${formatBytes(progress.received)} / ${formatBytes(progress.total)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun ratio(received: Long, total: Long): Float {
    if (total <= 0) return 0f
    return (received.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
}
