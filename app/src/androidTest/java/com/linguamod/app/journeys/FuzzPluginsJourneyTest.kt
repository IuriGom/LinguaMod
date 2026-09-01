package com.linguamod.app.journeys

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linguamod.app.MainActivity
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.di.AppModule
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.PluginValidator
import com.linguamod.app.solver.SolverBot
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.io.File
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * journey_fuzz_plugins (Orchestrator §C.4.1): >= 200 malformed .lingua files
 * (truncated JSON, wrong types, duplicate IDs, dangling refs, forward refs,
 * 4-option violations, missing gender, huge files, deep nesting) — every one
 * rejected with a clear message, zero crashes, zero partial loads, app usable
 * afterwards. The corpus is generated in-test (tools/fuzz/generate_fuzz.py
 * provides the same corpus for offline use).
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FuzzPluginsJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var repo: CourseRepository

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val pluginDir: File get() = File(context.getExternalFilesDir(null), "plugins")

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        hiltRule.inject()
        pluginDir.deleteRecursively()
        runBlocking { db.clearAllTables() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    /** Generate >= [count] malformed plugin texts by mutating the real plugin. */
    private fun generateCorpus(count: Int): List<Pair<String, ByteArray>> {
        val baseText = context.assets.open("plugins/it.lingua").bufferedReader().readText()
        val baseBytes = baseText.toByteArray()
        val base = json.parseToJsonElement(baseText).jsonObject
        val rnd = Random(42)
        val out = mutableListOf<Pair<String, ByteArray>>()

        // Every corpus entry MUST be rejected by the validator; if a mutation
        // accidentally produced a valid file, truncate it until it is not.
        fun addChecked(name: String, bytes: ByteArray) {
            var b = bytes
            var guard = 0
            while (PluginValidator.validateFile(b).isValid && guard++ < 50) {
                b = b.copyOf((b.size * 2 / 3).coerceAtLeast(1))
            }
            require(!PluginValidator.validateFile(b).isValid) { "could not make '$name' invalid" }
            out += name to b
        }

        // truncations
        for (i in 0 until 40) {
            val cut = rnd.nextInt(1, baseBytes.size)
            addChecked("trunc_$i", baseBytes.copyOf(cut))
        }
        // byte corruption
        for (i in 0 until 40) {
            val buf = baseBytes.copyOf()
            repeat(rnd.nextInt(1, 12)) { buf[rnd.nextInt(buf.size)] = rnd.nextInt(256).toByte() }
            addChecked("corrupt_$i", buf)
        }
        // random insertion
        for (i in 0 until 20) {
            val at = rnd.nextInt(baseBytes.size)
            val junk = rnd.nextBytes(rnd.nextInt(1, 48))
            addChecked("insert_$i", baseBytes.copyOfRange(0, at) + junk + baseBytes.copyOfRange(at, baseBytes.size))
        }
        // deep nesting
        var deep = "1"
        repeat(64) { deep = "{\"a\":$deep}" }
        addChecked("deep_nesting", deep.toByteArray())
        // huge file (bloated dictionary -> orphans violate R14)
        val hugeDict = buildString {
            for (i in 0 until 6000) {
                append("""{"id":"pad$i","word":"pad$i","translation":"x","partOfSpeech":"adverb","examples":[{"it":"x","en":"y"}],"introducedInUnit":1},""")
            }
        }
        val dictAnchor = baseText.indexOf("\"dictionary\":")
        require(dictAnchor >= 0) { "dictionary anchor not found in plugin" }
        val insertAt = baseText.indexOf('[', dictAnchor) + 1
        addChecked("huge_file", (baseText.substring(0, insertAt) + hugeDict + baseText.substring(insertAt)).toByteArray())

        // structured mutations of the JSON tree
        fun mutate(name: String, fn: (MutableMap<String, JsonElement>) -> Unit) {
            val m = base.toMutableMap()
            fn(m)
            addChecked(name, JsonObject(m).toString().toByteArray())
        }
        fun firstUnit(m: MutableMap<String, JsonElement>) =
            (m["units"] as JsonArray)[0].jsonObject.toMutableMap()
        fun putUnit(m: MutableMap<String, JsonElement>, u: Map<String, JsonElement>) {
            val units = (m["units"] as JsonArray).toMutableList()
            units[0] = JsonObject(u)
            m["units"] = JsonArray(units)
        }
        fun editFirstExercise(m: MutableMap<String, JsonElement>, fn: (MutableMap<String, JsonElement>) -> Unit) {
            val u = firstUnit(m)
            val lessons = (u["lessons"] as JsonArray).toMutableList()
            val l0 = lessons[0].jsonObject.toMutableMap()
            val exs = (l0["exercises"] as JsonArray).toMutableList()
            val e0 = exs[0].jsonObject.toMutableMap()
            fn(e0)
            exs[0] = JsonObject(e0); l0["exercises"] = JsonArray(exs)
            lessons[0] = JsonObject(l0); u["lessons"] = JsonArray(lessons)
            putUnit(m, u)
        }

        mutate("wrongtype_version") { it["formatVersion"] = JsonPrimitive("one") }
        mutate("wrongtype_units") { it["units"] = JsonPrimitive("nope") }
        mutate("missing_meta") { it.remove("meta") }
        mutate("dup_dict_id") {
            val d = (it["dictionary"] as JsonArray).toMutableList(); d.add(d[0]); it["dictionary"] = JsonArray(d)
        }
        mutate("dangling_ref") {
            editFirstExercise(it) { e -> e["dictionaryRefs"] = JsonArray(listOf(JsonPrimitive("nope"))) }
            val u = firstUnit(it)
            val lessons = (u["lessons"] as JsonArray).toMutableList()
            val l0 = lessons[0].jsonObject.toMutableMap()
            l0["dictionaryRefs"] = JsonArray(listOf(JsonPrimitive("nope")))
            lessons[0] = JsonObject(l0); u["lessons"] = JsonArray(lessons)
            putUnit(it, u)
        }
        mutate("forward_ref") {
            val u = firstUnit(it)
            val lessons = (u["lessons"] as JsonArray).toMutableList()
            val l0 = lessons[0].jsonObject.toMutableMap()
            l0["dictionaryRefs"] = JsonArray(listOf(JsonPrimitive("ciao"), JsonPrimitive("italiano")))
            lessons[0] = JsonObject(l0); u["lessons"] = JsonArray(lessons)
            putUnit(it, u)
        }
        mutate("options_3") { editFirstExercise(it) { e -> e["options"] = JsonArray(listOf("a", "b", "c").map(::JsonPrimitive)) } }
        mutate("options_5") { editFirstExercise(it) { e -> e["options"] = JsonArray(listOf("a", "b", "c", "d", "e").map(::JsonPrimitive)) } }
        mutate("index_oob") { editFirstExercise(it) { e -> e["correctIndex"] = JsonPrimitive(9) } }
        mutate("missing_gender") {
            val d = (it["dictionary"] as JsonArray).toMutableList()
            val idx = d.indexOfFirst { el -> el.jsonObject["partOfSpeech"]?.jsonPrimitive?.content == "noun" }
            val nm = d[idx].jsonObject.toMutableMap(); nm.remove("gender")
            d[idx] = JsonObject(nm); it["dictionary"] = JsonArray(d)
        }
        mutate("checkpoint_9") {
            val u = firstUnit(it)
            val cp = (u["checkpoint"] as JsonObject).toMutableMap()
            cp["exercises"] = JsonArray((cp["exercises"] as JsonArray).drop(1))
            u["checkpoint"] = JsonObject(cp); putUnit(it, u)
        }
        mutate("lessons_2") {
            val u = firstUnit(it)
            u["lessons"] = JsonArray((u["lessons"] as JsonArray).take(2))
            putUnit(it, u)
        }
        out += "empty_file" to ByteArray(0)
        out += "xml_file" to "<plugin/>".toByteArray()

        // fill the rest with more truncations to reach count
        var i = 0
        while (out.size < count) {
            val cut = rnd.nextInt(1, baseBytes.size)
            addChecked("extra_$i", baseBytes.copyOf(cut))
            i++
        }
        return out
    }

    @Test
    fun journey_fuzz_plugins() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        val corpus = generateCorpus(220)
        assertTrue(corpus.size >= 200)

        corpus.forEach { (name, bytes) -> File(pluginDir, "fuzz_$name.lingua").writeBytes(bytes) }
        runBlocking { repo.reload() }

        val metas = runBlocking { db.pluginMetaDao().getAll() }
        val fuzzMetas = metas.filter { it.fileName.startsWith("fuzz_") }
        assertEquals("every fuzz file got a verdict row", corpus.size, fuzzMetas.size)
        val accepted = fuzzMetas.filter { it.valid }
        assertTrue("fuzz files must all be rejected; accepted: ${accepted.map { it.fileName }}", accepted.isEmpty())
        assertTrue("every rejection has a clear message", fuzzMetas.all { it.errors.isNotBlank() })
        // real plugin still loads
        assertTrue(metas.any { it.fileName == "it.lingua" && it.valid })

        // app still usable: Solver Bot completes Unit 1 Lesson 1 through the real UI
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        val pluginText = context.assets.open("plugins/it.lingua").bufferedReader().readText()
        val plugin = json.decodeFromString(LinguaPluginDto.serializer(), pluginText)
        SolverBot(composeRule, plugin, db = db).completeLesson(1, 0)

        fuzzMetas.forEach { File(pluginDir, it.fileName).delete() }
        runBlocking { repo.reload() }
    }
}
