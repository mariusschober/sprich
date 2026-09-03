package com.sprich.app.ui.settings

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sprich.app.R
import com.sprich.app.api.*
import com.sprich.app.speech.TranscriptionMode
import com.sprich.app.speech.refinement.RefinementMode
import com.sprich.app.speech.remote.ApiFailure
import com.sprich.app.speech.remote.VoiceApiOptions
import com.sprich.app.speech.remote.RemoteTranscriptUpdate
import com.sprich.app.storage.ApiSecretStore
import com.sprich.app.storage.Preferences
import com.sprich.app.ui.setup.MicrophoneAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun ApiSettingsScreen(use: ApiUse, onBack: () -> Unit, initialProviderId: String? = null, onSetUpOther: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val focus = LocalFocusManager.current
    val prefs = remember { Preferences(context) }
    val secrets = remember { ApiSecretStore(context) }
    val scope = rememberCoroutineScope()
    val config by prefs.runtimeConfigSnapshot.collectAsState(initial = null)
    val saved = config?.apiChoice(use)
    var providerId by remember { mutableStateOf("meta-muse-voice-transcribe") }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") } // Never saveable, logged, placed in an Intent, or restored from disk.
    var keyFocused by remember { mutableStateOf(false) }
    var voiceOptions by remember { mutableStateOf(VoiceApiOptions(streaming = true)) }
    var liveProgress by remember { mutableStateOf<RemoteTranscriptUpdate?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ApiCheckResult?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var providerDetail by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var micAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val preset = ApiCatalog.preset(providerId)
    val custom = providerId == "custom" || providerId == "openai-compatible"
    val effectiveEndpoint = if (custom) endpoint.trim().trimEnd('/') else preset.endpoint
    val effectiveModel = if (custom) model.trim() else if (use == ApiUse.VOICE) preset.voiceModel else preset.writingModel
    val metaVoice = use == ApiUse.VOICE && providerId == "meta-muse-voice-transcribe"
    val effectiveOptions = if (metaVoice) voiceOptions else VoiceApiOptions()
    val other = config?.apiChoice(if (use == ApiUse.VOICE) ApiUse.WRITING else ApiUse.VOICE)
    val savedRef = listOfNotNull(saved, other).firstOrNull {
        it.providerId == providerId && it.endpoint == effectiveEndpoint && it.credentialRef.startsWith("bound_")
    }?.credentialRef.orEmpty()
    val matchesSaved = saved?.let { it.providerId == providerId && it.endpoint == effectiveEndpoint && it.model == effectiveModel && key.isEmpty() &&
        (!metaVoice || it.voiceOptions == effectiveOptions) } == true
    val verified = matchesSaved && saved?.verified == true
    val enabled = matchesSaved && (if (use == ApiUse.VOICE) config?.transcriptionMode == TranscriptionMode.API_PRIMARY else config?.refinementMode != null && config?.refinementMode != RefinementMode.OFF)

    LaunchedEffect(initialProviderId) {
        val choice = prefs.runtimeConfigSnapshot.first().apiChoice(use)
        if (choice.credentialRef.startsWith("bound_") && (initialProviderId == null || initialProviderId == choice.providerId)) {
            providerId = choice.providerId; endpoint = choice.endpoint; model = choice.model
            voiceOptions = choice.voiceOptions
            advanced = choice.providerId in setOf("custom", "openai-compatible")
        } else if (initialProviderId != null && ApiCatalog.presets.any { it.id == initialProviderId }) {
            providerId = initialProviderId
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { job?.cancel(); key = "" }
            if (event == Lifecycle.Event.ON_RESUME) micAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel(); key = "" }
    }
    val protectWindow = keyFocused || key.isNotEmpty()
    DisposableEffect(protectWindow) {
        val window = (context as? Activity)?.window
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (protectWindow) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (protectWindow && !alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun launchCheck() {
        if (busy) return
        val enteredKey = key.trim()
        val id = providerId
        val base = effectiveEndpoint
        val chosenModel = effectiveModel
        val reuse = savedRef
        val chosenOptions = effectiveOptions.copy(languageHints = effectiveOptions.languageHints.toSet())
        if (!ApiChoice(use, id, base, chosenModel, "", voiceOptions = chosenOptions).configurationValid) {
            message = R.string.api_config_error
            return
        }
        focus.clearFocus()
        job = scope.launch {
            busy = true; result = null; message = null; providerDetail = null; liveProgress = null
            var createdRef: String? = null
            var replacedRef: String? = null
            try {
                val epoch = ApiHttp.currentEpoch
                val previousChoice = prefs.apiChoiceForCheck(use)
                val previous = prefs.runtimeConfigSnapshot.first()
                // A stale screen must not reattach an unreferenced key while its removal is finishing.
                val currentReuse = reuse.takeIf { it in setOf(previous.sttCredentialRef, previous.refinementCredentialRef) }.orEmpty()
                val secret = enteredKey.ifBlank { secrets.loadBoundSecret(currentReuse, id, base).orEmpty() }
                if (secret.isBlank()) { message = R.string.api_key_missing; return@launch }
                require(secret.isNotBlank() && secret.length <= 4096 && secret.none { it.isISOControl() })
                val ref = if (enteredKey.isEmpty()) currentReuse else (secrets.saveBoundSecret(id, base, secret) ?: error("Secure storage unavailable")).also { createdRef = it }
                val choice = ApiChoice(use, id, base, chosenModel, ref, voiceOptions = chosenOptions)
                if (ApiHttp.currentEpoch != epoch) throw CancellationException("API permission changed")
                val checked = if (use == ApiUse.VOICE) {
                    val vocabulary = if (previous.personalVocabHintEnabled) {
                        val repository = com.sprich.app.vocab.VocabRepository(context, prefs)
                        repository.load()
                        repository.entries().map { it.written }.take(100)
                    } else emptyList()
                    ApiConnectionCheck.recordAndCheck(choice, secret, vocabulary,
                        onRecording = { recording = it },
                        onProgress = { progress -> scope.launch(Dispatchers.Main) { if (busy) liveProgress = progress } })
                } else ApiConnectionCheck.check(choice, secret)
                currentCoroutineContext().ensureActive()
                if (prefs.commitApiCheck(choice, previousChoice, epoch)) {
                    replacedRef = previousChoice.credentialRef
                    key = ""
                    result = checked
                }
            } catch (e: TimeoutCancellationException) {
                message = R.string.api_timeout
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                providerDetail = (e as? ApiException)?.publicDetail
                message = if (e is ApiException) when (e.failure) {
                    ApiFailure.Authentication -> R.string.api_auth_error
                    ApiFailure.RateLimited -> R.string.api_rate_error
                    ApiFailure.ModelUnavailable -> R.string.api_model_error
                    ApiFailure.Offline -> R.string.api_offline
                    ApiFailure.Timeout -> R.string.api_timeout
                    ApiFailure.InvalidResponse -> R.string.api_response_error
                    ApiFailure.OutputLimit -> R.string.api_output_limit
                    ApiFailure.OutputRejected -> R.string.api_output_rejected
                    else -> R.string.api_unavailable
                } else if (e is MicrophoneUnavailableException) R.string.ime_mic_unavailable else R.string.api_save_error
            } finally {
                recording = false; busy = false
                // Keep a failed candidate's masked input for retry, and only remove keys no longer in use.
                withContext(NonCancellable + Dispatchers.IO) {
                    val latest = prefs.runtimeConfigSnapshot.first()
                    listOfNotNull(createdRef, replacedRef).filter { it.startsWith("bound_") }.distinct().forEach { ref ->
                        if (ref !in setOf(latest.sttCredentialRef, latest.refinementCredentialRef)) secrets.removeSecret(ref)
                    }
                }
            }
        }
    }
    fun changeSetting(action: suspend () -> Unit) {
        if (busy) return
        job = scope.launch {
            try { busy = true; message = null; providerDetail = null; action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = R.string.api_settings_error }
            finally { busy = false }
        }
    }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(stringResource(if (use == ApiUse.WRITING) R.string.cleanup_title else R.string.voice_typing)) }, windowInsets = WindowInsets(0), navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(if (use == ApiUse.WRITING) R.string.cleanup_intro else R.string.api_voice_intro), style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApiCatalog.presets.forEach { p -> FilterChip(selected = providerId == p.id, enabled = !busy, onClick = {
                    providerId = p.id; key = ""; result = null; message = null
                }, label = { Text(p.name) }) }
            }
            if (preset.experimental) Text(stringResource(R.string.api_experimental), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (metaVoice) {
                Text(stringResource(R.string.api_transmission), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = voiceOptions.streaming, enabled = !busy, onClick = { voiceOptions = voiceOptions.copy(streaming = true); result = null }, label = { Text(stringResource(R.string.api_streaming)) })
                    FilterChip(selected = !voiceOptions.streaming, enabled = !busy, onClick = { voiceOptions = voiceOptions.copy(streaming = false); result = null }, label = { Text(stringResource(R.string.api_recording_mode)) })
                }
                Text(stringResource(if (voiceOptions.streaming) R.string.api_streaming_description else R.string.api_recording_description), style = MaterialTheme.typography.bodyMedium)

            }
            if (custom) {
                OutlinedTextField(endpoint, { endpoint = it; result = null; message = null }, enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.api_base_url)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it; result = null; message = null }, enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.api_model)) }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(key, { if (it.length <= 4096) key = it; result = null; message = null }, enabled = !busy,
                label = { Text(stringResource(R.string.api_key)) },
                placeholder = { Text(stringResource(if (savedRef.isNotEmpty()) R.string.api_key_saved else R.string.api_key_paste)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().onFocusChanged { keyFocused = it.isFocused })
            if (savedRef.isNotEmpty() && other?.credentialRef == savedRef) Text(stringResource(R.string.api_shared_key, preset.name), style = MaterialTheme.typography.bodySmall)
            if (preset.keyUrl.isNotEmpty()) TextButton(onClick = { uri.openUri(preset.keyUrl) }) { Text(stringResource(R.string.api_get_key, preset.name)) }
            Text(stringResource(if (use == ApiUse.WRITING) R.string.api_text_disclosure else R.string.api_audio_disclosure, preset.name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.api_key_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (use == ApiUse.VOICE && !micAllowed) MicrophoneAccess(onGranted = { micAllowed = true })
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(stringResource(if (recording) R.string.api_recording else R.string.api_checking))
                }
                TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.cancel)) }
                liveProgress?.preview?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            } else {
                OutlinedButton(onClick = ::launchCheck, enabled = (key.isNotBlank() || savedRef.isNotBlank()) && (use == ApiUse.WRITING || micAllowed), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (use == ApiUse.WRITING) R.string.api_save_check else R.string.api_record_check))
                }
            }
            message?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                providerDetail?.let { detail -> Text(stringResource(R.string.api_provider_explanation, preset.name, detail), style = MaterialTheme.typography.bodySmall) }
            }
            result?.let { checked ->
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.api_connected_ms, checked.latencyMs), style = MaterialTheme.typography.titleSmall)
                        Text(checked.text, style = MaterialTheme.typography.bodyLarge)
                        if (!enabled) Text(stringResource(R.string.api_not_enabled), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (verified && !enabled && !busy) Button(onClick = { changeSetting { prefs.setApiEnabled(use, true); onBack() } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (use == ApiUse.WRITING) R.string.api_use_cleanup else R.string.api_use_voice))
            }
            if (enabled && !busy) OutlinedButton(onClick = { changeSetting { prefs.setApiEnabled(use, false) } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (use == ApiUse.WRITING) R.string.api_turn_off_cleanup else R.string.api_use_device))
            }
            if (verified && onSetUpOther != null && !custom && !busy) TextButton(onClick = { onSetUpOther(providerId) }) {
                Text(stringResource(if (use == ApiUse.VOICE) R.string.api_setup_writing_same_key else R.string.api_setup_voice_same_key))
            }
            if (metaVoice) {
                HorizontalDivider()
                Text(stringResource(R.string.api_recognition_options), style = MaterialTheme.typography.titleMedium)
                SettingsToggle(stringResource(R.string.api_turn_detection), stringResource(R.string.api_turn_detection_description), voiceOptions.detectTurns) {
                    if (!busy) { voiceOptions = voiceOptions.copy(detectTurns = it, speakerLabels = if (it) voiceOptions.speakerLabels else false); result = null }
                }
                Text(stringResource(R.string.api_preferred_languages), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en" to "English", "de" to "Deutsch", "es" to "Español", "fr" to "Français").forEach { (code, name) ->
                        FilterChip(selected = code in voiceOptions.languageHints, enabled = !busy, onClick = {
                            val hints = voiceOptions.languageHints.toMutableSet()
                            if (!hints.add(code)) hints.remove(code)
                            voiceOptions = voiceOptions.copy(languageHints = hints.toSet()); result = null
                        }, label = { Text(name) })
                    }
                }
                Text(stringResource(R.string.api_language_bias_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsToggle(stringResource(R.string.api_vocabulary_title), stringResource(R.string.api_vocabulary_description, preset.name), config?.personalVocabHintEnabled == true) {
                    changeSetting { prefs.setPersonalVocabHintEnabled(it) }
                }
            }
            if (use == ApiUse.WRITING && verified) SettingsToggle(stringResource(R.string.api_context_title), stringResource(R.string.api_context_description, preset.name), config?.refinementContextEnabled == true) {
                changeSetting { prefs.setRefinementContext(it) }
            }
            if (use == ApiUse.VOICE && verified) SettingsToggle(stringResource(R.string.api_fallback_title), stringResource(R.string.api_fallback_description), config?.apiLocalFallback == true) {
                changeSetting { prefs.setApiLocalFallback(it) }
            }
            if (saved?.credentialRef?.startsWith("bound_") == true && !busy) TextButton(onClick = {
                job?.cancel(); key = ""; result = null
                changeSetting { val orphan = prefs.removeApi(use); if (orphan.isNotBlank()) withContext(NonCancellable + Dispatchers.IO) { secrets.removeSecret(orphan) } }
            }) { Text(stringResource(R.string.api_remove_key), color = MaterialTheme.colorScheme.error) }
            HorizontalDivider()
            TextButton(onClick = { advanced = !advanced }, enabled = !busy) { Text(stringResource(if (advanced) R.string.hide_advanced else R.string.advanced)) }
            if (advanced) {
                if (metaVoice) SettingsToggle(stringResource(R.string.api_speaker_labels), stringResource(R.string.api_speaker_description), voiceOptions.speakerLabels) {
                    if (!busy) { voiceOptions = voiceOptions.copy(speakerLabels = it, detectTurns = if (it) true else voiceOptions.detectTurns); result = null }
                }
                Text(stringResource(R.string.api_model_detail, effectiveModel), style = MaterialTheme.typography.bodySmall)
                if (preset.privacyUrl.isNotEmpty()) TextButton(onClick = { uri.openUri(preset.privacyUrl) }) { Text(stringResource(R.string.api_provider_privacy)) }
                TextButton(onClick = { providerId = "custom"; key = ""; result = null; message = null }, enabled = !busy) { Text(stringResource(R.string.api_custom)) }
            }
        }
    }
}
