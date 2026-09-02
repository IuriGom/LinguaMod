package com.linguamod.app.journeys

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 2 AC#3: for every unit N in 1..10, fast-forward units 1..N-1 to complete
 * via direct DB writes, assert unit N unlocked / unit N+1 locked, run the Solver Bot
 * through all 4 lessons + the checkpoint of unit N, then assert the checkpoint is
 * recorded passed and unit N+1 is unlocked. Progression is strictly linear: unit N+1
 * must stay locked after all 4 lessons until the checkpoint passes.
 *
 * One test per unit (10 runs) instead of a single sequential pass: each run gets a
 * fresh in-memory DB and activity, so an emulator flake in unit K cannot poison the
 * state of units K+1..10, and failures point at exactly one unit.
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CompleteUnitsStage2JourneyTest {

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
        // The activity is launched inside runUnitJourney AFTER the fast-forward DB
        // writes: launching first would recompose Home concurrently with the writes
        // (Compose SnapshotStateObserver is not thread-safe — seen as a flake).
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test fun unit_01() = runUnitJourney(1)
    @Test fun unit_02() = runUnitJourney(2)
    @Test fun unit_03() = runUnitJourney(3)
    @Test fun unit_04() = runUnitJourney(4)
    @Test fun unit_05() = runUnitJourney(5)
    @Test fun unit_06() = runUnitJourney(6)
    @Test fun unit_07() = runUnitJourney(7)
    @Test fun unit_08() = runUnitJourney(8)
    @Test fun unit_09() = runUnitJourney(9)
    @Test fun unit_10() = runUnitJourney(10)

    private fun runUnitJourney(n: Int) {
        val plugin = loadPlugin()
        val totalUnits = plugin.units.size
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

        // Home: unit N unlocked, unit N+1 locked (strictly-linear gate).
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        if (n < totalUnits) assertUnitLockedOnHome(n)
        openUnitFromHome(n)

        // Solver Bot: all 4 lessons of unit N through the real UI.
        val bot = SolverBot(composeRule, plugin, db = db)
        for (l in 0..3) bot.completeLesson(n, l)

        // Strictly linear: with all 4 lessons done but no checkpoint pass,
        // unit N+1 must STILL be locked.
        if (n < totalUnits) {
            pressBackToHome()
            assertUnitLockedOnHome(n)
            openUnitFromHome(n)
        }

        // Checkpoint passes.
        assertTrue("unit $n checkpoint should pass", bot.runCheckpoint(n))
        runBlocking {
            assertTrue(
                "unit $n checkpoint recorded passed",
                db.progressDao().getLessonProgress(n, 4)?.completed == true,
            )
        }

        // After the pass: unit N+1 unlocked (or stage-complete state for the last unit).
        pressBackToHome()
        if (n < totalUnits) {
            openUnitFromHome(n + 1)
        } else {
            // Last unit of the stage: no unit N+1 exists. The app must settle into a
            // completed-course home without crashing; unit N stays navigable.
            runBlocking {
                assertEquals(n, db.progressDao().highestCompletedCheckpoint())
                assertEquals(n, db.progressDao().countCompletedCheckpoints())
            }
            // scroll back up: with Stage 4 path rows (stories/bosses/practice card)
            // the top of the list is disposed when scrolled deep to unit 10
            composeRule.onNodeWithTag("path_list")
                .performScrollToNode(hasTestTag("progress_bars"))
            composeRule.waitUntilExactlyOneExists(hasTestTag("progress_bars"), 10_000)
            openUnitFromHome(n)
        }
    }

    /** Clicks the locked node for unit [afterUnit] + 1 and asserts the lock snackbar. */
    private fun assertUnitLockedOnHome(afterUnit: Int) {
        val locked = afterUnit + 1
        scrollToUnit(locked)
        // Retry the tap: under emulator load a click can be swallowed mid-scroll.
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            composeRule.onNodeWithTag("unit_node_$locked").performClick()
            try {
                composeRule.waitUntilExactlyOneExists(
                    hasText("Complete Unit $afterUnit to unlock."), 8_000,
                )
                break
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                if (System.currentTimeMillis() >= deadline) throw e
                scrollToUnit(locked)
            }
        }
        // locked nodes must not navigate
        composeRule.onNodeWithTag("lesson_row_0").assertDoesNotExist()
    }

    /** Clicks unit [n]'s node and asserts navigation to its detail screen. */
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

    private fun pressBackToHome() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 10_000)
    }
}
