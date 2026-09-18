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

    private lateinit var scenario: ActivityScenario<MainActivity>
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
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    private fun seedThrough(unit: Int, lesson: Int) = runBlocking {
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

    @Test
    fun journey_airplane_phases() {
        val plugin = loadPlugin()
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 30_000)
        listOf(5, 20, 35, 50).forEach { unit ->
            seedThrough(unit, 1)
            composeRule.onNodeWithTag("path_list").performScrollToNode(hasTestTag("unit_node_$unit"))
            composeRule.onNodeWithTag("unit_node_$unit").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_1"), 10_000)
            SolverBot(composeRule, plugin, db = db).completeLesson(unit, 1)
        }
    }
}
