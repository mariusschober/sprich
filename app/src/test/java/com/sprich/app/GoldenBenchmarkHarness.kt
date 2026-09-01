package com.sprich.app

import com.sprich.app.input.typography.TypographyNormalizer
import com.sprich.app.speech.api.Language
import kotlin.math.min

/**
 * Reproducible multilingual golden benchmark harness.
 * Every engine sees SAME speech.
 * Three layers per benchmark:
 *  RAW MODEL OUTPUT
 *  TYPOGRAPHY/POST-PROCESS OUTPUT (deterministic normalizer)
 *  FINAL EDITOR OUTPUT (InputConnection simulation)
 *
 * WER/CER primarily score RAW; typography scored separately.
 * Baseline: AI polish OFF, personal vocab OFF, spoken editing OFF.
 *
 * Corpus: public jfk.wav + synthetic tones + local private recordings (not committed without consent).
 * For T807D private corpus, retain locally in developer storage.
 */
object GoldenBenchmarkHarness {

    data class GoldenEntry(
        val id: String,
        val language: Language,
        val expected: String, // RAW expected (punctuated, cased)
        val pcm: ShortArray, // 16k mono
        val isWhisper: Boolean = false,
        val isCodeSwitch: Boolean = false,
    )

    data class EngineResult(
        val raw: String,
        val normalized: String,
        val editor: String,
        val detectedLang: String? = null,
        val confidence: Float? = null,
        val latencyMs: Long = 0,
    )

    data class WerResult(
        val wer: Double,
        val cer: Double,
        val insertions: Int,
        val deletions: Int,
        val substitutions: Int,
        val blankRate: Double,
        val hallucinatedSilence: Int,
    )

    // Simple WER via Levenshtein on words
    fun computeWer(expected: String, actual: String): WerResult {
        if (expected.isBlank() && actual.isBlank()) return WerResult(0.0,0.0,0,0,0,0.0,0)
        if (expected.isBlank()) return WerResult(1.0,1.0, actual.split(Regex("\\s+")).size,0,0,0.0, if(actual.isNotBlank()) 1 else 0)
        val expWords = expected.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val actWords = actual.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val n = expWords.size
        val m = actWords.size
        val dp = Array(n+1) { IntArray(m+1) }
        for (i in 0..n) dp[i][0]=i
        for (j in 0..m) dp[0][j]=j
        for (i in 1..n) for(j in 1..m) {
            dp[i][j] = if (expWords[i-1]==actWords[j-1]) dp[i-1][j-1] else min(min(dp[i-1][j]+1, dp[i][j-1]+1), dp[i-1][j-1]+1)
        }
        val insDelSub = backtrace(expWords, actWords, dp)
        val wer = dp[n][m].toDouble() / n
        // CER
        val cer = if (expected.isEmpty()) 0.0 else {
            val dpC = Array(expected.length+1){IntArray(actual.length+1)}
            for(i in 0..expected.length) dpC[i][0]=i
            for(j in 0..actual.length) dpC[0][j]=j
            for(i in 1..expected.length) for(j in 1..actual.length) dpC[i][j]= if(expected[i-1]==actual[j-1]) dpC[i-1][j-1] else min(min(dpC[i-1][j]+1, dpC[i][j-1]+1), dpC[i-1][j-1]+1)
            dpC[expected.length][actual.length].toDouble()/expected.length
        }
        val blank = if (actual.isBlank()) 1.0 else 0.0
        return WerResult(wer, cer, insDelSub.first, insDelSub.second, insDelSub.third, blank, if(expected.isBlank() && actual.isNotBlank()) 1 else 0)
    }

    private fun backtrace(exp: List<String>, act: List<String>, dp: Array<IntArray>): Triple<Int,Int,Int> {
        var i=exp.size; var j=act.size
        var ins=0; var del=0; var sub=0
        while(i>0 || j>0){
            if(i>0 && j>0 && exp[i-1]==act[j-1]){ i--; j-- }
            else if(i>0 && j>0 && dp[i][j]==dp[i-1][j-1]+1){ sub++; i--; j-- }
            else if(j>0 && dp[i][j]==dp[i][j-1]+1){ ins++; j-- }
            else if(i>0 && dp[i][j]==dp[i-1][j]+1){ del++; i-- } else break
        }
        return Triple(ins,del,sub)
    }

    fun normalizeForScoring(raw: String, lang: Language): String =
        TypographyNormalizer.normalize(raw, lang)

    // Golden corpus — minimal reproducible set (jfk + synthetic). Private recordings added locally.
    fun buildGoldenCorpus(): List<GoldenEntry> {
        val list = mutableListOf<GoldenEntry>()
        // English — 20+ sentences (mix of short, 5-10s, punctuation, numbers, names)
        // Using synthetic PCM for host; real T807D will replace with actual jfk.wav slices + private recordings
        fun synth(seed: Int, secs: Float): ShortArray = ShortArray((16000*secs).toInt()){(kotlin.math.sin(it*0.08 + seed)*6000).toInt().toShort()}
        // EN examples
        list.add(GoldenEntry("en-01", Language.EN, "Hello world.", synth(1,1.5f)))
        list.add(GoldenEntry("en-02", Language.EN, "The quick brown fox jumps over the lazy dog.", synth(2,3f)))
        list.add(GoldenEntry("en-03", Language.EN, "Please schedule the meeting for tomorrow at nine AM.", synth(3,3f)))
        list.add(GoldenEntry("en-04", Language.EN, "My name is Marius Schober and I live in Munich.", synth(4,3f)))
        list.add(GoldenEntry("en-05", Language.EN, "The price is 3.14 dollars.", synth(5,2f)))
        list.add(GoldenEntry("en-06", Language.EN, "Contact me at marius@example.com.", synth(6,2.5f)))
        list.add(GoldenEntry("en-07", Language.EN, "Very very good.", synth(7,1.5f)))
        list.add(GoldenEntry("en-whisper-01", Language.EN, "This is a whisper test.", synth(8,2f).map{ (it*0.05).toInt().toShort() }.toShortArray(), isWhisper=true))
        // German — 20+ (compound nouns, umlauts, numbers)
        list.add(GoldenEntry("de-01", Language.DE, "Guten Morgen, wie geht es dir?", synth(20,2f)))
        list.add(GoldenEntry("de-02", Language.DE, "Ich habe morgen um neun Uhr einen Termin in München.", synth(21,4f)))
        list.add(GoldenEntry("de-03", Language.DE, "Die Donaudampfschifffahrtsgesellschaft ist ein langes Wort.", synth(22,4f)))
        list.add(GoldenEntry("de-04", Language.DE, "Der Preis beträgt 3,14 Euro.", synth(23,2f)))
        list.add(GoldenEntry("de-05", Language.DE, "Sehr sehr gut.", synth(24,1.5f)))
        list.add(GoldenEntry("de-whisper-01", Language.DE, "Das ist ein Flüstertest.", synth(25,2f).map{(it*0.05).toInt().toShort()}.toShortArray(), isWhisper=true))
        // Spanish 10+
        list.add(GoldenEntry("es-01", Language.ES, "Hola, ¿cómo estás?", synth(30,2f)))
        list.add(GoldenEntry("es-02", Language.ES, "Nos vemos mañana a las nueve.", synth(31,2.5f)))
        // French 10+
        list.add(GoldenEntry("fr-01", Language.FR, "Bonjour, comment allez-vous?", synth(40,2f)))
        list.add(GoldenEntry("fr-02", Language.FR, "Rendez-vous demain à neuf heures.", synth(41,2.5f)))
        // Alternating same-field sequence (without Settings change)
        // EN DE EN DE ES FR DE EN
        list.add(GoldenEntry("alt-en-1", Language.EN, "Hello, how are you?", synth(50,1.5f)))
        list.add(GoldenEntry("alt-de-1", Language.DE, "Hallo, wie geht es dir?", synth(51,1.5f)))
        list.add(GoldenEntry("alt-en-2", Language.EN, "See you tomorrow.", synth(52,1.5f)))
        list.add(GoldenEntry("alt-de-2", Language.DE, "Bis morgen.", synth(53,1f)))
        list.add(GoldenEntry("alt-es-1", Language.ES, "Hasta mañana.", synth(54,1f)))
        list.add(GoldenEntry("alt-fr-1", Language.FR, "À demain.", synth(55,1f)))
        list.add(GoldenEntry("alt-de-3", Language.DE, "Guten Abend.", synth(56,1f)))
        list.add(GoldenEntry("alt-en-3", Language.EN, "Good evening.", synth(57,1f)))
        // Rapid switch DE->EN <1s, EN->DE <1s (simulated by consecutive entries)
        list.add(GoldenEntry("rapid-de-en", Language.EN, "Good morning.", synth(60,1f)))
        list.add(GoldenEntry("rapid-en-de", Language.DE, "Guten Morgen.", synth(61,1f)))
        // Code switch (optional)
        list.add(GoldenEntry("cs-01", Language.DE, "Das Meeting ist tomorrow at nine.", synth(70,2.5f), isCodeSwitch=true))
        list.add(GoldenEntry("cs-02", Language.EN, "I need the Rechnung by Friday.", synth(71,2.5f), isCodeSwitch=true))
        return list
    }

    // For device benchmark: read jfk.wav and split into real expected
    fun jfkEntry(samples: ShortArray): GoldenEntry =
        GoldenEntry("jfk", Language.EN, "And so, my fellow Americans, ask not what your country can do for you. Ask what ", samples)
}
