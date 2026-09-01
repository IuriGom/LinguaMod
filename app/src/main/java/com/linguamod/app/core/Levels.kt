package com.linguamod.app.core

/** Levels and phases (Stage 2B). Exactly four levels, each at a phase boundary.
 *  All helpers tolerate plugins with fewer units than the full 60-unit course. */
object Levels {
    /** Phase boundaries: phase 1 = units 1–10, 2 = 11–25, 3 = 26–40, 4 = 41–60. */
    val PHASE_ENDS = listOf(10, 25, 40, 60)

    /** Level N is earned when the checkpoint of unit PHASE_ENDS[N-1] is passed. */
    fun levelFor(highestCompletedCheckpoint: Int?): Int {
        val h = highestCompletedCheckpoint ?: return 0
        return PHASE_ENDS.count { h >= it }
    }

    /** Checkpoint count needed for the next level; null when at max level. */
    fun nextThreshold(level: Int): Int? = PHASE_ENDS.getOrNull(level)

    /** Phase (1-based) containing [unitNumber]. */
    fun phaseFor(unitNumber: Int): Int =
        PHASE_ENDS.indexOfFirst { unitNumber <= it }.let { if (it < 0) PHASE_ENDS.size else it + 1 }

    /** Inclusive [start, end] unit range of a 1-based phase, clamped to [totalUnits]. */
    fun phaseRange(phase: Int, totalUnits: Int): IntRange {
        val start = if (phase <= 1) 1 else PHASE_ENDS[phase - 2] + 1
        val end = PHASE_ENDS.getOrNull(phase - 1)?.coerceAtMost(totalUnits) ?: totalUnits
        return start..maxOf(start, end)
    }
}
