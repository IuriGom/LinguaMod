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
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.di.AppModule
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.solver.SolverBot
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Module
@InstallIn(SingletonComponent::class)
object TestDbModule {
    @Provides
    @Singleton
    fun provideTestDb(@ApplicationContext context: Context): AppDatabase =
        androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
}

@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CompleteUnitJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: AppDatabase

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
        runBlocking { db.clearAllTables() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun journey_fresh_install() {
        // cold start -> demo plugin installs -> unit 1 unlocked, unit 2 locked
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("unit_node_2").assertExists()
        runBlocking {
            assertEquals(null, db.progressDao().getLessonProgress(1, 0))
            assertEquals(null, db.progressDao().getLessonProgress(1, 4)?.completed)
        }
        assertTrue(pluginDir.listFiles()?.any { it.name == "it.lingua" } == true)
    }

    @Test
    fun journey_complete_unit() {
        val plugin = loadPlugin()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        val bot = SolverBot(composeRule, plugin, db = db)
        for (l in 0..3) bot.completeLesson(1, l)
        assertTrue("checkpoint should pass", bot.runCheckpoint(1))
        runBlocking {
            for (l in 0..4) {
                assertTrue("unit1 lesson $l completed", db.progressDao().getLessonProgress(1, l)?.completed == true)
            }
            assertTrue("XP written", db.progressDao().getUserProgress()!!.totalXp > 0)
        }
        // Unit 2 now unlocked: back on Home, its node navigates to detail.
        // Generous timeout: the suite is heavier now that the bot also plays
        // through the audio exercises (Stage 3).
        androidx.test.uiautomator.UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        ).pressBack()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_2"), 30_000)
        composeRule.onNodeWithTag("unit_node_2").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
    }

    @Test
    fun journey_fail_checkpoint() {
        val plugin = loadPlugin()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        val bot = SolverBot(composeRule, plugin, db = db)
        for (l in 0..3) bot.completeLesson(1, l)
        // chaos mode fails the checkpoint -> friendly retry screen, no penalty
        val chaosBot = SolverBot(composeRule, plugin, chaos = true, chaosStaysWrong = true, db = db)
        assertFalse("chaos checkpoint must fail", chaosBot.runCheckpoint(1))
        runBlocking {
            assertTrue("checkpoint not completed", db.progressDao().getLessonProgress(1, 4)?.completed != true)
        }
        // retry works
        assertTrue("retry should pass", bot.runCheckpoint(1))
    }

    @Test
    fun journey_empty_plugins() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        // delete plugin files -> rescan -> empty state, no crash
        pluginDir.deleteRecursively()
        composeRule.onNodeWithTag("tab_profile").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("rescan_button"), 10_000)
        composeRule.onNodeWithTag("rescan_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_home").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("empty_plugins_state"), 15_000)
    }
}
