package com.linguamod.app.journeys

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linguamod.app.MainActivity
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

/** Stage 1 acceptance #9: dictionary gating.
 *  Empty at fresh install; contains exactly the taught words once Unit 1 starts. */
@OptIn(ExperimentalTestApi::class)
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DictionaryGatingTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: AppDatabase

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        hiltRule.inject()
        File(context.getExternalFilesDir(null), "plugins").deleteRecursively()
        runBlocking { db.clearAllTables() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() { scenario.close() }

    @Test
    fun dictionary_gating() {
        // fresh install: dictionary shows nothing taught yet
        composeRule.waitUntilExactlyOneExists(hasTestTag("unit_node_1"), 30_000)
        composeRule.onNodeWithTag("tab_dictionary").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_empty"), 10_000)

        // Solver Bot starts Unit 1 (completes lesson 1) -> words appear
        composeRule.onNodeWithTag("tab_home").performClick()
        composeRule.onNodeWithTag("unit_node_1").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("lesson_row_0"), 10_000)
        val text = context.assets.open("plugins/it.lingua").bufferedReader().readText()
        val plugin = Json { ignoreUnknownKeys = true }.decodeFromString(LinguaPluginDto.serializer(), text)
        SolverBot(composeRule, plugin, db = db).completeLesson(1, 0)

        // dictionary now lists exactly the unit-1 entries (unit 2 still locked)
        val unit1Entries = plugin.dictionary.count { it.introducedInUnit == 1 }
        val rows = runBlocking { db.dictionaryDao().getUpToUnit(1) }
        assertEquals(unit1Entries, rows.size)
        assertTrue(rows.none { it.introducedInUnit > 1 })
        // back to a tab screen (unit detail has no bottom bar), then dictionary
        androidx.test.uiautomator.UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        ).pressBack()
        composeRule.waitUntilExactlyOneExists(hasTestTag("tab_dictionary"), 10_000)
        composeRule.onNodeWithTag("tab_dictionary").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dictionary_list"), 10_000)
    }
}
