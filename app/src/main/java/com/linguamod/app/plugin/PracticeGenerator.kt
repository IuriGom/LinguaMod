package com.linguamod.app.plugin

import kotlin.random.Random

/**
 * Mixed Practice generation (Stage 4 §4): 10 exercises sampled across ALL
 * completed units (lessons and checkpoints, all implemented types). Each slot
 * draws from the focus set — exercises previously answered wrong or never
 * attempted — with probability [FOCUS_SHARE], and from random review
 * (previously-correct exercises) otherwise. Pure and deterministic under an
 * injected [Random] so the 60/40 weighting is unit-testable.
 */
object PracticeGenerator {
    const val QUESTIONS = 10
    const val FOCUS_SHARE = 0.6

    /**
     * @param results exerciseId → last-known correctness; absence = never attempted.
     */
    fun generate(
        plugin: LinguaPluginDto,
        completedUnits: Set<Int>,
        results: Map<String, Boolean>,
        random: Random,
    ): List<ExerciseDto> {
        val pool = plugin.units
            .filter { it.number in completedUnits }
            .flatMap { u ->
                u.lessons.orEmpty().flatMap { it.exercises } + u.checkpoint?.exercises.orEmpty()
            }
            .filter { it.type in ExerciseTypes.ALL }
        if (pool.isEmpty()) return emptyList()

        fun focusOf(source: List<ExerciseDto>) = source.filter { results[it.id] != true }
        fun reviewOf(source: List<ExerciseDto>) = source.filter { results[it.id] == true }

        val focus = focusOf(pool).toMutableList()
        val review = reviewOf(pool).toMutableList()
        val out = mutableListOf<ExerciseDto>()
        while (out.size < QUESTIONS) {
            if (focus.isEmpty() && review.isEmpty()) {
                // Pool smaller than QUESTIONS: recycle so the session fills up.
                focus += focusOf(pool)
                review += reviewOf(pool)
            }
            val wantFocus = random.nextDouble() < FOCUS_SHARE
            val source = when {
                wantFocus && focus.isNotEmpty() -> focus
                !wantFocus && review.isNotEmpty() -> review
                focus.isNotEmpty() -> focus
                review.isNotEmpty() -> review
                else -> break
            }
            out += source.removeAt(random.nextInt(source.size))
        }
        return out
    }
}
