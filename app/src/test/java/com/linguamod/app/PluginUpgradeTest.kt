package com.linguamod.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.UserProgressEntity
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.PluginLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bundled-plugin upgrade path (Stage 4B): a newer bundled plugin version must
 * replace the installed copy WITHOUT wiping user progress or dictionary lookup
 * counts; same/older versions and hand-modified installs are left alone.
 */
@RunWith(RobolectricTestRunner::class)
class PluginUpgradeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var loader: PluginLoader

    private val pluginDir: File get() = loader.pluginDir
    private val installed: File get() = File(pluginDir, "it.lingua")

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        loader = PluginLoader(context, db)
        pluginDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        pluginDir.deleteRecursively()
    }

    private fun bundledText(): String =
        context.assets.open("plugins/it.lingua").bufferedReader().readText()

    private fun bundledVersion(): Int =
        Json.decodeFromString(LinguaPluginDto.serializer(), bundledText()).meta!!.version!!

    /** The bundled plugin with meta.version rewritten (simulates an old install). */
    private fun bundledWithVersion(version: Int): String {
        val root = Json.parseToJsonElement(bundledText()).jsonObject.toMutableMap()
        val meta = root.getValue("meta").jsonObject.toMutableMap()
        meta["version"] = JsonPrimitive(version)
        root["meta"] = JsonObject(meta)
        return JsonObject(root).toString()
    }

    private fun installedVersion(): Int =
        Json.decodeFromString(LinguaPluginDto.serializer(), installed.readText()).meta!!.version!!

    @Test fun `fresh install copies the bundled plugin`() = runBlocking {
        loader.installBundledDemoIfNeeded()
        assertTrue(installed.exists())
        assertEquals(bundledVersion(), installedVersion())
    }

    @Test fun `existing install with older bundled plugin upgrades`() = runBlocking {
        // simulate an existing install carrying plugin v(bundled-1)
        pluginDir.mkdirs()
        installed.writeText(bundledWithVersion(bundledVersion() - 1))
        db.progressDao().upsertUserProgress(UserProgressEntity(totalXp = 123, gems = 7))

        loader.installBundledDemoIfNeeded()

        assertEquals("bundled plugin should have been upgraded", bundledVersion(), installedVersion())
        // user progress untouched
        val p = db.progressDao().getUserProgress()!!
        assertEquals(123, p.totalXp)
        assertEquals(7, p.gems)
    }

    @Test fun `same or newer installed version is not touched`() = runBlocking {
        pluginDir.mkdirs()
        installed.writeText(bundledWithVersion(bundledVersion() + 1))
        val before = installed.readBytes()
        loader.installBundledDemoIfNeeded()
        assertTrue("file must not be overwritten", before.contentEquals(installed.readBytes()))
    }

    @Test fun `existing install without plugin files does not reinstall`() = runBlocking {
        db.progressDao().upsertUserProgress(UserProgressEntity())
        loader.installBundledDemoIfNeeded()
        assertFalse("user removed all plugins — do not reinstall", installed.exists())
    }

    @Test fun `user-replaced bundled plugin is left alone`() = runBlocking {
        pluginDir.mkdirs()
        File(pluginDir, "fr.lingua").writeText("{}") // another plugin, no it.lingua
        db.progressDao().upsertUserProgress(UserProgressEntity())
        loader.installBundledDemoIfNeeded()
        assertFalse(installed.exists())
    }

    @Test fun `upgrade preserves dictionary lookup counts`() = runBlocking {
        // install the "old" version and accumulate lookups on a known word
        pluginDir.mkdirs()
        installed.writeText(bundledWithVersion(bundledVersion() - 1))
        db.progressDao().upsertUserProgress(UserProgressEntity())
        loader.rescan()
        val entry = db.dictionaryDao().findByLemma("ciao")!!
        repeat(3) { db.dictionaryDao().incrementLookup(entry.id) }
        assertEquals(3, db.dictionaryDao().findByLemma("ciao")!!.lookupCount)

        // upgrade + rescan: content replaced, lookup counts carried over
        loader.installBundledDemoIfNeeded()
        assertEquals(bundledVersion(), installedVersion())
        loader.rescan()
        assertEquals(3, db.dictionaryDao().findByLemma("ciao")!!.lookupCount)
        assertTrue("upgraded plugin still populates the dictionary", db.dictionaryDao().count() > 0)
    }
}
