package com.buxingjie.localai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buxingjie.localai.ui.AppRoot
import com.buxingjie.localai.ui.theme.LocalAiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: com.buxingjie.localai.ui.ChatViewModel = viewModel()
            val state by viewModel.state.collectAsState()
            LocalAiTheme(
                primary = state.primaryColor,
                accent = state.accentColor,
                opacity = state.glassOpacity,
                darkTheme = state.themeMode != "light",
            ) {
                AppRoot(viewModel = viewModel)
            }
        }
    }
}
