package com.linguamod.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.linguamod.app.core.Clock
import com.linguamod.app.core.SystemClock
import com.linguamod.app.data.FeatureUnlocksPrefs
import com.linguamod.app.data.ThemePrefs
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.featureUnlocksDataStore
import com.linguamod.app.data.themeDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "linguamod.db").build()
}

/** DataStore providers live apart from AppModule: instrumented tests uninstall
 *  AppModule (to swap in an in-memory DB) but keep the real preference stores. */
@Module
@InstallIn(SingletonComponent::class)
object StoreModule {
    @Provides
    @Singleton
    @FeatureUnlocksPrefs
    fun provideFeatureUnlocksStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.featureUnlocksDataStore

    @Provides
    @Singleton
    @ThemePrefs
    fun provideThemeStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.themeDataStore
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}
