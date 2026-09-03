package com.sprich.app.speech.refinement

/** Small rejection guards, not a claim to prove semantic equivalence or prompt-injection immunity. */
object RefinementValidator {
    sealed class Result {
        data object Accept : Result()
        data class Reject(val reason: String) : Result()
    }
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val email = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val number = Regex("(?<![\\p{L}\\p{N}])[+−-]?\\d+(?:[.,:/-]\\d+)*(?:[%°])?")
    private val correction = Regex("(\\d+(?:[.,:/-]\\d+)*)\\s*[,;—-]?\\s*(?:no|sorry|actually|nein|sondern|pardon|perdón|digo|plutôt)\\s*[,;—-]?\\s*(\\d+(?:[.,:/-]\\d+)*)", RegexOption.IGNORE_CASE)
    // Repeated units are part of the same explicit correction: "50 Euro, nein, 15 Euro".
    private val unitCorrection = Regex("([+−-]?\\d+(?:[.,:/-]\\d+)*)\\s+([\\p{L}µ%°]{1,20})\\s*[,;—-]?\\s*(?:no|sorry|actually|nein|sondern|pardon|perdón|digo|plutôt)\\s*[,;—-]?\\s*([+−-]?\\d+(?:[.,:/-]\\d+)*)\\s+\\2(?!\\p{L})", RegexOption.IGNORE_CASE)
    private val assistantPrefix = Regex("^(here is (?:the|your) (?:corrected|cleaned)|sure[,!:]|corrected text:|here’s (?:the|your) (?:corrected|cleaned))", RegexOption.IGNORE_CASE)
    private val negation = Regex("\\b(?:no|not|never|cannot|nein|nicht|niemals|kein\\p{L}*|non|pas|jamais|nunca|ningún|ninguna)\\b|n’t\\b|n't\\b", RegexOption.IGNORE_CASE)
    private val correctionCue = Regex("(\\p{L}{2,})\\s*,\\s*(?:no|nein|non)\\s*,\\s*(\\p{L}{2,})", RegexOption.IGNORE_CASE)
    private val currency = Regex("[$€£¥₹₩₽]")

    fun validate(original: String, candidate: String, mode: RefinementMode, protectedTerms: List<String> = emptyList()): Result {
        if (candidate.isBlank()) return Result.Reject("empty")
        if (candidate.length > 16_384) return Result.Reject("oversized")
        if (candidate == original) return Result.Accept
        if (candidate.contains("```") && !original.contains("```")) return Result.Reject("added_markup")
        if (assistantPrefix.containsMatchIn(candidate) && !assistantPrefix.containsMatchIn(original)) return Result.Reject("added_commentary")
        val lowerBound = if (mode == RefinementMode.CLEAN_DICTATION) .25 else .5
        if (candidate.length < original.length * lowerBound) return Result.Reject("excessive_shortening")
        if (candidate.length > original.length * 1.6 + 24) return Result.Reject("excessive_expansion")
        fun literals(regex: Regex, text: String) = regex.findAll(text).map { it.value.trimEnd('.', ',', ';', '!', '?', ')', ']') }.toSet()
        if (literals(url, original) != literals(url, candidate)) return Result.Reject("url_changed")
        if (literals(email, original) != literals(email, candidate)) return Result.Reject("email_changed")
        fun numbers(text: String) = number.findAll(text).map { it.value }.toList()
        val oldNumbers = numbers(original)
        val newNumbers = numbers(candidate)
        val corrected = if (mode == RefinementMode.CLEAN_DICTATION) {
            val units = unitCorrection.replace(original) { "${it.groupValues[3]} ${it.groupValues[2]}" }
            correction.replace(units) { it.groupValues[2] }
        } else original
        if (oldNumbers.isNotEmpty() && oldNumbers != newNumbers) {
            // Only a directly stated numeric correction may discard the immediately superseded value.
            if (numbers(corrected) != newNumbers) return Result.Reject("number_changed")
        }
        if (currency.containsMatchIn(original) && literals(currency, original) != literals(currency, candidate)) return Result.Reject("currency_changed")
        val semanticOriginal = if (mode == RefinementMode.CLEAN_DICTATION) correctionCue.replace(corrected, "$1 $2") else original
        if (negation.containsMatchIn(semanticOriginal) != negation.containsMatchIn(candidate)) return Result.Reject("negation_changed")
        for (term in protectedTerms) {
            if (term.isNotBlank() && original.contains(term) && !candidate.contains(term)) return Result.Reject("protected_term_changed")
        }
        return Result.Accept
    }
}
