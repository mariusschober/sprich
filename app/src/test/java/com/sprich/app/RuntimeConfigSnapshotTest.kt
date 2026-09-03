package com.sprich.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.api.*
import com.sprich.app.speech.TranscriptionMode
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.refinement.RefinementMode
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeConfigSnapshotTest {
    private fun prefs() = Preferences(ApplicationProvider.getApplicationContext<Context>())
    private fun choice(use: ApiUse = ApiUse.VOICE, ref: String = "bound_test") = ApiCatalog.preset("openai").let {
        ApiChoice(use, it.id, it.endpoint, if (use == ApiUse.VOICE) it.voiceModel else it.writingModel, ref)
    }
    // Synthetic connection evidence for preference tests; this helper never exercises a provider.
    private suspend fun accept(prefs: Preferences, choice: ApiChoice) {
        val epoch = ApiHttp.currentEpoch
        assertTrue(prefs.commitApiCheck(choice, prefs.apiChoiceForCheck(choice.use), epoch))
    }

    @Test fun verifiedChoiceIsAtomicAndFrozenAtOnset() = runBlocking {
        val prefs = prefs()
        val voice = choice()
        accept(prefs, voice)
        prefs.setSpeechLanguage(SpeechLanguage.Fixed("de"))
        prefs.setApiEnabled(ApiUse.VOICE, true)
        val first = prefs.runtimeConfigSnapshot.first()
        assertEquals(TranscriptionMode.API_PRIMARY, first.transcriptionMode)
        assertEquals(voice.providerId, first.sttProviderId)
        assertEquals(voice.model, first.sttModel)
        assertEquals(voice.endpoint, first.sttBaseUrl)
        assertEquals(voice.credentialRef, first.sttCredentialRef)
        prefs.setSpeechLanguage(SpeechLanguage.Fixed("en"))
        val next = prefs.runtimeConfigSnapshot.first()
        assertEquals(SpeechLanguage.Fixed("de"), first.speechLanguage)
        assertEquals(SpeechLanguage.Fixed("en"), next.speechLanguage)
        assertEquals(TranscriptionMode.API_PRIMARY, next.transcriptionMode)
    }

    @Test fun checkingCannotEnableEitherApiEvenWithAnOldOnValue() = runBlocking {
        val prefs = prefs()
        for (use in ApiUse.entries) {
            val selected = choice(use)
            accept(prefs, selected)
            fun isOff(s: com.sprich.app.storage.RuntimeConfigSnapshot) = if (use == ApiUse.VOICE) s.transcriptionMode == TranscriptionMode.ON_DEVICE else s.refinementMode == RefinementMode.OFF
            assertTrue(isOff(prefs.runtimeConfigSnapshot.first()))
            // Simulate an old enabled preference whose proof is missing/expired after an upgrade.
            if (use == ApiUse.VOICE) {
                prefs.setSttCredentialRef("bound_legacy"); prefs.setTranscriptionMode(TranscriptionMode.API_PRIMARY)
            } else {
                prefs.setRefinementCredentialRef("bound_legacy"); prefs.setRefinementMode(RefinementMode.CLEAN_DICTATION)
            }
            assertTrue(isOff(prefs.runtimeConfigSnapshot.first()))
            accept(prefs, selected.copy(credentialRef = "bound_legacy"))
            assertTrue(isOff(prefs.runtimeConfigSnapshot.first()))
            prefs.setApiEnabled(use, true)
            assertFalse(isOff(prefs.runtimeConfigSnapshot.first()))
        }
    }

    @Test fun aLateCheckCannotRestoreDisconnectedOrReplacedCredentials() = runBlocking {
        val prefs = prefs()
        val old = choice()
        accept(prefs, old)
        val previous = prefs.apiChoiceForCheck(ApiUse.VOICE)
        val epoch = ApiHttp.currentEpoch
        prefs.removeApi(ApiUse.VOICE)
        assertFalse(prefs.commitApiCheck(old, previous, epoch))
        assertEquals("", prefs.runtimeConfigSnapshot.first().sttCredentialRef)
        val replacement = choice(ref = "bound_replacement")
        accept(prefs, replacement)
        assertFalse(prefs.commitApiCheck(old, previous, ApiHttp.currentEpoch))
        assertEquals(replacement.credentialRef, prefs.runtimeConfigSnapshot.first().sttCredentialRef)
        assertEquals(TranscriptionMode.ON_DEVICE, prefs.runtimeConfigSnapshot.first().transcriptionMode)
    }

    @Test fun revokedCheckKeepsWorkingChoiceAndCannotResurrectClearedData() = runBlocking {
        val prefs = prefs()
        val old = choice()
        accept(prefs, old)
        prefs.setApiEnabled(ApiUse.VOICE, true)
        val previous = prefs.apiChoiceForCheck(ApiUse.VOICE)
        val epoch = ApiHttp.currentEpoch
        ApiHttp.revoke()
        assertFalse(prefs.commitApiCheck(choice(ref = "bound_candidate"), previous, epoch))
        assertEquals(previous, prefs.apiChoiceForCheck(ApiUse.VOICE))
        assertEquals(TranscriptionMode.API_PRIMARY, prefs.runtimeConfigSnapshot.first().transcriptionMode)
        prefs.clearAll()
        assertFalse(prefs.commitApiCheck(old, previous, ApiHttp.currentEpoch))
        assertEquals("", prefs.runtimeConfigSnapshot.first().sttCredentialRef)
    }

    @Test fun recordingModeProofDoesNotReuseStreamingPermission() = runBlocking {
        val prefs = prefs()
        val preset = ApiCatalog.preset("meta-muse-voice-transcribe")
        val stream = ApiChoice(ApiUse.VOICE, preset.id, preset.endpoint, preset.voiceModel, "bound_meta",
            voiceOptions = com.sprich.app.speech.remote.VoiceApiOptions(streaming = true))
        accept(prefs, stream)
        prefs.setApiEnabled(ApiUse.VOICE, true)
        val previous = prefs.apiChoiceForCheck(ApiUse.VOICE)
        // Reading a candidate for a check must not alter the working setup.
        val recording = stream.copy(voiceOptions = stream.voiceOptions.copy(streaming = false))
        assertEquals(previous, prefs.apiChoiceForCheck(ApiUse.VOICE))
        assertTrue(prefs.commitApiCheck(recording, previous, ApiHttp.currentEpoch))
        assertTrue(prefs.apiChoiceForCheck(ApiUse.VOICE).verified)
        assertEquals(TranscriptionMode.ON_DEVICE, prefs.runtimeConfigSnapshot.first().transcriptionMode)
        prefs.setApiEnabled(ApiUse.VOICE, true)
        // Rechecking an unchanged, working setup preserves its existing permission.
        accept(prefs, recording)
        assertEquals(TranscriptionMode.API_PRIMARY, prefs.runtimeConfigSnapshot.first().transcriptionMode)
    }
}
