package com.linguamod.app.data

import com.linguamod.app.core.Badges
import com.linguamod.app.core.Clock
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.BadgeEntity
import com.linguamod.app.data.db.DailyXpEntity
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
    private val featureUnlocks: FeatureUnlocks,
    private val themeStore: ThemeStore,
) {
    private val _plugin = MutableStateFlow<LinguaPluginDto?>(null)
    val plugin: StateFlow<LinguaPluginDto?> = _plugin

    /** Progress flow so UI recomputes unlocks when lessons complete. */
    val lessonProgressFlow = db.progressDao().observeAllLessonProgress()

    /** Live user progress (XP, streak, hearts, gems) for UI. */
    val userProgressFlow = db.progressDao().observeUserProgress()

    suspend fun initialize() {
        pluginLoader.installBundledDemoIfNeeded()
        reload()
        refreshHearts()
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

    // --- hearts (Stage 2B §5): never block learning ---

    /** Time-based refill: +1 heart per 30 min, capped at [MAX_HEARTS]. Call on launch/read. */
    suspend fun refreshHearts() {
        val p = userProgress()
        if (p.hearts >= MAX_HEARTS) {
            if (p.lastHeartRefillMillis != clock.nowMillis()) {
                db.progressDao().upsertUserProgress(p.copy(lastHeartRefillMillis = clock.nowMillis()))
            }
            return
        }
        val gained = ((clock.nowMillis() - p.lastHeartRefillMillis) / HEART_REFILL_MILLIS).toInt()
        if (gained <= 0) return
        val newHearts = minOf(MAX_HEARTS, p.hearts + gained)
        val newLast = if (newHearts >= MAX_HEARTS) clock.nowMillis()
        else p.lastHeartRefillMillis + gained * HEART_REFILL_MILLIS
        db.progressDao().upsertUserProgress(
            p.copy(hearts = newHearts, lastHeartRefillMillis = newLast)
        )
    }

    /** −1 heart per wrong answer; never below 0 and never blocks a lesson. */
    suspend fun loseHeart() {
        val p = userProgress()
        val wasFull = p.hearts >= MAX_HEARTS
        db.progressDao().upsertUserProgress(
            p.copy(
                hearts = (p.hearts - 1).coerceAtLeast(0),
                // start the refill timer when leaving full hearts
                lastHeartRefillMillis = if (wasFull) clock.nowMillis() else p.lastHeartRefillMillis,
            )
        )
    }

    /** Full refill on lesson completion. */
    private suspend fun refillHeartsFully() {
        val p = userProgress()
        db.progressDao().upsertUserProgress(
            p.copy(hearts = MAX_HEARTS, lastHeartRefillMillis = clock.nowMillis())
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
        refillHeartsFully()
        touchStreak()
    }

    /**
     * Records a checkpoint attempt. On a pass: streak, +[GEMS_PER_CHECKPOINT] gems,
     * badge awards, and feature-gate evaluation. Returns the feature keys whose
     * "New feature unlocked" snackbar has not been shown yet.
     */
    suspend fun recordCheckpointAttempt(unitNumber: Int, passed: Boolean, score: Double): List<String> {
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
        if (!passed) return emptyList()
        touchStreak()
        val p = userProgress()
        db.progressDao().upsertUserProgress(p.copy(gems = p.gems + GEMS_PER_CHECKPOINT))
        when (unitNumber) {
            1 -> awardBadge(Badges.PRIMO_PASSO)
            10 -> awardBadge(Badges.DIECI_UNITA)
        }
        if (score >= 1.0) awardBadge(Badges.PERFEZIONISTA)
        return evaluateFeatureUnlocks()
    }

    /** Feature gates (Stage 2B §8). Tolerates plugins with fewer units than the triggers. */
    private suspend fun evaluateFeatureUnlocks(): List<String> {
        val highest = db.progressDao().highestCompletedCheckpoint() ?: return emptyList()
        val completedUnits = db.progressDao().countCompletedCheckpoints()
        val triggers = listOf(
            FeatureUnlocks.LEADERBOARDS to (highest >= 1),
            FeatureUnlocks.BOSS_BATTLES to (completedUnits >= 5),
            FeatureUnlocks.STORY1 to (highest >= 5),
            FeatureUnlocks.STORY2 to (highest >= 15),
            FeatureUnlocks.STORY3 to (highest >= 28),
            FeatureUnlocks.STORY4 to (highest >= 45),
            FeatureUnlocks.OCR_CAMERA to (highest >= 10),
            FeatureUnlocks.MIXED_PRACTICE to (highest >= 10), // phase 1 complete
        )
        return triggers
            .filter { (key, condition) -> condition && featureUnlocks.unlock(key) }
            .map { it.first }
            .filter { !featureUnlocks.wasShown(it) }
    }

    /** Called by the UI once the unlock snackbar has been displayed. */
    suspend fun markUnlockShown(key: String) = featureUnlocks.markShown(key)

    suspend fun isFeatureUnlocked(key: String): Boolean = featureUnlocks.isUnlocked(key)

    private suspend fun awardBadge(id: String) {
        if (db.badgeDao().get(id) == null) {
            db.badgeDao().upsert(BadgeEntity(id, unlockedAt = clock.nowMillis()))
        }
    }

    // --- gems & themes (Stage 2B §6): cosmetic only, never purchasable with money ---

    /** Buys a theme with gems and applies it. Re-selecting an owned theme is free. */
    suspend fun purchaseTheme(themeId: String): Boolean {
        val spec = ThemeCatalog.ALL.firstOrNull { it.id == themeId } ?: return false
        if (themeStore.isPurchased(themeId)) {
            themeStore.setActive(themeId)
            return true
        }
        val p = userProgress()
        if (p.gems < spec.priceGems) return false
        db.progressDao().upsertUserProgress(p.copy(gems = p.gems - spec.priceGems))
        themeStore.markPurchased(themeId)
        themeStore.setActive(themeId)
        return true
    }

    /** Applies an already-owned theme (or the default). */
    suspend fun selectTheme(themeId: String): Boolean {
        if (themeId == ThemeCatalog.DEFAULT.id) {
            themeStore.setActive(themeId)
            return true
        }
        if (!themeStore.isPurchased(themeId)) return false
        themeStore.setActive(themeId)
        return true
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
        if (newStreak >= STREAK_BADGE_DAYS) awardBadge(Badges.SETTIMANA_ITALIANA)
    }

    suspend fun addXp(amount: Int) {
        val p = userProgress()
        db.progressDao().upsertUserProgress(p.copy(totalXp = p.totalXp + amount))
        val today = clock.today().toString()
        val day = db.dailyXpDao().get(today)
        db.dailyXpDao().upsert(DailyXpEntity(today, (day?.xp ?: 0) + amount))
    }

    companion object {
        const val CHECKPOINT_INDEX = 4
        const val MAX_HEARTS = 5
        const val HEART_REFILL_MILLIS = 30 * 60 * 1000L
        const val GEMS_PER_CHECKPOINT = 10
        const val STREAK_BADGE_DAYS = 7
    }
}
