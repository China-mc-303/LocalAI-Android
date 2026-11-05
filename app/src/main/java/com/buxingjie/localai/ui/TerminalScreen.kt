package com.buxingjie.localai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.buxingjie.localai.tools.WorkspaceFile
import java.util.Locale

@Composable
fun TerminalScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
    onClose: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        LiquidBackground {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("终端 · ${state.engineLabel}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.workingDir,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.refreshWorkspace() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("终端") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("工作目录") })
                    Spacer(Modifier.weight(1f))
                    if (tab == 0) {
                        TextButton(onClick = { viewModel.clearTerminal() }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                            Text("清空")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (tab == 0) {
                    TerminalConsole(state, viewModel)
                } else {
                    WorkspaceBrowser(state, viewModel)
                }
            }
        }
    }

    if (state.previewTitle != null) {
        PreviewDialog(state, viewModel)
    }
}

@Composable
private fun TerminalConsole(state: ChatUiState, viewModel: ChatViewModel) {
    var command by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val listState = rememberLazyListState()

    fun submit() {
        val value = command.trim()
        if (value.isEmpty() || state.terminalBusy) return
        viewModel.runTerminal(value)
        command = ""
    }

    LaunchedEffect(state.terminalLines.size, state.terminalBusy) {
        if (state.terminalLines.isNotEmpty()) {
            listState.scrollToItem(state.terminalLines.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        GlassPanel(Modifier.fillMaxWidth().weight(1f)) {
            if (state.terminalLines.isEmpty()) {
                Text(
                    "这是 ${state.engineLabel} 的独立终端。\n命令在 ${state.workingDir} 中执行。\n\n例如：\n  ls -la\n  unzip -l demo.zip\n  cat note.txt",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.terminalLines) { line ->
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = when (line.kind) {
                                "input" -> MaterialTheme.colorScheme.primary
                                "error" -> MaterialTheme.colorScheme.error
                                "info" -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入命令，例如 ls -la") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Ascii),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { submit() }, enabled = !state.terminalBusy && command.isNotBlank()) {
                if (state.terminalBusy) {
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "执行")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceBrowser(state: ChatUiState, viewModel: ChatViewModel) {
    Column(Modifier.fillMaxSize()) {
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("当前目录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    state.workspacePath.ifBlank { state.workingDir },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "点击文件可解析内容；zip 压缩包可查看内部文件清单",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (state.workspaceLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        GlassPanel(Modifier.fillMaxWidth().weight(1f)) {
            if (state.workspaceFiles.isEmpty()) {
                Text(
                    "目录为空，或路径不可访问。\n可在“设置”里修改工作目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    items(state.workspaceFiles, key = { it.path }) { file ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (file.isDir) viewModel.refreshWorkspace(file.path)
                                    else viewModel.openWorkspaceFile(file.path)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (file.isDir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                                contentDescription = null,
                                tint = if (file.isDir) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (file.isDir) "文件夹" else formatSize(file.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = state.workspacePath != state.workingDir &&
                    state.workspacePath.isNotBlank(),
                onClick = {
                    val parent = java.io.File(state.workspacePath).parent
                    if (parent != null && parent.startsWith(state.workingDir)) {
                        viewModel.refreshWorkspace(parent)
                    } else {
                        viewModel.refreshWorkspace(state.workingDir)
                    }
                },
            ) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null)
                Text("上一级")
            }
            TextButton(onClick = { viewModel.refreshWorkspace(state.workingDir) }) {
                Text("回到工作目录")
            }
        }
    }
}

@Composable
private fun PreviewDialog(state: ChatUiState, viewModel: ChatViewModel) {
    Dialog(
        onDismissRequest = { viewModel.closePreview() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LiquidBackground {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                GlassPanel(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.previewTitle.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.closePreview() }) {
                                Icon(Icons.Outlined.Close, contentDescription = "关闭")
                            }
                        }
                        if (state.previewLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (state.previewEntries.isNotEmpty()) {
                            Text(
                                "压缩包内 ${state.previewEntries.size} 个文件，点一项查看内容",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(vertical = 4.dp)) {
                                items(state.previewEntries, key = { it.path }) { entry ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.readArchiveEntry(entry.path) }
                                            .padding(vertical = 6.dp),
                                    ) {
                                        Text(
                                            entry.path,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            formatSize(entry.chars.toLong()),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                state.previewText.ifBlank { "选择上方文件查看内容" },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f)
}
