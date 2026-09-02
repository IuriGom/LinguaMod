package com.linguamod.app.plugin

/**
 * Spec §6.6 speaking scoring. Pure JVM.
 *
 * Both strings normalized per §9; token overlap vs `targetIt` (exact or the §9
 * fuzzy rule) via [AnswerMatcher.tokenOverlap]; pass at >= minAccuracy
 * (default 0.7). On failure the score also distinguishes a pronunciation
 * issue from the recognizer hearing something completely different.
 */
object SpeakingScorer {

    const val DEFAULT_MIN_ACCURACY = 0.7

    /** Failed attempts below this overlap are "completely different", not accent. */
    const val COMPLETELY_DIFFERENT_BELOW = 0.35

    data class Result(
        val score: Double,
        val minAccuracy: Double,
        val passed: Boolean,
        /** True when a failed attempt barely overlaps the target at all. */
        val completelyDifferent: Boolean,
    )

    fun score(targetIt: String, heard: String, minAccuracy: Double? = null): Result {
        val threshold = minAccuracy?.takeIf { it > 0.0 && it <= 1.0 } ?: DEFAULT_MIN_ACCURACY
        val overlap = AnswerMatcher.tokenOverlap(targetIt, heard)
        val passed = overlap >= threshold
        return Result(
            score = overlap,
            minAccuracy = threshold,
            passed = passed,
            completelyDifferent = !passed && overlap < COMPLETELY_DIFFERENT_BELOW,
        )
    }
}
