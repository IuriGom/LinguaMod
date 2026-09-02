package com.linguamod.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.ThemeStore
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.DictionaryEntryEntity
import com.linguamod.app.debug.FakeClock
import com.linguamod.app.plugin.PluginLoader
import com.linguamod.app.ui.flashcard.FlashcardSession
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stage 3 §6 flashcards: deck = dictionary entries from STARTED units only
 * (same gating rule as the dictionary), session = 20 cards or deck exhaustion,
 * "Still learning" re-queues within the session.
 */
@RunWith(RobolectricTestRunner::class)
class FlashcardTest {

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

    private fun entry(unit: Int, word: String) = DictionaryEntryEntity(
        id = "w_$word", pluginId = "test", word = word, article = null,
        translation = "t_$word", partOfSpeech = "noun", gender = "m",
        examplesJson = "[]", introducedInUnit = unit,
    )

    @Test fun `deck is empty until a unit is started`() = runBlocking {
        repo.initialize()
        db.dictionaryDao().upsertAll(listOf(entry(1, "ciao"), entry(2, "gatto")))
        assertTrue(repo.flashcardEntries().isEmpty())
        assertEquals(0, repo.highestStartedUnit())
    }

    @Test fun `deck contains only words from started units`() = runBlocking {
        repo.initialize()
        // these sit alongside the bundled plugin's own dictionary entries
        db.dictionaryDao().upsertAll(
            listOf(entry(1, "ciao"), entry(2, "gatto"), entry(3, "cane"), entry(4, "casa")),
        )
        // opening unit 1 lesson 0 and unit 2 lesson 0 marks units 1-2 started
        repo.markLessonStarted(1, 0)
        repo.markLessonStarted(2, 0)
        val deck = repo.flashcardEntries().map { it.word }.toSet()
        assertTrue("ciao" in deck && "gatto" in deck)
        assertFalse("unit 3 not started", "cane" in deck)
        assertFalse("unit 4 not started", "casa" in deck)
        // starting unit 4 also admits unit 3's words (highest-started gating,
        // same as the dictionary)
        repo.markLessonStarted(4, 2)
        val wider = repo.flashcardEntries().map { it.word }.toSet()
        assertTrue("cane" in wider && "casa" in wider)
    }

    @Test fun `session draws at most 20 cards`() {
        val entries = (1..25).map { entry(1, "word$it") }
        val s = FlashcardSession(entries, Random(42))
        assertEquals(20, s.size)
        assertEquals(FlashcardSession.SESSION_SIZE, 20)
        // a smaller deck exhausts at its own size
        assertEquals(3, FlashcardSession(entries.take(3), Random(42)).size)
    }

    @Test fun `still learning re-queues the card within the session`() {
        val entries = (1..3).map { entry(1, "word$it") }
        val s = FlashcardSession(entries, Random(7))
        val first = s.current!!
        s.gradeStillLearning()
        assertEquals(0, s.gradedGotIt)
        assertFalse(s.isExhausted)
        assertTrue("the card left the top", s.current!!.id != first.id || s.size == 1)
        // it comes back after the other cards
        s.gradeGotIt()
        s.gradeGotIt()
        assertEquals(first.id, s.current!!.id)
        s.gradeGotIt()
        assertTrue(s.isExhausted)
        assertEquals(3, s.gradedGotIt)
    }

    @Test fun `still learning on the last remaining card keeps it on top`() {
        val s = FlashcardSession(listOf(entry(1, "solo")), Random(1))
        assertEquals("w_solo", s.current!!.id)
        s.gradeStillLearning()
        assertFalse(s.isExhausted)
        assertEquals("w_solo", s.current!!.id)
        s.gradeGotIt()
        assertTrue(s.isExhausted)
        assertNull(s.current)
    }
}
