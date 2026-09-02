package com.linguamod.app.journeys

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linguamod.app.MainActivity
import com.linguamod.app.data.AudioStore
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.LessonProgressEntity
import com.linguamod.app.di.AppModule
import com.linguamod.app.fakes.FakeTtsGateway
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
 * Stage 3 acceptance §3: device without the Italian TTS voice. The one-time
 * "Install the Italian voice" dialog shows once (persisted flag), audio
 * buttons hide instead of erroring, and a full oral lesson stays completable.
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NoItalianVoiceJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var fakeTts: FakeTtsGateway

    @Inject
    lateinit var audioStore: AudioStore

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
        runBlocking { db.clearAllTables(); audioStore.clearAll() }
        fakeTts.available = false // no Italian voice on this device
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun journey_no_italian_voice() {
        val plugin = loadPlugin()

        // one-time setup dialog, persisted flag
        composeRule.waitUntilExactlyOneExists(hasTestTag("tts_voice_dialog"), 30_000)
        composeRule.onNodeWithTag("tts_voice_not_now").performClick()
        composeRule.waitUntilDoesNotExist(hasTestTag("tts_voice_dialog"), 5_000)
        assertTrue(
            "the dialog-shown flag must persist",
            runBlocking { audioStore.wasVoicePromptShown() },
        )

        // open the oral lesson (listening + speaking); lessons 0-2 pre-completed
        runBlocking {
            for (l in 0..2) {
                db.progressDao().upsertLessonProgress(
                    LessonProgressEntity(1, l, completed = true, score = 1.0, attempts = 1, lastAccessed = 0L)
                )
            }
        }
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_3"), 10_000)
        composeRule.onNodeWithTag("lesson_row_3").performClick()

        // audio buttons HIDE on the listening exercise — no error, no crash
        val oral = plugin.units[0].lessons!![3]
        composeRule.waitUntilExactlyOneExists(hasTestTag("exercise_${oral.exercises[0].id}"), 15_000)
        composeRule.onNodeWithTag("listen_play").assertDoesNotExist()
        composeRule.onNodeWithTag("listen_slow").assertDoesNotExist()

        // Solver Bot completes the whole lesson without any audio
        SolverBot(composeRule, plugin, db = db).solveOpenSession(oral.exercises.map { it.id!! })

        // the dialog never comes back (shown once ever): full restart, still hidden
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("tts_voice_dialog").assertDoesNotExist()
    }
}
