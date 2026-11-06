package com.buxingjie.localai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buxingjie.localai.doc.ParsedDoc

@Composable
fun FilesScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
    onOpenChat: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.attachFiles(uris) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("文件与压缩包", style = MaterialTheme.typography.titleLarge)
                Text(
                    "支持 txt / md / pdf / docx / pptx / xlsx / zip 等",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = { launcher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text(" 选择文件")
            }
        }

        if (state.docBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (state.docs.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "还没有导入文件",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "导入文件后，可以让 AI 总结、提取要点或翻译；在对话中提问时会自动检索文件内容。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "所有解析都在手机本地完成，不会上传。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.docs, key = { it.id }) { doc ->
                DocCard(
                    doc = doc,
                    onSummarize = {
                        viewModel.summarizeDoc(doc)
                        onOpenChat()
                    },
                    onKeyPoints = {
                        viewModel.keyPointsOf(doc)
                        onOpenChat()
                    },
                    onTranslateZh = {
                        viewModel.translateDoc(doc, true)
                        onOpenChat()
                    },
                    onTranslateEn = {
                        viewModel.translateDoc(doc, false)
                        onOpenChat()
                    },
                    onDelete = { viewModel.removeDoc(doc) },
                )
            }
        }
    }
}

@Composable
private fun DocCard(
    doc: ParsedDoc,
    onSummarize: () -> Unit,
    onKeyPoints: () -> Unit,
    onTranslateZh: () -> Unit,
    onTranslateEn: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        doc.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${doc.charCount} 字" + if (doc.entries.size > 1) " · ${doc.entries.size} 个条目" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除")
                }
            }

            Text(
                text = doc.text.replace("【文件：", "").take(160).replace("\n", " "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (doc.note.isNotBlank()) {
                Text(
                    doc.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                TextButton(onClick = onSummarize) { Text("总结") }
                TextButton(onClick = onKeyPoints) { Text("要点") }
                TextButton(onClick = onTranslateZh) { Text("译成中文") }
                TextButton(onClick = onTranslateEn) { Text("译成英文") }
            }
        }
    }
}
