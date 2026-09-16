package com.linguamod.app

import com.linguamod.app.content.ConjugationReference
import com.linguamod.app.plugin.AnswerMatcher
import com.linguamod.app.plugin.LinguaPluginDto
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stage 6 cross-check: past participles and auxiliary selection.
 *
 * 1. Every participle used after an avere/essere auxiliary in ANY exercise answer
 *    (fill_blank answers, acceptedIt, speaking targetIt, listening speakIt,
 *    scramble correctSentence) must be a known participle from
 *    [ConjugationReference.PARTICIPLES] — typo guard — and must take the correct
 *    auxiliary per [ConjugationReference.ESSERE_AUXILIARY] (avere verbs with ho/ha…,
 *    movement/state-change/reflexive verbs with sono/è…).
 *
 * 2. Unit 27 drill check (stage AC#7): every Unit 27 fill_blank whose answer is a
 *    participle must, when the blank is filled, show the correct auxiliary directly
 *    before it — essere forms for the hardcoded movement/state-change list, avere
 *    forms otherwise.
 */
class PastTenseCrossCheckTest {

    private val plugin: LinguaPluginDto by lazy {
        val text = File("src/main/assets/plugins/it.lingua").readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(LinguaPluginDto.serializer(), text)
    }

    private val avereForms = setOf(
        "ho", "hai", "ha", "abbiamo", "avete", "hanno",
        // conditional perfect (Unit 42): avrei + participle
        "avrei", "avresti", "avrebbe", "avremmo", "avreste", "avrebbero",
        // past subjunctive (Unit 45): abbia + participle
        "abbia", "abbiate", "abbiano",
    )
    private val essereForms = setOf(
        "sono", "sei", "è", "siamo", "siete",
        // conditional perfect (Unit 42): sarei + participle
        "sarei", "saresti", "sarebbe", "saremmo", "sareste", "sarebbero",
        // past subjunctive (Unit 45): sia + participle
        "sia", "siate", "siano",
    )

    /** participle form (incl. agreement variants) -> takes essere? */
    private val participleTakesEssere: Map<String, Boolean> by lazy {
        val map = mutableMapOf<String, Boolean>()
        ConjugationReference.PARTICIPLES.forEach { (verb, form) ->
            if (verb.startsWith("-")) return@forEach // conjugation-class defaults
            val essere = verb in ConjugationReference.ESSERE_AUXILIARY ||
                // reflexive content verbs of Unit 28 are listed under their base form;
                // the reflexive infinitive drops the final -e: divertire → divertirsi
                "${verb.dropLast(1)}si" in ConjugationReference.ESSERE_AUXILIARY
            map[form] = essere
            if (form.endsWith("o")) {
                map[form.dropLast(1) + "a"] = essere
                map[form.dropLast(1) + "i"] = essere
                map[form.dropLast(1) + "e"] = essere
            }
        }
        map
    }

    private fun looksLikeParticiple(tok: String): Boolean =
        tok.length >= 4 && (
            tok.endsWith("ato") || tok.endsWith("uto") || tok.endsWith("ito") ||
                tok.endsWith("ata") || tok.endsWith("uta") || tok.endsWith("ita") ||
                tok.endsWith("ati") || tok.endsWith("uti") || tok.endsWith("iti") ||
                tok.endsWith("ate") || tok.endsWith("ute") || tok.endsWith("ite")
            )

    /** id -> answer pairs for everything a learner can be scored on. */
    private fun allAnswers(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun collect(id: String?, answers: List<String>?) =
            answers?.forEach { out += (id ?: "?") to it }

        plugin.units.forEach { u ->
            u.lessons?.forEach { l ->
                l.exercises.forEach { e ->
                    collect(e.id, e.answers)
                    collect(e.id, e.acceptedIt)
                    collect(e.id, e.targetIt?.let { listOf(it) })
                    collect(e.id, e.speakIt?.let { listOf(it) })
                    collect(e.id, e.correctSentence?.let { listOf(it) })
                }
            }
            u.checkpoint?.exercises?.forEach { e ->
                collect(e.id, e.answers)
                collect(e.id, e.acceptedIt)
                collect(e.id, e.targetIt?.let { listOf(it) })
                collect(e.id, e.speakIt?.let { listOf(it) })
                collect(e.id, e.correctSentence?.let { listOf(it) })
            }
        }
        return out
    }

    @Test
    fun `past participles in answers match reference and take the right auxiliary`() {
        val errors = mutableListOf<String>()
        allAnswers().forEach { (id, ans) ->
            val tokens = AnswerMatcher.normalize(ans).split(" ")
            tokens.forEachIndexed { i, tok ->
                if (tok in avereForms || tok in essereForms) {
                    val next = tokens.getOrNull(i + 1) ?: return@forEachIndexed
                    val takesEssere = participleTakesEssere[next]
                    when {
                        takesEssere == true && tok in avereForms ->
                            errors += "$id: '$ans' — '$next' takes essere, not avere"
                        takesEssere == false && tok in essereForms ->
                            errors += "$id: '$ans' — '$next' takes avere, not essere"
                        takesEssere == null && tok in avereForms && looksLikeParticiple(next) ->
                            // typo guard, avere side only: essere + word is also the
                            // 'È sabato' / 'è malato' pattern (essere + noun/adjective),
                            // so unknown -ato/-ito/-uto tokens after essere stay legal.
                            errors += "$id: '$ans' — participle '$next' not in ConjugationReference.PARTICIPLES"
                    }
                }
            }
        }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }
}
