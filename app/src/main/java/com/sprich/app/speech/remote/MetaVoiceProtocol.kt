package com.sprich.app.speech.remote

import com.sprich.app.api.ApiException
import com.sprich.app.speech.LanguagePolicy
import org.json.JSONArray
import org.json.JSONObject

/** Meta's published voice schemas, supplied on 2026-09-03. No SDK-shaped guesses. */
internal object MetaVoiceProtocol {
    const val MAX_TEXT = 16_384
    const val MAX_EVENT_BYTES = 65_536L
    const val MAX_AUDIO_SAMPLES = 16_000 * 120
    val languages = linkedMapOf("en" to "English", "de" to "German", "es" to "Spanish", "fr" to "French")

    fun settings(model: String, request: RemoteSttRequest, realtime: Boolean): JSONObject = JSONObject().apply {
        put("model", model)
        put("audioEncoding", if (realtime) "PCM_16KHZ" else "WAV")
        put("mode", request.options.mode.name)
        put("partialMode", "CUMULATIVE")
        put("emitAudioProgress", true)
        val hints = request.options.languageHints.ifEmpty {
            (request.languagePolicy as? LanguagePolicy.Fixed)?.tag?.substringBefore('-')?.let { setOf(it) }.orEmpty()
        }.sorted().mapNotNull(languages::get)
        if (hints.isNotEmpty()) put("languageBias", JSONArray(hints))
        val words = request.personalVocabularyHints.filter { it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl) }.distinct().take(100)
        if (words.isNotEmpty()) put("keywords", JSONArray(words))
        if (realtime) {
            put("authorization", JSONObject().put("accessToken", "Bearer ${request.credential}"))
            put("zdrOverride", true)
        }
    }

    fun text(json: JSONObject, key: String): String = (json.opt(key) as? String)?.takeIf { it.length <= MAX_TEXT }
        ?: throw ApiException(ApiFailure.InvalidResponse)
    fun time(json: JSONObject, key: String): Long = (json.opt(key) as? Number)?.let { value ->
        value.toLong().takeIf { it in 0..600_000 && it.toDouble() == value.toDouble() }
    }
        ?: throw ApiException(ApiFailure.InvalidResponse)
    fun errorFailure(json: JSONObject): ApiFailure {
        // Classify only; provider text never enters logs, diagnostics, or a persisted exception.
        val message = (json.opt("message") as? String).orEmpty().take(2048).lowercase(java.util.Locale.ROOT)
        return when {
            listOf("rate limit", "quota", "capacity", "credits").any(message::contains) -> ApiFailure.RateLimited
            listOf("unauthorized", "authentication", "api key", "access token", "forbidden").any(message::contains) -> ApiFailure.Authentication
            listOf("model", "mode", "not supported", "unsupported").any(message::contains) -> ApiFailure.ModelUnavailable
            else -> ApiFailure.ProviderUnavailable
        }
    }
    fun error(json: JSONObject, credential: String, fallback: ApiFailure? = null): ApiException {
        // Meta documents ErrorEvent.message as client-safe. Display only that field, bounded and
        // with credentials removed. Never retain a response body in an exception or diagnostic.
        val detail = (json.opt("message") as? String)?.let { message ->
            val withoutKey = if (credential.isNotEmpty()) message.replace(credential, "[key removed]") else message
            withoutKey.replace(Regex("(?i)Bearer\\s+\\S+"), "Bearer [key removed]")
                .replace(Regex("\\s+"), " ").filterNot(Char::isISOControl).take(360).takeIf(String::isNotBlank)
        }
        val classified = errorFailure(json)
        return ApiException(if (classified == ApiFailure.ProviderUnavailable) fallback ?: classified else classified, detail)
    }
    fun httpFailure(response: okhttp3.Response, credential: String): ApiException {
        val fallback = ApiFailure.fromHttpCode(response.code)
        return try {
            val source = response.body?.source() ?: return ApiException(fallback)
            val buffer = okio.Buffer()
            while (buffer.size <= 4096) { if (source.read(buffer, 4097 - buffer.size) == -1L) break }
            if (buffer.size > 4096) return ApiException(fallback)
            val json = JSONObject(buffer.readUtf8())
            error(json.optJSONObject("error") ?: json, credential, fallback)
        } catch (_: Exception) { ApiException(fallback) }
    }
    private fun identity(json: JSONObject, key: String): Int = (json.opt(key) as? Number)?.let {
        it.toLong().takeIf { n -> n in Int.MIN_VALUE..Int.MAX_VALUE && n.toDouble() == it.toDouble() }?.toInt()
    } ?: throw ApiException(ApiFailure.InvalidResponse)
    private fun label(json: JSONObject): String = text(json, "label").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,24}")) }
        ?: throw ApiException(ApiFailure.InvalidResponse)

    fun buffered(body: String, mode: VoiceApiMode): String {
        try {
            val json = JSONObject(body)
            if (text(json, "sessionId").isBlank()) throw ApiException(ApiFailure.InvalidResponse)
            time(json, "audioDurationMs")
            val full = text(json, "transcript").trim()
            val turns = json.getJSONArray("turns")
            if (turns.length() > 1000) throw ApiException(ApiFailure.InvalidResponse)
            val ids = mutableSetOf<Int>()
            val spans = (0 until turns.length()).map { index ->
                val turn = turns.getJSONObject(index)
                if (!ids.add(identity(turn, "turnId"))) throw ApiException(ApiFailure.InvalidResponse)
                if (time(turn, "startMs") > time(turn, "endMs")) throw ApiException(ApiFailure.InvalidResponse)
                val transcript = text(turn, "transcript").trim()
                val speaker = (turn.opt("speaker") as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,24}")) }
                transcript to speaker
            }
            // Only attach labels when their exact spans account for the authoritative whole-clip text.
            return if (mode == VoiceApiMode.DIARIZATION && spans.isNotEmpty() && spans.all { it.second != null } &&
                normalize(spans.joinToString(" ") { it.first }) == normalize(full)) {
                spans.joinToString("\n") { (words, speaker) -> "$speaker: $words" }
            } else full
        } catch (e: ApiException) { throw e }
        catch (_: Exception) { throw ApiException(ApiFailure.InvalidResponse) }
    }
    private fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")

    /** Order is first speechStart, never arithmetic on turnId. Turn B may start before A completes. */
    class Events(private val mode: VoiceApiMode, private val credential: String = "") {
        private data class Turn(var text: String? = null, var ended: Boolean = false, val spans: MutableList<Pair<String, String>> = mutableListOf())
        private val turns = linkedMapOf<Int, Turn>()
        private var currentTurn: Int? = null
        private var partial = ""
        private var speaker: String? = null
        private var finalText: String? = null
        private var processedMs = 0L
        private var stage = VoiceApiStage.LISTENING
        private var eventCount = 0
        private var spanCount = 0

        @Synchronized fun accept(body: String): RemoteTranscriptUpdate {
            try {
                if (++eventCount > 10_000 || body.length > MAX_EVENT_BYTES) throw ApiException(ApiFailure.InvalidResponse)
                val json = JSONObject(body)
                when (json.optString("type")) {
                    "error" -> throw error(json, credential)
                    "audioProgress" -> processedMs = maxOf(processedMs, time(json, "audioProcessedMs"))
                    "speechStart" -> {
                        val id = identity(json, "turnId")
                        if (id in turns || turns.size >= 1000) throw ApiException(ApiFailure.InvalidResponse)
                        turns[id] = Turn(); currentTurn = id; partial = ""; speaker = null
                        processedMs = maxOf(processedMs, time(json, "audioProcessedMs")); stage = VoiceApiStage.LISTENING
                    }
                    "speechEnd" -> {
                        val id = identity(json, "turnId")
                        (turns[id] ?: throw ApiException(ApiFailure.InvalidResponse)).ended = true
                        processedMs = maxOf(processedMs, time(json, "audioProcessedMs")); stage = VoiceApiStage.FINISHING
                    }
                    "speechComplete" -> {
                        val id = identity(json, "turnId")
                        val turn = turns[id] ?: throw ApiException(ApiFailure.InvalidResponse)
                        val words = text(json, "transcript").trim()
                        if (turn.text != null && turn.text != words) throw ApiException(ApiFailure.InvalidResponse)
                        turn.text = words; turn.ended = true
                        if (currentTurn == id) { partial = ""; speaker = null }
                        processedMs = maxOf(processedMs, time(json, "audioProcessedMs"))
                    }
                    "transcript" -> {
                        partial = text(json, "transcript") // Cumulative replaces, including revisions.
                        speaker = null
                        val stable = json.opt("final") as? Boolean ?: throw ApiException(ApiFailure.InvalidResponse)
                        processedMs = maxOf(processedMs, time(json, "audioProcessedMs"))
                        if (mode == VoiceApiMode.PUSH_TO_TALK && stable) {
                            if (finalText != null && finalText != partial) throw ApiException(ApiFailure.InvalidResponse)
                            finalText = partial
                        }
                    }
                    "speaker" -> {
                        val who = label(json)
                        processedMs = maxOf(processedMs, time(json, "audioProcessedMs"))
                        if (mode == VoiceApiMode.DIARIZATION) {
                            if (++spanCount > 1000) throw ApiException(ApiFailure.InvalidResponse)
                            currentTurn?.let { turns[it]?.spans?.add(partial.trim() to who) }
                            speaker = who
                        }
                    }
                    // The protocol explicitly requires ignoring unknown event types.
                    else -> Unit
                }
                val preview = preview()
                if (preview.length > MAX_TEXT) throw ApiException(ApiFailure.InvalidResponse)
                return snapshot(preview)
            } catch (e: ApiException) { throw e }
            catch (_: Exception) { throw ApiException(ApiFailure.InvalidResponse) }
        }

        private fun preview(): String = if (mode == VoiceApiMode.PUSH_TO_TALK) partial else
            turns.entries.mapNotNull { (id, turn) -> turn.text ?: partial.takeIf { id == currentTurn && it.isNotBlank() } }.joinToString(" ")
        private fun snapshot(preview: String) = RemoteTranscriptUpdate(preview, processedMs, stage = stage,
            completedTurns = turns.values.count { it.text != null }, activeTurns = turns.values.count { it.text == null }, speaker = speaker)

        /** Called only after successful EOF/1000. A stable partial is not a completed multi-turn result. */
        @Synchronized fun finish(): String {
            val result = if (mode == VoiceApiMode.PUSH_TO_TALK) {
                finalText ?: throw ApiException(ApiFailure.InvalidResponse)
            } else {
                if (turns.values.any { it.text == null }) throw ApiException(ApiFailure.InvalidResponse)
                turns.values.joinToString(if (mode == VoiceApiMode.DIARIZATION) "\n" else " ") { turn ->
                    val full = turn.text.orEmpty()
                    if (mode == VoiceApiMode.DIARIZATION && turn.spans.isNotEmpty() &&
                        normalize(turn.spans.joinToString(" ") { it.first }) == normalize(full)) {
                        turn.spans.joinToString("\n") { (words, who) -> "$who: $words" }
                    } else full
                }
            }.trim()
            if (result.length > MAX_TEXT) throw ApiException(ApiFailure.InvalidResponse)
            return result
        }
    }
}
