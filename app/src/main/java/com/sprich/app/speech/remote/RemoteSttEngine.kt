package com.sprich.app.speech.remote

import com.sprich.app.speech.api.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible backup speech-to-text.
 * POST {baseUrl}/audio/transcriptions (multipart: file, model, language?, response_format=json)
 * Works with Grok x.ai, Groq, fal Wizper proxies, and any OpenAI-compatible gateway
 * (OpenCode Go / Zen included). Response parsed as {"text": "..."}; Deepgram-style plain
 * text responses are also accepted.
 */
class RemoteSttEngine(
    private val baseUrlProvider: () -> String,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean =
        baseUrlProvider().startsWith("http") && apiKeyProvider().isNotBlank() && modelProvider().isNotBlank()

    suspend fun transcribe(pcm: ShortArray, sampleRate: Int, language: Language): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (!isConfigured()) return@withContext Result.failure(IllegalStateException("Remote STT not configured"))
                if (pcm.isEmpty()) return@withContext Result.failure(IllegalArgumentException("No audio captured"))

                val wav = wavBytes(pcm, sampleRate)
                val langCode = when (language) {
                    Language.EN -> "en"; Language.DE -> "de"; Language.ES -> "es"; Language.FR -> "fr"; else -> ""
                }

                val fileBody = wav.toRequestBody("audio/wav".toMediaType())
                var partBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "dictation.wav", fileBody)
                    .addFormDataPart("model", modelProvider())
                    .addFormDataPart("response_format", "json")
                if (langCode.isNotBlank()) partBuilder = partBuilder.addFormDataPart("language", langCode)

                val req = Request.Builder()
                    .url(baseUrlProvider().trimEnd('/') + "/audio/transcriptions")
                    .header("Authorization", "Bearer " + apiKeyProvider())
                    .post(partBuilder.build())
                    .build()

                client.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IllegalStateException("STT HTTP ${resp.code}: ${bodyStr.take(180)}"))
                    }
                    val text = parseText(bodyStr)
                    if (text.isNullOrBlank()) {
                        return@withContext Result.failure(IllegalStateException("STT empty response"))
                    }
                    Result.success(text.trim())
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    private fun parseText(body: String): String? = try {
        val json = JSONObject(body)
        when {
            json.has("text") -> json.optString("text")
            json.has("transcript") -> json.optString("transcript") // Deepgram style
            else -> null
        }
    } catch (_: Exception) {
        body.takeIf { it.isNotBlank() && !it.startsWith("{") } // plain-text endpoints
    }

    companion object {
        /** Minimal 44-byte RIFF header + PCM16 mono little-endian data. */
        fun wavBytes(pcm: ShortArray, sampleRate: Int): ByteArray {
            val dataSize = pcm.size * 2
            val out = ByteArray(44 + dataSize)
            fun putAscii(offset: Int, s: String) {
                for (i in s.indices) out[offset + i] = s[i].code.toByte()
            }
            fun putInt32(offset: Int, v: Int) {
                out[offset] = (v and 0xff).toByte(); out[offset+1] = ((v shr 8) and 0xff).toByte()
                out[offset+2] = ((v shr 16) and 0xff).toByte(); out[offset+3] = ((v shr 24) and 0xff).toByte()
            }
            fun putInt16(offset: Int, v: Int) {
                out[offset] = (v and 0xff).toByte(); out[offset+1] = ((v shr 8) and 0xff).toByte()
            }
            putAscii(0, "RIFF"); putInt32(4, 36 + dataSize); putAscii(8, "WAVE")
            putAscii(12, "fmt "); putInt32(16, 16); putInt16(20, 1)
            putInt16(22, 1); putInt32(24, sampleRate); putInt32(28, sampleRate * 2)
            putInt16(32, 2); putInt16(34, 16)
            putAscii(36, "data"); putInt32(40, dataSize)
            var o = 44
            for (s in pcm) {
                out[o] = (s.toInt() and 0xff).toByte()
                out[o+1] = ((s.toInt() shr 8) and 0xff).toByte()
                o += 2
            }
            return out
        }
    }
}
