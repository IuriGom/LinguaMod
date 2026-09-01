package com.linguamod.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserProgressEntity::class,
        LessonProgressEntity::class,
        DictionaryEntryEntity::class,
        ExerciseResultEntity::class,
        PluginMetaEntity::class,
        ReviewItemEntity::class,
        BadgeEntity::class,
        BossResultEntity::class,
        DailyXpEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
    abstract fun dictionaryDao(): DictionaryDao
    abstract fun exerciseResultDao(): ExerciseResultDao
    abstract fun pluginMetaDao(): PluginMetaDao
    abstract fun reviewDao(): ReviewDao
    abstract fun badgeDao(): BadgeDao
    abstract fun bossDao(): BossDao
    abstract fun dailyXpDao(): DailyXpDao
}
