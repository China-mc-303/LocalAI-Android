package com.buxingjie.localai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buxingjie.localai.net.PublicMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 公共聊天广场：所有用户可见，带刷新按钮。 */
@Composable
fun PublicChatScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
) {
    var input by remember { mutableStateOf("") }
    var editingName by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.refreshPublic() }
    LaunchedEffect(state.publicMessages.size) {
        if (state.publicMessages.isNotEmpty()) {
            listState.scrollToItem(state.publicMessages.lastIndex)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(14.dp),
    ) {
        // 顶栏：标题 + 昵称 + 刷新
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("公共聊天广场", style = MaterialTheme.typography.titleMedium)
                Text(
                    "我是：${state.publicName}（点击编辑图标改名）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { editingName = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = "修改昵称")
            }
            IconButton(onClick = { viewModel.refreshPublic() }, enabled = !state.publicBusy) {
                if (state.publicBusy) {
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        GlassPanel(Modifier.fillMaxWidth().weight(1f)) {
            if (state.publicMessages.isEmpty()) {
                Text(
                    if (state.publicBusy) "正在加载…" else "还没有消息，来发第一条吧！\n点右上角刷新按钮获取最新消息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.publicMessages, key = { "${it.time}-${it.name}-${it.text.hashCode()}" }) { msg ->
                        PublicBubble(msg, msg.name == state.publicName)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…（最多 300 字）") },
                maxLines = 2,
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    viewModel.sendPublic(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !state.publicBusy,
            ) {
                Icon(Icons.Outlined.Send, contentDescription = "发送")
            }
        }
    }

    if (editingName) {
        var name by remember { mutableStateOf(state.publicName) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("修改昵称") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(16) },
                    singleLine = true,
                    label = { Text("昵称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setPublicName(name)
                    editingName = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PublicBubble(msg: PublicMessage, mine: Boolean) {
    val time = remember(msg.time) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(msg.time))
    }
    GlassPanel(
        shape = RoundedCornerShape(if (mine) 18.dp else 18.dp),
        opacity = if (mine) 0.24f else 0.14f,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    msg.name + if (mine) "（我）" else "",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (mine) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(msg.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
