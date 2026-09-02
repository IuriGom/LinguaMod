package com.linguamod.app.journeys

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.linguamod.app.MainActivity
import com.linguamod.app.audio.SpeakingSubstitution
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.LessonProgressEntity
import com.linguamod.app.di.AppModule
import com.linguamod.app.fakes.FakeSpeechRecognizerGateway
import com.linguamod.app.fakes.FakeTtsGateway
import com.linguamod.app.plugin.ExerciseTypes
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

/** journey_rotation: rotate mid-lesson, state restored, no crash.
 *  journey_airplane: radios are off (script enforces); full lesson playable. */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RotationAirplaneJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var fakeTts: FakeTtsGateway

    @Inject
    lateinit var fakeRecognizer: FakeSpeechRecognizerGateway

    @Inject
    lateinit var speakingSubstitution: SpeakingSubstitution

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()

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

    @Test
    fun journey_rotation() {
        val plugin = loadPlugin()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        composeRule.onNodeWithTag("lesson_row_0").performClick()
        // mid-lesson: first exercise visible
        val firstId = plugin.units[0].lessons!![0].exercises[0].id!!
        composeRule.waitUntilExactlyOneExists(hasTestTag("exercise_$firstId"), 15_000)
        // rotate both ways
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.setOrientationLeft()
        composeRule.waitForIdle()
        device.setOrientationNatural()
        composeRule.waitForIdle()
        // state restored, no crash: same exercise still on screen
        composeRule.waitUntilExactlyOneExists(hasTestTag("exercise_$firstId"), 15_000)
    }

    @Test
    fun journey_airplane() {
        // Radios off are enforced by tools/journeys/airplane.sh around this test;
        // the app has no network permission at all in Stage 1, so a full lesson
        // must be playable regardless.
        val plugin = loadPlugin()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        SolverBot(composeRule, plugin, db = db).completeLesson(1, 0)
    }

    /**
     * Extended airplane journey (Stage 3 acceptance §2): radios off, an oral
     * lesson (listening + speaking) end-to-end. The fake recognizer is
     * unavailable (offline): both speaking exercises silently substitute to
     * listening variants, the explanation snackbar logic fires exactly once,
     * and every expected string was requested from the TTS gateway.
     */
    @Test
    fun journey_airplane_audio() {
        val plugin = loadPlugin()
        fakeRecognizer.available = false // offline: no speech recognizer
        // cacheDir/tts survives `adb install -r`; clear it so every playback
        // really reaches the fake gateway's synthesizeToFile
        File(context.cacheDir, "tts").deleteRecursively()
        // open the oral lesson (unit 1 lesson 4 = index 3: 3 listening + 2 speaking)
        runBlocking {
            for (l in 0..2) {
                db.progressDao().upsertLessonProgress(
                    LessonProgressEntity(1, l, completed = true, score = 1.0, attempts = 1, lastAccessed = 0L)
                )
            }
        }
        val oral = plugin.units[0].lessons!![3]
        val speakCount = oral.exercises.count { it.type == ExerciseTypes.SPEAKING }
        assertTrue("oral lesson must contain speaking exercises", speakCount >= 2)
        val expectedSpoken = oral.exercises.mapNotNull {
            when (it.type) {
                ExerciseTypes.LISTENING -> it.speakIt
                ExerciseTypes.SPEAKING -> it.targetIt // substituted variant speaks the target
                else -> null
            }
        }

        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_3"), 10_000)
        SolverBot(composeRule, plugin, db = db).completeLesson(1, 3)

        // both speaking exercises were substituted (snackbar shown once per
        // session — exactly-once is unit-tested in SpeakingSubstitutionTest)
        assertEquals(speakCount, speakingSubstitution.substitutionCount.get())
        // every expected string was requested from the TTS gateway
        val requested = fakeTts.synthesized.toSet() + fakeTts.spoken.map { it.first }.toSet()
        expectedSpoken.forEach {
            assertTrue("TTS gateway was never asked for '$it'", it in requested)
        }
    }
}
