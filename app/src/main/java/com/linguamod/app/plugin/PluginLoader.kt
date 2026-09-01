package com.linguamod.app.plugin

import android.content.Context
import android.util.Log
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.DictionaryEntryEntity
import com.linguamod.app.data.db.PluginMetaEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Scans the plugin directory, validates every .lingua file per spec §8,
 * loads valid ones, records per-plugin status in Room. Never crashes,
 * never partially loads an invalid plugin.
 */
@Singleton
class PluginLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val pluginDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "plugins")

    /** First launch only (fresh DB): copy the bundled plugin into the plugin dir. */
    suspend fun installBundledDemoIfNeeded() = withContext(Dispatchers.IO) {
        pluginDir.mkdirs()
        if (pluginDir.listFiles()?.any { it.extension == "lingua" } == true) return@withContext
        if (db.progressDao().getUserProgress() != null) return@withContext // not a fresh install
        try {
            context.assets.open("plugins/it.lingua").use { input ->
                File(pluginDir, "it.lingua").outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to install bundled plugin", e)
        }
    }

    /** Rescan plugin dir. Returns loaded plugins (valid only). */
    suspend fun rescan(): List<LinguaPluginDto> = withContext(Dispatchers.IO) {
        pluginDir.mkdirs()
        db.pluginMetaDao().deleteAll()
        val loaded = mutableListOf<LinguaPluginDto>()
        val files = pluginDir.listFiles { f -> f.extension == "lingua" }?.sortedBy { it.name } ?: emptyList()
        for (file in files) {
            val result = try {
                PluginValidator.validateFile(file.readBytes())
            } catch (e: Exception) {
                Log.e(TAG, "validator threw on ${file.name}", e)
                ValidationResult(null, listOf(ValidationError("R1", "internal error: ${e.message}")))
            }
            if (result.isValid && result.plugin != null) {
                val p = result.plugin
                db.pluginMetaDao().upsert(
                    PluginMetaEntity(
                        fileName = file.name,
                        pluginId = p.meta!!.id!!,
                        languageName = p.meta.languageName ?: "?",
                        version = p.meta.version ?: 1,
                        unitCount = p.units.size,
                        valid = true,
                        errors = "",
                    )
                )
                populateDictionary(p)
                loaded += p
            } else {
                db.pluginMetaDao().upsert(
                    PluginMetaEntity(
                        fileName = file.name,
                        pluginId = file.nameWithoutExtension,
                        languageName = "?",
                        version = 0,
                        unitCount = 0,
                        valid = false,
                        errors = result.errors.joinToString("\n").take(4000),
                    )
                )
                Log.w(TAG, "rejected plugin ${file.name}: ${result.errors.firstOrNull()}")
            }
        }
        loaded
    }

    private suspend fun populateDictionary(p: LinguaPluginDto) {
        val pluginId = p.meta!!.id!!
        db.dictionaryDao().deleteByPlugin(pluginId)
        val exJson = Json { }
        db.dictionaryDao().upsertAll(
            p.dictionary.map {
                DictionaryEntryEntity(
                    id = "${pluginId}:${it.id}",
                    pluginId = pluginId,
                    word = it.word!!,
                    article = it.article,
                    translation = it.translation!!,
                    partOfSpeech = it.partOfSpeech!!,
                    gender = it.gender,
                    examplesJson = exJson.encodeToString(it.examples),
                    introducedInUnit = it.introducedInUnit!!,
                )
            }
        )
    }

    companion object { private const val TAG = "PluginLoader" }
}
