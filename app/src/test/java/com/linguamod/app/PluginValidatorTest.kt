package com.linguamod.app

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.linguamod.app.plugin.PluginValidator

/**
 * Spec §8 validator tests: the bundled plugin must pass; every rejection
 * rule has at least one failing case. Runs as a plain JVM test.
 */
class PluginValidatorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val baseFile = File("src/main/assets/plugins/it.lingua")
    private val base: JsonObject by lazy {
        json.parseToJsonElement(baseFile.readText()).jsonObject
    }

    private fun validate(obj: JsonObject) = PluginValidator.validateText(obj.toString())

    private fun rules(errors: List<com.linguamod.app.plugin.ValidationError>) =
        errors.map { it.rule }.toSet()

    private fun mutate(name: String, fn: (root: MutableMap<String, JsonElement>) -> Unit): JsonObject {
        val m = base.toMutableMap()
        fn(m)
        return JsonObject(m)
    }

    private fun unitsOf(root: MutableMap<String, JsonElement>) =
        (root["units"] as JsonArray).toMutableList()

    private fun setUnits(root: MutableMap<String, JsonElement>, units: List<JsonElement>) {
        root["units"] = JsonArray(units)
    }

    private fun editUnit(units: List<JsonElement>, idx: Int, fn: (MutableMap<String, JsonElement>) -> Unit): List<JsonElement> {
        val list = units.toMutableList()
        val u = list[idx].jsonObject.toMutableMap()
        fn(u)
        list[idx] = JsonObject(u)
        return list
    }

    private fun firstExerciseOf(unit: JsonObject, lessonIdx: Int): MutableMap<String, JsonElement> =
        unit["lessons"]!!.jsonArray[lessonIdx].jsonObject["exercises"]!!.jsonArray[0].jsonObject.toMutableMap()

    // ---------- the real plugin must pass ----------

    @Test fun `bundled it_lingua validates with zero errors`() {
        val result = PluginValidator.validateFile(baseFile.readBytes())
        assertTrue("errors: ${result.errors}", result.isValid)
    }

    // ---------- R1 ----------

    @Test fun `R1 rejects non-JSON`() {
        val r = PluginValidator.validateText("this is not json")
        assertFalse(r.isValid); assertTrue(rules(r.errors).contains("R1"))
    }

    @Test fun `R1 rejects empty file`() {
        val r = PluginValidator.validateFile(ByteArray(0))
        assertFalse(r.isValid); assertTrue(rules(r.errors).contains("R1"))
    }

    @Test fun `R1 rejects wrong formatVersion`() {
        val r = validate(mutate("x") { it["formatVersion"] = JsonPrimitive(2) })
        assertTrue(rules(r.errors).contains("R1"))
    }

    @Test fun `R1 rejects deep nesting`() {
        var s = "1"
        repeat(40) { s = "{\"a\":$s}" }
        val r = PluginValidator.validateText(s)
        assertTrue(rules(r.errors).contains("R1"))
    }

    @Test fun `R1 rejects wrong types`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { it["number"] = JsonPrimitive("one") })
        })
        assertTrue(rules(r.errors).contains("R1"))
    }

    // ---------- R2 ----------

    @Test fun `R2 rejects missing meta fields`() {
        val r = validate(mutate("x") {
            val meta = it["meta"]!!.jsonObject.toMutableMap(); meta.remove("languageName"); it["meta"] = JsonObject(meta)
        })
        assertTrue(rules(r.errors).contains("R2"))
    }

    // ---------- R3 ----------

    @Test fun `R3 rejects duplicate dictionary id`() {
        val r = validate(mutate("x") { root ->
            val d = (root["dictionary"] as JsonArray).toMutableList()
            d.add(d[0])
            root["dictionary"] = JsonArray(d)
        })
        assertTrue(rules(r.errors).contains("R3"))
    }

    // ---------- R4 ----------

    @Test fun `R4 rejects non-consecutive unit numbers`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 1) { it["number"] = JsonPrimitive(5) })
        })
        assertTrue(rules(r.errors).contains("R4"))
    }

    // ---------- R5 ----------

    @Test fun `R5 rejects three lessons`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                u["lessons"] = JsonArray(u["lessons"]!!.jsonArray.drop(1))
            })
        })
        assertTrue(rules(r.errors).contains("R5"))
    }

    @Test fun `R5 rejects wrong lesson type order`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                val lessons = u["lessons"]!!.jsonArray.toMutableList()
                val l0 = lessons[0].jsonObject.toMutableMap(); l0["type"] = JsonPrimitive("grammar")
                lessons[0] = JsonObject(l0)
                u["lessons"] = JsonArray(lessons)
            })
        })
        assertTrue(rules(r.errors).contains("R5"))
    }

    // ---------- R6 ----------

    @Test fun `R6 rejects checkpoint with 9 exercises`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                val cp = u["checkpoint"]!!.jsonObject.toMutableMap()
                cp["exercises"] = JsonArray(cp["exercises"]!!.jsonArray.drop(1))
                u["checkpoint"] = JsonObject(cp)
            })
        })
        assertTrue(rules(r.errors).contains("R6"))
    }

    // ---------- R7 ----------

    @Test fun `R7 rejects three options`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                val lessons = u["lessons"]!!.jsonArray.toMutableList()
                val l0 = lessons[0].jsonObject.toMutableMap()
                val exs = l0["exercises"]!!.jsonArray.toMutableList()
                val e0 = exs[0].jsonObject.toMutableMap()
                e0["options"] = JsonArray(listOf("a", "b", "c").map { JsonPrimitive(it) })
                exs[0] = JsonObject(e0); l0["exercises"] = JsonArray(exs); lessons[0] = JsonObject(l0)
                u["lessons"] = JsonArray(lessons)
            })
        })
        assertTrue(rules(r.errors).contains("R7"))
    }

    @Test fun `R7 rejects out-of-range correctIndex`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                val lessons = u["lessons"]!!.jsonArray.toMutableList()
                val l0 = lessons[0].jsonObject.toMutableMap()
                val exs = l0["exercises"]!!.jsonArray.toMutableList()
                val e0 = exs[0].jsonObject.toMutableMap()
                e0["correctIndex"] = JsonPrimitive(4)
                exs[0] = JsonObject(e0); l0["exercises"] = JsonArray(exs); lessons[0] = JsonObject(l0)
                u["lessons"] = JsonArray(lessons)
            })
        })
        assertTrue(rules(r.errors).contains("R7"))
    }

    // ---------- R8 ----------

    @Test fun `R8 rejects empty explanation`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                val lessons = u["lessons"]!!.jsonArray.toMutableList()
                val l0 = lessons[0].jsonObject.toMutableMap()
                val exs = l0["exercises"]!!.jsonArray.toMutableList()
                val e0 = exs[0].jsonObject.toMutableMap()
                e0["explanation"] = JsonPrimitive("")
                exs[0] = JsonObject(e0); l0["exercises"] = JsonArray(exs); lessons[0] = JsonObject(l0)
                u["lessons"] = JsonArray(lessons)
            })
        })
        assertTrue(rules(r.errors).contains("R8"))
    }

    // ---------- R9 / R12 ----------

    @Test fun `R9 rejects noun without gender`() {
        val r = validate(mutate("x") { root ->
            val d = (root["dictionary"] as JsonArray).toMutableList()
            val e0 = d[6].jsonObject.toMutableMap() // "piacere" noun
            e0.remove("gender")
            d[6] = JsonObject(e0)
            root["dictionary"] = JsonArray(d)
        })
        assertTrue(rules(r.errors).contains("R9"))
    }

    @Test fun `R12 rejects wrong article form`() {
        val r = validate(mutate("x") { root ->
            val d = (root["dictionary"] as JsonArray).toMutableList()
            val e0 = d[6].jsonObject.toMutableMap()
            e0["article"] = JsonPrimitive("lo") // lo piacere is wrong
            d[6] = JsonObject(e0)
            root["dictionary"] = JsonArray(d)
        })
        assertTrue(rules(r.errors).contains("R12"))
    }

    // ---------- R10 / R11 ----------

    @Test fun `R10 rejects dangling dictionaryRef`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                val lessons = u["lessons"]!!.jsonArray.toMutableList()
                val l0 = lessons[0].jsonObject.toMutableMap()
                l0["dictionaryRefs"] = JsonArray(listOf(JsonPrimitive("does-not-exist")))
                lessons[0] = JsonObject(l0)
                u["lessons"] = JsonArray(lessons)
            })
        })
        assertTrue(rules(r.errors).contains("R10"))
    }

    @Test fun `R10 rejects dangling introducedInUnit`() {
        val r = validate(mutate("x") { root ->
            val d = (root["dictionary"] as JsonArray).toMutableList()
            val e0 = d[0].jsonObject.toMutableMap()
            e0["introducedInUnit"] = JsonPrimitive(99)
            d[0] = JsonObject(e0)
            root["dictionary"] = JsonArray(d)
        })
        assertTrue(rules(r.errors).contains("R10"))
    }

    @Test fun `R11 rejects forward vocabulary reference`() {
        val r = validate(mutate("x") { root ->
            // unit 1 lesson refs a word introduced in unit 2
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                val lessons = u["lessons"]!!.jsonArray.toMutableList()
                val l0 = lessons[0].jsonObject.toMutableMap()
                l0["dictionaryRefs"] = JsonArray(
                    (l0["dictionaryRefs"]!!.jsonArray + JsonPrimitive("italiano"))
                )
                lessons[0] = JsonObject(l0)
                u["lessons"] = JsonArray(lessons)
            })
        })
        assertTrue(rules(r.errors).contains("R11"))
    }

    // ---------- R13 / R15 / R16 ----------

    @Test fun `R13 rejects scramble that is not a permutation`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                val lessons = u["lessons"]!!.jsonArray.toMutableList()
                val l3 = lessons[3].jsonObject.toMutableMap()
                val exs = l3["exercises"]!!.jsonArray.toMutableList()
                // u1l4e4 is index 3 (scramble? no — it's listening). Find scramble if present; else craft one.
                val scr = exs.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.content == "sentence_scramble" }
                if (scr >= 0) {
                    val e = exs[scr].jsonObject.toMutableMap()
                    e["tokens"] = JsonArray(listOf("a", "b", "c").map { JsonPrimitive(it) })
                    exs[scr] = JsonObject(e)
                } else {
                    val e = exs[0].jsonObject.toMutableMap()
                    e["type"] = JsonPrimitive("sentence_scramble")
                    e["tokens"] = JsonArray(listOf("Ciao", "mondo").map { JsonPrimitive(it) })
                    e["correctSentence"] = JsonPrimitive("Ciao terra")
                    exs[0] = JsonObject(e)
                }
                l3["exercises"] = JsonArray(exs); lessons[3] = JsonObject(l3)
                u["lessons"] = JsonArray(lessons)
            })
        })
        assertTrue(rules(r.errors).contains("R13"))
    }

    @Test fun `R16 rejects fill_blank without blank`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                val lessons = u["lessons"]!!.jsonArray.toMutableList()
                val l0 = lessons[0].jsonObject.toMutableMap()
                val exs = l0["exercises"]!!.jsonArray.toMutableList()
                val idx = exs.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.content == "fill_blank" }
                val e = exs[idx].jsonObject.toMutableMap()
                e["sentence"] = JsonPrimitive("Ciao mondo")
                exs[idx] = JsonObject(e); l0["exercises"] = JsonArray(exs); lessons[0] = JsonObject(l0)
                u["lessons"] = JsonArray(lessons)
            })
        })
        assertTrue(rules(r.errors).contains("R16"))
    }

    @Test fun `R15 rejects lesson with 3 exercises`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                val lessons = u["lessons"]!!.jsonArray.toMutableList()
                val l0 = lessons[0].jsonObject.toMutableMap()
                l0["exercises"] = JsonArray(l0["exercises"]!!.jsonArray.take(3))
                lessons[0] = JsonObject(l0)
                u["lessons"] = JsonArray(lessons)
            })
        })
        assertTrue(rules(r.errors).contains("R15"))
    }

    @Test fun `R15 rejects bad speaking minAccuracy`() {
        val r = validate(mutate("x") { root ->
            setUnits(root, editUnit(unitsOf(root), 0) { u ->
                val lessons = u["lessons"]!!.jsonArray.toMutableList()
                val l3 = lessons[3].jsonObject.toMutableMap()
                val exs = l3["exercises"]!!.jsonArray.toMutableList()
                val idx = exs.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.content == "speaking" }
                val e = exs[idx].jsonObject.toMutableMap()
                e["minAccuracy"] = JsonPrimitive(1.5)
                exs[idx] = JsonObject(e); l3["exercises"] = JsonArray(exs); lessons[3] = JsonObject(l3)
                u["lessons"] = JsonArray(lessons)
            })
        })
        assertTrue(rules(r.errors).contains("R15"))
    }

    // ---------- R14 ----------

    @Test fun `R14 rejects orphan dictionary entry`() {
        val r = validate(mutate("x") { root ->
            val d = (root["dictionary"] as JsonArray).toMutableList()
            d.add(JsonObject(mapOf(
                "id" to JsonPrimitive("orphan"),
                "word" to JsonPrimitive("orphan"),
                "translation" to JsonPrimitive("orphan"),
                "partOfSpeech" to JsonPrimitive("adverb"),
                "examples" to JsonArray(listOf(JsonObject(mapOf(
                    "it" to JsonPrimitive("x"), "en" to JsonPrimitive("y"))))),
                "introducedInUnit" to JsonPrimitive(1),
            )))
            root["dictionary"] = JsonArray(d)
        })
        assertTrue(rules(r.errors).contains("R14"))
    }

    // ---------- R17 ----------

    @Test fun `R17 rejects story node with two correct choices`() {
        val story = JsonObject(mapOf(
            "id" to JsonPrimitive("s1"),
            "title" to JsonPrimitive("t"),
            "unlockAfterUnit" to JsonPrimitive(1),
            "nodes" to JsonArray(listOf(
                JsonObject(mapOf(
                    "id" to JsonPrimitive("n1"), "speaker" to JsonPrimitive("x"),
                    "textIt" to JsonPrimitive("t"),
                    "choices" to JsonArray(listOf(
                        JsonObject(mapOf("text" to JsonPrimitive("a"), "next" to JsonPrimitive("n2"), "correct" to JsonPrimitive(true))),
                        JsonObject(mapOf("text" to JsonPrimitive("b"), "next" to JsonPrimitive("n2"), "correct" to JsonPrimitive(true))),
                    )),
                )),
                JsonObject(mapOf("id" to JsonPrimitive("n2"), "speaker" to JsonPrimitive("x"), "textIt" to JsonPrimitive("t"), "terminal" to JsonPrimitive(true))),
            )),
        ))
        val r = validate(mutate("x") { it["stories"] = JsonArray(listOf(story)) })
        assertTrue(rules(r.errors).contains("R17"))
    }

    @Test fun `R17 rejects story node that cannot reach terminal`() {
        val story = JsonObject(mapOf(
            "id" to JsonPrimitive("s1"),
            "title" to JsonPrimitive("t"),
            "unlockAfterUnit" to JsonPrimitive(1),
            "nodes" to JsonArray(listOf(
                JsonObject(mapOf(
                    "id" to JsonPrimitive("n1"), "speaker" to JsonPrimitive("x"),
                    "textIt" to JsonPrimitive("t"),
                    "choices" to JsonArray(listOf(
                        JsonObject(mapOf("text" to JsonPrimitive("a"), "next" to JsonPrimitive("n1"), "correct" to JsonPrimitive(true))),
                        JsonObject(mapOf("text" to JsonPrimitive("b"), "next" to JsonPrimitive("n1"), "correct" to JsonPrimitive(false), "feedbackEn" to JsonPrimitive("f"))),
                    )),
                )),
                JsonObject(mapOf("id" to JsonPrimitive("n2"), "speaker" to JsonPrimitive("x"), "textIt" to JsonPrimitive("t"), "terminal" to JsonPrimitive(true))),
            )),
        ))
        val r = validate(mutate("x") { it["stories"] = JsonArray(listOf(story)) })
        assertTrue(rules(r.errors).contains("R17"))
    }
}
