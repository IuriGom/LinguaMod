package com.linguamod.app.debug

import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.LessonProgressEntity
import com.linguamod.app.data.db.UserProgressEntity

/**
 * BuildConfig.DEBUG-only fast-forward hook (Orchestrator §C.6).
 * Compiled out of release builds; attached via reflection from LinguaModApp.
 * Never exposed in any UI.
 */
object TestHooks {
    @Volatile
    private var db: AppDatabase? = null

    @JvmStatic
    fun attach(database: AppDatabase) {
        db = database
    }

    /** Mark all lessons + checkpoints of units 1..unitsCompleted as passed. */
    suspend fun setProgress(unitsCompleted: Int, xp: Int = 0, hearts: Int = 5, gems: Int = 0) {
        val database = db ?: error("TestHooks not attached")
        for (u in 1..unitsCompleted) {
            for (l in 0..4) {
                database.progressDao().upsertLessonProgress(
                    LessonProgressEntity(
                        unitNumber = u, lessonIndex = l, completed = true,
                        score = 1.0, attempts = 1, lastAccessed = System.currentTimeMillis(),
                    )
                )
            }
        }
        val current = database.progressDao().getUserProgress() ?: UserProgressEntity()
        database.progressDao().upsertUserProgress(
            current.copy(totalXp = xp, hearts = hearts, gems = gems)
        )
    }

    /** Mark only lessons 0-3 of a unit completed (checkpoint still open). */
    suspend fun setUnitLessonsDone(unit: Int) {
        val database = db ?: error("TestHooks not attached")
        for (l in 0..3) {
            database.progressDao().upsertLessonProgress(
                LessonProgressEntity(
                    unitNumber = unit, lessonIndex = l, completed = true,
                    score = 1.0, attempts = 1, lastAccessed = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun setHearts(hearts: Int) {
        val database = db ?: error("TestHooks not attached")
        val current = database.progressDao().getUserProgress() ?: UserProgressEntity()
        database.progressDao().upsertUserProgress(current.copy(hearts = hearts))
    }
}
