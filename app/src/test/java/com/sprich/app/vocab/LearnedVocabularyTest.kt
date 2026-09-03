package com.sprich.app.vocab

import com.sprich.app.speech.*
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.remote.RemoteSttConfig
import com.sprich.app.speech.remote.VoiceApiOptions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LearnedVocabularyTest {
    private val local = RecognitionProfile.local(LocalAsrRoute.AutomaticFastConformer, false)
    private fun word(form: String, target: String, profile: RecognitionProfile = local) =
        WordLesson.create(profile, target, List(3) { form }, setOf(VocabularyText.key(VocabularyText.heardForm(form))))

    @Test fun learnsObservedFormsAndPreservesOriginalAttempts() {
        val samples = listOf("Sprick.", "sprick!", "Spritch")
        assertEquals(setOf("sprick"), WordLesson.suggested(samples, "Sprich"))
        val learned = WordLesson.create(local, "Sprich", samples, setOf("sprick", "spritch"))
        assertEquals(samples, learned.samples)
        assertEquals(listOf("Sprick", "Spritch"), learned.forms)
        assertThrows(IllegalArgumentException::class.java) { WordLesson.create(local, "Sprich", samples, setOf("imagined error")) }
        assertThrows(IllegalArgumentException::class.java) { WordLesson.create(local, "Sprich", samples.take(2), emptySet()) }
    }

    @Test fun capitalizationIsLearnedEvenIfFirstAttemptWasCorrect() {
        val samples = listOf("Sprich.", "sprich", "sprich!")
        assertEquals(setOf("sprich"), WordLesson.suggested(samples, "Sprich"))
        val learned = WordLesson.create(local, "Sprich", samples, setOf("sprich"))
        val store = PersonalVocabStore().apply { replace(emptyList(), listOf(learned)) }
        assertEquals("Try Sprich.", store.snapshot().apply("Try sprich.", local.key))
        assertEquals("Try sprich.", store.snapshot().apply("Try sprich.", "another recognizer"))
    }

    @Test fun correctAttemptsCanSaveOnlyTheSpellingWithoutInventingAliases() {
        val samples = listOf("Sprich", "Sprich.", "Sprich!")
        assertTrue(WordLesson.suggested(samples, "Sprich").isEmpty())
        val learned = WordLesson.create(local, "Sprich", samples, emptySet())
        val snapshot = PersonalVocabStore().apply { replace(emptyList(), listOf(learned)) }.snapshot()
        assertEquals("sprich", snapshot.apply("sprich", local.key))
        assertEquals(listOf("Sprich"), snapshot.terms())
    }

    @Test fun scopedRulesAreLongestFirstAndNeverCascadeIntoAnotherRule() {
        val snapshot = PersonalVocabStore().apply {
            replace(listOf(VocabEntry("york", "YORK"), VocabEntry("beta", "gamma")),
                listOf(word("new york", "New York City"), word("alpha", "beta")))
        }.snapshot()
        assertEquals("New York City and beta", snapshot.apply("new york and alpha", local.key))
        assertEquals("new YORK and alpha", snapshot.apply("new york and alpha"))
    }

    @Test fun unicodeSymbolsAndCombiningMarksHaveCompleteBoundaries() {
        val snapshot = PersonalVocabStore().apply { replace(emptyList(), listOf(word("c++", "C++"), word(".net", ".NET"), word("e\u0301clipse", "Éclipse"))) }.snapshot()
        assertEquals("C++, .NET and Éclipse 🚀", snapshot.apply("c++, .net and e\u0301clipse 🚀", local.key))
        assertEquals("c++builder .network éclipseur", snapshot.apply("c++builder .network éclipseur", local.key))
        assertEquals("éclipse\u0301", snapshot.apply("éclipse\u0301", local.key))
        assertEquals("𝒜c++ .net𝒜", snapshot.apply("𝒜c++ .net𝒜", local.key))
        assertEquals("U.S.", VocabularyText.heardForm("U.S."))
        assertFalse(VocabularyText.validSample("name\uD800"))
    }

    @Test fun spacesCanVaryWithoutReplacingAcrossParagraphs() {
        val snapshot = PersonalVocabStore().apply { replace(emptyList(), listOf(word("green house", "Greenhouse"))) }.snapshot()
        assertEquals("Greenhouse", snapshot.apply("green\u00a0  house", local.key))
        assertEquals("green\nhouse", snapshot.apply("green\nhouse", local.key))
    }

    @Test fun conflictsIncludeExactSpellingAndGlobalRulesButNotOtherRecognizers() {
        val document = VocabJson().addWord(word("sprick", "Sprich"))
        assertThrows(VocabularyConflictException::class.java) { document.addManual("sprick", "SPRICH") }
        assertThrows(VocabularyConflictException::class.java) { document.addWord(word("sprick", "Other")) }
        assertThrows(WordAlreadyLearnedException::class.java) { document.addWord(word("spritch", "Sprich")) }
        val different = RecognitionProfile.local(LocalAsrRoute.AccurateCanary(Language.DE), false)
        assertEquals(2, document.addWord(word("sprick", "Other", different)).learned.size)
        assertThrows(VocabularyConflictException::class.java) { VocabJson().addManual("sprick", "Other").addWord(word("sprick", "Sprich")) }
    }

    @Test fun capturedRulesSurviveLaterSaveAndRemovalAndHintsContainOnlyIntendedSpellings() {
        val store = PersonalVocabStore().apply { add("acme", "ACME") }
        val before = store.snapshot()
        store.replace(store.all(), listOf(word("sprick", "Sprich")))
        val captured = store.snapshot()
        store.clear()
        assertEquals("sprick", before.apply("sprick", local.key))
        assertEquals("Sprich", captured.apply("sprick", local.key))
        assertEquals("sprick", store.snapshot().apply("sprick", local.key))
        assertEquals(listOf("ACME", "Sprich"), captured.terms())
        assertFalse(captured.terms().contains("sprick"))
    }

    @Test fun oldDictionaryAndLearnedObservationsRoundTripWithoutChangingMeaning() {
        val old = Json.decodeFromString<VocabJson>("""{"entries":[{"spoken":"happy","written":"🙂"}]}""")
        assertTrue(old.learned.isEmpty())
        val updated = old.addWord(word("sprick", "Sprich"))
        assertEquals(updated, Json.decodeFromString<VocabJson>(Json.encodeToString(updated)))
        assertEquals("🙂", old.addManual("happy", "🙂").entries.single().written)
        assertFalse(updated.toString().contains("sprick"))
        assertFalse(updated.learned.single().toString().contains("Sprich"))
        assertFalse(updated.learned.single().copy(forms = listOf("unobserved")).isValid())
    }

    @Test fun profileFollowsActualRecognizerAndIgnoresCredentialChanges() {
        val remote = RemoteSttConfig("meta-muse-voice-transcribe", "https://api.meta.ai", "model-one", LanguagePolicy.Automatic, 4000,
            "bound_one", preferStreaming = true, options = VoiceApiOptions(streaming = true, languageHints = setOf("de", "en")))
        val expected = RecognitionProfile.remote(remote, false)
        assertEquals(expected, RecognitionProfile.remote(remote.copy(credentialRef = "bound_two", deadlineMs = 5000), false))
        assertNotEquals(expected.key, RecognitionProfile.remote(remote.copy(model = "model-two"), false).key)
        assertNotEquals(expected.key, RecognitionProfile.remote(remote.copy(endpoint = "https://other.example"), false).key)
        assertNotEquals(expected.key, RecognitionProfile.remote(remote.copy(preferStreaming = false), false).key)
        assertNotEquals(expected.key, RecognitionProfile.remote(remote, true).key)
        val plan = UtterancePlan(TranscriptionPlan.ApiPrimary(remote, LocalAsrRoute.AutomaticFastConformer), RefinementPlan.Off, SpeechSessionConfig())
        assertEquals(expected, RecognitionProfile.result(plan, TranscriptionSourceId.API_META_MUSE))
        assertEquals(local, RecognitionProfile.result(plan, TranscriptionSourceId.LOCAL_FAST))
        assertNull(RecognitionProfile.result(plan, TranscriptionSourceId.LOCAL_CANARY))
        assertNull(RecognitionProfile.result(plan, TranscriptionSourceId.API_GEMINI))
    }

    @Test fun boundsRejectOversizedOrUnconfirmedLessons() {
        assertThrows(IllegalArgumentException::class.java) { WordLesson.create(local, "Name", List(6) { "neme" }, setOf("neme")) }
        assertThrows(IllegalArgumentException::class.java) { WordLesson.create(local, "n".repeat(129), List(3) { "neme" }, setOf("neme")) }
        assertFalse(VocabularyText.validSample(".?!"))
        assertThrows(IllegalArgumentException::class.java) { VocabJson(List(200) { VocabEntryJson("form$it", "Target") }).addWord(word("sprick", "Sprich")) }
    }
}
