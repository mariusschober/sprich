package com.sprich.app.ui.setup

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sprich.app.R
import com.sprich.app.models.download.DownloadManager
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.models.manager.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class KeyboardState(val enabled: Boolean = false, val selected: Boolean = false, val microphone: Boolean = false)

/** Android settings and permission state can change while the activity stays alive. */
@Composable internal fun rememberKeyboardState(): KeyboardState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun read(): KeyboardState {
        val imm = context.getSystemService(InputMethodManager::class.java)
        val selected = ComponentName.unflattenFromString(Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "")
        return KeyboardState(
            imm.enabledInputMethodList.any { it.packageName == context.packageName },
            selected?.packageName == context.packageName,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var state by remember { mutableStateOf(read()) }
    DisposableEffect(context, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state = read() }
        }
        val listener = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) state = read() }
        lifecycle.addObserver(listener)
        listOf(Settings.Secure.DEFAULT_INPUT_METHOD, Settings.Secure.ENABLED_INPUT_METHODS).forEach {
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(it), false, observer)
        }
        onDispose { lifecycle.removeObserver(listener); context.contentResolver.unregisterContentObserver(observer) }
    }
    return state
}

internal fun showKeyboardPicker(context: Context) = context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
internal fun openKeyboardSettings(context: Context) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
internal fun openAppSettings(context: Context) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }

@Composable internal fun MicrophoneAccess(onGranted: () -> Unit = {}) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) onGranted()
    }
    Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.allow_microphone)) }
    if (denied) {
        Text(stringResource(R.string.microphone_help), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.open_app_settings)) }
    }
}

@Composable internal fun ModelSetup(automatic: Boolean) {
    val context = LocalContext.current
    val manager = remember { ModelManager(context) }
    val downloader = remember { DownloadManager(context, manager) }
    val scope = rememberCoroutineScope()
    val lid by manager.lidStatus.collectAsState()
    val fast by manager.fastConformerStatus.collectAsState()
    val accurate by manager.canaryStatus.collectAsState()
    val states = if (automatic) listOf(lid, fast) else listOf(accurate)
    val ids = if (automatic) listOf("lid", "fastconformer") else listOf("accurate")
    val ready = states.all { it is ModelStatus.Ready }
    val busy = states.any { it is ModelStatus.Downloading || it is ModelStatus.Verifying }
    val failed = states.filterIsInstance<ModelStatus.Failed>().firstOrNull()
    val missingIds = ids.filterIndexed { index, _ -> states[index] !is ModelStatus.Ready }
    val total = manager.getManifest().models.filter { it.id in missingIds }.sumOf { it.sizeBytes }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (automatic) R.string.automatic else R.string.accurate), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (automatic) R.string.automatic_description else R.string.accurate_description), style = MaterialTheme.typography.bodyMedium)
            when {
                ready -> Text(stringResource(R.string.ready_offline), style = MaterialTheme.typography.labelLarge)
                busy -> {
                    val downloading = states.filterIsInstance<ModelStatus.Downloading>().firstOrNull()
                    if (downloading != null) {
                        val index = states.indexOf(downloading)
                        Text(stringResource(R.string.download_part, index + 1, states.size))
                        LinearProgressIndicator(progress = { downloading.progress }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.download_bytes,
                            android.text.format.Formatter.formatShortFileSize(context, downloading.bytes),
                            android.text.format.Formatter.formatShortFileSize(context, downloading.total)), style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.checking_download))
                    }
                    TextButton(onClick = { downloader.cancel() }) { Text(stringResource(R.string.cancel_download)) }
                }
                else -> {
                    if (failed != null) Text(failed.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.download_size, android.text.format.Formatter.formatShortFileSize(context, total)), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        scope.launch {
                            try { if (automatic) downloader.downloadAutomatic() else downloader.downloadCanary() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* ModelStatus carries the actionable error. */ }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (failed != null) R.string.retry_download else if (automatic) R.string.setup_automatic else R.string.setup_accurate))
                    }
                }
            }
        }
    }
}
