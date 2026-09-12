package com.linguamod.app.journeys

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import com.linguamod.app.MainActivity
import com.linguamod.app.core.Badges
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.LessonProgressEntity
import com.linguamod.app.di.AppModule
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.StoryNodeDto
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.io.File
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
 * Stage 6 batch 1, story acceptance: Story 3 "Il messaggio" (unlockAfterUnit 28)
 * completable end-to-end through the real UI; two wrong choices show teaching
 * feedback and loop; completion awards the XP and the Narratore badge; replayable.
 * Mirrors the Story 1 journey in [Stage4JourneyTest].
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class Story3JourneyTest {

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

    /** Seeds units 1..n as fully completed (lessons + passed checkpoints). */
    private fun seedCompletedUnits(n: Int) {
        runBlocking {
            for (u in 1..n) {
                for (l in 0..4) {
                    db.progressDao().upsertLessonProgress(
                        LessonProgressEntity(u, l, completed = true, score = 1.0, attempts = 1, lastAccessed = 0L)
                    )
                }
            }
        }
    }

    /** Scrolls to a path row and taps it, retrying until [targetTag] appears
     *  (taps can be swallowed mid-scroll on a loaded emulator, and the
     *  scroll-through itself can miss rows on the 25-unit path under load). */
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
            if (nodeExists("path_list")) scrollPathToTop()
        }
        throw AssertionError("row $rowTag never opened ($targetTag)", last)
    }

    /**
     * performScrollToNode's scroll-through can misjudge the end on the long
     * 25-unit path under load — swipe back up until the list can't scroll
     * further before retrying (same idiom as Stage4GatingTest).
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

    private fun nodeExists(tag: String): Boolean =
        composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun journey_story3_correct_path_wrong_loops_and_rewards() {
        val plugin = loadPlugin()
        val story = plugin.stories.first { it.id == "story3" }
        seedCompletedUnits(28)
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.STORY3) }

        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 30_000)
        openPathRow("story_row_story3", "story_screen")

        // English toggle shows the translation
        composeRule.onNodeWithTag("story_toggle_en").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_text_en"), 10_000)

        val byId = story.nodes.associateBy { it.id!! }
        var node: StoryNodeDto = story.nodes.first()
        var loops = 0
        while (!node.terminal) {
            val correctIdx = node.choices.indexOfFirst { it.correct }
            val wrongIdx = node.choices.indexOfFirst { !it.correct }
            if (loops < 2) {
                // wrong choice: teaching feedback shows, the story loops on the node
                composeRule.onNodeWithTag("story_choice_$wrongIdx").performClick()
                composeRule.waitUntilExactlyOneExists(hasTestTag("story_feedback"), 10_000)
                composeRule.waitUntilExactlyOneExists(
                    hasText(node.choices[wrongIdx].feedbackEn!!), 10_000,
                )
                composeRule.waitUntilExactlyOneExists(hasTestTag("story_choice_0"), 10_000)
                loops++
            }
            composeRule.onNodeWithTag("story_choice_$correctIdx").performClick()
            composeRule.waitForIdle()
            node = byId.getValue(node.choices[correctIdx].next!!)
        }
        assertEquals(2, loops)

        // terminal node → completion: +30 XP and the Narratore badge
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_finish"), 10_000)
        val xpBefore = runBlocking { db.progressDao().getUserProgress()?.totalXp ?: 0 }
        composeRule.onNodeWithTag("story_finish").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_complete"), 10_000)
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_xp"), 10_000)
        runBlocking {
            assertEquals(
                xpBefore + CourseRepository.XP_PER_STORY,
                db.progressDao().getUserProgress()!!.totalXp,
            )
            assertTrue(db.badgeDao().get(Badges.narratoreId("story3")) != null)
        }

        // replayable: back to the first node, no double reward
        composeRule.onNodeWithTag("story_replay").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_screen"), 10_000)
        composeRule.onNodeWithTag("story_speaker")
            .assert(hasText(story.nodes.first().speaker!!))
        // leave
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_choice_0"), 10_000)
    }
}
