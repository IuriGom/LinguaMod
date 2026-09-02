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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.linguamod.app.MainActivity
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.di.AppModule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 4 part B acceptance #3 — CAMERA permission denied via the real system
 * dialog: the OCR feature hides gracefully (denial card, no scanner UI), no
 * crash, and the rest of the app is unaffected.
 *
 * Ordering note: this class must run before any class that GRANTS the CAMERA
 * permission (OcrJourneyTest's GrantPermissionRule). Runtime-permission grants
 * persist for the whole instrumentation run, and revoking kills the app
 * process mid-test — so denial can only be exercised from a never-granted
 * state. The instrumentation runner enumerates classes sorted by name, and
 * "OcrDenialJourneyTest" sorts before "OcrJourneyTest".
 */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OcrDenialJourneyTest {

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
        File(context.getExternalFilesDir(null), "plugins").deleteRecursively()
        runBlocking {
            db.clearAllTables()
            featureUnlocks.clearAll()
            featureUnlocks.unlock(FeatureUnlocks.OCR_CAMERA)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun journey_ocr_permission_denied_hides_gracefully() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("tab_dictionary"), 30_000)
        composeRule.onNodeWithTag("tab_dictionary").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_ocr_button"), 15_000)
        composeRule.onNodeWithTag("dictionary_ocr_button").performClick()

        // rationale first, then the system dialog → "Don't allow"
        composeRule.waitUntilExactlyOneExists(hasTestTag("ocr_permission_rationale"), 15_000)
        composeRule.onNodeWithTag("ocr_grant_permission").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val deny = device.wait(
            Until.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button")),
            10_000,
        ) ?: device.wait(Until.findObject(By.textContains("Don")), 5_000)
        checkNotNull(deny) { "system permission dialog never appeared" }
        deny.click()

        // denial → scanner hidden behind a graceful denial card, no crash
        composeRule.waitUntilExactlyOneExists(hasTestTag("ocr_permission_denied"), 15_000)
        composeRule.onNodeWithTag("ocr_scan_button").assertDoesNotExist()
        composeRule.onNodeWithTag("ocr_denied_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_search"), 10_000)
    }
}
