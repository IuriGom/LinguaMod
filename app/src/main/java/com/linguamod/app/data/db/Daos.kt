package com.linguamod.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    @Query("SELECT * FROM user_progress WHERE id = 1")
    fun observeUserProgress(): Flow<UserProgressEntity?>

    @Query("SELECT * FROM user_progress WHERE id = 1")
    suspend fun getUserProgress(): UserProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserProgress(p: UserProgressEntity)

    @Query("SELECT * FROM lesson_progress")
    fun observeAllLessonProgress(): Flow<List<LessonProgressEntity>>

    @Query("SELECT * FROM lesson_progress WHERE unitNumber = :unit AND lessonIndex = :lesson")
    suspend fun getLessonProgress(unit: Int, lesson: Int): LessonProgressEntity?

    @Query("SELECT * FROM lesson_progress")
    suspend fun getAllLessonProgress(): List<LessonProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLessonProgress(p: LessonProgressEntity)

    @Query("SELECT COUNT(*) FROM lesson_progress WHERE completed = 1 AND lessonIndex = 4")
    suspend fun countCompletedCheckpoints(): Int

    @Query("SELECT MAX(unitNumber) FROM lesson_progress WHERE completed = 1 AND lessonIndex = 4")
    suspend fun highestCompletedCheckpoint(): Int?
}

@Dao
interface DictionaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<DictionaryEntryEntity>)

    @Query("DELETE FROM dictionary_entries WHERE pluginId = :pluginId")
    suspend fun deleteByPlugin(pluginId: String)

    @Query("SELECT * FROM dictionary_entries WHERE introducedInUnit <= :maxUnit ORDER BY word")
    fun observeUpToUnit(maxUnit: Int): Flow<List<DictionaryEntryEntity>>

    @Query("SELECT * FROM dictionary_entries WHERE introducedInUnit <= :maxUnit ORDER BY word")
    suspend fun getUpToUnit(maxUnit: Int): List<DictionaryEntryEntity>

    @Query("SELECT * FROM dictionary_entries WHERE LOWER(word) = LOWER(:lemma) LIMIT 1")
    suspend fun findByLemma(lemma: String): DictionaryEntryEntity?

    @Query("UPDATE dictionary_entries SET lookupCount = lookupCount + 1 WHERE id = :id")
    suspend fun incrementLookup(id: String)

    @Query("SELECT * FROM dictionary_entries ORDER BY lookupCount DESC LIMIT :limit")
    suspend fun mostLookedUp(limit: Int): List<DictionaryEntryEntity>

    @Query("SELECT COUNT(*) FROM dictionary_entries")
    suspend fun count(): Int
}

@Dao
interface ExerciseResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: ExerciseResultEntity)

    @Query("SELECT * FROM exercise_results WHERE exerciseId = :id")
    suspend fun get(id: String): ExerciseResultEntity?

    @Query("SELECT * FROM exercise_results")
    suspend fun getAll(): List<ExerciseResultEntity>

    @Query("SELECT exerciseId FROM exercise_results WHERE correct = 0")
    suspend fun wrongExerciseIds(): List<String>
}

@Dao
interface PluginMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: PluginMetaEntity)

    @Query("SELECT * FROM plugin_meta")
    fun observeAll(): Flow<List<PluginMetaEntity>>

    @Query("SELECT * FROM plugin_meta")
    suspend fun getAll(): List<PluginMetaEntity>

    @Query("DELETE FROM plugin_meta WHERE fileName = :fileName")
    suspend fun delete(fileName: String)

    @Query("DELETE FROM plugin_meta")
    suspend fun deleteAll()
}

@Dao
interface ReviewDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ReviewItemEntity)

    @Query("SELECT * FROM review_items WHERE exerciseId = :id")
    suspend fun get(id: String): ReviewItemEntity?

    @Query("SELECT * FROM review_items WHERE dueAtMillis <= :nowMillis ORDER BY dueAtMillis")
    suspend fun dueItems(nowMillis: Long): List<ReviewItemEntity>

    /** Live table: the Home review card recomputes its due count from this + Clock. */
    @Query("SELECT * FROM review_items")
    fun observeAll(): Flow<List<ReviewItemEntity>>

    @Query("SELECT COUNT(*) FROM review_items WHERE dueAtMillis <= :nowMillis")
    fun observeDueCount(nowMillis: Long): Flow<Int>

    @Query("DELETE FROM review_items WHERE exerciseId = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM review_items")
    suspend fun count(): Int
}

@Dao
interface BadgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(b: BadgeEntity)

    @Query("SELECT * FROM badges")
    fun observeAll(): Flow<List<BadgeEntity>>

    @Query("SELECT * FROM badges WHERE badgeId = :id")
    suspend fun get(id: String): BadgeEntity?
}

@Dao
interface BossDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(b: BossResultEntity)

    @Query("SELECT * FROM boss_results")
    fun observeAll(): Flow<List<BossResultEntity>>

    @Query("SELECT * FROM boss_results WHERE bossId = :id")
    suspend fun get(id: String): BossResultEntity?
}

@Dao
interface DailyXpDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: DailyXpEntity)

    @Query("SELECT * FROM daily_xp WHERE date = :date")
    suspend fun get(date: String): DailyXpEntity?

    @Query("SELECT * FROM daily_xp ORDER BY date DESC LIMIT :days")
    suspend fun lastDays(days: Int): List<DailyXpEntity>
}
