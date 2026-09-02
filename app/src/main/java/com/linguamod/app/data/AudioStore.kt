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

val Context.audioDataStore: DataStore<Preferences> by preferencesDataStore("audio_prefs")

/** Marks the DataStore holding audio/TTS preferences. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AudioPrefs

/**
 * Audio preferences (Stage 3 §1): persists whether the one-time
 * "Install the Italian voice" dialog has already been shown.
 */
@Singleton
class AudioStore @Inject constructor(
    @AudioPrefs private val dataStore: DataStore<Preferences>,
) {
    private val voicePromptShownKey = booleanPreferencesKey("voice_prompt_shown")

    suspend fun wasVoicePromptShown(): Boolean =
        dataStore.data.first()[voicePromptShownKey] ?: false

    suspend fun markVoicePromptShown() {
        dataStore.edit { it[voicePromptShownKey] = true }
    }

    /** Test hook: reset the one-time flag. */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
