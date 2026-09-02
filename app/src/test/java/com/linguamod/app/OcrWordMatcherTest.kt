package com.linguamod.app

import com.linguamod.app.ocr.OcrWordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR word tokenization + lemma candidates (Stage 4 §1). Tokenization follows
 * spec §9 normalization exactly: NFC, lowercase, punctuation stripped,
 * apostrophes act as token separators.
 */
class OcrWordMatcherTest {

    @Test fun `plain sentence tokenizes per word`() {
        assertEquals(listOf("ciao", "come", "stai"), OcrWordMatcher.tokens("Ciao, come stai?"))
    }

    @Test fun `punctuation is stripped`() {
        assertEquals(listOf("buongiorno"), OcrWordMatcher.tokens("«Buongiorno!»"))
        assertEquals(listOf("un", "caffe", "per", "favore"),
            OcrWordMatcher.tokens("Un caffe... per favore!"))
    }

    @Test fun `apostrophes split tokens and unify variants`() {
        assertEquals(listOf("l", "amore"), OcrWordMatcher.tokens("l'amore"))
        assertEquals(listOf("l", "amore"), OcrWordMatcher.tokens("L’amore")) // curly
        assertEquals(listOf("un", "amica"), OcrWordMatcher.tokens("un'amica"))
        assertEquals(listOf("l", "amore"), OcrWordMatcher.tokens("l' amore"))
    }

    @Test fun `blank and punctuation-only text yields no tokens`() {
        assertTrue(OcrWordMatcher.tokens("").isEmpty())
        assertTrue(OcrWordMatcher.tokens("  ,;!  ").isEmpty())
    }

    @Test fun `lemma candidate is the token itself first`() {
        assertEquals(listOf("ciao"), OcrWordMatcher.lemmaCandidates("Ciao!"))
    }

    @Test fun `glued article falls back to stripped lemma`() {
        val c = OcrWordMatcher.lemmaCandidates("lagatta")
        assertEquals("lagatta", c.first())
        assertTrue("gatta" in c)
    }

    @Test fun `blank token yields no candidates`() {
        assertTrue(OcrWordMatcher.lemmaCandidates("  ").isEmpty())
    }
}
