package com.linguamod.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.featureUnlocksDataStore: DataStore<Preferences> by preferencesDataStore("feature_unlocks")

/** Marks the DataStore holding feature-gate flags. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FeatureUnlocksPrefs

/**
 * Persisted feature-gate flags (Stage 2B §8). This stage implements ONLY the flag
 * mechanism + a one-time "New feature unlocked" snackbar; unlocked-but-unbuilt
 * features never surface in the UI. Each flag also tracks a `shown_<key>` marker
 * so the snackbar appears exactly once.
 */
@Singleton
class FeatureUnlocks @Inject constructor(
    @FeatureUnlocksPrefs private val dataStore: DataStore<Preferences>,
) {
    private fun flagKey(key: String) = booleanPreferencesKey(key)
    private fun shownKey(key: String) = booleanPreferencesKey("shown_$key")

    suspend fun isUnlocked(key: String): Boolean =
        dataStore.data.first()[flagKey(key)] ?: false

    /** Live set of tripped flags, so gated UI reacts the moment a flag flips. */
    val flagsFlow: kotlinx.coroutines.flow.Flow<Set<String>> =
        dataStore.data.map { prefs -> KEYS.filter { prefs[flagKey(it)] == true }.toSet() }

    /** Sets the flag; returns true only when it was newly tripped. */
    suspend fun unlock(key: String): Boolean {
        if (isUnlocked(key)) return false
        dataStore.edit { it[flagKey(key)] = true }
        return true
    }

    suspend fun wasShown(key: String): Boolean =
        dataStore.data.first()[shownKey(key)] ?: false

    suspend fun markShown(key: String) {
        dataStore.edit { it[shownKey(key)] = true }
    }

    /** Test hook: wipe all flags and shown markers. */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    companion object {
        const val LEADERBOARDS = "leaderboards"
        const val BOSS_BATTLES = "bossBattles"
        const val STORY1 = "story1"
        const val STORY2 = "story2"
        const val STORY3 = "story3"
        const val STORY4 = "story4"
        const val OCR_CAMERA = "ocrCamera"
        const val MIXED_PRACTICE = "mixedPractice"

        val KEYS = listOf(
            LEADERBOARDS, BOSS_BATTLES, STORY1, STORY2, STORY3, STORY4, OCR_CAMERA, MIXED_PRACTICE,
        )

        fun displayName(key: String): String = when (key) {
            LEADERBOARDS -> "Leaderboards"
            BOSS_BATTLES -> "Boss Battles"
            STORY1 -> "Story 1"
            STORY2 -> "Story 2"
            STORY3 -> "Story 3"
            STORY4 -> "Story 4"
            OCR_CAMERA -> "Camera OCR"
            MIXED_PRACTICE -> "Mixed Practice"
            else -> key
        }
    }
}
