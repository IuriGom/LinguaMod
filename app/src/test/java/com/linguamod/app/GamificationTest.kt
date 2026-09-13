package com.linguamod.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.linguamod.app.core.Badges
import com.linguamod.app.core.Levels
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.ThemeStore
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.debug.FakeClock
import com.linguamod.app.plugin.PluginLoader
import com.linguamod.app.ui.lesson.LessonViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Stage 2B gamification: XP, hearts, gems, levels, badges, feature gates — all via FakeClock. */
@RunWith(RobolectricTestRunner::class)
class GamificationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CourseRepository
    private lateinit var clock: FakeClock
    private lateinit var featureUnlocks: FeatureUnlocks
    private lateinit var themeStore: ThemeStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        clock = FakeClock()
        featureUnlocks = FeatureUnlocks(TestStores.prefs())
        themeStore = ThemeStore(TestStores.prefs())
        repo = CourseRepository(db, PluginLoader(context, db), clock, featureUnlocks, themeStore)
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun setHearts(hearts: Int) {
        db.progressDao().upsertUserProgress(
            repo.userProgress().copy(hearts = hearts, lastHeartRefillMillis = clock.nowMillis())
        )
    }

    private suspend fun setGems(gems: Int) {
        db.progressDao().upsertUserProgress(repo.userProgress().copy(gems = gems))
    }

    // --- XP ---

    @Test fun `xp constants match spec and accumulate`() = runBlocking {
        assertEquals(5, LessonViewModel.XP_PER_CORRECT)
        assertEquals(10, LessonViewModel.XP_PER_LESSON)
        assertEquals(50, LessonViewModel.XP_PER_CHECKPOINT)
        // a 4-exercise lesson, all correct: 4*5 + 10
        repeat(4) { repo.addXp(LessonViewModel.XP_PER_CORRECT) }
        repo.addXp(LessonViewModel.XP_PER_LESSON)
        assertEquals(30, repo.userProgress().totalXp)
    }

    // --- hearts ---

    @Test fun `hearts refill one per 30 minutes via clock`() = runBlocking {
        setHearts(3)
        repo.refreshHearts()
        assertEquals(3, repo.userProgress().hearts)
        clock.advanceMinutes(30)
        repo.refreshHearts()
        assertEquals(4, repo.userProgress().hearts)
        clock.advanceMinutes(60)
        repo.refreshHearts()
        assertEquals(5, repo.userProgress().hearts)
        clock.advanceMinutes(300) // capped
        repo.refreshHearts()
        assertEquals(CourseRepository.MAX_HEARTS, repo.userProgress().hearts)
    }

    @Test fun `lesson completion refills hearts fully`() = runBlocking {
        setHearts(1)
        repo.completeLesson(1, 0, 1.0)
        assertEquals(CourseRepository.MAX_HEARTS, repo.userProgress().hearts)
    }

    @Test fun `hearts never drop below zero and never block`() = runBlocking {
        setHearts(0)
        repo.loseHeart()
        assertEquals(0, repo.userProgress().hearts)
        // lesson stays completable at 0 hearts
        repo.completeLesson(1, 0, 1.0)
        assertTrue(repo.isLessonCompleted(1, 0))
    }

    @Test fun `wrong answer costs one heart and starts the refill timer`() = runBlocking {
        repo.userProgress()
        repo.loseHeart()
        assertEquals(4, repo.userProgress().hearts)
        assertEquals(clock.nowMillis(), repo.userProgress().lastHeartRefillMillis)
    }

    // --- gems & themes ---

    @Test fun `gems awarded per passed checkpoint`() = runBlocking {
        repo.recordCheckpointAttempt(1, true, 0.9)
        assertEquals(10, repo.userProgress().gems)
        repo.recordCheckpointAttempt(2, true, 0.9)
        assertEquals(20, repo.userProgress().gems)
        repo.recordCheckpointAttempt(2, false, 0.5) // failed attempt: no gems
        assertEquals(20, repo.userProgress().gems)
    }

    @Test fun `theme purchase flow deducts gems and persists`() = runBlocking {
        setGems(100)
        assertTrue(repo.purchaseTheme("azure"))
        assertEquals(50, repo.userProgress().gems)
        assertTrue(themeStore.isPurchased("azure"))
        assertEquals("azure", themeStore.activeTheme.first())
        // re-selecting an owned theme is free
        assertTrue(repo.selectTheme("default"))
        assertEquals("default", themeStore.activeTheme.first())
        assertTrue(repo.purchaseTheme("azure")) // owned: applies without charging
        assertEquals(50, repo.userProgress().gems)
        // unknown id and insufficient funds both refuse
        assertFalse(repo.purchaseTheme("nope"))
        assertFalse(repo.purchaseTheme("violet")) // 100 gems, only 50 left
        assertFalse(repo.selectTheme("midnight")) // not owned
    }

    // --- levels ---

    @Test fun `level 1 triggers exactly on unit 10 checkpoint pass`() = runBlocking {
        for (u in 1..9) repo.recordCheckpointAttempt(u, true, 0.9)
        assertEquals(0, Levels.levelFor(db.progressDao().highestCompletedCheckpoint()))
        repo.recordCheckpointAttempt(10, true, 0.9)
        assertEquals(1, Levels.levelFor(db.progressDao().highestCompletedCheckpoint()))
        assertEquals(25, Levels.nextThreshold(1))
    }

    @Test fun `level 2 triggers exactly on unit 25 checkpoint pass`() = runBlocking {
        for (u in 1..24) repo.recordCheckpointAttempt(u, true, 0.9)
        assertEquals(1, Levels.levelFor(db.progressDao().highestCompletedCheckpoint()))
        repo.recordCheckpointAttempt(25, true, 0.9)
        assertEquals(2, Levels.levelFor(db.progressDao().highestCompletedCheckpoint()))
        assertEquals(40, Levels.nextThreshold(2))
    }

    @Test fun `level 3 triggers exactly on unit 40 checkpoint pass`() = runBlocking {
        for (u in 1..39) repo.recordCheckpointAttempt(u, true, 0.9)
        assertEquals(2, Levels.levelFor(db.progressDao().highestCompletedCheckpoint()))
        repo.recordCheckpointAttempt(40, true, 0.9)
        assertEquals(3, Levels.levelFor(db.progressDao().highestCompletedCheckpoint()))
        assertEquals(60, Levels.nextThreshold(3))
    }

    @Test fun `levels tolerate a plugin with only two units`() = runBlocking {
        repo.recordCheckpointAttempt(1, true, 0.9)
        repo.recordCheckpointAttempt(2, true, 0.9)
        val highest = db.progressDao().highestCompletedCheckpoint()
        assertEquals(0, Levels.levelFor(highest)) // no level yet, no error
        assertEquals(10, Levels.nextThreshold(0))
        // phase math for a short plugin does not crash
        assertEquals(1, Levels.phaseFor(2))
        assertEquals(1..2, Levels.phaseRange(1, totalUnits = 2))
    }

    // --- badges ---

    @Test fun `badges trip on their conditions`() = runBlocking {
        assertNull(db.badgeDao().get(Badges.PRIMO_PASSO))
        repo.recordCheckpointAttempt(1, true, 0.9)
        assertNotNull(db.badgeDao().get(Badges.PRIMO_PASSO))
        assertNull(db.badgeDao().get(Badges.PERFEZIONISTA)) // 90% is not 100%
        repo.recordCheckpointAttempt(2, true, 1.0)
        assertNotNull(db.badgeDao().get(Badges.PERFEZIONISTA))
        for (u in 3..10) repo.recordCheckpointAttempt(u, true, 0.9)
        assertNotNull(db.badgeDao().get(Badges.DIECI_UNITA))
    }

    @Test fun `settimana italiana badge at 7 day streak`() = runBlocking {
        repeat(6) { repo.completeLesson(1, it % 4, 1.0); clock.advanceDays(1) }
        assertEquals(6, repo.userProgress().streakCount)
        assertNull(db.badgeDao().get(Badges.SETTIMANA_ITALIANA))
        repo.completeLesson(1, 0, 1.0) // day 7
        assertEquals(7, repo.userProgress().streakCount)
        assertNotNull(db.badgeDao().get(Badges.SETTIMANA_ITALIANA))
    }

    // --- feature gates ---

    @Test fun `feature flags trip at their triggers`() = runBlocking {
        var shown = repo.recordCheckpointAttempt(1, true, 0.9)
        assertEquals(listOf(FeatureUnlocks.LEADERBOARDS), shown)
        assertTrue(repo.isFeatureUnlocked(FeatureUnlocks.LEADERBOARDS))
        assertFalse(repo.isFeatureUnlocked(FeatureUnlocks.BOSS_BATTLES))
        // re-passing the same checkpoint trips nothing new
        assertEquals(emptyList<String>(), repo.recordCheckpointAttempt(1, true, 0.9))

        for (u in 2..4) repo.recordCheckpointAttempt(u, true, 0.9)
        assertFalse(repo.isFeatureUnlocked(FeatureUnlocks.BOSS_BATTLES))
        shown = repo.recordCheckpointAttempt(5, true, 0.9)
        assertTrue(FeatureUnlocks.BOSS_BATTLES in shown)
        assertTrue(FeatureUnlocks.STORY1 in shown)

        for (u in 6..9) repo.recordCheckpointAttempt(u, true, 0.9)
        assertFalse(repo.isFeatureUnlocked(FeatureUnlocks.OCR_CAMERA))
        shown = repo.recordCheckpointAttempt(10, true, 0.9)
        assertTrue(FeatureUnlocks.OCR_CAMERA in shown)
        assertTrue(FeatureUnlocks.MIXED_PRACTICE in shown)

        // triggers beyond a 10-unit course stay locked
        assertFalse(repo.isFeatureUnlocked(FeatureUnlocks.STORY2))
        assertFalse(repo.isFeatureUnlocked(FeatureUnlocks.STORY3))
        assertFalse(repo.isFeatureUnlocked(FeatureUnlocks.STORY4))
    }

    @Test fun `unlock snackbar shown exactly once`() = runBlocking {
        val shown = repo.recordCheckpointAttempt(1, true, 0.9)
        assertEquals(listOf(FeatureUnlocks.LEADERBOARDS), shown)
        shown.forEach { repo.markUnlockShown(it) }
        assertTrue(featureUnlocks.wasShown(FeatureUnlocks.LEADERBOARDS))
        // later passes never re-announce it
        for (u in 2..3) repo.recordCheckpointAttempt(u, true, 0.9)
        assertTrue(repo.recordCheckpointAttempt(4, true, 0.9).isEmpty())
    }
}
