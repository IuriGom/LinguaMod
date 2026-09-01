package com.linguamod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_progress")
data class UserProgressEntity(
    @PrimaryKey val id: Int = 1,
    val currentUnit: Int = 1,
    val currentLesson: Int = 1,
    val totalXp: Int = 0,
    val streakCount: Int = 0,
    val lastActiveDate: String? = null, // ISO-8601, from Clock
    val hearts: Int = 5,
    val lastHeartRefillMillis: Long = 0L,
    val gems: Int = 0,
    val longestStreak: Int = 0,
)

@Entity(tableName = "lesson_progress", primaryKeys = ["unitNumber", "lessonIndex"])
data class LessonProgressEntity(
    val unitNumber: Int,
    val lessonIndex: Int, // 0-3 lessons, 4 = checkpoint
    val completed: Boolean = false,
    val score: Double = 0.0,
    val attempts: Int = 0,
    val lastAccessed: Long = 0L,
)

@Entity(tableName = "dictionary_entries")
data class DictionaryEntryEntity(
    @PrimaryKey val id: String,
    val pluginId: String,
    val word: String,
    val article: String?,
    val translation: String,
    val partOfSpeech: String,
    val gender: String?,
    val examplesJson: String,
    val introducedInUnit: Int,
    val lookupCount: Int = 0,
)

@Entity(tableName = "exercise_results")
data class ExerciseResultEntity(
    @PrimaryKey val exerciseId: String,
    val correct: Boolean,
    val timestamp: Long,
    val attempts: Int,
)

@Entity(tableName = "plugin_meta")
data class PluginMetaEntity(
    @PrimaryKey val fileName: String,
    val pluginId: String,
    val languageName: String,
    val version: Int,
    val unitCount: Int,
    val valid: Boolean,
    val errors: String, // joined validation errors, empty when valid
)

@Entity(tableName = "review_items")
data class ReviewItemEntity(
    @PrimaryKey val exerciseId: String,
    val intervalMillis: Long,
    val dueAtMillis: Long,
    val repetitions: Int,
)

@Entity(tableName = "badges")
data class BadgeEntity(
    @PrimaryKey val badgeId: String,
    val unlockedAt: Long,
)

@Entity(tableName = "boss_results")
data class BossResultEntity(
    @PrimaryKey val bossId: String, // e.g. "boss_5"
    val won: Boolean,
    val playedAt: Long,
)

@Entity(tableName = "daily_xp")
data class DailyXpEntity(
    @PrimaryKey val date: String, // ISO-8601
    val xp: Int,
)
