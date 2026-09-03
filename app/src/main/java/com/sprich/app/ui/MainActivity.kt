package com.sprich.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.*
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.sprich.app.storage.Preferences
import com.sprich.app.api.ApiUse
import com.sprich.app.ui.settings.ApiSettingsScreen
import com.sprich.app.ui.settings.NoticesScreen
import com.sprich.app.ui.benchmark.BenchmarkScreen
import com.sprich.app.ui.home.HomeScreen
import com.sprich.app.ui.onboarding.OnboardingScreen
import com.sprich.app.ui.settings.SettingsScreen
import com.sprich.app.ui.theme.SprichTheme
import com.sprich.app.ui.vocab.VocabScreen
import com.sprich.app.ui.vocab.WordLearningScreen

class MainActivity : ComponentActivity() {
    private var settingsRequest by mutableIntStateOf(0)

    companion object { const val ACTION_OPEN_SETTINGS = "com.sprich.app.OPEN_SETTINGS" }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_SETTINGS) {
            settingsRequest++
            intent.action = Intent.ACTION_MAIN
        }
        setIntent(intent)
    }

    @SuppressLint("WrongStartDestinationType")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_OPEN_SETTINGS) {
            settingsRequest++
            intent.action = Intent.ACTION_MAIN
        }
        enableEdgeToEdge()
        val prefs = Preferences(this)
        setContent {
            SprichTheme {
                val nav = rememberNavController()
                var startDest by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    val done = try { prefs.initializeFirstRun() } catch (_: Exception) { false }
                    startDest = if (done) "home" else "onboarding"
                }
                LaunchedEffect(startDest, settingsRequest) {
                    if (startDest != null && settingsRequest > 0) {
                        nav.navigate("settings") { launchSingleTop = true }
                    }
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    containerColor = MaterialTheme.colorScheme.surface
                ) { innerPadding ->
                    if (startDest == null) {
                        Box(
                            Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(24.dp))
                        }
                    } else {
                        NavHost(
                            navController = nav,
                            startDestination = startDest!!,
                            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
                        ) {
                            composable("onboarding") {
                                OnboardingScreen(
                                    onDone = {
                                        nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                                    },
                                    onOpenImeSettings = { openImeSettings() }
                                )
                            }
                            composable("home") { HomeScreen(onSettings = { nav.navigate("settings") }, onBenchmarkTap = { if (com.sprich.app.BuildConfig.ENABLE_BENCHMARK) nav.navigate("benchmark") }) }
                            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }, onBenchmark = { if (com.sprich.app.BuildConfig.ENABLE_BENCHMARK) nav.navigate("benchmark") }, onVocab = { nav.navigate("vocab") }, onApi = { nav.navigate(if (it == ApiUse.VOICE) "voice-api" else "writing-api") }, onLicenses = { nav.navigate("notices") }) }
                            for ((route, use, otherRoute) in listOf(Triple("voice-api", ApiUse.VOICE, "writing-api"), Triple("writing-api", ApiUse.WRITING, "voice-api"))) {
                                composable("$route?provider={provider}", arguments = listOf(navArgument("provider") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
                                    ApiSettingsScreen(use, onBack = { nav.popBackStack() }, initialProviderId = entry.arguments?.getString("provider"),
                                        onSetUpOther = { provider -> nav.navigate("$otherRoute?provider=${android.net.Uri.encode(provider)}") { launchSingleTop = true } })
                                }
                            }
                            composable("notices") { NoticesScreen(onBack = { nav.popBackStack() }) }
                            composable("vocab") { VocabScreen(onBack = { nav.popBackStack() }, onLearn = { nav.navigate("learn-word") }) }
                            composable("learn-word") { WordLearningScreen(onBack = { nav.popBackStack() }) }
                            // Benchmark only in debug source set — in release this destination is not reachable (no route)
                            if (com.sprich.app.BuildConfig.ENABLE_BENCHMARK) {
                                composable("benchmark") { BenchmarkScreen(onBack = { nav.popBackStack() }) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openImeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (_: Exception) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }
}
