package com.linguamod.app

import com.linguamod.app.plugin.SpeakingScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §6.6 speaking scoring (Stage 3): token overlap vs targetIt after §9
 * normalization, pass at >= minAccuracy (default 0.7), with the
 * pronunciation-issue vs completely-different distinction.
 */
class SpeakingScorerTest {

    private val target = "Mi chiamo Marco e vivo a Roma da dieci anni" // 10 tokens

    @Test
    fun `exact transcript passes with score 1`() {
        val r = SpeakingScorer.score(target, target)
        assertEquals(1.0, r.score, 1e-9)
        assertTrue(r.passed)
        assertFalse(r.completelyDifferent)
    }

    @Test
    fun `normalization still passes - case punctuation apostrophes`() {
        val r = SpeakingScorer.score("L'amore vince.", "l amore vince")
        assertTrue(r.passed)
        assertEquals(1.0, r.score, 1e-9)
    }

    @Test
    fun `minAccuracy boundary - 8 of 10 tokens passes at default 0_7`() {
        // target tokens: mi chiamo marco e vivo a roma da dieci anni → last two miss
        val heard = "Mi chiamo Marco e vivo a Roma da xxxx xxxx"
        val r = SpeakingScorer.score(target, heard)
        assertEquals(0.8, r.score, 1e-9)
        assertTrue(r.passed)
    }

    @Test
    fun `minAccuracy boundary - 7 of 10 hits exactly 0_7 passes`() {
        val heard = "Mi chiamo Marco e vivo a Roma xxxx xxxx xxxx"
        val r = SpeakingScorer.score(target, heard)
        assertEquals(0.7, r.score, 1e-9)
        assertTrue("0.7 >= 0.7 must pass", r.passed)
        assertFalse(r.completelyDifferent) // fail=false so flag is false regardless
    }

    @Test
    fun `6 of 10 hits fails below the 0_7 threshold but is a pronunciation issue`() {
        val heard = "Mi chiamo Marco e vivo a xxxx xxxx xxxx xxxx"
        val r = SpeakingScorer.score(target, heard)
        assertEquals(0.6, r.score, 1e-9)
        assertFalse(r.passed)
        assertFalse("0.6 overlap is close — a pronunciation issue", r.completelyDifferent)
    }

    @Test
    fun `garbage transcript fails and is flagged completely different`() {
        val r = SpeakingScorer.score(target, "banana hammock trolley soup")
        assertEquals(0.0, r.score, 1e-9)
        assertFalse(r.passed)
        assertTrue(r.completelyDifferent)
    }

    @Test
    fun `fuzzy token counts - expected length 4+ at edit distance 1`() {
        // "marco" -> "marco" mistyped as "marxo": distance 1, length >= 4 → hit
        val r = SpeakingScorer.score("Mi chiamo Marco.", "mi chiamo marxo")
        assertTrue(r.passed)
        assertEquals(1.0, r.score, 1e-9)
    }

    @Test
    fun `short tokens are not fuzzy-matched`() {
        // "e" (length 1) -> "o": distance 1 but length < 4 → miss
        val r = SpeakingScorer.score("tè o caffè", "te e caffe")
        // tokens: te/o/caffe vs te/e/caffe → 2/3 ≈ 0.67 < 0.7
        assertFalse(r.passed)
    }

    @Test
    fun `explicit minAccuracy is honored`() {
        val r = SpeakingScorer.score(target, "Mi chiamo Marco e vivo a Roma da xxxx xxxx", minAccuracy = 0.9)
        assertEquals(0.8, r.score, 1e-9)
        assertFalse(r.passed)
    }

    @Test
    fun `out-of-range minAccuracy falls back to the 0_7 default`() {
        val r = SpeakingScorer.score(target, "Mi chiamo Marco e vivo a Roma xxxx xxxx xxxx", minAccuracy = 1.5)
        assertEquals(SpeakingScorer.DEFAULT_MIN_ACCURACY, r.minAccuracy, 1e-9)
        assertTrue(r.passed)
    }

    @Test
    fun `duplicate heard tokens do not inflate the score`() {
        val r = SpeakingScorer.score("Mi chiamo Marco.", "mi mi mi mi mi")
        assertTrue("only one 'mi' can be consumed", r.score <= 1.0 / 3.0 + 1e-9)
        assertFalse(r.passed)
    }
}
