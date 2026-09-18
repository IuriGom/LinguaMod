package com.linguamod.app.plugin

import java.text.Normalizer
import java.util.Locale

/**
 * Spec §9 answer matching. Pure JVM.
 */
object AnswerMatcher {

    private val apostrophes = Regex("[’`ʼ´]")
    private val punctuation = Regex("[.,!?;:\"«»()]")

    /** §9 normalization: NFC, lowercase, trim, collapse whitespace,
     *  unify apostrophes (they act as token separators), strip punctuation. */
    fun normalize(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFC)
        t = apostrophes.replace(t, "'")
        t = punctuation.replace(t, "")
        t = t.lowercase(Locale.ROOT)
        // elision: "l'amore" == "l' amore" == "l amore" — apostrophe is a word boundary
        t = t.replace("'", " ")
        t = t.trim().replace(Regex("\\s+"), " ")
        return t
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[n]
    }

    /** §9: token-by-token; exact, or expected token length >= 4 and edit distance exactly 1. */
    fun matches(expectedRaw: String, actualRaw: String): Boolean {
        val expected = normalize(expectedRaw).split(" ").filter { it.isNotEmpty() }
        val actual = normalize(actualRaw).split(" ").filter { it.isNotEmpty() }
        if (expected.size != actual.size) return false
        return expected.zip(actual).all { (e, a) ->
            e == a || (e.length >= 4 && levenshtein(e, a) == 1)
        }
    }

    fun matchesAny(accepted: List<String>, actual: String): Boolean = accepted.any { matches(it, actual) }

    // --- accent leniency (Stage 8 audit): an answer whose ONLY deviation is a
    // missing accent (è→e, à→a, …) is still correct, but the engine flags it so
    // the learner sees a typo notice instead of a silent pass.

    private val combiningMarks = Regex("\\p{Mn}+")

    /** §9 normalization followed by accent folding (NFD, strip combining marks). */
    fun normalizeFolded(s: String): String =
        combiningMarks.replace(Normalizer.normalize(normalize(s), Normalizer.Form.NFD), "")

    private fun exactTokens(s: String) = normalize(s).split(" ").filter { it.isNotEmpty() }
    private fun foldedTokens(s: String) = normalizeFolded(s).split(" ").filter { it.isNotEmpty() }

    /** Token-exact match after §9 normalization — no fuzzy typo tolerance. */
    fun matchesExactly(expectedRaw: String, actualRaw: String): Boolean {
        val expected = exactTokens(expectedRaw)
        val actual = exactTokens(actualRaw)
        return expected.size == actual.size &&
            expected.zip(actual).all { (e, a) -> e == a }
    }

    fun matchesAnyExact(accepted: List<String>, actual: String): Boolean =
        accepted.any { matchesExactly(it, actual) }

    /** True when [actual] equals some accepted variant after accent folding but
     *  not exactly — i.e. the only mistakes are missing accents. */
    fun accentOnlyDifference(accepted: List<String>, actual: String): Boolean =
        accepted.any { exp ->
            val e = foldedTokens(exp)
            val a = foldedTokens(actual)
            e.size == a.size && e.zip(a).all { (x, y) -> x == y } && !matchesExactly(exp, actual)
        }

    /** §6.6 speaking scoring: token overlap vs target after §9 normalization.
     *  Fraction of target tokens present (exact or fuzzy) in the transcript. */
    fun tokenOverlap(targetRaw: String, heardRaw: String): Double {
        val target = normalize(targetRaw).split(" ").filter { it.isNotEmpty() }
        if (target.isEmpty()) return 0.0
        val heard = normalize(heardRaw).split(" ").filter { it.isNotEmpty() }
        val remaining = heard.toMutableList()
        var hit = 0
        for (t in target) {
            val exactIdx = remaining.indexOf(t)
            if (exactIdx >= 0) { remaining.removeAt(exactIdx); hit++; continue }
            if (t.length >= 4) {
                val fuzzyIdx = remaining.indexOfFirst { levenshtein(t, it) == 1 }
                if (fuzzyIdx >= 0) { remaining.removeAt(fuzzyIdx); hit++ }
            }
        }
        return hit.toDouble() / target.size
    }
}
