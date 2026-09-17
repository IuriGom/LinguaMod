package com.linguamod.app

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 2 AC#4: mixed-lesson recycling. Static analysis of the bundled plugin JSON
 * (repo-root `plugins/it.lingua`, symlinked into assets — same location strategy as
 * [PluginValidatorTest]).
 *
 * Required assertion: for each unit 3..45, lesson 3 (the mixed lesson) references at
 * least one dictionary entry whose `introducedInUnit` is strictly earlier than the
 * unit number — i.e. mixed lessons recycle earlier vocabulary.
 *
 * Cheap structural checks piggyback: fixed lesson-type order per unit, lesson ids
 * u{N}l1..u{N}l4, checkpoint u{N}c with exactly 10 exercises, unit 1 lessons exist.
 */
class MixedLessonRecyclingTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val root by lazy {
        json.parseToJsonElement(File("src/main/assets/plugins/it.lingua").readText()).jsonObject
    }

    private val introducedInUnitById: Map<String, Int> by lazy {
        root["dictionary"]!!.jsonArray.associate {
            it.jsonObject["id"]!!.jsonPrimitive.content to
                it.jsonObject["introducedInUnit"]!!.jsonPrimitive.int
        }
    }

    @Test
    fun `mixed lessons recycle vocabulary from earlier units`() {
        val units = root["units"]!!.jsonArray
        assertEquals(50, units.size)
        for (unit in units) {
            val u = unit.jsonObject
            val number = u["number"]!!.jsonPrimitive.int
            val lessons = u["lessons"]!!.jsonArray

            // fixed structure: ids + type order + 10-exercise checkpoint
            assertEquals(
                "unit $number lesson ids",
                (1..4).map { "u${number}l$it" },
                lessons.map { it.jsonObject["id"]!!.jsonPrimitive.content },
            )
            assertEquals(
                "unit $number lesson type order",
                listOf("vocabulary", "grammar", "mixed", "oral"),
                lessons.map { it.jsonObject["type"]!!.jsonPrimitive.content },
            )
            val checkpoint = u["checkpoint"]!!.jsonObject
            assertEquals("u${number}c", checkpoint["id"]!!.jsonPrimitive.content)
            assertEquals(
                "unit $number checkpoint size",
                10, checkpoint["exercises"]!!.jsonArray.size,
            )

            if (number < 3) continue // recycling gate applies to units 3..50
            val mixed = lessons[2].jsonObject
            val refs = mixed["dictionaryRefs"]!!.jsonArray.map { it.jsonPrimitive.content }
            val recycled = refs.filter { (introducedInUnitById[it] ?: Int.MAX_VALUE) < number }
            assertTrue(
                "unit $number mixed lesson must reference vocabulary introduced before unit $number " +
                    "(refs=$refs)",
                recycled.isNotEmpty(),
            )
            // every ref must resolve to a real dictionary entry
            for (ref in refs) {
                assertTrue("unit $number mixed ref '$ref' missing from dictionary", ref in introducedInUnitById)
            }
        }
    }

    @Test
    fun `unit 1 lessons 1 and 2 exist`() {
        val unit1 = root["units"]!!.jsonArray.first { it.jsonObject["number"]!!.jsonPrimitive.int == 1 }
        val ids = unit1.jsonObject["lessons"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("u1l1 exists", "u1l1" in ids)
        assertTrue("u1l2 exists", "u1l2" in ids)
    }
}
