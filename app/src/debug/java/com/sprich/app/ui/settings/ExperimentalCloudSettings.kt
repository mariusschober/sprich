package com.sprich.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.sprich.app.storage.Preferences
import com.sprich.app.storage.ApiSecretStore
import com.sprich.app.speech.TranscriptionMode
import kotlinx.coroutines.launch

/** Debug-only controls. Saving a bound key never changes either network consent setting. */
@Composable internal fun ExperimentalCloudSettings(prefs: Preferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ApiSecretStore(context) }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val mode by prefs.transcriptionMode.collectAsState(initial = TranscriptionMode.ON_DEVICE)
    Text("Experimental custom provider · debug only")
    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("HTTPS transcription endpoint") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Button(enabled = key.isNotBlank() && model.isNotBlank(), onClick = { scope.launch {
        try {
            val ref = checkNotNull(store.saveBoundSecret("openai-compatible", endpoint, key))
            prefs.setSttProviderId("openai-compatible")
            prefs.setSttBaseUrl(endpoint)
            prefs.setSttModel(model)
            prefs.setSttCredentialRef(ref)
            key = ""
            message = "Key saved. Online transcription remains ${if (mode == TranscriptionMode.ON_DEVICE) "off" else "on"}."
        } catch (_: Exception) { message = "Could not save. Check the HTTPS endpoint and device lock settings." }
    } }) { Text("Save key") }
    Text(message)
    Row { Checkbox(checked = mode == TranscriptionMode.API_PRIMARY, onCheckedChange = { enabled -> scope.launch { prefs.setTranscriptionMode(if (enabled) TranscriptionMode.API_PRIMARY else TranscriptionMode.ON_DEVICE) } }); Text("Send dictation audio to this provider") }
}
