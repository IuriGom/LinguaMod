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
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linguamod.app.MainActivity
import com.linguamod.app.core.Badges
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.LessonProgressEntity
import com.linguamod.app.data.db.UserProgressEntity
import com.linguamod.app.di.AppModule
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.StoryNodeDto
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 4 part A acceptance journeys:
 * - Story 1 completable end-to-end; wrong choices show teaching feedback and
 *   loop; terminal completion awards 30 XP + the Narratore badge; replayable;
 * - placeholder stories (nodes: []) render the "future content pack" message;
 * - boss battle: losing costs nothing, winning awards 100 XP + gems ×2 + badge;
 * - mixed practice session results feed the Stage 3 review scheduler.
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class Stage4JourneyTest {

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

    // ---------- Story Mode (§2) ----------

    @Test
    fun journey_story1_correct_path_wrong_loops_and_rewards() {
        val plugin = loadPlugin()
        val story = plugin.stories.first { it.id == "story1" }
        seedCompletedUnits(5)
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.STORY1) }

        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 30_000)
        composeRule.onNodeWithTag("path_list").performScrollToNode(hasTestTag("story_row_story1"))
        composeRule.onNodeWithTag("story_row_story1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_screen"), 15_000)

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
            assertTrue(db.badgeDao().get(Badges.narratoreId("story1")) != null)
        }

        // replayable: back to the first node, no double reward
        composeRule.onNodeWithTag("story_replay").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_screen"), 10_000)
        composeRule.onNodeWithTag("story_speaker")
            .assert(hasText(story.nodes.first().speaker!!))
        // leave
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_choice_0"), 10_000)
    }

    @Test
    fun journey_placeholder_story_renders_future_pack() {
        // story2's anchor unit (15) is not in the 10-unit plugin; trip the flag
        // directly — the row parks at the end of the path.
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.STORY2) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 30_000)
        composeRule.onNodeWithTag("path_list").performScrollToNode(hasTestTag("story_row_story2"))
        composeRule.onNodeWithTag("story_row_story2").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_placeholder"), 15_000)
        composeRule.waitUntilExactlyOneExists(
            hasText("This story arrives with a future content pack."), 10_000,
        )
        composeRule.onNodeWithTag("finish_button").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 10_000)
    }

    // ---------- Boss Battles (§3) ----------

    @Test
    fun journey_boss_lose_costs_nothing_then_win_rewards() {
        val plugin = loadPlugin()
        seedCompletedUnits(5)
        runBlocking {
            featureUnlocks.unlock(FeatureUnlocks.BOSS_BATTLES)
            db.progressDao().upsertUserProgress(UserProgressEntity(totalXp = 50, gems = 8, hearts = 5))
        }
        val bot = SolverBot(composeRule, plugin, db = db)

        // lose: 3 strikes end the battle early, no penalty
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 30_000)
        composeRule.onNodeWithTag("path_list").performScrollToNode(hasTestTag("boss_row_5"))
        composeRule.onNodeWithTag("boss_row_5").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("boss_banner"), 15_000)
        composeRule.waitUntilExactlyOneExists(hasTestTag("boss_strikes"), 10_000)
        bot.solveSampledSession("boss_won", wrongAnswers = 3)
        composeRule.waitUntilExactlyOneExists(hasTestTag("boss_lost"), 30_000)
        runBlocking {
            val p = db.progressDao().getUserProgress()!!
            assertEquals(50, p.totalXp)
            assertEquals(8, p.gems)
            assertEquals(5, p.hearts)
            assertEquals(false, db.bossDao().get("boss_5")?.won)
            assertTrue(db.badgeDao().get(Badges.bossChampionId(5)) == null)
        }
        composeRule.onNodeWithTag("finish_button").performClick()

        // win: 100 XP, gems ×2, per-boss badge
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 15_000)
        composeRule.onNodeWithTag("path_list").performScrollToNode(hasTestTag("boss_row_5"))
        composeRule.onNodeWithTag("boss_row_5").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("boss_banner"), 15_000)
        bot.solveSampledSession("boss_won")
        composeRule.waitUntilExactlyOneExists(hasTestTag("boss_won"), 30_000)
        runBlocking {
            val p = db.progressDao().getUserProgress()!!
            assertEquals(50 + CourseRepository.XP_PER_BOSS_WIN, p.totalXp)
            assertEquals(16, p.gems)
            assertTrue(db.badgeDao().get(Badges.bossChampionId(5)) != null)
            assertEquals(true, db.bossDao().get("boss_5")?.won)
        }
        composeRule.onNodeWithTag("finish_button").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 15_000)
    }

    // ---------- Mixed Practice (§4) ----------

    @Test
    fun journey_mixed_practice_feeds_review_scheduler() {
        val plugin = loadPlugin()
        seedCompletedUnits(10)
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.MIXED_PRACTICE) }
        val bot = SolverBot(composeRule, plugin, db = db)

        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 30_000)
        composeRule.waitUntilExactlyOneExists(hasTestTag("practice_card"), 15_000)
        val xpBefore = runBlocking { db.progressDao().getUserProgress()?.totalXp ?: 0 }
        val heartsBefore = runBlocking { db.progressDao().getUserProgress()?.hearts ?: 5 }
        assertEquals(0, runBlocking { db.reviewDao().count() })

        composeRule.onNodeWithTag("practice_card").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("practice_banner"), 15_000)
        // 2 wrong answers: each must land in the review table (SM-2 lite)
        bot.solveSampledSession("practice_complete", wrongAnswers = 2)
        composeRule.waitUntilExactlyOneExists(hasTestTag("practice_complete"), 30_000)
        composeRule.onNodeWithTag("finish_button").performClick()

        runBlocking {
            assertEquals(2, db.reviewDao().count())
            val p = db.progressDao().getUserProgress()!!
            assertEquals(xpBefore, p.totalXp)
            assertEquals(heartsBefore, p.hearts)
        }
    }
}
