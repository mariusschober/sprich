package com.sprich.app.speech

import com.sprich.app.speech.refinement.RefinementMode
import com.sprich.app.speech.refinement.RefinementValidator
import org.junit.Assert.*
import org.junit.Test

/**
 * S02/S03 — numeric, unit, negation and critical-fact protection across EN/DE/ES/FR.
 * Both Faithful (default) and Polished must reject meaning changes; the corrected
 * explicit self-correction ("50 Euro, nein, 15 Euro" -> "15 Euro") must be preserved.
 */
class CriticalMeaningTest {

    private fun rejectFaithful(o: String, c: String) = assertTrue(
        "'$o' -> '$c' must reject in Faithful, got ${RefinementValidator.validate(o, c, RefinementMode.FAITHFUL)}",
        RefinementValidator.validate(o, c, RefinementMode.FAITHFUL) is RefinementValidator.Result.Reject,
    )
    private fun rejectPolished(o: String, c: String) = assertTrue(
        "'$o' -> '$c' must reject in Polished, got ${RefinementValidator.validate(o, c, RefinementMode.POLISHED)}",
        RefinementValidator.validate(o, c, RefinementMode.POLISHED) is RefinementValidator.Result.Reject,
    )
    private fun rejectBoth(o: String, c: String) {
        rejectFaithful(o, c); rejectPolished(o, c)
    }
    private fun acceptFaithful(o: String, c: String) = assertTrue(
        "'$o' -> '$c' must accept in Faithful, got ${RefinementValidator.validate(o, c, RefinementMode.FAITHFUL)}",
        RefinementValidator.validate(o, c, RefinementMode.FAITHFUL) is RefinementValidator.Result.Accept,
    )

    // S02: invented / removed / altered numeric meaning ----------------------------

    @Test fun inventedNumberInNonnumericSentence() {
        rejectBoth("Meet tomorrow.", "Meet tomorrow at 5.")
    }

    @Test fun addedPriceToNonnumericSentence() {
        rejectBoth("The report is ready.", "The report is ready for 20 Euro.")
    }

    @Test fun droppedMinusSign() {
        rejectBoth("The balance is -40 Euro.", "The balance is 40 Euro.")
    }

    @Test fun decimalSeparatorsAreNotGuessed() {
        // 1.000 vs 1,000 ambiguity is preserved, never silently converted.
        rejectBoth("Transfer 1.000 Euro.", "Transfer 1,000 Euro.")
        rejectBoth("Transfer 1,000 Euro.", "Transfer 1.000 Euro.")
    }

    @Test fun rangeVersusNumber() {
        rejectBoth("Take 3–5 tablets.", "Take 35 tablets.")
        rejectBoth("Take 3-5 tablets.", "Take 35 tablets.")
    }

    @Test fun datesTimes() {
        rejectBoth("Meeting on 2026-09-01.", "Meeting on 2026-09-02.")
        rejectBoth("Call at 9:30.", "Call at 10:30.")
    }

    @Test fun mixedLanguageAmounts() {
        rejectBoth("Das kostet 50 Euro.", "Das kostet 15 Euro.")
        rejectBoth("Cuesta 50 euros.", "Cuesta 15 euros.")
        rejectBoth("Ça coûte 50 euros.", "Ça coûte 15 euros.")
    }

    @Test fun explicitCorrectionPreserved() {
        // "50 Euro, nein, 15 Euro" -> "15 Euro" when correctly parsed: accepted, both modes.
        val orig = "50 Euro, nein, 15 Euro"
        assertTrue(RefinementValidator.validate(orig, "15 Euro.", RefinementMode.FAITHFUL) is RefinementValidator.Result.Accept)
        assertTrue(RefinementValidator.validate(orig, "15 Euro.", RefinementMode.POLISHED) is RefinementValidator.Result.Accept)
        val origEn = "Meet Tuesday, no, Wednesday at 2"
        assertTrue(RefinementValidator.validate(origEn, "Meet Wednesday at 2.", RefinementMode.FAITHFUL) is RefinementValidator.Result.Accept)
    }

    // S03: unit, negation, modality, uncertainty, recipients, order ----------------

    @Test fun unitChangeMgToG() {
        rejectBoth("Take 5 mg daily.", "Take 5 g daily.")
        rejectBoth("Nimm 5 mg täglich.", "Nimm 5 g täglich.")
        rejectBoth("Toma 5 mg al día.", "Toma 5 g al día.")
        rejectBoth("Prends 5 mg par jour.", "Prends 5 g par jour.")
    }

    @Test fun movedNegation() {
        rejectBoth(
            "I do not want tea, I want coffee.",
            "I want tea, I do not want coffee.",
        )
        rejectBoth(
            "Ich will keinen Tee, ich will Kaffee.",
            "Ich will Tee, ich will keinen Kaffee.",
        )
        rejectBoth(
            "No quiero té, quiero café.",
            "Quiero té, no quiero café.",
        )
        rejectBoth(
            "Je ne veux pas de thé, je veux du café.",
            "Je veux du thé, je ne veux pas de café.",
        )
    }

    @Test fun mayVersusMust() {
        rejectBoth("You may leave now.", "You must leave now.")
        rejectBoth("Du darfst jetzt gehen.", "Du musst jetzt gehen.")
    }

    @Test fun uncertaintyRemoval() {
        rejectBoth("Maybe we should wait.", "We should wait.")
        rejectBoth("Vielleicht sollten wir warten.", "Wir sollten warten.")
        rejectBoth("Quizás deberíamos esperar.", "Deberíamos esperar.")
        rejectBoth("Peut-être devrions-nous attendre.", "Nous devrions attendre.")
    }

    @Test fun changedRecipients() {
        rejectFaithful("Tell Marius the news.", "Tell John the news.")
    }

    @Test fun beforeAfterReversal() {
        rejectFaithful("Wash your hands before eating.", "Wash your hands after eating.")
    }

    @Test fun irrelevantAssistantResponse() {
        rejectBoth("hello world", "Hello! How can I help you today?")
        rejectBoth("hallo welt", "Hallo! Wie kann ich helfen?")
    }

    @Test fun meaningfulRepetitionPreserved() {
        // Faithful must not delete meaningful repetition or ordinary filler-like words.
        acceptFaithful("I said no, no, no.", "I said no, no, no.")
        acceptFaithful("very very good", "Very very good.")
    }

    @Test fun faithfulFormattingStillAccepted() {
        // Conservative rejection must not damage useful formatting acceptance.
        acceptFaithful("tomorrow i think we should meet at nine", "Tomorrow I think we should meet at nine.")
        acceptFaithful("morgen glaube ich sollten wir uns um neun treffen", "Morgen glaube ich, sollten wir uns um neun treffen.")
        acceptFaithful("hello world", "Hello world.")
    }

    @Test fun validatorFallbackIsCounted() {
        RefinementValidator.validate("Meet tomorrow.", "Meet tomorrow at 5.", RefinementMode.FAITHFUL)
        val snap = RefinementValidator.ValidationStats.snapshot()
        assertTrue((snap["total"] ?: 0) > 0)
        assertTrue((snap["reject:number_changed"] ?: 0) > 0)
    }
}
