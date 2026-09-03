package com.sprich.app.ui.vocab

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sprich.app.R
import com.sprich.app.api.ApiCatalog
import com.sprich.app.ui.theme.VoiceMark
import com.sprich.app.vocab.*

/** Our IME switches this own-app field to the user's typing keyboard before instant mode can start. */
const val TYPED_SPELLING_IME_OPTION = "com.sprich.app.TYPED_SPELLING"

@Composable internal fun recognitionLabel(profile: RecognitionProfile): String {
    val name = when (profile.engine) {
        "automatic" -> stringResource(R.string.automatic)
        "accurate" -> stringResource(R.string.accurate)
        else -> if (profile.engine in setOf("custom", "openai-compatible")) stringResource(R.string.api_custom_name)
            else ApiCatalog.preset(profile.engine).name
    }
    return buildList {
        add(name)
        profile.language?.let { add(it.uppercase(java.util.Locale.ROOT)) }
        if (profile.streaming) add(stringResource(R.string.learn_streaming_label))
        if (profile.whisper) add(stringResource(R.string.whisper_mode))
    }.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WordLearningScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val vm: WordLearningViewModel = viewModel { WordLearningViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val spellingFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var permissionDenied by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permissionDenied = !allowed
        if (allowed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.record()
    }
    val appPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.resolvePickedName { LocalNamePicker.appDisplayName(app, result.data) }
        }
    }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.resolvePickedName { LocalNamePicker.contactDisplayName(app, result.data?.data) }
        }
    }
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.pause() }
    }
    LaunchedEffect(state.step) {
        focus.clearFocus(); keyboard?.hide()
        listState.scrollToItem(0)
    }
    fun back() {
        if (!vm.previous()) {
            if (state.samples.isNotEmpty() && state.step != LearningStep.SAVED) discard = true else onBack()
        }
    }
    BackHandler { back() }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.learn_discard_title)) },
        text = { Text(stringResource(R.string.learn_discard_body)) },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text(stringResource(R.string.learn_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.learn_keep)) } })

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.learn_title)) }, windowInsets = WindowInsets(0), navigationIcon = {
            IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), state = listState, contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (state.step != LearningStep.SAVED) item {
                Text(stringResource(when (state.step) {
                    LearningStep.RECORD -> R.string.learn_step_record
                    LearningStep.SPELL -> R.string.learn_step_spell
                    else -> R.string.learn_step_review
                }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.profile?.let { Text(recognitionLabel(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            }
            state.error?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
            when (state.step) {
                LearningStep.RECORD -> {
                    item {
                        LearningCard {
                            VoiceMark(Modifier.size(72.dp).align(Alignment.CenterHorizontally))
                            Text(stringResource(R.string.learn_record_title), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.learn_record_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(if (state.samples.size >= WordLesson.MIN_SAMPLES) R.string.learn_attempt_ready else R.string.learn_attempt_count, state.samples.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            when (state.progress.phase) {
                                LearningPhase.IDLE -> {
                                    val ready = state.samples.size >= WordLesson.MIN_SAMPLES
                                    val startRecording: () -> Unit = {
                                        focus.clearFocus(); keyboard?.hide()
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.record()
                                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                    Button(modifier = Modifier.fillMaxWidth(), enabled = state.profile != null,
                                        onClick = if (ready) vm::spell else startRecording) {
                                        if (!ready) { Icon(Icons.Rounded.Mic, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
                                        Text(stringResource(when {
                                            ready -> R.string.learn_next_spelling
                                            state.samples.isEmpty() -> R.string.learn_record
                                            else -> R.string.learn_record_again
                                        }))
                                    }
                                    if (ready && state.samples.size < WordLesson.MAX_SAMPLES) {
                                        TextButton(onClick = startRecording, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.learn_record_more)) }
                                    }
                                }
                                LearningPhase.RECORDING -> {
                                    Text(stringResource(R.string.learn_listening), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                    LinearProgressIndicator(progress = { state.progress.fraction }, modifier = Modifier.fillMaxWidth())
                                    if (state.progress.preview.isNotBlank()) Text(state.progress.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                                    Button(onClick = vm::finishSpeaking, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.learn_done_speaking)) }
                                }
                                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    Text(stringResource(if (state.progress.phase == LearningPhase.PREPARING) R.string.learn_preparing else R.string.learn_recognizing),
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                }
                            }
                            if (state.busy) TextButton(onClick = { vm.cancelAttempt() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                    if (permissionDenied) item {
                        Text(stringResource(R.string.learn_permission), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) {
                            Text(stringResource(R.string.learn_open_settings))
                        }
                    }
                    itemsIndexed(state.samples) { index, heard ->
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.learn_heard_attempt, index + 1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(heard, style = MaterialTheme.typography.titleMedium)
                                }
                                IconButton(onClick = { vm.removeSample(index) }, enabled = !state.busy) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.learn_remove_attempt, index + 1))
                                }
                            }
                        }
                    }
                    item {
                        Text(stringResource(if (state.usesApi) R.string.learn_privacy_api else R.string.learn_privacy_local), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LearningStep.SPELL -> {
                    item {
                        Text(stringResource(R.string.learn_spell_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.learn_spell_body), modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.learn_choose_name_title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.learn_choose_name_body), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(enabled = !state.busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                    focus.clearFocus(); keyboard?.hide()
                                    try {
                                        appPicker.launch(LocalNamePicker.appIntent().putExtra(Intent.EXTRA_TITLE,
                                            context.getString(R.string.learn_choose_app_title)))
                                    } catch (_: ActivityNotFoundException) { vm.namePickerUnavailable() }
                                    catch (_: SecurityException) { vm.namePickerUnavailable() }
                                }) {
                                    Icon(Icons.Rounded.Apps, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.learn_choose_app))
                                }
                                OutlinedButton(enabled = !state.busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                    focus.clearFocus(); keyboard?.hide()
                                    try { contactPicker.launch(LocalNamePicker.contactIntent()) }
                                    catch (_: ActivityNotFoundException) { vm.namePickerUnavailable() }
                                    catch (_: SecurityException) { vm.namePickerUnavailable() }
                                }) {
                                    Icon(Icons.Rounded.Person, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.learn_choose_contact))
                                }
                                if (state.resolvingName) Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.learn_reading_name), style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                }
                            }
                        }
                    }
                    item {
                        LaunchedEffect(Unit) { spellingFocus.requestFocus(); keyboard?.show() }
                        OutlinedTextField(value = state.written, onValueChange = vm::setWritten, label = { Text(stringResource(R.string.learn_correct_spelling)) },
                            modifier = Modifier.fillMaxWidth().focusRequester(spellingFocus), singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done,
                                platformImeOptions = PlatformImeOptions(privateImeOptions = TYPED_SPELLING_IME_OPTION)),
                            keyboardActions = KeyboardActions(onDone = { vm.review() }))
                    }
                    item {
                        Button(onClick = vm::review, enabled = !state.busy && VocabularyText.validTerm(VocabularyText.clean(state.written)), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.learn_review)) }
                    }
                }
                LearningStep.REVIEW -> {
                    item {
                        LearningCard {
                            Text(stringResource(R.string.learn_will_write), style = MaterialTheme.typography.labelLarge)
                            Text(state.written, style = MaterialTheme.typography.headlineMedium)
                            Text(stringResource(R.string.learn_review_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val forms = WordLesson.forms(state.samples)
                    for (form in forms) item(key = form.key) {
                        val correct = !form.needsCorrection(state.written)
                        val conflict = state.existing.conflicts(form.text, state.written, state.profile?.key)
                        val enabled = !correct && !conflict && !state.saving
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.fillMaxWidth().toggleable(value = form.key in state.selected, enabled = enabled, role = Role.Checkbox,
                                onValueChange = { vm.select(form.key) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = form.key in state.selected, onCheckedChange = null, enabled = enabled)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(form.text, style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.learn_frequency, form.count, state.samples.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (correct || conflict) Text(stringResource(if (correct) R.string.learn_already_correct else R.string.learn_conflict_form),
                                        style = MaterialTheme.typography.bodySmall, color = if (conflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item {
                        Text(stringResource(R.string.learn_replacement_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.selected.isEmpty()) Text(stringResource(R.string.learn_no_replacements), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        Button(onClick = vm::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.learn_save))
                        }
                        Text(stringResource(R.string.learn_save_privacy), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LearningStep.SAVED -> item {
                    LearningCard {
                        Icon(Icons.Rounded.Check, null, Modifier.size(40.dp).align(Alignment.CenterHorizontally))
                        Text(stringResource(R.string.learn_saved_title), style = MaterialTheme.typography.headlineSmall)
                        Text(state.written, style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.learn_saved_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.learn_done)) }
                    }
                }
            }
        }
    }
}

@Composable internal fun LearningCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0x18FF7A67), Color(0x08FF4D76), Color.Transparent)))
            .padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
