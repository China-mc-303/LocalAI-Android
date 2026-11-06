package com.buxingjie.localai.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.buxingjie.localai.BuildConfig
import com.buxingjie.localai.data.AiProvider
import java.io.File
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
    onOpenModels: () -> Unit,
) {
    val context = LocalContext.current
    val maxThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
    val provider = AiProvider.fromId(state.engineId)
    var dirInput by remember { mutableStateOf(state.workingDir) }
    LaunchedEffect(state.workingDir) { dirInput = state.workingDir }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        SettingCard {
            Text("模型来源", style = MaterialTheme.typography.titleSmall)
            Text(
                "本地模型完全离线；在线模型需要联网并填写对应平台的 API Key",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EngineRow(
                title = "本地 GGUF 模型",
                subtitle = when {
                    state.modelLoading -> "正在加载…"
                    state.modelLoaded -> "${state.modelName} 已加载"
                    else -> "尚未加载，前往模型页导入或下载"
                },
                selected = state.engineId == "local",
                onClick = { viewModel.selectEngine("local") },
            )
            AiProvider.entries.forEach { item ->
                EngineRow(
                    title = item.label,
                    subtitle = item.vendor + " · " + item.modelHint,
                    selected = state.engineId == item.id,
                    onClick = { viewModel.selectEngine(item.id) },
                    badge = if (state.engineId == item.id && state.remoteApiKey.isNotBlank()) "已填写 Key" else null,
                )
            }
        }

        if (provider != null) {
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${provider.label} 接入配置", style = MaterialTheme.typography.titleSmall)
                        Text(
                            provider.keyHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { viewModel.resetRemoteConfig() }) { Text("恢复默认") }
                }
                OutlinedTextField(
                    value = state.remoteApiKey,
                    onValueChange = { viewModel.setRemoteApiKey(it) },
                    label = { Text("API Key") },
                    placeholder = { Text(provider.keyHint) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = state.remoteModel,
                    onValueChange = { viewModel.setRemoteModel(it) },
                    label = { Text("模型名称") },
                    placeholder = { Text(provider.modelHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.remoteBaseUrl,
                    onValueChange = { viewModel.setRemoteBaseUrl(it) },
                    label = { Text("接口地址 Base URL") },
                    placeholder = { Text("https://…/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (state.engineReady) "配置完整，可以直接对话" else "填写 API Key 后即可使用（Key 只保存在本机）",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.engineReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SettingCard {
            Text("工作目录", style = MaterialTheme.typography.titleSmall)
            Text(
                "每个 AI 的终端、shell 命令、工作目录文件浏览都使用这个目录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = dirInput,
                onValueChange = { dirInput = it },
                label = { Text("绝对路径") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "默认：应用专属目录（免权限）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.setWorkingDir(dirInput) }) { Text("保存") }
            }
            Text(
                "在对话页右上角点终端图标，可查看该 AI 的终端历史和此目录下的文件。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("联网查看网页", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "所有模型都能打开网址、读取网页正文；也可以直接把链接发到对话框",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.webToolsEnabled,
                    onCheckedChange = { viewModel.setWebToolsEnabled(it) },
                )
            }
        }

        SettingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI 操控手机", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "通过无障碍服务读取屏幕并点击、滑动、输入、打开应用。操控期间屏幕顶部会显示“紧急停止”按钮",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.phoneToolsEnabled,
                    onCheckedChange = { viewModel.setPhoneToolsEnabled(it) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            PermissionRow(
                title = "无障碍服务",
                enabled = state.accessibilityEnabled,
                action = "去开启",
            ) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            PermissionRow(
                title = "悬浮窗（紧急停止按钮）",
                enabled = state.overlayEnabled,
                action = "去授权",
            ) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            Text(
                "提示：无障碍服务需要在系统设置中手动开启，重启手机后如失效请重新开启。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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

        SettingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("版本 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.updateInfo?.let { "GitHub 上发现新版本 v${it.version}" }
                            ?: "每次进入应用时自动检查 GitHub 最新版本",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.updateInfo != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { viewModel.checkUpdate(manual = true) },
                    enabled = !state.updateChecking,
                ) {
                    Text(if (state.updateChecking) "检查中…" else "检查更新")
                }
            }
            if (state.updateInfo != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.showUpdateDialog() }) {
                        Text(if (state.updateApkPath != null) "查看安装包" else "查看更新")
                    }
                    if (state.updateApkPath != null) {
                        OutlinedButton(onClick = { viewModel.installUpdate() }) { Text("立即安装") }
                    }
                }
            }
        }

        SettingCard {
            Text("个性化", style = MaterialTheme.typography.titleSmall)
            Text(
                "自定义主题模式、主题色、强调色、玻璃透明度与壁纸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("主题模式", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = state.themeMode != "light",
                    onClick = { viewModel.setThemeMode("dark") },
                    label = { Text("黑夜") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = state.themeMode == "light",
                    onClick = { viewModel.setThemeMode("light") },
                    label = { Text("白天") },
                )
            }
            var showPrimaryPicker by remember { mutableStateOf(false) }
            var showAccentPicker by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("主题色", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(state.primaryColor))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable { showPrimaryPicker = true }
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { showPrimaryPicker = true }) { Text("选择颜色") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("强调色", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(state.accentColor))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable { showAccentPicker = true }
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { showAccentPicker = true }) { Text("选择颜色") }
            }
            if (showPrimaryPicker) {
                ColorPickerDialog(
                    title = "选择主题色",
                    initialColor = state.primaryColor,
                    onConfirm = { viewModel.setPrimaryColor(it); showPrimaryPicker = false },
                    onDismiss = { showPrimaryPicker = false },
                )
            }
            if (showAccentPicker) {
                ColorPickerDialog(
                    title = "选择强调色",
                    initialColor = state.accentColor,
                    onConfirm = { viewModel.setAccentColor(it); showAccentPicker = false },
                    onDismiss = { showAccentPicker = false },
                )
            }
            SliderSetting(
                title = "玻璃透明度",
                valueText = "${(state.glassOpacity * 100).roundToInt()}%",
                hint = "数值越高，玻璃卡片越不透明",
            ) {
                Slider(
                    value = state.glassOpacity,
                    onValueChange = { viewModel.setGlassOpacity(it) },
                    valueRange = 0.05f..0.35f,
                )
            }
            val wallpaperLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri ->
                uri?.let {
                    val file = File(context.filesDir, "wallpaper_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        context.contentResolver.openInputStream(it)?.use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                        viewModel.setWallpaperPath(file.absolutePath)
                    }.onFailure { e ->
                        android.widget.Toast.makeText(context, "设置壁纸失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("壁纸", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        state.wallpaperPath?.let { File(it).name } ?: "默认深色渐变",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { wallpaperLauncher.launch("image/*") }) { Text("选择图片") }
                if (state.wallpaperPath != null) {
                    TextButton(onClick = { viewModel.setWallpaperPath(null) }) { Text("恢复默认") }
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
                "• 本地 GGUF 模型推理、文件解析、聊天记录全部在手机本地完成，不上传服务器。\n" +
                "• 使用 DeepSeek / Kimi / 豆包 / 元宝 / GPT 等在线模型时，对话内容会发送到对应平台，API Key 仅保存在本机。\n" +
                "• 每个 AI 都有独立终端：可手动执行命令，AI 也能通过 shell 工具在工作目录中查看文件、解压 zip。\n" +
                "• 工作目录可在上方设置，终端里能浏览目录、解析文件、查看 zip 内文件清单。\n" +
                "• “查看网页”和“操控手机”是同一个工具协议，本地与在线模型均可使用。\n" +
                "• 手机操控基于无障碍服务，AI 操作期间屏幕顶部始终有紧急关闭按钮，可随时中断。\n" +
                "• 语音输入调用手机自带的识别服务，部分机型可能需要联网。\n" +
                "• 小模型能力有限，复杂问题请配合文件资料使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun EngineRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (badge != null) {
                    Text(
                        " · $badge",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!selected) {
            TextButton(onClick = onClick) { Text("选择") }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    enabled: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (enabled) "已开启" else "未开启",
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onAction) { Text(if (enabled) "查看" else action) }
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
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
