package com.buxingjie.localai.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("对话", Icons.Outlined.ChatBubbleOutline),
    SQUARE("广场", Icons.Outlined.Forum),
    FILES("文件", Icons.Outlined.FolderOpen),
    DOWNLOADS("下载", Icons.Outlined.FileDownload),
    MODELS("模型", Icons.Outlined.Memory),
    SETTINGS("设置", Icons.Outlined.Settings),
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppRoot(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { Tab.entries.size })
    var showTerminal by remember { mutableStateOf(false) }
    var showSplash by remember { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2200)
        showSplash = false
    }

    LaunchedEffect(state.error) {
        val message = state.error
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    // 滑动到新页面时刷新对应数据
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            viewModel.refreshSystemState()
            when (page) {
                1 -> viewModel.refreshPublic()
                2 -> viewModel.refreshWorkspace()
                4 -> viewModel.refreshModels()
            }
        }
    }

    fun goTab(index: Int) {
        scope.launch { pagerState.animateScrollToPage(index) }
    }

    LiquidBackground(
        primaryColor = Color(state.primaryColor),
        accentColor = Color(state.accentColor),
        opacity = state.glassOpacity,
        wallpaperPath = state.wallpaperPath,
        wallpaperBlur = state.wallpaperBlur,
    ) {
        // 键盘弹出时隐藏悬浮导航栏，并让输入框贴合键盘顶部
        val imeVisible = WindowInsets.isImeVisible
        val contentBottom by animateDpAsState(
            targetValue = if (imeVisible) 0.dp else 96.dp,
            label = "contentBottom",
        )
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!imeVisible) {
                    FloatingPillNavBar(
                        currentPage = pagerState.currentPage,
                        opacity = state.glassOpacity,
                        onSelect = { goTab(it) },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            },
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                        end = padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                        top = padding.calculateTopPadding(),
                        bottom = contentBottom,
                    ),
                beyondViewportPageCount = 1,
            ) { page ->
                when (Tab.entries[page]) {
                    Tab.CHAT -> ChatScreen(
                        state = state,
                        viewModel = viewModel,
                        onOpenModels = { goTab(if (state.engineId == "local") 4 else 5) },
                        onOpenTerminal = { showTerminal = true },
                    )
                    Tab.SQUARE -> PublicChatScreen(
                        state = state,
                        viewModel = viewModel,
                    )
                    Tab.FILES -> FilesScreen(
                        state = state,
                        viewModel = viewModel,
                        onOpenChat = { goTab(0) },
                    )
                    Tab.DOWNLOADS -> DownloadsScreen(
                        state = state,
                        viewModel = viewModel,
                    )
                    Tab.MODELS -> ModelsScreen(
                        state = state,
                        viewModel = viewModel,
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        state = state,
                        viewModel = viewModel,
                        onOpenModels = { goTab(4) },
                    )
                }
            }
        }
    }

    if (showTerminal) {
        TerminalScreen(
            state = state,
            viewModel = viewModel,
            onClose = { showTerminal = false },
        )
    }

    if (state.updateDialogVisible && state.updateInfo != null) {
        UpdateDialog(
            state = state,
            onDownload = { viewModel.downloadUpdate() },
            onInstall = { viewModel.installUpdate() },
            onDismiss = { viewModel.dismissUpdate() },
        )
    }

    SplashOverlay(
        visible = showSplash,
        primaryColor = Color(state.primaryColor),
        accentColor = Color(state.accentColor),
    )
}

/** 进入应用时的特效画面：光斑 + Logo 弹性缩放 + 淡出。 */
@Composable
private fun SplashOverlay(visible: Boolean, primaryColor: Color, accentColor: Color) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(500)) +
            androidx.compose.animation.scaleOut(
                androidx.compose.animation.core.tween(500),
                targetScale = 1.15f,
            ),
    ) {
        val scale = remember { androidx.compose.animation.core.Animatable(0.4f) }
        val glow = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) {
            scale.animateTo(
                1f,
                androidx.compose.animation.core.spring(
                    dampingRatio = 0.45f,
                    stiffness = 200f,
                ),
            )
        }
        LaunchedEffect(Unit) {
            glow.animateTo(1f, androidx.compose.animation.core.tween(1200))
        }
        LiquidBackground(
            primaryColor = primaryColor,
            accentColor = accentColor,
            opacity = 0.3f,
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(96.dp)
                        .scale(scale.value)
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            Brush.linearGradient(listOf(primaryColor, accentColor))
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "AI",
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "本地AI",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f + glow.value * 0.6f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "步行街工作室",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = glow.value),
                )
            }
        }
    }
}

/** 图2 风格：悬浮圆角胶囊导航栏，选中项带高亮胶囊底。 */
@Composable
private fun FloatingPillNavBar(
    currentPage: Int,
    opacity: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            opacity = (opacity * 1.6f).coerceAtMost(0.6f),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEachIndexed { index, tab ->
                    val selected = currentPage == index
                    val iconTint by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "tint",
                    )
                    val pillAlpha by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        else Color.Transparent,
                        label = "pill",
                    )
                    val itemHeight by animateDpAsState(
                        if (selected) 40.dp else 36.dp,
                        animationSpec = spring(dampingRatio = 0.7f),
                        label = "h",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(itemHeight)
                            .clip(CircleShape)
                            .background(pillAlpha)
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                tint = iconTint,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = iconTint,
                            )
                        }
                    }
                }
            }
        }
    }
}
