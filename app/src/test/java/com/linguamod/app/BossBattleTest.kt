package com.linguamod.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.linguamod.app.core.Badges
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.ThemeStore
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.UserProgressEntity
import com.linguamod.app.debug.FakeClock
import com.linguamod.app.plugin.BossGenerator
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.PluginLoader
import java.io.File
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
 * Stage 4 §3 boss battles: the gauntlet samples only from completed units'
 * checkpoints, weighting favors previously-wrong exercises, and win/lose
 * rewards are exactly per spec (win: 100 XP + gems ×2 + badge; lose: nothing).
 */
@RunWith(RobolectricTestRunner::class)
class BossBattleTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CourseRepository

    private val plugin: LinguaPluginDto by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(
            LinguaPluginDto.serializer(),
            File("src/main/assets/plugins/it.lingua").readText(),
        )
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = CourseRepository(
            db, PluginLoader(context, db), FakeClock(),
            FeatureUnlocks(TestStores.prefs()), ThemeStore(TestStores.prefs()),
        )
    }

    @After
    fun tearDown() { db.close() }

    // ---------- generation ----------

    @Test fun `boss gauntlet samples only from completed units' checkpoints`() {
        val completed = setOf(1, 2, 3)
        val allowedIds = plugin.units.filter { it.number in completed }
            .flatMap { it.checkpoint!!.exercises.map { e -> e.id!! } }.toSet()
        repeat(20) { seed ->
            val gauntlet = BossGenerator.generate(plugin, completed, emptySet(), kotlin.random.Random(seed))
            assertEquals(BossGenerator.QUESTIONS, gauntlet.size)
            assertTrue(
                "gauntlet contains an exercise from a non-completed unit or a lesson",
                gauntlet.all { it.id in allowedIds },
            )
        }
    }

    @Test fun `boss gauntlet from no completed units is empty`() {
        assertTrue(BossGenerator.generate(plugin, emptySet(), emptySet(), kotlin.random.Random(1)).isEmpty())
    }

    @Test fun `wrong exercises are weighted into the gauntlet`() {
        val completed = setOf(1, 2, 3, 4, 5)
        val checkpointIds = plugin.units.filter { it.number in completed }
            .flatMap { it.checkpoint!!.exercises.map { e -> e.id!! } }
        // ten previously-wrong exercises: 3x weight
        val wrong = checkpointIds.take(10).toSet()
        var wrongPicks = 0
        val runs = 200
        repeat(runs) { seed ->
            val g = BossGenerator.generate(plugin, completed, wrong, kotlin.random.Random(seed))
            wrongPicks += g.count { it.id in wrong }
        }
        val mean = wrongPicks.toDouble() / runs
        // unweighted expectation: 15 * 10/50 = 3.0; weighted (3x): 15 * 30/70 ≈ 6.4
        assertTrue("weighted mean $mean should be well above the unweighted 3.0", mean > 4.5)
    }

    // ---------- rewards ----------

    @Test fun `boss win awards 100 XP, doubles gems, grants the badge`() = runBlocking {
        db.progressDao().upsertUserProgress(UserProgressEntity(gems = 7))
        val xpBefore = repo.userProgress().totalXp
        val rewarded = repo.recordBossResult(5, won = true)
        assertTrue(rewarded)
        val p = repo.userProgress()
        assertEquals(xpBefore + CourseRepository.XP_PER_BOSS_WIN, p.totalXp)
        assertEquals(14, p.gems)
        assertTrue(db.badgeDao().get(Badges.bossChampionId(5)) != null)
        assertEquals(true, db.bossDao().get("boss_5")?.won)
    }

    @Test fun `boss loss costs nothing`() = runBlocking {
        db.progressDao().upsertUserProgress(UserProgressEntity(totalXp = 50, gems = 7, hearts = 3))
        val rewarded = repo.recordBossResult(5, won = false)
        assertTrue(!rewarded)
        val p = repo.userProgress()
        assertEquals(50, p.totalXp)
        assertEquals(7, p.gems)
        assertEquals(3, p.hearts)
        assertEquals(false, db.bossDao().get("boss_5")?.won)
        assertTrue(db.badgeDao().get(Badges.bossChampionId(5)) == null)
    }

    @Test fun `repeat boss win grants no further rewards`() = runBlocking {
        db.progressDao().upsertUserProgress(UserProgressEntity(gems = 10))
        repo.recordBossResult(5, won = true)
        val xpAfterFirst = repo.userProgress().totalXp
        val gemsAfterFirst = repo.userProgress().gems
        val rewarded = repo.recordBossResult(5, won = true)
        assertTrue(!rewarded)
        assertEquals(xpAfterFirst, repo.userProgress().totalXp)
        assertEquals(gemsAfterFirst, repo.userProgress().gems)
    }
}
