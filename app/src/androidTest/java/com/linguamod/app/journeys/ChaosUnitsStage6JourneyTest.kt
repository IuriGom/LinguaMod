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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.linguamod.app.MainActivity
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.LessonProgressEntity
import com.linguamod.app.di.AppModule
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.solver.SolverBot
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 6 AC4: chaos-mode pass on units 27, 33 and 40.
 * Same harness as [ChaosUnitsStage5JourneyTest]: the bot answers wrong on
 * purpose — up to 3 distinct exercises per lesson are failed once (proving
 * wrong answers re-queue and are then answered correctly), and the checkpoint
 * is first attempted with [SolverBot.chaosStaysWrong] so it fails gracefully
 * (retry screen, no crash, no progress recorded), then a correct retry passes.
 * Unit 40 is the course capstone: its checkpoint pass must still settle the
 * app into the completed-course home without a crash.
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChaosUnitsStage6JourneyTest {

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
        runBlocking { db.clearAllTables() }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test fun chaos_unit_27() = runChaosUnitJourney(27)
    @Test fun chaos_unit_33() = runChaosUnitJourney(33)
    @Test fun chaos_unit_40() = runChaosUnitJourney(40)

    private fun runChaosUnitJourney(n: Int) {
        val plugin = loadPlugin()
        assertTrue("plugin must contain unit $n", plugin.units.any { it.number == n })

        // Fast-forward units 1..n-1: all 4 lessons + checkpoint completed.
        runBlocking {
            for (u in 1 until n) {
                for (l in 0..4) {
                    db.progressDao().upsertLessonProgress(
                        LessonProgressEntity(
                            unitNumber = u, lessonIndex = l,
                            completed = true, score = 1.0, attempts = 1,
                        )
                    )
                }
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        openUnitFromHome(n)

        // Lessons: up to 3 distinct exercises answered wrong once. They must
        // re-queue and the lesson must still complete.
        val chaosBot = SolverBot(composeRule, plugin, chaos = true, chaosMax = 3, db = db)
        for (l in 0..3) chaosBot.completeLesson(n, l)
        runBlocking {
            for (l in 0..3) {
                assertTrue(
                    "unit $n lesson $l completed despite chaos",
                    db.progressDao().getLessonProgress(n, l)?.completed == true,
                )
            }
        }

        // Checkpoint with chaosStaysWrong: fails gracefully, no progress written.
        val stuckBot = SolverBot(
            composeRule, plugin, chaos = true, chaosStaysWrong = true, db = db,
        )
        assertFalse("chaos checkpoint must fail", stuckBot.runCheckpoint(n))
        runBlocking {
            assertTrue(
                "unit $n checkpoint must not be recorded as completed",
                db.progressDao().getLessonProgress(n, 4)?.completed != true,
            )
        }

        // Correct retry passes.
        val bot = SolverBot(composeRule, plugin, db = db)
        assertTrue("unit $n checkpoint retry should pass", bot.runCheckpoint(n))
        runBlocking {
            assertTrue(
                "unit $n checkpoint recorded passed",
                db.progressDao().getLessonProgress(n, 4)?.completed == true,
            )
        }

        // The app is still healthy: back to Home renders the path.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 10_000)
    }

    private fun openUnitFromHome(n: Int) {
        scrollToUnit(n)
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            composeRule.onNodeWithTag("unit_node_$n").performClick()
            try {
                composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 8_000)
                return
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                if (System.currentTimeMillis() >= deadline) throw e
                scrollToUnit(n)
            }
        }
    }

    private fun scrollToUnit(n: Int) {
        composeRule.onNodeWithTag("path_list")
            .performScrollToNode(hasTestTag("unit_node_$n"))
    }
}
