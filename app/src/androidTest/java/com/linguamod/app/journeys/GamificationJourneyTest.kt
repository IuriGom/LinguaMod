package com.linguamod.app.journeys

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linguamod.app.MainActivity
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.ThemeStore
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stage 2B journeys: hearts never block; the unlock snackbar shows exactly once. */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class GamificationJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var featureUnlocks: FeatureUnlocks

    @Inject
    lateinit var themeStore: ThemeStore

    private lateinit var scenario: ActivityScenario<MainActivity>

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val pluginDir: File get() = File(context.getExternalFilesDir(null), "plugins")

    private fun loadPlugin(): LinguaPluginDto {
        val text = context.assets.open("plugins/it.lingua").bufferedReader().readText()
        return Json { ignoreUnknownKeys = true }.decodeFromString(LinguaPluginDto.serializer(), text)
    }

    @Before
    fun setup() {
        hiltRule.inject()
        pluginDir.deleteRecursively()
        runBlocking {
            db.clearAllTables()
            featureUnlocks.clearAll() // persisted prefs survive across test runs: reset for determinism
            themeStore.clearAll()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun hearts_at_zero_lesson_still_completable() {
        val plugin = loadPlugin()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        runBlocking {
            val p = db.progressDao().getUserProgress()
                ?: com.linguamod.app.data.db.UserProgressEntity()
            db.progressDao().upsertUserProgress(p.copy(hearts = 0))
        }
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        composeRule.onNodeWithTag("lesson_row_0").performClick()
        // 0 hearts: empty display + subtle note, no blocking UI
        composeRule.waitUntilExactlyOneExists(hasTestTag("hearts_display"), 10_000)
        composeRule.waitUntilExactlyOneExists(hasText("take your time", substring = true), 10_000)
        androidx.test.uiautomator.UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        ).pressBack()
        // Solver Bot completes the lesson anyway
        val bot = SolverBot(composeRule, plugin, db = db)
        bot.completeLesson(1, 0)
        runBlocking {
            assertTrue(db.progressDao().getLessonProgress(1, 0)?.completed == true)
            // lesson completion refills hearts fully
            assertEquals(5, db.progressDao().getUserProgress()!!.hearts)
        }
    }

    @Test
    fun unlock_snackbar_shown_once() {
        val plugin = loadPlugin()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        val bot = SolverBot(composeRule, plugin, db = db)
        for (l in 0..3) bot.completeLesson(1, l)
        // pass the Unit 1 checkpoint -> leaderboards gate trips -> snackbar on the finish screen
        assertTrue(bot.runCheckpoint(1, clickFinish = false))
        composeRule.waitUntilExactlyOneExists(hasTestTag("unlock_snackbar"), 10_000)
        runBlocking { assertTrue(featureUnlocks.isUnlocked(FeatureUnlocks.LEADERBOARDS)) }
        composeRule.onNodeWithTag("finish_button").performClick()
        // re-pass the same checkpoint: snackbar must NOT reappear
        assertTrue(bot.runCheckpoint(1, clickFinish = false))
        composeRule.onNodeWithTag("checkpoint_passed").assertExists()
        composeRule.onNodeWithTag("unlock_snackbar").assertDoesNotExist()
        runBlocking { assertTrue(featureUnlocks.wasShown(FeatureUnlocks.LEADERBOARDS)) }
    }
}
