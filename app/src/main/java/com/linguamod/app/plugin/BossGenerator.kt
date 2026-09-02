package com.linguamod.app.plugin

import kotlin.random.Random

/**
 * Boss battle gauntlet generation (Stage 4 §3): 15 questions sampled from the
 * checkpoints of all completed units — content is 100% recycled from what was
 * already taught. Exercises the learner previously got wrong (ExerciseResult)
 * are [WRONG_WEIGHT]× as likely to be picked. Pure and deterministic under an
 * injected [Random] so the sampling rules are unit-testable.
 */
object BossGenerator {
    const val QUESTIONS = 15
    const val MAX_WRONG = 3
    const val WRONG_WEIGHT = 3

    fun generate(
        plugin: LinguaPluginDto,
        completedUnits: Set<Int>,
        wrongIds: Set<String>,
        random: Random,
    ): List<ExerciseDto> {
        val pool = plugin.units
            .filter { it.number in completedUnits }
            .mapNotNull { it.checkpoint }
            .flatMap { it.exercises }
            .filter { it.type in ExerciseTypes.ALL }
        if (pool.isEmpty()) return emptyList()
        val out = mutableListOf<ExerciseDto>()
        var remaining = pool
        while (out.size < QUESTIONS) {
            if (remaining.isEmpty()) remaining = pool // tiny pools: cycle
            val pick = weightedPick(remaining, wrongIds, random)
            out += pick
            remaining = remaining - pick
        }
        return out
    }

    private fun weightedPick(
        pool: List<ExerciseDto>,
        wrongIds: Set<String>,
        random: Random,
    ): ExerciseDto {
        val weights = pool.map { if (it.id in wrongIds) WRONG_WEIGHT else 1 }
        var r = random.nextInt(weights.sum())
        pool.forEachIndexed { i, e ->
            r -= weights[i]
            if (r < 0) return e
        }
        return pool.last()
    }
}
