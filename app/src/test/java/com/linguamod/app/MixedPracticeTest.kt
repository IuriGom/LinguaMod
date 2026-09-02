package com.linguamod.app

import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.PracticeGenerator
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4 §4 mixed practice: 10 exercises across all completed units, each
 * slot weighted 60% toward wrong-or-never-attempted exercises and 40% toward
 * random review. The 60/40 distribution is asserted over 100 samples.
 */
class MixedPracticeTest {

    private val plugin: LinguaPluginDto by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(
            LinguaPluginDto.serializer(),
            File("src/main/assets/plugins/it.lingua").readText(),
        )
    }

    @Test fun `practice samples only from completed units`() {
        val completed = setOf(1, 2, 3)
        val allowedIds = plugin.units.filter { it.number in completed }
            .flatMap { u ->
                u.lessons!!.flatMap { it.exercises.map { e -> e.id!! } } +
                    u.checkpoint!!.exercises.map { it.id!! }
            }.toSet()
        repeat(20) { seed ->
            val session = PracticeGenerator.generate(plugin, completed, emptyMap(), kotlin.random.Random(seed))
            assertEquals(PracticeGenerator.QUESTIONS, session.size)
            assertTrue(session.all { it.id in allowedIds })
        }
    }

    @Test fun `practice from no completed units is empty`() {
        assertTrue(PracticeGenerator.generate(plugin, emptySet(), emptyMap(), kotlin.random.Random(1)).isEmpty())
    }

    @Test fun `focus share is about 60 percent over 100 samples`() {
        val completed = setOf(1, 2, 3, 4, 5)
        val allIds = plugin.units.filter { it.number in completed }
            .flatMap { u ->
                u.lessons!!.flatMap { it.exercises.map { e -> e.id!! } } +
                    u.checkpoint!!.exercises.map { it.id!! }
            }
        // A realistic mixed history: ~30 correct (review pool), 10 wrong,
        // the rest never attempted. Focus = wrong + never attempted.
        val results = allIds.mapIndexed { i, id ->
            id to when {
                i < 30 -> true
                i < 40 -> false
                else -> null
            }
        }.mapNotNull { (id, c) -> c?.let { id to it } }.toMap()
        val focusIds = allIds.filter { results[it] != true }.toSet()
        assertTrue("test needs both pools non-empty", results.values.any { it } && focusIds.isNotEmpty())

        var focusPicks = 0
        var total = 0
        repeat(100) { seed ->
            val session = PracticeGenerator.generate(plugin, completed, results, kotlin.random.Random(seed))
            assertEquals(PracticeGenerator.QUESTIONS, session.size)
            focusPicks += session.count { it.id in focusIds }
            total += session.size
        }
        val share = focusPicks.toDouble() / total
        // expected 0.6; 1000 Bernoulli draws → sd ≈ 0.015; 0.08 is >5 sd of slack
        assertTrue("focus share $share outside [0.52, 0.68]", share in 0.52..0.68)
    }
}
