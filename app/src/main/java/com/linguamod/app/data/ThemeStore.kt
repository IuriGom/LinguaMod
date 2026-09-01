package com.linguamod.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore("themes")

/** Marks the DataStore holding theme purchases. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ThemePrefs

/** Cosmetic themes purchasable with gems only (Stage 2B §6) — never with money. */
data class ThemeSpec(
    val id: String,
    val name: String,
    val priceGems: Int,
    val accentArgb: Long?,
    val altDark: Boolean = false,
)

object ThemeCatalog {
    val DEFAULT = ThemeSpec("default", "Default Green", priceGems = 0, accentArgb = null)
    val ALL = listOf(
        ThemeSpec("azure", "Azure", priceGems = 50, accentArgb = 0xFF1565C0),
        ThemeSpec("violet", "Violet", priceGems = 100, accentArgb = 0xFF6A1B9A),
        ThemeSpec("midnight", "Midnight", priceGems = 150, accentArgb = 0xFF90CAF9, altDark = true),
    )

    fun byId(id: String?): ThemeSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/** Persists purchased theme ids and the active theme in DataStore. */
@Singleton
class ThemeStore @Inject constructor(
    @ThemePrefs private val dataStore: DataStore<Preferences>,
) {
    private val purchasedKey = stringSetPreferencesKey("purchased_themes")
    private val activeKey = stringPreferencesKey("active_theme")

    val purchased: Flow<Set<String>> = dataStore.data.map { it[purchasedKey] ?: emptySet() }
    val activeTheme: Flow<String> = dataStore.data.map { it[activeKey] ?: ThemeCatalog.DEFAULT.id }

    suspend fun isPurchased(id: String): Boolean = purchased.first().contains(id)

    suspend fun markPurchased(id: String) {
        dataStore.edit { it[purchasedKey] = (it[purchasedKey] ?: emptySet()) + id }
    }

    suspend fun setActive(id: String) {
        dataStore.edit { it[activeKey] = id }
    }

    /** Test hook: wipe purchases and active theme. */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
