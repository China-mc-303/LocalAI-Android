package com.buxingjie.localai.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
    onOpenModels: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val canSend = input.isNotBlank() && state.engineReady && !state.generating

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.attachFiles(uris) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                input = if (input.isBlank()) spoken else "$input $spoken"
            }
        }
    }

    fun launchSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { speechLauncher.launch(intent) }.onFailure {
            Toast.makeText(context, "当前手机没有可用的语音识别服务", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchSpeech()
        else Toast.makeText(context, "需要麦克风权限", Toast.LENGTH_SHORT).show()
    }

    fun startVoice() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchSpeech() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(Modifier.fillMaxSize()) {
        ChatTopBar(
            state = state,
            onOpenModels = onOpenModels,
            onOpenTerminal = onOpenTerminal,
            onNewChat = {
                viewModel.newChat()
                input = ""
            },
        )

        if (state.modelLoading || state.status != null) {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                state.status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }

        if (state.docs.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已关联 ${state.docs.size} 个文件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                state.docs.take(2).forEach { doc ->
                    Text(
                        doc.name.take(14),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            if (state.messages.isEmpty()) {
                EmptyChat(
                    state = state,
                    onOpenModels = onOpenModels,
                    onSend = { viewModel.send(it) },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                }
                LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
                    if (state.messages.isNotEmpty()) {
                        listState.scrollToItem(state.messages.lastIndex)
                    }
                }
            }
        }

        HorizontalDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            if (!state.engineReady && !state.modelLoading) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.engineId == "local") "尚未加载本地模型" else "在线模型尚未配置完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenModels) {
                        Text(if (state.engineId == "local") "去加载" else "去设置")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { fileLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = "添加文件")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (state.docs.isEmpty()) "输入消息…" else "针对文件提问…") },
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                )
                IconButton(onClick = { startVoice() }) {
                    Icon(Icons.Outlined.Mic, contentDescription = "语音输入")
                }
                Spacer(Modifier.width(2.dp))
                FilledIconButton(
                    onClick = {
                        if (state.generating) {
                            viewModel.stop()
                        } else if (canSend) {
                            viewModel.send(input.trim())
                            input = ""
                        }
                    },
                    enabled = state.generating || canSend,
                ) {
                    if (state.generating) {
                        Icon(Icons.Outlined.Stop, contentDescription = "停止")
                    } else {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    state: ChatUiState,
    onOpenModels: () -> Unit,
    onOpenTerminal: () -> Unit,
    onNewChat: () -> Unit,
) {
    Surface(color = Color.Transparent) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("本地AI", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(6.dp)
                            .height(6.dp)
                            .background(
                                if (state.engineReady) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                RoundedCornerShape(3.dp)
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when {
                            state.modelLoading -> "正在加载模型…"
                            state.engineReady -> state.engineLabel
                            state.engineId == "local" -> "未加载模型 · 点击加载"
                            else -> "${state.engineLabel} · 未配置"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onOpenModels() },
                    )
                    if (state.engineReady) {
                        Text(
                            text = if (state.workMode) " · 办公" else " · 通用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (state.generating) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onOpenTerminal) {
                Icon(Icons.Outlined.Terminal, contentDescription = "终端")
            }
            IconButton(onClick = onNewChat) {
                Icon(Icons.Outlined.AddComment, contentDescription = "新对话")
            }
        }
    }
}

@Composable
private fun EmptyChat(
    state: ChatUiState,
    onOpenModels: () -> Unit,
    onSend: (String) -> Unit,
) {
    val tips = if (state.workMode) {
        listOf(
            "帮我写一份本周工作周报",
            "把这段会议记录整理成纪要",
            "帮我润色一封工作邮件",
            "把这段话翻译成英文",
        )
    } else {
        listOf(
            "用简单的语言解释什么是 RAG",
            "帮我列一个学习计划",
            "写一首关于秋天的短诗",
            "把我上传的文件总结一下",
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (state.workMode) "办公助手" else "本地 AI 助手",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state.engineId == "local") {
                "完全在手机本地运行，断网也能用。"
            } else {
                "当前使用 ${state.engineLabel}，可联网查看网页与操控手机。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        if (!state.engineReady) {
            AssistChip(
                onClick = onOpenModels,
                label = { Text(if (state.engineId == "local") "先加载一个本地模型" else "先去设置填写 API Key") },
            )
            Spacer(Modifier.height(20.dp))
        }
        tips.forEach { tip ->
            AssistChip(
                onClick = { if (state.engineReady) onSend(tip) },
                label = { Text(tip) },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MessageBubble(message: UiMessage) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) Color(0x2E2DD4BF) else Color(0x14FFFFFF),
            border = BorderStroke(1.dp, GlassBorder),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.82f else 0.92f),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = if (message.streaming && message.content.isEmpty()) "正在思考…" else message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (message.streaming && message.content.isNotEmpty()) {
                    Text(
                        "▍",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
