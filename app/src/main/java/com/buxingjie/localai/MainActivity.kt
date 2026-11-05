package com.buxingjie.localai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.buxingjie.localai.ui.AppRoot
import com.buxingjie.localai.ui.theme.LocalAiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalAiTheme {
                AppRoot()
            }
        }
    }
}
