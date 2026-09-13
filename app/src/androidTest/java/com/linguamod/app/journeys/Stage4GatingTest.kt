package com.linguamod.app.journeys

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.linguamod.app.MainActivity
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.db.AppDatabase
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 4 part B acceptance #2 — gating regression:
 * fresh install → Solver Bot completes Units 1–3 through the real UI →
 * OCR / story / boss / mixed-practice entry points are all hidden →
 * each flag tripped via fast-forward makes exactly its entry point appear.
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class Stage4GatingTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var featureUnlocks: FeatureUnlocks

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
        runBlocking {
            db.clearAllTables()
            featureUnlocks.clearAll()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    private fun nodeExists(tag: String): Boolean =
        composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /**
     * performScrollToNode only scrolls FORWARD — to reach the top of the path
     * from a deep position, swipe down until the list can't scroll back further.
     */
    private fun scrollPathToTop() {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val range = composeRule.onNodeWithTag("path_list").fetchSemanticsNode()
                .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)
            if (range == null || range.value() <= 0f) return
            composeRule.onNodeWithTag("path_list").performTouchInput { swipeDown() }
            composeRule.waitForIdle()
        }
    }

    private fun scrollToUnit(n: Int) {
        composeRule.onNodeWithTag("path_list")
            .performScrollToNode(hasTestTag("unit_node_$n"))
    }

    /**
     * Scrolls the path until [rowTag] is composed, retrying from the top when a
     * scroll-through doesn't find it (performScrollToNode scrolls forward only;
     * under emulator load a recomposition can lag the scroll-through).
     */
    private fun awaitPathRow(rowTag: String) {
        val deadline = System.currentTimeMillis() + 45_000
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                composeRule.onNodeWithTag("path_list")
                    .performScrollToNode(hasTestTag(rowTag))
                composeRule.waitUntilExactlyOneExists(hasTestTag(rowTag), 10_000)
                return
            } catch (e: AssertionError) {
                last = e
                scrollPathToTop()
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                last = e
                scrollPathToTop()
            }
        }
        throw AssertionError("row $rowTag never appeared on the path", last)
    }

    /** Clicks unit [n]'s node and waits for its detail screen (retry on swallowed taps). */
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

    private fun pressBackToHome() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 15_000)
    }

    /** story_row_story1 / boss_row_5 anchor after the unit-5 node: when unit 5
     *  is in view, both rows would be composed if they existed. */
    private fun assertPathRowsHidden() {
        scrollToUnit(5)
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_5"), 10_000)
        composeRule.onNodeWithTag("story_row_story1").assertDoesNotExist()
        composeRule.onNodeWithTag("boss_row_5").assertDoesNotExist()
        // practice card lives at the top of the list
        scrollPathToTop()
        composeRule.onNodeWithTag("practice_card").assertDoesNotExist()
    }

    @Test
    fun journey_gating_regression_units_1_to_3_then_flags() {
        val plugin = loadPlugin()
        val bot = SolverBot(composeRule, plugin, db = db)

        // Solver Bot completes Units 1–3 through the real UI
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        for (u in 1..3) {
            openUnitFromHome(u)
            for (l in 0..3) bot.completeLesson(u, l)
            assertTrue("unit $u checkpoint should pass", bot.runCheckpoint(u))
            pressBackToHome()
        }

        // sanity: exactly 3 checkpoints passed — leaderboards may have tripped,
        // but story/boss/ocr/mixed must not. The VM records the checkpoint in a
        // fire-and-forget coroutine, so poll the DataStore-backed flags until
        // the write lands instead of reading once (racy under emulator load).
        runBlocking {
            val mustStayLocked = listOf(
                FeatureUnlocks.STORY1, FeatureUnlocks.BOSS_BATTLES,
                FeatureUnlocks.OCR_CAMERA, FeatureUnlocks.MIXED_PRACTICE,
            )
            val deadline = System.currentTimeMillis() + 15_000
            while (true) {
                val leaderboards = featureUnlocks.isUnlocked(FeatureUnlocks.LEADERBOARDS)
                val leaked = mustStayLocked.filter { featureUnlocks.isUnlocked(it) }
                if (leaderboards && leaked.isEmpty()) break
                assertTrue(
                    "leaderboards tripped=${leaderboards}, must-stay-locked leaked=${leaked}",
                    System.currentTimeMillis() < deadline,
                )
                kotlinx.coroutines.delay(200)
            }
        }

        // all Stage 4 entry points hidden
        assertPathRowsHidden()
        composeRule.onNodeWithTag("tab_dictionary").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_search"), 10_000)
        composeRule.onNodeWithTag("dictionary_ocr_button").assertDoesNotExist()
        composeRule.onNodeWithTag("tab_home").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 10_000)

        // trip story1 → exactly the story row appears
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.STORY1) }
        awaitPathRow("story_row_story1")
        composeRule.onNodeWithTag("boss_row_5").assertDoesNotExist()
        scrollPathToTop()
        composeRule.onNodeWithTag("practice_card").assertDoesNotExist()

        // trip bossBattles → the boss overlay appears after unit 5
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.BOSS_BATTLES) }
        awaitPathRow("boss_row_5")

        // trip mixedPractice → the practice card appears at the top
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.MIXED_PRACTICE) }
        scrollPathToTop()
        composeRule.waitUntilExactlyOneExists(hasTestTag("practice_card"), 15_000)

        // trip ocrCamera → the camera button appears on Dictionary
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.OCR_CAMERA) }
        composeRule.onNodeWithTag("tab_dictionary").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_ocr_button"), 15_000)
    }
}
