package com.linguamod.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Per-test DataStore instances on unique temp files (avoids "multiple active DataStores"). */
object TestStores {
    private var n = 0

    fun prefs(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO),
        produceFile = { File.createTempFile("linguamod_test_${n++}", ".preferences_pb") },
    )
}
