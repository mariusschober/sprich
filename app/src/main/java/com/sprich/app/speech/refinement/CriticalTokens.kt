package com.sprich.app.speech.refinement

import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic critical-token representation for meaning protection (S02).
 *
 * Covers numeric values with signs, decimals, ranges, dates, times, currency, percentages,
 * measurement units attached to numbers, and identifiers (phone/account/reference strings).
 * Occurrence AND order are preserved — comparison is on the ordered sequence, not a set,
 * so dropped/duplicated/reordered values are detected.
 *
 * Phone/account/reference strings are treated as opaque identifiers, never arithmetic values.
 * Numeric ambiguity is preserved rather than guessed: "1.000" and "1,000" are different
 * tokens, as are "3–5" and "35". Only documented locale transformations (none currently
 * beyond case/compatibility folding) and explicitly stated corrections may change the sequence.
 */
object CriticalTokens {

    // Amount with optional sign, decimals, ranges (3-5 / 3–5), times (9:30), dates (2026-09-01),
    // decimals with . or , kept literally so 1.000 != 1,000.
    // The leading signed alternative comes first so a directly attached sign sticks
    // to its number ("-5" is one token, never bare "5"): dropping a minus flips
    // meaning (freeze vs heat) and must reject. findAll is left-to-right and
    // non-overlapping, so longer established spans ("5-10", "2026-09-01") still win
    // and hyphenated words ("well-known", no digit after the sign) never match.
    private val amount = Regex(
        "[+−\\-–—]\\d[\\d.,:/\\-–—]*\\d?|[+−\\-–—]?\\d[\\d.,:/\\-–—]*\\d|\\d[+−\\-–—]?|\\d",
    )
    // Unit directly attached or adjacent: mg, g, kg, ml, €-words, %, °, etc.
    private val unitWord = Regex("[\\p{L}µ%°$€£¥₹₩₽]{1,20}")
    // Identifiers: T807D-style codes, long digit strings, dotted/grouped references.
    private val identifier = Regex("[\\p{L}]*\\d[\\p{L}\\d]*(?:[\\-–—/.:][\\p{L}\\d]+)+|[\\p{L}]{1,8}\\d[\\p{L}\\d]{0,12}|\\d{5,}")

    private val currencySymbol = Regex("[$€£¥₹₩₽]")

    /**
     * Ordered canonical critical tokens of [text]. Adjacent "amount + unit" pairs
     * ("50 Euro", "5 mg") are fused into one token so a unit change (mg→g) is detected
     * together with its number (S03 number+unit protection).
     */
    fun sequence(text: String): List<String> {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = mutableListOf<String>()
        // Collect amount spans with positions to fuse following units.
        data class Span(val start: Int, val end: Int, val token: String)
        val spans = mutableListOf<Span>()
        for (m in amount.findAll(norm)) {
            spans.add(Span(m.range.first, m.range.last + 1, canon(m.value)))
        }
        for (m in identifier.findAll(norm)) {
            // Skip identifiers fully inside an already-taken amount span.
            if (spans.any { m.range.first >= it.start && m.range.last + 1 <= it.end }) continue
            spans.add(Span(m.range.first, m.range.last + 1, canon(m.value)))
        }
        spans.sortBy { it.start }
        var i = 0
        while (i < spans.size) {
            val s = spans[i]
            // Fuse a directly following unit word ("50 Euro", "5 mg", "9:30 Uhr", "20 %").
            val rest = norm.substring(s.end)
            val unitMatch = Regex("^\\s{0,3}([\\p{L}µ%°]{1,20})").find(rest)
            if (unitMatch != null) {
                val unit = unitMatch.groupValues[1]
                // Only fuse plausible measurement/currency/time words, not arbitrary prose.
                if (isUnitWord(unit)) {
                    out.add(s.token + "⟨" + canon(unit) + "⟩")
                    i++
                    continue
                }
            }
            // Fuse a preceding currency symbol already glued ("€500", "$20").
            out.add(s.token)
            i++
        }
        // Standalone currency symbols attached to nothing numeric above still matter for
        // currency_changed-style checks when amounts were fused; handled by validator.
        return out
    }

    private fun canon(raw: String): String = raw.lowercase(Locale.ROOT)

    // Conservative unit lexicon (EN/DE/ES/FR + SI + currency words + time words).
    private val units = setOf(
        "mg", "g", "kg", "t", "ml", "l", "cl", "dl", "mm", "cm", "m", "km",
        "ms", "s", "sec", "min", "h", "uhr", "euro", "euros", "cent", "dollar", "dollars",
        "pfund", "pound", "pounds", "usd", "eur", "gbp", "chf", "%", "prozent", "percent",
        "grad", "°", "hz", "khz", "mb", "gb", "tb", "kmh", "mph", "m²", "m³",
        "euro", "dólar", "dólares", "libra", "hora", "horas", "minuto", "minutos",
        "heure", "heures", "minute", "minutes", "livre", "dollar", "dollars", "pourcent",
    )

    private fun isUnitWord(w: String): Boolean = units.contains(w.lowercase(Locale.ROOT))

    /** True when a currency symbol occurs anywhere (for currency_changed detection). */
    fun currencySymbols(text: String): Set<String> =
        currencySymbol.findAll(text).map { it.value }.toSet()
}
