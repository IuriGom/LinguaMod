package com.linguamod.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.ReviewItemEntity
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.PluginValidator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stage 8 gauntlet performance budgets (JVM, Robolectric):
 *  - review-deck due query < 100 ms with 1000 seeded due items
 *  - plugin parse (full decode + validation) of the real 60-unit it.lingua < 1 s
 * Numbers are printed for the BUILD_REPORT gauntlet log. Budgets are generous
 * enough to stay meaningful on a loaded CI host while catching real regressions
 * (today's parse is ~100x under budget, the query ~1000x under).
 */
@RunWith(RobolectricTestRunner::class)
class PerfBudgetTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `review due query under 100ms with 1000 seeded due items`() = runBlocking {
        val now = 1_700_000_000_000L
        repeat(1000) { i ->
            db.reviewDao().upsert(
                ReviewItemEntity(
                    exerciseId = "seed_ex_$i",
                    intervalMillis = 86_400_000L,
                    dueAtMillis = now - i, // all due, distinct order keys
                    repetitions = 1,
                )
            )
        }
        // warm the in-memory SQLite page cache, then measure steady-state
        val warm = db.reviewDao().dueItems(now)
        assertEquals(1000, warm.size)

        val timings = (0 until 5).map {
            val t0 = System.nanoTime()
            val rows = db.reviewDao().dueItems(now)
            assertEquals(1000, rows.size)
            (System.nanoTime() - t0) / 1_000_000.0
        }
        val median = timings.sorted()[timings.size / 2]
        println("[perf] review due query, 1000 due items: median ${"%.2f".format(median)} ms (runs: ${timings.joinToString { "%.2f".format(it) }})")
        assertTrue("review due query took ${"%.2f".format(median)} ms (budget 100 ms)", median < 100.0)
    }

    @Test
    fun `real plugin parses and validates under 1s`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = context.assets.open("plugins/it.lingua").bufferedReader().readText()
        val json = Json { ignoreUnknownKeys = true }

        val timings = (0 until 5).map {
            val t0 = System.nanoTime()
            val plugin = json.decodeFromString(LinguaPluginDto.serializer(), text)
            val verdict = PluginValidator.validateFile(text.toByteArray())
            assertTrue("real plugin must stay valid: ${verdict.errors}", verdict.isValid)
            assertEquals(60, plugin.units.size)
            (System.nanoTime() - t0) / 1_000_000.0
        }
        val median = timings.sorted()[timings.size / 2]
        println("[perf] plugin parse+validate (60 units, full dictionary): median ${"%.2f".format(median)} ms")
        assertTrue("plugin parse took ${"%.2f".format(median)} ms (budget 1000 ms)", median < 1000.0)
    }
}
