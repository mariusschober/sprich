package com.sprich.app.ui.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sprich.app.ui.theme.SprichTheme

class BenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SprichTheme {
                BenchmarkScreen(onBack = { finish() })
            }
        }
    }
}
