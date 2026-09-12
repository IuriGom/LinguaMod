package com.linguamod.app.journeys

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.waitUntilDoesNotExist
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
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 3 part 2 acceptance (§5, §6, §6.7):
 * - review card shows exactly 3 due after 3 chaos-mode failures, runs a review
 *   session from the original payloads, and disappears once cleared — without
 *   touching hearts, XP, or streaks;
 * - flashcards: empty until a unit is started, "Still learning" repeats
 *   in-session, session capped at 20 cards;
 * - sentence_scramble with duplicate tokens (u9l2e11) solved through the UI.
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReviewFlashcardJourneyTest {

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

    private fun pressBack() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitForIdle()
    }

    // --- §5 review ---

    @Test
    fun journey_review_card_three_due_then_cleared() {
        val plugin = loadPlugin()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        // exactly 3 exercises fail (first try), the rest pass
        SolverBot(composeRule, plugin, chaos = true, chaosMax = 3, db = db).completeLesson(1, 0)

        // exactly 3 review items exist; fast-forward past the 10-minute interval
        // (the scheduling rule itself is unit-tested with FakeClock)
        val items = runBlocking { db.reviewDao().dueItems(Long.MAX_VALUE) }
            .sortedBy { it.dueAtMillis }
        assertEquals(3, items.size)
        val past = System.currentTimeMillis() - 60_000
        runBlocking {
            items.forEachIndexed { i, item ->
                db.reviewDao().upsert(item.copy(dueAtMillis = past + i))
            }
        }
        val progressBefore = runBlocking { db.progressDao().getUserProgress()!! }

        // Home: the card appears above the path with exactly 3 due
        pressBack()
        composeRule.waitUntilExactlyOneExists(hasTestTag("review_card"), 15_000)
        // the clickable card merges its children's text into its own node
        composeRule.onNodeWithTag("review_card")
            .assert(hasText("3 exercises due for review"))

        // tapping it runs a review session re-rendered from the original payloads
        composeRule.onNodeWithTag("review_card").performClick()
        SolverBot(composeRule, plugin, db = db).solveOpenSession(items.map { it.exerciseId })

        // cleared: the card is gone and stays gone
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 15_000)
        composeRule.waitUntilDoesNotExist(hasTestTag("review_card"), 15_000)

        // review answers never touched hearts, XP, or streaks
        val progressAfter = runBlocking { db.progressDao().getUserProgress()!! }
        assertEquals(progressBefore.totalXp, progressAfter.totalXp)
        assertEquals(progressBefore.hearts, progressAfter.hearts)
        assertEquals(progressBefore.streakCount, progressAfter.streakCount)
        // all three items rescheduled into the future (correct in review → ×2.5)
        assertEquals(
            0,
            runBlocking { db.reviewDao().dueItems(System.currentTimeMillis()) }.size,
        )
    }

    // --- §6 flashcards ---

    @Test
    fun journey_flashcards_gating_requeue_and_cap() {
        val plugin = loadPlugin()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)

        // nothing started → empty deck
        composeRule.onNodeWithTag("tab_dictionary").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_flashcards"), 10_000)
        composeRule.onNodeWithTag("dictionary_flashcards").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("flashcard_empty"), 10_000)
        composeRule.onNodeWithTag("flashcard_back").performClick()

        // start unit 1: a lesson row marks the unit started (dictionary rule)
        runBlocking {
            db.progressDao().upsertLessonProgress(
                LessonProgressEntity(1, 0, completed = false, score = 0.0, attempts = 0, lastAccessed = 0L)
            )
        }
        val deckSize = plugin.dictionary.count { (it.introducedInUnit ?: 0) <= 1 }
        assertTrue("test needs at least 3 unit-1 words, got $deckSize", deckSize >= 3)

        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_flashcards"), 10_000)
        composeRule.onNodeWithTag("dictionary_flashcards").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("flashcard_card"), 10_000)
        composeRule.onNodeWithTag("flashcard_progress").assertTextEquals("Card 1 of $deckSize")

        // flip: English + example/gender side shows (the clickable card merges
        // its subtree — inner tags live in the unmerged tree). The tap can be
        // swallowed under suite load — retry it until the back face shows.
        val firstWord = cardWord()
        val flipDeadline = System.currentTimeMillis() + 20_000
        while (true) {
            composeRule.onNodeWithTag("flashcard_card").performClick()
            try {
                composeRule.waitUntil(5_000) {
                    composeRule.onAllNodes(hasTestTag("flashcard_translation"), useUnmergedTree = true)
                        .fetchSemanticsNodes().size == 1
                }
                break
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                if (System.currentTimeMillis() >= flipDeadline) throw e
            }
        }

        // "Still learning" re-queues the card within the session
        composeRule.onNodeWithTag("flashcard_still_learning").performClick()
        composeRule.waitForIdle()
        assertNotEquals(firstWord, cardWord())
        repeat(deckSize - 1) {
            composeRule.onNodeWithTag("flashcard_got_it").performClick()
            composeRule.waitForIdle()
        }
        assertEquals(firstWord, cardWord()) // the re-queued card is back
        composeRule.onNodeWithTag("flashcard_got_it").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("flashcard_done"), 10_000)
        composeRule.onNodeWithTag("finish_button").performClick()

        // 20-card cap: starting units 2 and 3 grows the deck past 20
        runBlocking {
            db.progressDao().upsertLessonProgress(
                LessonProgressEntity(2, 0, completed = false, score = 0.0, attempts = 0, lastAccessed = 0L)
            )
            db.progressDao().upsertLessonProgress(
                LessonProgressEntity(3, 0, completed = false, score = 0.0, attempts = 0, lastAccessed = 0L)
            )
        }
        val biggerDeck = plugin.dictionary.count { (it.introducedInUnit ?: 0) <= 3 }
        assertTrue("test needs >20 words in units 1-3, got $biggerDeck", biggerDeck > 20)
        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_flashcards"), 10_000)
        composeRule.onNodeWithTag("dictionary_flashcards").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("flashcard_card"), 10_000)
        composeRule.onNodeWithTag("flashcard_progress").assertTextEquals("Card 1 of 20")
    }

    private fun cardWord(): String =
        composeRule.onNodeWithTag("flashcard_word", useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }!!

    // --- §6.7 scramble with duplicate tokens ---

    @Test
    fun journey_scramble_duplicate_tokens() {
        // u9l2e11: tokens ["No,", "no,", "grazie!"] — the two "no," tokens are
        // interchangeable; the bot taps by visible text through the real UI.
        runBlocking {
            for (u in 1..8) {
                db.progressDao().upsertLessonProgress(
                    LessonProgressEntity(u, 4, completed = true, score = 1.0, attempts = 1, lastAccessed = 0L)
                )
            }
            db.progressDao().upsertLessonProgress(
                LessonProgressEntity(9, 0, completed = true, score = 1.0, attempts = 1, lastAccessed = 0L)
            )
        }
        val plugin = loadPlugin()
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 30_000)
        composeRule.onNodeWithTag("path_list").performScrollToNode(hasTestTag("unit_node_9"))
        composeRule.onNodeWithTag("unit_node_9").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_1"), 10_000)
        SolverBot(composeRule, plugin, db = db).completeLesson(9, 1)
        assertTrue(
            "u9l2e11 (duplicate tokens) answered correctly",
            runBlocking { db.exerciseResultDao().get("u9l2e11")?.correct == true },
        )
    }
}
