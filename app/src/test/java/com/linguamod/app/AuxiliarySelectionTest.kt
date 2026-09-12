package com.linguamod.app

import com.linguamod.app.content.ConjugationReference
import com.linguamod.app.plugin.AnswerMatcher
import com.linguamod.app.plugin.LinguaPluginDto
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stage 6 AC#7: auxiliary-selection drill check for Unit 27 (passato prossimo II).
 * Every Unit 27 fill_blank whose answer is a past participle must, with the blank
 * filled, show the correct auxiliary directly before it: essere forms
 * (sono/sei/è/siamo/siete) for the hardcoded movement/state-change/reflexive list
 * in [ConjugationReference.ESSERE_AUXILIARY], avere forms otherwise.
 */
class AuxiliarySelectionTest {

    private val plugin: LinguaPluginDto by lazy {
        val text = File("src/main/assets/plugins/it.lingua").readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(LinguaPluginDto.serializer(), text)
    }

    private val avereForms = setOf("ho", "hai", "ha", "abbiamo", "avete", "hanno")
    private val essereForms = setOf("sono", "sei", "è", "siamo", "siete")

    private val participleTakesEssere: Map<String, Boolean> by lazy {
        val map = mutableMapOf<String, Boolean>()
        ConjugationReference.PARTICIPLES.forEach { (verb, form) ->
            if (verb.startsWith("-")) return@forEach
            val essere = verb in ConjugationReference.ESSERE_AUXILIARY ||
                "${verb}si" in ConjugationReference.ESSERE_AUXILIARY
            map[form] = essere
            if (form.endsWith("o")) {
                map[form.dropLast(1) + "a"] = essere
                map[form.dropLast(1) + "i"] = essere
                map[form.dropLast(1) + "e"] = essere
            }
        }
        map
    }

    @Test
    fun `unit 27 fill_blank drills select the right auxiliary`() {
        val u27 = plugin.units.firstOrNull { it.number == 27 } ?: error("unit 27 missing")
        val errors = mutableListOf<String>()
        var drills = 0
        u27.lessons?.forEach { l ->
            l.exercises.filter { it.type == "fill_blank" }.forEach { e ->
                val answer = e.answers?.firstOrNull() ?: return@forEach
                val filled = e.sentence!!.replace("___", answer)
                val tokens = AnswerMatcher.normalize(filled).split(" ")
                val pIdx = tokens.indexOfFirst { it in participleTakesEssere }
                if (pIdx < 0) return@forEach // not a past-tense drill
                drills++
                val prev = tokens.getOrNull(pIdx - 1)
                val takesEssere = participleTakesEssere.getValue(tokens[pIdx])
                val ok = if (takesEssere) prev in essereForms else prev in avereForms
                if (!ok) {
                    errors += "${e.id}: '$filled' — participle '${tokens[pIdx]}' needs " +
                        (if (takesEssere) "essere" else "avere") + ", found '$prev'"
                }
            }
        }
        if (drills < 3) fail("unit 27 must drill auxiliary selection in >= 3 fill_blanks, found $drills")
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }
}
