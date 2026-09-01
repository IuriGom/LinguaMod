package com.linguamod.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.debug.FakeClock
import com.linguamod.app.plugin.PluginLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CourseRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CourseRepository
    private lateinit var clock: FakeClock

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        clock = FakeClock()
        repo = CourseRepository(db, PluginLoader(context, db), clock)
    }

    @After
    fun tearDown() { db.close() }

    @Test fun `streak increments on consecutive days`() = runBlocking {
        repo.completeLesson(1, 0, 1.0)
        assertEquals(1, repo.userProgress().streakCount)
        clock.advanceDays(1)
        repo.completeLesson(1, 1, 1.0)
        assertEquals(2, repo.userProgress().streakCount)
        clock.advanceDays(1)
        repo.completeLesson(1, 2, 1.0)
        assertEquals(3, repo.userProgress().streakCount)
    }

    @Test fun `streak stays when two lessons same day`() = runBlocking {
        repo.completeLesson(1, 0, 1.0)
        repo.completeLesson(1, 1, 1.0)
        assertEquals(1, repo.userProgress().streakCount)
    }

    @Test fun `streak resets silently after a gap`() = runBlocking {
        repo.completeLesson(1, 0, 1.0)
        clock.advanceDays(3)
        repo.completeLesson(1, 1, 1.0)
        assertEquals(1, repo.userProgress().streakCount)
        assertEquals(1, repo.userProgress().longestStreak)
    }

    @Test fun `longest streak tracked`() = runBlocking {
        repeat(5) { repo.completeLesson(1, it % 4, 1.0); clock.advanceDays(1) }
        assertEquals(5, repo.userProgress().longestStreak)
    }

    @Test fun `xp accumulates`() = runBlocking {
        repo.addXp(5); repo.addXp(10); repo.addXp(50)
        assertEquals(65, repo.userProgress().totalXp)
    }

    @Test fun `unlock gates are strictly linear`() = runBlocking {
        assertTrue(repo.isUnitUnlocked(1))
        assertFalse(repo.isUnitUnlocked(2))
        assertTrue(repo.isLessonUnlocked(1, 0))
        assertFalse(repo.isLessonUnlocked(1, 1))
        repo.completeLesson(1, 0, 1.0)
        assertTrue(repo.isLessonUnlocked(1, 1))
        assertFalse(repo.isCheckpointUnlocked(1))
        repo.completeLesson(1, 1, 1.0)
        repo.completeLesson(1, 2, 1.0)
        repo.completeLesson(1, 3, 1.0)
        assertTrue(repo.isCheckpointUnlocked(1))
        assertFalse(repo.isUnitUnlocked(2))
        repo.recordCheckpointAttempt(1, true, 0.9)
        assertTrue(repo.isUnitUnlocked(2))
        assertFalse(repo.isUnitUnlocked(3))
    }

    @Test fun `failed checkpoint does not unlock`() = runBlocking {
        repo.completeLesson(1, 0, 1.0); repo.completeLesson(1, 1, 1.0)
        repo.completeLesson(1, 2, 1.0); repo.completeLesson(1, 3, 1.0)
        repo.recordCheckpointAttempt(1, false, 0.5)
        assertFalse(repo.isUnitUnlocked(2))
        assertEquals(1, db.progressDao().getLessonProgress(1, 4)?.attempts)
    }

    @Test fun `daily xp recorded by clock date`() = runBlocking {
        repo.addXp(20)
        clock.advanceDays(1)
        repo.addXp(30)
        val days = db.dailyXpDao().lastDays(14)
        assertEquals(2, days.size)
        assertEquals(50, days.sumOf { it.xp })
    }
}
