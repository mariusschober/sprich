package com.sprich.app.speech

import com.sprich.app.speech.refinement.RefinementMode
import com.sprich.app.speech.refinement.RefinementValidator
import com.sprich.app.speech.refinement.TermCanonical
import org.junit.Assert.*
import org.junit.Test

/**
 * S04 — one canonical comparison for relevant-term discovery and enforcement.
 * Original spelling is preserved; matching is NFKC + case handling with token boundaries.
 * No blanket accent stripping: José != Jose.
 */
class TermCanonicalTest {

    @Test fun caseInsensitiveMatch() {
        assertTrue(TermCanonical.containsTerm("my name is Marius", "marius"))
        assertTrue(TermCanonical.containsTerm("my name is marius", "Marius"))
        assertTrue(TermCanonical.containsTerm("ALICE joined", "alice"))
    }

    @Test fun combiningCharactersMatch() {
        // e + combining acute (U+0301) must match precomposed U+00E9 via NFKC.
        val decomposed = "my name is Jose\u0301"
        val precomposed = "my name is Jos\u00E9"
        assertTrue(decomposed.length > precomposed.length) // sanity: forms really differ
        assertTrue(TermCanonical.containsTerm(decomposed, "Jos\u00E9"))
        assertTrue(TermCanonical.containsTerm(precomposed, "Jose\u0301"))
    }

    @Test fun accentsAreNotStripped() {
        assertFalse(TermCanonical.containsTerm("my name is Jose", "José"))
        assertFalse(TermCanonical.containsTerm("my name is José", "Jose"))
    }

    @Test fun multiwordCompanyNames() {
        assertTrue(TermCanonical.containsTerm("work at Acme Corp tomorrow", "Acme Corp"))
        assertFalse(TermCanonical.containsTerm("work at Acme tomorrow", "Acme Corp"))
        // Mixed-language names.
        assertTrue(TermCanonical.containsTerm("treffe Müller García um drei", "Müller García"))
    }

    @Test fun shortNamesDoNotMatchInsideWords() {
        assertFalse(TermCanonical.containsTerm("also tall", "Al"))
        assertFalse(TermCanonical.containsTerm("send it to Alice", "Al"))
        assertTrue(TermCanonical.containsTerm("call Al now", "Al"))
        assertFalse(TermCanonical.containsTerm("the allies arrived", "Al"))
    }

    @Test fun punctuationBoundaries() {
        assertTrue(TermCanonical.containsTerm("call Marius, please", "Marius"))
        assertTrue(TermCanonical.containsTerm("(Marius) called", "Marius"))
        assertTrue(TermCanonical.containsTerm("project T807D ships", "T807D"))
    }

    @Test fun discoveryAndEnforcementAgree() {
        val vocab = listOf("Marius", "Acme Corp", "Al", "José")
        val transcript = "call Al at Acme Corp with marius and josé"
        val relevant = TermCanonical.relevantTerms(transcript, vocab)
        assertTrue(relevant.contains("Marius"))
        assertTrue(relevant.contains("Acme Corp"))
        assertTrue(relevant.contains("Al"))
        // Validator enforcement uses the same comparison: dropping a discovered term rejects.
        val dropped = "call Al at Acme Corp with josé"
        val res = RefinementValidator.validate(transcript, dropped, RefinementMode.FAITHFUL, relevant)
        assertTrue(res is RefinementValidator.Result.Reject)
        // Keeping all discovered terms (plus formatting) accepts.
        val kept = "Call Al at Acme Corp with Marius and José."
        assertTrue(
            RefinementValidator.validate(transcript, kept, RefinementMode.FAITHFUL, relevant)
                is RefinementValidator.Result.Accept,
        )
    }

    @Test fun droppedButNeverPresentTermIsIgnored() {
        // A protected term absent from the original cannot be "dropped".
        val res = RefinementValidator.validate(
            "hello world", "Hello world.", RefinementMode.FAITHFUL, listOf("Marius"),
        )
        assertTrue(res is RefinementValidator.Result.Accept)
    }
}
