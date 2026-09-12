package com.linguamod.app

import java.io.File
import kotlinx.serialization.json.Json
import com.linguamod.app.content.ConjugationReference
import com.linguamod.app.plugin.LinguaPluginDto
import org.junit.Assert.fail
import org.junit.Test

/**
 * Conjugation cross-check (Stage 2, "build it now, use it forever"):
 * fails if any grammarNotes conjugation table or exercise answer contradicts
 * the hardcoded reference paradigms in [ConjugationReference].
 *
 * Checks:
 * 1. If grammarNotes mentions a reference verb (e.g. "ESSERE"), its six present
 *    forms must all appear, exactly as in the reference.
 * 2. No exercise answer may place a wrong reference form after a subject
 *    pronoun (e.g. "io sei", "tu sono", "noi è").
 * 3. Exercise answers containing a reference verb form must use the form that
 *    exists in the reference (typo guard for content authors).
 */
class ConjugationCrossCheckTest {

    private val plugin: LinguaPluginDto by lazy {
        val text = File("src/main/assets/plugins/it.lingua").readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(LinguaPluginDto.serializer(), text)
    }

    private val persons = listOf("io", "tu", "lui/lei", "noi", "voi", "loro")

    @Test
    fun `grammarNotes conjugation tables match reference`() {
        val errors = mutableListOf<String>()
        plugin.units.forEach { unit ->
            unit.lessons?.forEach { lesson ->
                val notes = lesson.grammarNotes ?: return@forEach
                ConjugationReference.PRESENT.forEach { (verb, paradigm) ->
                    // trigger: the verb name appears uppercase as a table header, e.g. "ESSERE (to be)"
                    if (notes.contains(verb.uppercase())) {
                        paradigm.persons.forEachIndexed { i, form ->
                            val personLabel = persons[i].split("/")[0]
                            if (!notes.contains(form)) {
                                errors += "u${unit.number}/${lesson.id}: grammarNotes mention ${verb.uppercase()} but miss form '$form' ($personLabel)"
                            }
                        }
                    }
                }
            }
        }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }

    @Test
    fun `grammarNotes future tables match reference`() {
        // Stage 6: if a notes table uses a future form (trigger: the io-form, e.g.
        // "andrò"), ALL six persons of that verb's future must appear exactly as in
        // the reference — a half-written table is a content bug.
        val errors = mutableListOf<String>()
        plugin.units.forEach { unit ->
            unit.lessons?.forEach { lesson ->
                val notes = lesson.grammarNotes ?: return@forEach
                ConjugationReference.FUTURE.forEach { (verb, paradigm) ->
                    if (notes.contains(paradigm.io)) {
                        paradigm.persons.forEachIndexed { i, form ->
                            val personLabel = persons[i].split("/")[0]
                            if (!notes.contains(form)) {
                                errors += "u${unit.number}/${lesson.id}: grammarNotes use future '${paradigm.io}' ($verb) but miss form '$form' ($personLabel)"
                            }
                        }
                    }
                }
            }
        }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }

    @Test
    fun `no exercise answer contradicts subject pronoun + reference form`() {
        val errors = mutableListOf<String>()
        // wrong pairs: subject -> forms that must NOT follow it
        val forbidden = mutableMapOf<String, MutableSet<String>>()
        ConjugationReference.PRESENT.forEach { (_, p) ->
            fun forbid(subject: String, correct: String, wrongForms: List<String>) {
                forbidden.getOrPut(subject) { mutableSetOf() } += wrongForms.filter { it != correct }
            }
            forbid("io", p.io, listOf(p.tu, p.luiLei, p.noi, p.voi, p.loro))
            forbid("tu", p.tu, listOf(p.io, p.luiLei, p.noi, p.voi, p.loro))
            forbid("noi", p.noi, listOf(p.io, p.tu, p.luiLei, p.voi, p.loro))
            forbid("voi", p.voi, listOf(p.io, p.tu, p.luiLei, p.noi, p.loro))
            forbid("loro", p.loro, listOf(p.io, p.tu, p.luiLei, p.noi, p.voi))
        }
        val allAnswers = mutableListOf<Pair<String, String>>()
        plugin.units.forEach { u ->
            val collect = { id: String?, answers: List<String>? ->
                answers?.forEach { allAnswers += (id ?: "?") to it }
            }
            u.lessons?.forEach { l ->
                l.exercises.forEach { e ->
                    collect(e.id, e.answers); collect(e.id, e.acceptedIt); collect(e.id, e.acceptedEn)
                }
            }
            u.checkpoint?.exercises?.forEach { e ->
                collect(e.id, e.answers); collect(e.id, e.acceptedIt); collect(e.id, e.acceptedEn)
            }
        }
        allAnswers.forEach { (id, ans) ->
            val tokens = com.linguamod.app.plugin.AnswerMatcher.normalize(ans).split(" ")
            tokens.forEachIndexed { i, tok ->
                if (tok in forbidden) {
                    val next = tokens.getOrNull(i + 1)
                    if (next != null && next in forbidden[tok]!!) {
                        errors += "$id: '$ans' places '$next' after '$tok'"
                    }
                }
            }
        }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }
}
