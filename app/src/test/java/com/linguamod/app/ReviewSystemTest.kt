package com.linguamod.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.ThemeStore
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.debug.FakeClock
import com.linguamod.app.plugin.PluginLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stage 3 §5 spaced repetition (SM-2 lite): every wrong exercise creates/updates
 * a review item keyed by exercise ID; correct answers to previously-wrong items
 * update it. Wrong → due in 10 minutes; correct → interval × 2.5 from a 1-day
 * start, capped at 30 days. All via FakeClock.
 */
@RunWith(RobolectricTestRunner::class)
class ReviewSystemTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CourseRepository
    private lateinit var clock: FakeClock

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        clock = FakeClock()
        repo = CourseRepository(
            db, PluginLoader(context, db), clock,
            FeatureUnlocks(TestStores.prefs()), ThemeStore(TestStores.prefs()),
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test fun `wrong answer creates a review item due in 10 minutes`() = runBlocking {
        repo.recordExerciseResult("ex1", correct = false)
        val item = db.reviewDao().get("ex1")!!
        assertEquals(CourseRepository.REVIEW_WRONG_INTERVAL_MILLIS, item.intervalMillis)
        assertEquals(clock.nowMillis() + CourseRepository.REVIEW_WRONG_INTERVAL_MILLIS, item.dueAtMillis)
        assertEquals(0, item.repetitions)
        // not due right now, due after the 10 minutes pass
        assertTrue(repo.dueReviewItems().isEmpty())
        clock.advanceMinutes(10)
        assertEquals(listOf("ex1"), repo.dueReviewItems().map { it.exerciseId })
    }

    @Test fun `wrong again resets the interval to 10 minutes and repetitions to 0`() = runBlocking {
        repo.recordExerciseResult("ex1", false)
        clock.advanceMinutes(10)
        repo.recordExerciseResult("ex1", true) // grows to 1 day
        clock.advanceDays(1)
        repo.recordExerciseResult("ex1", true) // grows to 2.5 days
        assertEquals(2, db.reviewDao().get("ex1")!!.repetitions)
        clock.advanceMinutes(1)
        repo.recordExerciseResult("ex1", false)
        val item = db.reviewDao().get("ex1")!!
        assertEquals(CourseRepository.REVIEW_WRONG_INTERVAL_MILLIS, item.intervalMillis)
        assertEquals(clock.nowMillis() + CourseRepository.REVIEW_WRONG_INTERVAL_MILLIS, item.dueAtMillis)
        assertEquals(0, item.repetitions)
    }

    @Test fun `correct answer to a never-wrong exercise creates no review item`() = runBlocking {
        repo.recordExerciseResult("ex1", true)
        assertNull(db.reviewDao().get("ex1"))
    }

    @Test fun `correct after wrong starts the interval at 1 day`() = runBlocking {
        repo.recordExerciseResult("ex1", false)
        clock.advanceMinutes(10)
        repo.recordExerciseResult("ex1", true)
        val item = db.reviewDao().get("ex1")!!
        assertEquals(CourseRepository.REVIEW_START_INTERVAL_MILLIS, item.intervalMillis)
        assertEquals(clock.nowMillis() + CourseRepository.REVIEW_START_INTERVAL_MILLIS, item.dueAtMillis)
        assertEquals(1, item.repetitions)
    }

    @Test fun `successive correct answers multiply by 2_5 and cap at 30 days`() = runBlocking {
        repo.recordExerciseResult("ex1", false)
        clock.advanceMinutes(10)
        suspend fun correctAndAssert(expectedMillis: Long, reps: Int) {
            repo.recordExerciseResult("ex1", true)
            val item = db.reviewDao().get("ex1")!!
            assertEquals(expectedMillis, item.intervalMillis)
            assertEquals(clock.nowMillis() + expectedMillis, item.dueAtMillis)
            assertEquals(reps, item.repetitions)
            clock.advanceMinutes(expectedMillis / 60_000) // arrive exactly at due time
        }
        correctAndAssert(CourseRepository.REVIEW_START_INTERVAL_MILLIS, 1) // 1 day
        correctAndAssert((1 * 2.5 * 24 * 60 * 60 * 1000).toLong(), 2) // 2.5 days
        correctAndAssert((2.5 * 2.5 * 24 * 60 * 60 * 1000).toLong(), 3) // 6.25 days
        correctAndAssert((6.25 * 2.5 * 24 * 60 * 60 * 1000).toLong(), 4) // 15.625 days
        // 15.625 × 2.5 = 39.0625 days → capped at 30
        correctAndAssert(CourseRepository.REVIEW_MAX_INTERVAL_MILLIS, 5)
        correctAndAssert(CourseRepository.REVIEW_MAX_INTERVAL_MILLIS, 6) // stays capped
    }

    @Test fun `scheduling constants match the spec`() {
        assertEquals(10 * 60 * 1000L, CourseRepository.REVIEW_WRONG_INTERVAL_MILLIS)
        assertEquals(24 * 60 * 60 * 1000L, CourseRepository.REVIEW_START_INTERVAL_MILLIS)
        assertEquals(30L * 24 * 60 * 60 * 1000, CourseRepository.REVIEW_MAX_INTERVAL_MILLIS)
        assertEquals(2.5, CourseRepository.REVIEW_INTERVAL_FACTOR, 1e-9)
    }

    @Test fun `review session source resolves the original exercise payloads in due order`() = runBlocking {
        repo.initialize()
        val plugin = repo.plugin.value!!
        val e1 = plugin.units[0].lessons!![0].exercises[0]
        val e2 = plugin.units[0].lessons!![0].exercises[1]
        repo.recordExerciseResult(e1.id!!, false)
        clock.advanceMinutes(10)
        repo.recordExerciseResult(e2.id!!, false) // due 10 min later than e1
        clock.advanceMinutes(10)
        val due = repo.dueReviewExercises()
        assertEquals(listOf(e1.id, e2.id), due.map { it.id })
        // payloads are the originals, not copies with stripped fields
        assertEquals(e1.type, due[0].type)
        assertEquals(e2.prompt, due[1].prompt)
        // an item not yet due is excluded
        repo.recordExerciseResult("not_due_yet", false)
        assertEquals(2, repo.dueReviewExercises().size)
    }

    @Test fun `answering correctly in review pushes the item out of the due set`() = runBlocking {
        repo.initialize()
        val e = repo.plugin.value!!.units[0].lessons!![0].exercises[0]
        repo.recordExerciseResult(e.id!!, false)
        clock.advanceMinutes(10)
        assertEquals(1, repo.dueReviewItems().size)
        repo.recordExerciseResult(e.id!!, true) // the review answer
        assertTrue("due in 1 day now — the Home card clears", repo.dueReviewItems().isEmpty())
    }
}
