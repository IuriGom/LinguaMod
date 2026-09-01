package com.linguamod.app

import android.app.Application
import com.linguamod.app.data.db.AppDatabase
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LinguaModApp : Application() {

    @Inject
    lateinit var db: AppDatabase

    override fun onCreate() {
        super.onCreate()
        // Attach the debug-only fast-forward hook (Orchestrator §C.6).
        // The class does not exist in release builds; reflection keeps main code clean.
        if (BuildConfig.TEST_HOOKS) {
            runCatching {
                Class.forName("com.linguamod.app.debug.TestHooks")
                    .getMethod("attach", AppDatabase::class.java)
                    .invoke(null, db)
            }
        }
    }
}
