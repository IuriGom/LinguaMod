package com.linguamod.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.ThemeStore
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.LessonProgressEntity
import com.linguamod.app.debug.DummyPluginGenerator
import com.linguamod.app.debug.FakeClock
import com.linguamod.app.plugin.PluginLoader
import com.linguamod.app.plugin.PluginValidator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase-2 readiness check (Stage 4 §6) — JVM half:
 * - the generated 60-unit dummy plugin passes the spec §8 validator and loads;
 * - FeatureUnlocks flags whose trigger units (15/28/45) don't exist in the
 *   loaded plugin evaluate without error.
 */
@RunWith(RobolectricTestRunner::class)
class Phase2ReadinessTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var loader: PluginLoader

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        loader = PluginLoader(context, db)
        loader.pluginDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        loader.pluginDir.deleteRecursively()
    }

    @Test fun `generated 60-unit dummy plugin validates and loads`() = runBlocking {
        val text = DummyPluginGenerator.generate(60)
        val result = PluginValidator.validateText(text)
        assertTrue("dummy plugin must validate: ${result.errors.take(5)}", result.isValid)

        loader.pluginDir.mkdirs()
        File(loader.pluginDir, "dummy60.lingua").writeText(text)
        val loaded = loader.rescan()
        assertEquals(1, loaded.size)
        assertEquals(60, loaded[0].units.size)
        assertEquals(60, db.dictionaryDao().count())
    }

    @Test fun `feature flags tolerate trigger units missing from the plugin`() = runBlocking {
        val unlocks = FeatureUnlocks(TestStores.prefs())
        val repo = CourseRepository(db, loader, FakeClock(), unlocks, ThemeStore(TestStores.prefs()))
        // NOTE: no plugin is loaded at all — the gates must not touch it.

        fun pass(unit: Int) = runBlocking {
            repo.recordCheckpointAttempt(unit, passed = true, score = 1.0)
        }

        pass(10)
        assertTrue(unlocks.isUnlocked(FeatureUnlocks.OCR_CAMERA))
        assertTrue(unlocks.isUnlocked(FeatureUnlocks.MIXED_PRACTICE))
        assertFalse(unlocks.isUnlocked(FeatureUnlocks.STORY2))
        assertFalse(unlocks.isUnlocked(FeatureUnlocks.STORY3))
        assertFalse(unlocks.isUnlocked(FeatureUnlocks.STORY4))

        // trigger units 15/28/45 do not exist in the loaded (absent) plugin —
        // the flags must still trip cleanly off progress rows alone
        pass(15)
        assertTrue(unlocks.isUnlocked(FeatureUnlocks.STORY2))
        pass(28)
        assertTrue(unlocks.isUnlocked(FeatureUnlocks.STORY3))
        pass(45)
        assertTrue(unlocks.isUnlocked(FeatureUnlocks.STORY4))
    }

    @Test fun `gates stay dormant on a short plugin`() = runBlocking {
        val unlocks = FeatureUnlocks(TestStores.prefs())
        val repo = CourseRepository(db, loader, FakeClock(), unlocks, ThemeStore(TestStores.prefs()))
        // 10-unit world: checkpoints 1..10 pass; 15/28/45 never exist
        for (u in 1..10) {
            for (l in 0..4) {
                db.progressDao().upsertLessonProgress(
                    LessonProgressEntity(u, l, completed = true, score = 1.0, attempts = 1)
                )
            }
        }
        repo.recordCheckpointAttempt(10, passed = true, score = 1.0)
        assertFalse(unlocks.isUnlocked(FeatureUnlocks.STORY2))
        assertFalse(unlocks.isUnlocked(FeatureUnlocks.STORY3))
        assertFalse(unlocks.isUnlocked(FeatureUnlocks.STORY4))
        assertTrue(unlocks.isUnlocked(FeatureUnlocks.OCR_CAMERA))
    }
}
