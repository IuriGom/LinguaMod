package com.linguamod.app.journeys

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.linguamod.app.MainActivity
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.di.AppModule
import com.linguamod.app.fakes.FakeOcrGateway
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 4 part B acceptance #3 — OCR via the fake gateway (scripted blocks,
 * no real camera frames needed):
 * - known word tap → dictionary sheet, +2 XP, lookup counter incremented;
 * - unknown word tap → "not in your dictionary yet" with text + context;
 * - model not downloaded → "downloading text recognizer…" shown once;
 * - no Play Services → entry point hidden, one-time explanation, stays hidden.
 *
 * (Camera permission denial lives in [OcrDenialJourneyTest] — it needs a
 * class without the grant rule and must run before it; see its doc.)
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OcrJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @get:Rule(order = 2)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var featureUnlocks: FeatureUnlocks

    @Inject
    lateinit var fakeOcr: FakeOcrGateway

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        hiltRule.inject()
        File(context.getExternalFilesDir(null), "plugins").deleteRecursively()
        runBlocking {
            db.clearAllTables()
            featureUnlocks.clearAll()
        }
        fakeOcr.available = true
        fakeOcr.modelReady = true
        fakeOcr.failWith = null
        fakeOcr.script = emptyList()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** Home → Dictionary → OCR entry point → scanner screen. */
    private fun openOcrScreen() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("tab_dictionary"), 30_000)
        composeRule.onNodeWithTag("tab_dictionary").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_ocr_button"), 15_000)
        composeRule.onNodeWithTag("dictionary_ocr_button").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("ocr_scan_button"), 15_000)
    }

    @Test
    fun journey_ocr_known_and_unknown_words() {
        // block 1: known word "ciao" (dictionary entry, translation "hello; hi; bye")
        // block 2: made-up words that can't be in any dictionary
        fakeOcr.script = listOf("Ciao, come stai?", "xyzzy blorp")
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.OCR_CAMERA) }
        launch()
        openOcrScreen()

        composeRule.onNodeWithTag("ocr_scan_button").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("ocr_results_list"), 15_000)

        // tokens in order: ciao(0), come(1), stai(2) | xyzzy(3), blorp(4)
        val xpBefore = runBlocking { db.progressDao().getUserProgress()?.totalXp ?: 0 }
        val lookupsBefore = runBlocking { db.dictionaryDao().findByLemma("ciao")!!.lookupCount }

        // known word → dictionary sheet, 2 XP + lookup increment
        composeRule.onNodeWithTag("ocr_word_0").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("ocr_word_known"), 10_000)
        composeRule.waitUntilExactlyOneExists(hasText("hello; hi; bye"), 10_000)
        composeRule.waitUntilExactlyOneExists(hasTestTag("ocr_word_xp"), 10_000)
        runBlocking {
            assertEquals(
                xpBefore + CourseRepository.XP_PER_OCR_LOOKUP,
                db.progressDao().getUserProgress()!!.totalXp,
            )
            assertEquals(lookupsBefore + 1, db.dictionaryDao().findByLemma("ciao")!!.lookupCount)
        }
        composeRule.onNodeWithTag("ocr_sheet_close").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("ocr_scan_button"), 10_000)

        // unknown word → "not in your dictionary yet" + recognized text + context
        composeRule.onNodeWithTag("ocr_word_3").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("ocr_word_unknown"), 10_000)
        composeRule.waitUntilExactlyOneExists(hasText("not in your dictionary yet"), 10_000)
        composeRule.onNodeWithTag("ocr_word_unknown")
            .assert(hasAnyDescendant(hasText("xyzzy")))
        composeRule.onNodeWithTag("ocr_word_context")
            .assert(hasText("Recognized in: «xyzzy blorp»"))
        // unknown lookups award nothing
        runBlocking {
            assertEquals(
                xpBefore + CourseRepository.XP_PER_OCR_LOOKUP,
                db.progressDao().getUserProgress()!!.totalXp,
            )
        }
    }

    @Test
    fun journey_ocr_model_downloading_shown_once() {
        fakeOcr.modelReady = false // Play Services is still delivering the model
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.OCR_CAMERA) }
        launch()
        openOcrScreen()

        composeRule.onNodeWithTag("ocr_scan_button").performClick()
        composeRule.waitUntilExactlyOneExists(
            hasTestTag("ocr_downloading") and hasText("downloading text recognizer…"), 10_000,
        )
        // second attempt: the one-time message is not repeated verbatim
        composeRule.onNodeWithTag("ocr_scan_button").performClick()
        composeRule.waitUntilExactlyOneExists(hasText("still downloading", substring = true), 10_000)
        composeRule.onNodeWithTag("ocr_downloading").assertDoesNotExist()
    }

    @Test
    fun journey_ocr_no_play_services_hides_with_one_time_explanation() {
        fakeOcr.available = false // no Google Play Services
        runBlocking { featureUnlocks.unlock(FeatureUnlocks.OCR_CAMERA) }
        launch()

        // Dictionary: entry point hidden, one-time explanation shown
        composeRule.waitUntilExactlyOneExists(hasTestTag("tab_dictionary"), 30_000)
        composeRule.onNodeWithTag("tab_dictionary").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("ocr_unavailable_dialog"), 15_000)
        composeRule.onNodeWithTag("dictionary_ocr_button").assertDoesNotExist()
        composeRule.onNodeWithTag("ocr_unavailable_dismiss").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_search"), 10_000)
        composeRule.onNodeWithTag("ocr_unavailable_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("dictionary_ocr_button").assertDoesNotExist()

        // after an app restart the explanation is not shown again (shown-once
        // marker persisted) and the feature stays hidden
        scenario.close()
        launch()
        composeRule.waitUntilExactlyOneExists(hasTestTag("tab_dictionary"), 30_000)
        composeRule.onNodeWithTag("tab_dictionary").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_search"), 15_000)
        composeRule.onNodeWithTag("ocr_unavailable_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("dictionary_ocr_button").assertDoesNotExist()
        assertTrue(runBlocking { featureUnlocks.wasShown("ocr_unavailable_explained") })
    }
}
