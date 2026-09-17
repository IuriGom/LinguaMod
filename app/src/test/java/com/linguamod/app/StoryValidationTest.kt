package com.linguamod.app

import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.PluginValidator
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §7 story validation (Stage 4 §2): the bundled plugin's stories must
 * satisfy the structural rules — exactly one correct choice per non-terminal
 * node, every `next` resolves, every node reaches a terminal — and the
 * validator (§8-R17) must reject stories that break them.
 */
class StoryValidationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val plugin: LinguaPluginDto by lazy {
        json.decodeFromString(
            LinguaPluginDto.serializer(),
            File("src/main/assets/plugins/it.lingua").readText(),
        )
    }

    // ---------- the bundled stories must satisfy §7 ----------

    @Test fun `bundled plugin validates with stories present`() {
        val result = PluginValidator.validate(plugin)
        assertTrue("plugin must validate, got: ${result.errors}", result.isValid)
    }

    @Test fun `story1 is fully written and registered for unit 5`() {
        val s1 = plugin.stories.firstOrNull { it.id == "story1" }
            ?: error("story1 missing from plugin")
        assertEquals(5, s1.unlockAfterUnit)
        assertTrue("story1 needs 8-12 nodes, got ${s1.nodes.size}", s1.nodes.size in 8..12)
        assertTrue("story1 needs a terminal node", s1.nodes.any { it.terminal })
    }

    @Test fun `story2 is fully written and registered for unit 15`() {
        val s2 = plugin.stories.firstOrNull { it.id == "story2" }
            ?: error("story2 missing from plugin")
        assertEquals(15, s2.unlockAfterUnit)
        assertTrue("story2 needs 8-12 nodes, got ${s2.nodes.size}", s2.nodes.size in 8..12)
        assertTrue("story2 needs a terminal node", s2.nodes.any { it.terminal })
    }

    @Test fun `story3 is fully written and registered for unit 28`() {
        val s3 = plugin.stories.firstOrNull { it.id == "story3" }
            ?: error("story3 missing from plugin")
        assertEquals(28, s3.unlockAfterUnit)
        assertTrue("story3 needs 8-12 nodes, got ${s3.nodes.size}", s3.nodes.size in 8..12)
        assertTrue("story3 needs a terminal node", s3.nodes.any { it.terminal })
    }

    @Test fun `story4 is fully written and registered for unit 45`() {
        val s4 = plugin.stories.firstOrNull { it.id == "story4" }
            ?: error("story4 missing from plugin")
        assertEquals(45, s4.unlockAfterUnit)
        assertTrue("story4 needs 10-15 nodes, got ${s4.nodes.size}", s4.nodes.size in 10..15)
        assertTrue("story4 needs a terminal node", s4.nodes.any { it.terminal })
    }

    @Test fun `every non-terminal node has exactly one correct choice`() {
        for (story in plugin.stories) {
            for (node in story.nodes.filter { !it.terminal }) {
                assertEquals(
                    "${story.id}/${node.id}: expected exactly one correct choice",
                    1, node.choices.count { it.correct },
                )
                assertTrue(
                    "${story.id}/${node.id}: non-terminal needs 2-3 choices",
                    node.choices.size in 2..3,
                )
            }
        }
    }

    @Test fun `every next ref resolves within the story`() {
        for (story in plugin.stories) {
            val ids = story.nodes.mapNotNull { it.id }.toSet()
            for (node in story.nodes) {
                for (choice in node.choices) {
                    assertTrue(
                        "${story.id}/${node.id}: dangling next '${choice.next}'",
                        choice.next in ids,
                    )
                }
            }
        }
    }

    @Test fun `every node reaches a terminal`() {
        for (story in plugin.stories.filter { it.nodes.isNotEmpty() }) {
            val byId = story.nodes.associateBy { it.id!! }
            val terminalIds = story.nodes.filter { it.terminal }.map { it.id!! }.toSet()
            assertTrue("${story.id}: no terminal node", terminalIds.isNotEmpty())
            for (start in story.nodes) {
                val visited = mutableSetOf<String>()
                var frontier = listOf(start.id!!)
                var reaches = false
                while (frontier.isNotEmpty() && !reaches) {
                    val next = mutableListOf<String>()
                    for (id in frontier) {
                        if (!visited.add(id)) continue
                        if (id in terminalIds) { reaches = true; break }
                        byId[id]?.choices?.mapNotNullTo(next) { it.next }
                    }
                    frontier = next
                }
                assertTrue("${story.id}/${start.id}: cannot reach a terminal", reaches)
            }
        }
    }

    @Test fun `wrong choices carry teaching feedback and loop`() {
        for (story in plugin.stories) {
            val terminals = story.nodes.filter { it.terminal }.map { it.id }
            for (node in story.nodes.filter { !it.terminal }) {
                for (choice in node.choices.filter { !it.correct }) {
                    assertTrue(
                        "${story.id}/${node.id}: wrong choice missing feedbackEn",
                        !choice.feedbackEn.isNullOrBlank(),
                    )
                    assertTrue(
                        "${story.id}/${node.id}: wrong choice must loop (not reach a terminal directly)",
                        choice.next !in terminals,
                    )
                }
            }
        }
    }

    // ---------- the validator must reject broken stories (§8-R17) ----------

    private fun validateStory(storyJson: String) = PluginValidator.validateText(
        """{"formatVersion":1,
           "meta":{"id":"t","language":"it","languageName":"Italian","version":1},
           "units":${unitFixture()},"dictionary":[],"stories":[$storyJson]}"""
    )

    private fun rulesOf(result: com.linguamod.app.plugin.ValidationResult) =
        result.errors.map { it.rule }.toSet()

    @Test fun `validator rejects two correct choices on one node`() {
        val r = validateStory(
            """{"id":"s","title":"T","unlockAfterUnit":1,"nodes":[
                {"id":"a","speaker":"X","textIt":"t","choices":[
                  {"text":"1","next":"b","correct":true},
                  {"text":"2","next":"b","correct":true}]},
                {"id":"b","speaker":"X","textIt":"t","terminal":true}]}"""
        )
        assertTrue("expected R17, got ${r.errors}", "R17" in rulesOf(r))
    }

    @Test fun `validator rejects a dangling next ref`() {
        val r = validateStory(
            """{"id":"s","title":"T","unlockAfterUnit":1,"nodes":[
                {"id":"a","speaker":"X","textIt":"t","choices":[
                  {"text":"1","next":"nowhere","correct":true},
                  {"text":"2","next":"a","correct":false,"feedbackEn":"f"}]},
                {"id":"b","speaker":"X","textIt":"t","terminal":true}]}"""
        )
        assertTrue("expected R10, got ${r.errors}", "R10" in rulesOf(r))
    }

    @Test fun `validator rejects a node that cannot reach a terminal`() {
        val r = validateStory(
            """{"id":"s","title":"T","unlockAfterUnit":1,"nodes":[
                {"id":"a","speaker":"X","textIt":"t","choices":[
                  {"text":"1","next":"c","correct":true},
                  {"text":"2","next":"a","correct":false,"feedbackEn":"f"}]},
                {"id":"c","speaker":"X","textIt":"t","choices":[
                  {"text":"1","next":"a","correct":true},
                  {"text":"2","next":"c","correct":false,"feedbackEn":"f"}]},
                {"id":"b","speaker":"X","textIt":"t","terminal":true}]}"""
        )
        assertTrue("expected R17, got ${r.errors}", "R17" in rulesOf(r))
    }

    @Test fun `validator rejects a wrong choice without feedback`() {
        val r = validateStory(
            """{"id":"s","title":"T","unlockAfterUnit":1,"nodes":[
                {"id":"a","speaker":"X","textIt":"t","choices":[
                  {"text":"1","next":"b","correct":true},
                  {"text":"2","next":"a","correct":false}]},
                {"id":"b","speaker":"X","textIt":"t","terminal":true}]}"""
        )
        assertTrue("expected R17, got ${r.errors}", "R17" in rulesOf(r))
    }

    /** Minimal valid single-unit fixture so story-only mutations stay valid otherwise. */
    private fun unitFixture(): String {
        // Reuse unit 1 of the real plugin: guaranteed-valid structure.
        val units = File("src/main/assets/plugins/it.lingua").readText()
            .let { json.parseToJsonElement(it) }
            .let { it as kotlinx.serialization.json.JsonObject }
            .getValue("units").toString()
        val first = json.parseToJsonElement(units)
            .let { it as kotlinx.serialization.json.JsonArray }[0].toString()
        return "[$first]"
    }
}
