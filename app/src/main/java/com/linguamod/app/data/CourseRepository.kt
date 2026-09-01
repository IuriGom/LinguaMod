package com.linguamod.app.data

import com.linguamod.app.core.Clock
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.ExerciseResultEntity
import com.linguamod.app.data.db.LessonProgressEntity
import com.linguamod.app.data.db.UserProgressEntity
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.PluginLoader
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Holds the currently loaded course (primary valid plugin) and progress logic. */
@Singleton
class CourseRepository @Inject constructor(
    private val db: AppDatabase,
    private val pluginLoader: PluginLoader,
    private val clock: Clock,
) {
    private val _plugin = MutableStateFlow<LinguaPluginDto?>(null)
    val plugin: StateFlow<LinguaPluginDto?> = _plugin

    /** Progress flow so UI recomputes unlocks when lessons complete. */
    val lessonProgressFlow = db.progressDao().observeAllLessonProgress()

    suspend fun initialize() {
        pluginLoader.installBundledDemoIfNeeded()
        reload()
    }

    suspend fun reload() {
        _plugin.value = pluginLoader.rescan().firstOrNull()
        ensureUserProgress()
    }

    private suspend fun ensureUserProgress() {
        if (db.progressDao().getUserProgress() == null) {
            db.progressDao().upsertUserProgress(
                UserProgressEntity(lastHeartRefillMillis = clock.nowMillis())
            )
        }
    }

    suspend fun userProgress(): UserProgressEntity =
        db.progressDao().getUserProgress() ?: UserProgressEntity().also {
            db.progressDao().upsertUserProgress(it)
        }

    // --- unlock state (Stage 1: linear unit/lesson only) ---

    suspend fun isUnitUnlocked(unitNumber: Int): Boolean {
        if (unitNumber <= 1) return true
        return db.progressDao().getLessonProgress(unitNumber - 1, CHECKPOINT_INDEX)?.completed == true
    }

    suspend fun isLessonUnlocked(unitNumber: Int, lessonIndex: Int): Boolean {
        if (!isUnitUnlocked(unitNumber)) return false
        if (lessonIndex == 0) return true
        return db.progressDao().getLessonProgress(unitNumber, lessonIndex - 1)?.completed == true
    }

    suspend fun isCheckpointUnlocked(unitNumber: Int): Boolean {
        if (!isUnitUnlocked(unitNumber)) return false
        return (0..3).all { db.progressDao().getLessonProgress(unitNumber, it)?.completed == true }
    }

    suspend fun isLessonCompleted(unitNumber: Int, lessonIndex: Int): Boolean =
        db.progressDao().getLessonProgress(unitNumber, lessonIndex)?.completed == true

    /** Opening a lesson counts as "started" (dictionary gating, spec Stage 1 §7). */
    suspend fun markLessonStarted(unitNumber: Int, lessonIndex: Int) {
        if (db.progressDao().getLessonProgress(unitNumber, lessonIndex) == null) {
            db.progressDao().upsertLessonProgress(
                LessonProgressEntity(
                    unitNumber = unitNumber, lessonIndex = lessonIndex,
                    completed = false, score = 0.0, attempts = 0,
                    lastAccessed = clock.nowMillis(),
                )
            )
        }
    }

    // --- exercise results ---

    suspend fun recordExerciseResult(exerciseId: String, correct: Boolean) {
        val existing = db.exerciseResultDao().get(exerciseId)
        db.exerciseResultDao().upsert(
            ExerciseResultEntity(
                exerciseId = exerciseId,
                correct = correct,
                timestamp = clock.nowMillis(),
                attempts = (existing?.attempts ?: 0) + 1,
            )
        )
    }

    // --- lesson/checkpoint completion ---

    suspend fun completeLesson(unitNumber: Int, lessonIndex: Int, score: Double) {
        val existing = db.progressDao().getLessonProgress(unitNumber, lessonIndex)
        db.progressDao().upsertLessonProgress(
            LessonProgressEntity(
                unitNumber = unitNumber,
                lessonIndex = lessonIndex,
                completed = true,
                score = maxOf(score, existing?.score ?: 0.0),
                attempts = (existing?.attempts ?: 0) + 1,
                lastAccessed = clock.nowMillis(),
            )
        )
        touchStreak()
    }

    suspend fun recordCheckpointAttempt(unitNumber: Int, passed: Boolean, score: Double) {
        val existing = db.progressDao().getLessonProgress(unitNumber, CHECKPOINT_INDEX)
        db.progressDao().upsertLessonProgress(
            LessonProgressEntity(
                unitNumber = unitNumber,
                lessonIndex = CHECKPOINT_INDEX,
                completed = passed || existing?.completed == true,
                score = maxOf(score, existing?.score ?: 0.0),
                attempts = (existing?.attempts ?: 0) + 1,
                lastAccessed = clock.nowMillis(),
            )
        )
        if (passed) touchStreak()
    }

    /** Streak: consecutive days with >=1 lesson completed; silent reset after a gap. */
    private suspend fun touchStreak() {
        val p = userProgress()
        val today = clock.today()
        val last = p.lastActiveDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val newStreak = when {
            last == null -> 1
            last == today -> p.streakCount.coerceAtLeast(1)
            ChronoUnit.DAYS.between(last, today) == 1L -> p.streakCount + 1
            else -> 1 // silent reset, never blocks
        }
        db.progressDao().upsertUserProgress(
            p.copy(
                streakCount = newStreak,
                longestStreak = maxOf(p.longestStreak, newStreak),
                lastActiveDate = today.toString(),
            )
        )
    }

    suspend fun addXp(amount: Int) {
        val p = userProgress()
        db.progressDao().upsertUserProgress(p.copy(totalXp = p.totalXp + amount))
        val today = clock.today().toString()
        val day = db.dailyXpDao().get(today)
        db.dailyXpDao().upsert(
            com.linguamod.app.data.db.DailyXpEntity(today, (day?.xp ?: 0) + amount)
        )
    }

    companion object { const val CHECKPOINT_INDEX = 4 }
}
