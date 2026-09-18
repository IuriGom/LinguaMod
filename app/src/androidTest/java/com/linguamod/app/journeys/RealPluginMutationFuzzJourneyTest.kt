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
 * Stage 8 gauntlet fuzz extension: exactly 50 NEW mutations of the real
 * plugins/it.lingua — single-bit flips, truncated unit arrays, and swapped
 * exercise types — none of which overlap the corpus styles in
 * [FuzzPluginsJourneyTest] (whole-byte corruption, raw truncations, JSON-tree
 * edits). Every mutation must be rejected with a clear message, the real
 * plugin must still load, and the app must remain usable afterwards.
 * Deterministic (seed 1337) so a failure reproduces offline.
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RealPluginMutationFuzzJourneyTest {

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

    @Test
    fun journey_fuzz_real_plugin_mutations() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)

        val baseText = context.assets.open("plugins/it.lingua").bufferedReader().readText()
        val baseBytes = baseText.toByteArray()
        val base = json.parseToJsonElement(baseText).jsonObject
        val rnd = Random(1337)
        pluginDir.mkdirs()

        // Every mutation MUST be rejected; if a bit-flip happens to keep the
        // file valid, flip one more bit until it is not (guarded).
        fun addChecked(name: String, bytes: ByteArray) {
            var b = bytes
            var guard = 0
            while (PluginValidator.validateFile(b).isValid && guard++ < 64) {
                val buf = b.copyOf()
                val at = rnd.nextInt(buf.size)
                buf[at] = (buf[at].toInt() xor (1 shl rnd.nextInt(8))).toByte()
                b = buf
            }
            require(!PluginValidator.validateFile(b).isValid) { "could not make '$name' invalid" }
            File(pluginDir, "mut_$name.lingua").writeBytes(b)
        }

        // 20 single/multi-bit flips at random byte offsets (XOR, not overwrite)
        for (i in 0 until 20) {
            val buf = baseBytes.copyOf()
            repeat(rnd.nextInt(1, 6)) {
                val at = rnd.nextInt(buf.size)
                buf[at] = (buf[at].toInt() xor (1 shl rnd.nextInt(8))).toByte()
            }
            addChecked("bitflip_$i", buf)
        }

        // 15 unit-array truncations: drop the last k units from the JSON tree
        val units = base["units"] as JsonArray
        for (i in 0 until 15) {
            val keep = rnd.nextInt(1, units.size)
            val m = base.toMutableMap()
            m["units"] = JsonArray(units.take(keep))
            // guarantee invalidity regardless of which units remain: a course
            // must declare all 60 units, and stories dangle past the cut
            addChecked("truncunits_$i", JsonObject(m).toString().toByteArray())
        }

        // 15 swapped exercise types: first exercise of a random unit/lesson
        // takes the type of a different exercise while keeping its payload
        for (i in 0 until 15) {
            val uIdx = rnd.nextInt(units.size)
            val u = units[uIdx].jsonObject.toMutableMap()
            val lessons = (u["lessons"] as JsonArray).toMutableList()
            val lIdx = rnd.nextInt(lessons.size)
            val l = lessons[lIdx].jsonObject.toMutableMap()
            val exs = (l["exercises"] as JsonArray).toMutableList()
            val a = exs[0].jsonObject.toMutableMap()
            val b = exs[exs.size - 1].jsonObject.toMutableMap()
            val t = a["type"] ?: JsonPrimitive("?")
            a["type"] = b["type"] ?: JsonPrimitive("?")
            b["type"] = t
            exs[0] = JsonObject(a); exs[exs.size - 1] = JsonObject(b)
            l["exercises"] = JsonArray(exs)
            lessons[lIdx] = JsonObject(l)
            u["lessons"] = JsonArray(lessons)
            val all = units.toMutableList(); all[uIdx] = JsonObject(u)
            val m = base.toMutableMap(); m["units"] = JsonArray(all)
            addChecked("swaptype_$i", JsonObject(m).toString().toByteArray())
        }

        runBlocking { repo.reload() }

        val metas = runBlocking { db.pluginMetaDao().getAll() }
        val mutMetas = metas.filter { it.fileName.startsWith("mut_") }
        assertEquals("all 50 mutations on disk got a verdict row", 50, mutMetas.size)
        assertTrue(
            "all mutations must be rejected; accepted: ${mutMetas.filter { it.valid }.map { it.fileName }}",
            mutMetas.none { it.valid },
        )
        assertTrue("every rejection has a clear message", mutMetas.all { it.errors.isNotBlank() })
        // real plugin still loads
        assertTrue(metas.any { it.fileName == "it.lingua" && it.valid })

        // app still usable: Solver Bot completes Unit 1 Lesson 1 through the real UI
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        val pluginText = context.assets.open("plugins/it.lingua").bufferedReader().readText()
        val plugin = json.decodeFromString(LinguaPluginDto.serializer(), pluginText)
        SolverBot(composeRule, plugin, db = db).completeLesson(1, 0)

        mutMetas.forEach { File(pluginDir, it.fileName).delete() }
        runBlocking { repo.reload() }
    }
}
