package com.linguamod.app

import com.linguamod.app.content.ConjugationReference
import com.linguamod.app.plugin.AnswerMatcher
import com.linguamod.app.plugin.LinguaPluginDto
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stage 7 AC#7: subjunctive gating. No DISTINCTIVELY subjunctive form (a form
 * from the reference subjunctive tables that does not collide with the present
 * indicative or any other taught tense) may appear in any exercise answer in
 * units < 43. The subjunctive must be impossible to meet before Unit 43 teaches it.
 *
 * Scanned: fill_blank answers, acceptedIt, speaking targetIt, listening speakIt,
 * sentence_scramble correctSentence — everything a learner is scored on.
 */
class SubjunctiveGatingTest {

    private val plugin: LinguaPluginDto by lazy {
        val text = File("src/main/assets/plugins/it.lingua").readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(LinguaPluginDto.serializer(), text)
    }

    /** Forms that exist ONLY in subjunctive paradigms (not in any other reference tense). */
    private val distinctiveSubjunctiveForms: Set<String> by lazy {
        val others = mutableSetOf<String>()
        ConjugationReference.PRESENT.values.forEach { others += it.persons }
        ConjugationReference.FUTURE.values.forEach { others += it.persons }
        ConjugationReference.IMPERFECT.values.forEach { others += it.persons }
        ConjugationReference.CONDITIONAL.values.forEach { others += it.persons }
        others += ConjugationReference.PARTICIPLES.values
        val forms = mutableSetOf<String>()
        ConjugationReference.SUBJUNCTIVE_PRESENT.values.forEach { forms += it.persons }
        ConjugationReference.SUBJUNCTIVE_IMPERFECT.values.forEach { forms += it.persons }
        forms - others
    }

    @Test
    fun `no subjunctive form appears in answers before unit 43`() {
        val errors = mutableListOf<String>()
        fun scan(id: String?, answers: List<String>?, unitNumber: Int) {
            answers?.forEach { ans ->
                val tokens = AnswerMatcher.normalize(ans).split(" ")
                tokens.forEach { tok ->
                    if (tok in distinctiveSubjunctiveForms) {
                        errors += "$id (unit $unitNumber): answer '$ans' uses subjunctive form '$tok' before unit 43"
                    }
                }
            }
        }
        plugin.units.filter { (it.number ?: 0) < 43 }.forEach { u ->
            val n = u.number ?: return@forEach
            u.lessons?.forEach { l ->
                l.exercises.forEach { e ->
                    scan(e.id, e.answers, n)
                    scan(e.id, e.acceptedIt, n)
                    scan(e.id, e.targetIt?.let { listOf(it) }, n)
                    scan(e.id, e.speakIt?.let { listOf(it) }, n)
                    scan(e.id, e.correctSentence?.let { listOf(it) }, n)
                }
            }
            u.checkpoint?.exercises?.forEach { e ->
                scan(e.id, e.answers, n)
                scan(e.id, e.acceptedIt, n)
                scan(e.id, e.targetIt?.let { listOf(it) }, n)
                scan(e.id, e.speakIt?.let { listOf(it) }, n)
                scan(e.id, e.correctSentence?.let { listOf(it) }, n)
            }
        }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }
}
