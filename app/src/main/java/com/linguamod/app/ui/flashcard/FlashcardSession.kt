package com.linguamod.app.ui.flashcard

import com.linguamod.app.data.db.DictionaryEntryEntity
import kotlin.random.Random

/**
 * Flashcard session (Stage 3 §6). Pure JVM — no Android imports.
 *
 * The session draws up to [SESSION_SIZE] cards from the deck. Self-grading:
 * "Got it" retires the card; "Still learning" re-queues it within the session.
 * The session ends when every drawn card has been graded "Got it" (deck
 * exhaustion). Flashcards stay independent of the SRS — nothing here touches
 * review items, hearts, XP, or streaks.
 */
class FlashcardSession(
    entries: List<DictionaryEntryEntity>,
    random: Random = Random,
) {
    private val queue = ArrayDeque(entries.shuffled(random).take(SESSION_SIZE))

    /** Cards drawn into this session (at most [SESSION_SIZE]). */
    val size: Int = queue.size

    /** Cards retired with "Got it" so far. */
    var gradedGotIt: Int = 0
        private set

    val current: DictionaryEntryEntity? get() = queue.firstOrNull()
    val isExhausted: Boolean get() = queue.isEmpty()

    fun gradeGotIt() {
        if (queue.isEmpty()) return
        queue.removeFirst()
        gradedGotIt++
    }

    /** "Still learning": the card goes to the back of the session queue. */
    fun gradeStillLearning() {
        if (queue.isEmpty()) return
        queue.addLast(queue.removeFirst())
    }

    companion object {
        const val SESSION_SIZE = 20
    }
}
