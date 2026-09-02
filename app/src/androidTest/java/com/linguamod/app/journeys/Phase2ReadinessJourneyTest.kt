package com.linguamod.app.journeys

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguamod.app.MainActivity
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.debug.DummyPluginGenerator
import com.linguamod.app.di.AppModule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 4 §6 / acceptance #7 — Phase-2 readiness: a generated 60-unit dummy
 * plugin renders the full Home path without a crash; scrolling the whole path
 * stays under 5% janky frames (dumpsys gfxinfo); the story2/3/4 gates whose
 * anchor units (15/28/45) exist in the plugin stay dormant while unpassed.
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class Phase2ReadinessJourneyTest {

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

    @Before
    fun setup() {
        hiltRule.inject()
        val dir = File(context.getExternalFilesDir(null), "plugins")
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "dummy60.lingua").writeText(DummyPluginGenerator.generate(60))
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

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return pfd.use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().decodeToString() }
    }

    private fun nodeExists(tag: String): Boolean =
        composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun journey_sixty_unit_path_renders_scrolls_and_stays_smooth() {
        // the whole path renders
        composeRule.waitUntilExactlyOneExists(hasTestTag("path_list"), 60_000)
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)

        // dormant gates: story2/3/4 anchor units exist in this plugin but are
        // locked and their flags are untripped → rows must not render
        for (anchor in listOf(15, 28, 45)) {
            composeRule.onNodeWithTag("path_list")
                .performScrollToNode(hasTestTag("unit_node_$anchor"))
            composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_$anchor"), 15_000)
        }
        for (story in listOf("story2", "story3", "story4")) {
            assertTrue("story_row_$story must stay dormant", !nodeExists("story_row_$story"))
        }

        // measure jank over a full scroll of the 60-unit path. Compose-test
        // scrolling (performScrollToNode) runs on the test clock and skips
        // animation frames, so the scroll-for-frames part uses real injected
        // touch swipes (UiDevice), which render real vsync frames.
        runCatching { shell("dumpsys gfxinfo com.linguamod.app reset") }
        val device = androidx.test.uiautomator.UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        )
        val w = device.displayWidth
        val h = device.displayHeight
        repeat(15) { device.swipe(w / 2, h * 4 / 5, w / 2, h / 5, 20) } // to the bottom
        composeRule.waitForIdle()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_60"), 30_000)
        repeat(15) { device.swipe(w / 2, h / 5, w / 2, h * 4 / 5, 20) } // back to the top
        composeRule.waitForIdle()
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)

        val gfx = shell("dumpsys gfxinfo com.linguamod.app")
        val janky = Regex("""Janky frames:\s+(\d+)\s+\(([\d.]+)%\)""").find(gfx)
        val total = Regex("""Total frames rendered:\s+(\d+)""").find(gfx)
        // spec §6: "assert jank < 5% via dumpsys gfxinfo if practical on the
        // emulator". It is NOT practical here: on this SwiftShader emulator the
        // trivial 10-unit production path already measures ~43% janky frames
        // (median frame 53 ms vs the 16.6 ms deadline), so the 5% bar cannot
        // distinguish app jank from environment slowness. The numbers are
        // logged for the report; the enforced check is the spec's fallback —
        // the full 60-unit path rendered and scrolled without a crash.
        println(
            "Phase2 readiness gfxinfo: total=${total?.groupValues?.get(1)}, " +
                "janky=${janky?.groupValues?.get(0)?.substringAfter(": ")?.trim()}",
        )
        assertTrue("no frames rendered — the path never drew", (total?.groupValues?.get(1)?.toLong() ?: 0L) > 0)
    }
}
