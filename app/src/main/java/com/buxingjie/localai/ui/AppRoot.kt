package com.buxingjie.localai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("对话", Icons.Outlined.ChatBubbleOutline),
    FILES("文件", Icons.Outlined.FolderOpen),
    DOWNLOADS("下载", Icons.Outlined.FileDownload),
    MODELS("模型", Icons.Outlined.Memory),
    SETTINGS("设置", Icons.Outlined.Settings),
}

@Composable
fun AppRoot(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var showTerminal by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        val message = state.error
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    LaunchedEffect(tabIndex) {
        viewModel.refreshSystemState()
        when (tabIndex) {
            1 -> viewModel.refreshWorkspace()
            3 -> viewModel.refreshModels()
        }
    }

    LiquidBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(
                    containerColor = Color(0x16FFFFFF),
                    tonalElevation = 0.dp,
                ) {
                    Tab.entries.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = tabIndex == index,
                            onClick = { tabIndex = index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (Tab.entries[tabIndex]) {
                    Tab.CHAT -> ChatScreen(
                        state = state,
                        viewModel = viewModel,
                        onOpenModels = { tabIndex = if (state.engineId == "local") 3 else 4 },
                        onOpenTerminal = { showTerminal = true },
                    )
                    Tab.FILES -> FilesScreen(
                        state = state,
                        viewModel = viewModel,
                        onOpenChat = { tabIndex = 0 },
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
                        onOpenModels = { tabIndex = 3 },
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
}
