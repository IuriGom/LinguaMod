package com.linguamod.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.linguamod.app.plugin.AnswerMatcher

/** Spec §9 answer matching — including every normative boundary. */
class AnswerMatcherTest {

    @Test fun `exact match after normalization`() {
        assertTrue(AnswerMatcher.matches("Mi chiamo Marco.", "mi chiamo marco"))
        assertTrue(AnswerMatcher.matches("  Ciao!  ", "ciao"))
    }

    @Test fun `case and punctuation are insignificant`() {
        assertTrue(AnswerMatcher.matches("Buonasera!", "BUONASERA"))
        assertTrue(AnswerMatcher.matches("Come ti chiami?", "come ti chiami"))
    }

    @Test fun `typo tolerance - edit distance exactly 1 accepted for length 4+`() {
        assertTrue(AnswerMatcher.matches("ciao", "ciao"))
        assertTrue(AnswerMatcher.matches("ciao", "ciaoo"))   // dist 1, len 4
        assertTrue(AnswerMatcher.matches("buonasera", "buonasera"))
        assertTrue(AnswerMatcher.matches("grazie", "grazle")) // dist 1, len 6
    }

    @Test fun `typo tolerance - edit distance 1 rejected for length 3`() {
        assertFalse(AnswerMatcher.matches("sono".take(3), "sxo")) // "son" vs "sxo": len 3
        assertFalse(AnswerMatcher.matches("tre", "tra"))
        assertFalse(AnswerMatcher.matches("no", "ni"))
    }

    @Test fun `word length exactly 4 vs 3 boundary`() {
        assertTrue(AnswerMatcher.matches("ciao", "ciao".replace('a', 'e')))  // len 4, dist 1 -> accept
        assertFalse(AnswerMatcher.matches("cao", "ceo"))                     // len 3, dist 1 -> reject
    }

    @Test fun `edit distance 2 always rejected`() {
        assertFalse(AnswerMatcher.matches("ciao", "ciee"))      // dist 2
        assertFalse(AnswerMatcher.matches("buonasera", "buonisiro"))
    }

    @Test fun `token count mismatch rejected`() {
        assertFalse(AnswerMatcher.matches("mi chiamo marco", "mi chiamo"))
        assertFalse(AnswerMatcher.matches("mi chiamo", "mi chiamo marco rossi"))
    }

    @Test fun `apostrophe variants are equivalent`() {
        assertTrue(AnswerMatcher.matches("l'amore", "l’amore"))
        assertTrue(AnswerMatcher.matches("l'amico", "l'amico"))
        assertTrue(AnswerMatcher.matches("un'amica", "un'amica"))
        assertTrue(AnswerMatcher.matches("l'amore", "l amore")) // elision space insignificant
    }

    @Test fun `matchesAny accepts any variant`() {
        assertTrue(AnswerMatcher.matchesAny(listOf("Sì", "Si"), "si"))
        assertFalse(AnswerMatcher.matchesAny(listOf("Sì", "Si"), "no"))
    }

    @Test fun `speaking token overlap scoring`() {
        assertTrue(AnswerMatcher.tokenOverlap("Mi chiamo Marco", "mi chiamo marco") == 1.0)
        val twoThirds = AnswerMatcher.tokenOverlap("Mi chiamo Marco", "mi chiamo")
        assertTrue(twoThirds > 0.6 && twoThirds < 0.7)
        assertTrue(AnswerMatcher.tokenOverlap("Buongiorno", "xyz") == 0.0)
    }
}
