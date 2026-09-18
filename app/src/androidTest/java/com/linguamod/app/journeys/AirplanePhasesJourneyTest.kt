package com.linguamod.app.journeys

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import com.linguamod.app.MainActivity
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.LessonProgressEntity
import com.linguamod.app.di.AppModule
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.solver.SolverBot
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 8 gauntlet airplane extension: with radios off (enforced by
 * tools/journeys/airplane.sh around this class), Solver Bot completes one
 * lesson in each course phase — P1 unit 5, P2 unit 20, P3 unit 35,
 * P4 unit 50 — proving the whole course is playable fully offline.
 * Unit-1 coverage remains RotationAirplaneJourneyTest.journey_airplane.
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AirplanePhasesJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: AppDatabase

    private var scenario: ActivityScenario<MainActivity>? = null
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun loadPlugin(): LinguaPluginDto {
        val text = context.assets.open("plugins/it.lingua").bufferedReader().readText()
        return Json { ignoreUnknownKeys = true }.decodeFromString(LinguaPluginDto.serializer(), text)
    }

    @Before
    fun setup() {
        hiltRule.inject()
        File(context.getExternalFilesDir(null), "plugins").deleteRecursively()
        runBlocking { db.clearAllTables() }
        // No launch here: each phase seeds the DB FIRST, then launches a fresh
        // activity (the CompleteUnitsStage2JourneyTest ordering). Launching in
        // setup raced Home's first recompute against the raw writes — the path
        // rendered unit N locked and the tap only fired the lock snackbar.
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun seedThrough(unit: Int, lesson: Int) = runBlocking {
        // One transaction, one invalidation: seeding 170+ rows as separate
        // upserts each re-triggered Home's per-unit unlock recompute
        // (60 units × 2 queries per emission) and wedged the API-26 emulator
        // mid-lesson — taps stopped landing and the screen timed out.
        db.withTransaction {
            for (u in 1 until unit) {
                for (l in 0..4) {
                    db.progressDao().upsertLessonProgress(
                        LessonProgressEntity(u, l, completed = true, score = 1.0, attempts = 1, lastAccessed = 0L)
                    )
                }
            }
            for (l in 0 until lesson) {
                db.progressDao().upsertLessonProgress(
                    LessonProgressEntity(unit, l, completed = true, score = 1.0, attempts = 1, lastAccessed = 0L)
                )
            }
        }
    }

    /** Scrolls to a path row and taps it, retrying until [targetTag] appears
     *  (taps can be swallowed mid-scroll on a loaded emulator — same idiom as
     *  Story2JourneyTest.openPathRow). */
    private fun openPathRow(rowTag: String, targetTag: String) {
        val deadline = System.currentTimeMillis() + 60_000
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                composeRule.onNodeWithTag("path_list").performScrollToNode(hasTestTag(rowTag))
                composeRule.onNodeWithTag(rowTag).performClick()
                composeRule.waitUntilExactlyOneExists(hasTestTag(targetTag), 8_000)
                return
            } catch (e: AssertionError) {
                last = e
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                last = e
            }
        }
        throw AssertionError("row $rowTag never opened ($targetTag)", last)
    }

    @Test
    fun journey_airplane_phases() {
        val plugin = loadPlugin()
        // P1 u5, P2 u20, P3 u35, P4 u50 — per phase: seed cumulative progress,
        // then a FRESH activity so Home recomputes from the seeded rows before
        // the path is tapped.
        listOf(5, 20, 35, 50).forEach { unit ->
            seedThrough(unit, 1)
            scenario?.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 30_000)
            openPathRow("unit_node_$unit", "lesson_row_1")
            SolverBot(composeRule, plugin, db = db).completeLesson(unit, 1)
        }
    }
}
