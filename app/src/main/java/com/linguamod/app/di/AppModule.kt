package com.linguamod.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.linguamod.app.audio.AndroidSpeechRecognizerGateway
import com.linguamod.app.audio.AndroidTtsGateway
import com.linguamod.app.audio.SpeechRecognizerGateway
import com.linguamod.app.audio.TtsGateway
import com.linguamod.app.core.Clock
import com.linguamod.app.core.SystemClock
import com.linguamod.app.data.AudioPrefs
import com.linguamod.app.data.FeatureUnlocksPrefs
import com.linguamod.app.data.ThemePrefs
import com.linguamod.app.data.audioDataStore
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

    // Audio gateways live here (not StoreModule): instrumented tests uninstall
    // AppModule and swap in scripted fakes (Stage 3 §0 test seams).
    // Stage 9: fallback gateways — system engines primary, embedded
    // open-source engines (Piper TTS / Whisper STT) take over on GMS-free
    // devices once their model packs are installed.
    @Provides
    @Singleton
    fun provideTtsGateway(
        @ApplicationContext context: Context,
        models: com.linguamod.app.embedded.ModelManager,
    ): TtsGateway =
        com.linguamod.app.embedded.FallbackTtsGateway(context, models)

    @Provides
    @Singleton
    fun provideSpeechRecognizerGateway(
        @ApplicationContext context: Context,
        models: com.linguamod.app.embedded.ModelManager,
    ): SpeechRecognizerGateway =
        com.linguamod.app.embedded.FallbackSttGateway(context, models)

    // Stage 4 §1 + Stage 9: OCR gateway — ML Kit when Play Services exists,
    // bundled open-source Tesseract otherwise. Tests swap in the fake.
    @Provides
    @Singleton
    fun provideOcrGateway(@ApplicationContext context: Context): com.linguamod.app.ocr.OcrGateway =
        com.linguamod.app.ocr.FallbackOcrGateway(
            com.linguamod.app.ocr.MlKitOcrGateway(context),
            com.linguamod.app.ocr.TessOcrGateway(context),
        )
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

    @Provides
    @Singleton
    @AudioPrefs
    fun provideAudioStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.audioDataStore
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}
