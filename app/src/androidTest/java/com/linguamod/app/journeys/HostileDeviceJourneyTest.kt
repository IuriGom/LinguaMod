package com.linguamod.app.journeys

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import com.linguamod.app.data.FeatureUnlocks
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 8 gauntlet hostile-device matrix on emulator-5554 (API-34):
 *  - rotation mid-checkpoint and mid-story (mid-lesson already covered by
 *    RotationAirplaneJourneyTest.journey_rotation)
 *  - process death (HOME + am kill + relaunch) mid-lesson, mid-checkpoint,
 *    mid-story — must relaunch and render without a crash
 *  - am send-trim-memory 80 (TRIM_MEMORY_COMPLETE) at path, mid-lesson, and
 *    mid-story — the flow must survive and keep working
 *  (path end-to-end scroll + frame-stats jank proxy: reused from
 *   Phase2ReadinessJourneyTest since Stage 5, not duplicated here)
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HostileDeviceJourneyTest {

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
    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val pkg: String get() = context.packageName

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
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 30_000)
    }

    @After
    fun tearDown() {
        device.setOrientationNatural()
        scenario.close()
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
        return pfd.use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().decodeToString() }
    }

    /** Wait for a tag; on timeout, fail with a semantics dump of whatever the
     *  app actually rendered (gauntlet diagnostics — see what survived). */
    private fun waitOrDump(tag: String, ms: Long, label: String) {
        try {
            composeRule.waitUntilExactlyOneExists(hasTestTag(tag), ms)
        } catch (e: Throwable) {
            val dumpFile = File(context.getExternalFilesDir(null), "hostile_dump_$label.xml")
            runCatching { dumpFile.parentFile?.mkdirs(); device.dumpWindowHierarchy(dumpFile) }
            val top = shell("dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity'").trim()
            val crash = shell("logcat -d -b crash | tail -30").trim()
            throw AssertionError(
                "$label: tag '$tag' missing after ${ms}ms\n" +
                    "resumed: $top\n" +
                    "crash buffer tail: ${crash.take(1500)}\n" +
                    "window hierarchy dumped to ${dumpFile.absolutePath}",
                e,
            )
        }
    }

    private fun seedLessonsCompleted(unitNumber: Int, lessonIndexes: IntRange) = runBlocking {
        for (l in lessonIndexes) {
            db.progressDao().upsertLessonProgress(
                LessonProgressEntity(unitNumber, l, completed = true, score = 1.0, attempts = 1, lastAccessed = 0L)
            )
        }
    }

    private fun seedCompletedUnits(n: Int) = runBlocking {
        for (u in 1..n) {
            for (l in 0..4) {
                db.progressDao().upsertLessonProgress(
                    LessonProgressEntity(u, l, completed = true, score = 1.0, attempts = 1, lastAccessed = 0L)
                )
            }
        }
    }

    private fun assertNoCrash(label: String) {
        val log = shell("logcat -d -b crash")
        assertTrue("$label: crash buffer must stay empty", "FATAL EXCEPTION" !in log)
    }

    // --- navigation helpers -------------------------------------------------

    private fun openStoryRow() {
        val deadline = System.currentTimeMillis() + 60_000
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                composeRule.onNodeWithTag("path_list").performScrollToNode(hasTestTag("story_row_story1"))
                composeRule.onNodeWithTag("story_row_story1").performClick()
                composeRule.waitUntilExactlyOneExists(hasTestTag("story_screen"), 8_000)
                return
            } catch (e: AssertionError) {
                last = e
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                last = e
            }
        }
        throw AssertionError("story_row_story1 never opened (story_screen)", last)
    }

    private fun openUnitLesson(unitNodeTag: String, lessonRow: Int, firstExerciseTag: String) {
        val deadline = System.currentTimeMillis() + 60_000
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                composeRule.onNodeWithTag("path_list").performScrollToNode(hasTestTag(unitNodeTag))
                composeRule.onNodeWithTag(unitNodeTag).performClick()
                composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_$lessonRow"), 10_000)
                composeRule.onNodeWithTag("lesson_row_$lessonRow").performClick()
                // tap on a still-locked / recomposing row is swallowed by the UI;
                // only the lesson screen proving the row opened lets us proceed
                composeRule.waitUntilExactlyOneExists(hasTestTag(firstExerciseTag), 15_000)
                return
            } catch (e: AssertionError) {
                last = e
                // if a lesson opened, back out before retrying from the path
                runCatching { device.pressBack() }
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                last = e
                runCatching { device.pressBack() }
            }
        }
        throw AssertionError("openUnitLesson($unitNodeTag, $lessonRow) never reached $firstExerciseTag", last)
    }

    private fun plugin(): LinguaPluginDto = loadPlugin()

    // --- rotation -----------------------------------------------------------

    @Test
    fun hostile_rotation_mid_checkpoint() {
        val p = plugin()
        val firstEx = p.units.first { it.number == 1 }.checkpoint!!.exercises.first().id!!
        seedLessonsCompleted(1, 0..3) // checkpoint row is locked until the 4 lessons are done
        openUnitLesson("unit_node_1", 4, "exercise_$firstEx")
        device.setOrientationLeft()
        composeRule.waitForIdle()
        device.setOrientationNatural()
        composeRule.waitForIdle()
        waitOrDump("exercise_$firstEx", 15_000, "rotation mid-checkpoint post-rotation")
        assertNoCrash("rotation mid-checkpoint")
    }

    @Test
    fun hostile_rotation_mid_story() {
        seedCompletedUnits(5)
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.STORY1) }
        openStoryRow()
        waitOrDump("story_screen", 10_000, "rotation mid-story pre-rotation")
        device.setOrientationLeft()
        composeRule.waitForIdle()
        device.setOrientationNatural()
        composeRule.waitForIdle()
        waitOrDump("story_screen", 10_000, "rotation mid-story post-rotation")
        assertNoCrash("rotation mid-story")
    }

    // --- process death ------------------------------------------------------

    private fun processDeathAt(label: String, reachScreen: () -> Unit) {
        reachScreen()
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1_000)
        shell("am kill $pkg")
        Thread.sleep(1_000)
        shell("am start -n $pkg/.MainActivity")
        Thread.sleep(4_000)
        val pid = shell("pidof $pkg").trim()
        assertTrue("$label: app must relaunch after process death", pid.isNotEmpty())
        // the compose rule stays bound to the dead activity's window after a
        // process kill — rebind to the relaunched instance before asserting
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitOrDump("path_list", 15_000, label)
        assertNoCrash(label)
    }

    @Test
    fun hostile_process_death_mid_lesson() = processDeathAt("process death mid-lesson") {
        val firstEx = plugin().units.first { it.number == 1 }.lessons!![0].exercises.first().id!!
        openUnitLesson("unit_node_1", 0, "exercise_$firstEx")
    }

    @Test
    fun hostile_process_death_mid_checkpoint() = processDeathAt("process death mid-checkpoint") {
        val firstEx = plugin().units.first { it.number == 1 }.checkpoint!!.exercises.first().id!!
        seedLessonsCompleted(1, 0..3) // checkpoint row is locked until the 4 lessons are done
        openUnitLesson("unit_node_1", 4, "exercise_$firstEx")
    }

    @Test
    fun hostile_process_death_mid_story() = processDeathAt("process death mid-story") {
        seedCompletedUnits(5)
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.STORY1) }
        openStoryRow()
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_screen"), 10_000)
    }

    // --- trim memory (TRIM_MEMORY_COMPLETE = 80) ----------------------------

    private fun sendTrimCritical() {
        val pid = shell("pidof $pkg").trim()
        assertTrue("app must be running to receive trim", pid.isNotEmpty())
        shell("am send-trim-memory $pid 80")
        Thread.sleep(1_500)
        val after = shell("pidof $pkg").trim()
        assertTrue("app must survive TRIM_MEMORY_COMPLETE (pid was $pid)", after.isNotEmpty())
    }

    @Test
    fun hostile_trim_memory_at_path_then_lesson() {
        sendTrimCritical()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 10_000)
        // flow still works: Solver Bot completes unit 1 lesson 1 after the trim
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        SolverBot(composeRule, plugin(), db = db).completeLesson(1, 0)
        assertNoCrash("trim memory at path")
    }

    @Test
    fun hostile_trim_memory_mid_lesson() {
        val p = plugin()
        val firstEx = p.units.first { it.number == 1 }.lessons!![0].exercises.first().id!!
        openUnitLesson("unit_node_1", 0, "exercise_$firstEx")
        sendTrimCritical()
        device.pressBack() // SolverBot starts from the unit detail screen
        SolverBot(composeRule, p, db = db).completeLesson(1, 0)
        assertNoCrash("trim memory mid-lesson")
    }

    @Test
    fun hostile_trim_memory_mid_story() {
        seedCompletedUnits(5)
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.STORY1) }
        openStoryRow()
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_screen"), 10_000)
        sendTrimCritical()
        composeRule.waitUntilExactlyOneExists(hasTestTag("story_screen"), 10_000)
        assertNoCrash("trim memory mid-story")
    }

    /** Critical-flow clipping check under the hostile display config that
     *  tools/journeys/hostile_display.sh applies (720x1560 @ 280dpi,
     *  font_scale 1.3): path rows, lesson rows, and every exercise control
     *  must stay visible and tappable — Solver Bot taps them all. */
    @Test
    fun hostile_small_screen_large_font_flow() {
        val plugin = plugin()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("unit_node_1").assertIsDisplayed()
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        for (row in 0..4) {
            composeRule.onNodeWithTag("lesson_row_$row").assertIsDisplayed()
        }
        SolverBot(composeRule, plugin, db = db).completeLesson(1, 0)
        assertNoCrash("small screen + large font flow")
    }

}

/** NOTE: the path end-to-end scroll + frame-stats log for the gauntlet's
 *  jank proxy lives in Phase2ReadinessJourneyTest
 *  (journey_sixty_unit_path_renders_scrolls_and_stays_smooth) since Stage 5;
 *  it is not duplicated here. */
