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

    /**
     * First launch / bundled-plugin upgrade (Stage 4B):
     * - fresh DB and no plugin installed → copy the bundled plugin;
     * - the bundled asset is NEWER than the installed copy → overwrite it
     *   (progress rows key to unit numbers, never to plugin versions, so an
     *   upgrade is safe; dictionary lookup counts survive via
     *   [populateDictionary]);
     * - same or older bundled version, an existing install whose plugins were
     *   removed by hand, or a replaced bundled file → leave things alone.
     */
    suspend fun installBundledDemoIfNeeded() = withContext(Dispatchers.IO) {
        // defensive: the external plugins dir can be removed underneath us
        // (user cleanup, OEM file managers, test harnesses) between the
        // exists() check and the read — never crash the startup path over it
        try {
            pluginDir.mkdirs()
            val installed = File(pluginDir, BUNDLED_FILE_NAME)
            val anyPlugin = pluginDir.listFiles()?.any { it.extension == "lingua" } == true
            if (!anyPlugin) {
                if (db.progressDao().getUserProgress() != null) return@withContext // not a fresh install
                copyBundled(installed)
                return@withContext
            }
            if (!installed.exists()) return@withContext // user replaced the bundled plugin
            val bundledVersion = metaVersionOf(readBundledText()) ?: return@withContext
            val installedVersion = metaVersionOf(installed.readText()) ?: 0
            if (bundledVersion > installedVersion) {
                Log.i(TAG, "upgrading bundled plugin v$installedVersion → v$bundledVersion")
                copyBundled(installed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "bundled plugin install/upgrade check failed", e)
        }
    }

    private fun copyBundled(target: File) {
        try {
            context.assets.open("plugins/$BUNDLED_FILE_NAME").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to install bundled plugin", e)
        }
    }

    private fun readBundledText(): String? = try {
        context.assets.open("plugins/$BUNDLED_FILE_NAME").bufferedReader().readText()
    } catch (e: Exception) {
        Log.e(TAG, "failed to read bundled plugin", e); null
    }

    /** Lenient meta.version read; null when unreadable (never throws). */
    private fun metaVersionOf(text: String?): Int? {
        text ?: return null
        return runCatching {
            json.decodeFromString(LinguaPluginDto.serializer(), text).meta?.version
        }.getOrNull()
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
        // Preserve OCR lookup counts across rescans and plugin upgrades (§1).
        val lookupCounts = db.dictionaryDao().getByPlugin(pluginId)
            .associate { it.id to it.lookupCount }
        db.dictionaryDao().deleteByPlugin(pluginId)
        val exJson = Json { }
        db.dictionaryDao().upsertAll(
            p.dictionary.map {
                val id = "${pluginId}:${it.id}"
                DictionaryEntryEntity(
                    id = id,
                    pluginId = pluginId,
                    word = it.word!!,
                    article = it.article,
                    translation = it.translation!!,
                    partOfSpeech = it.partOfSpeech!!,
                    gender = it.gender,
                    examplesJson = exJson.encodeToString(it.examples),
                    introducedInUnit = it.introducedInUnit!!,
                    lookupCount = lookupCounts[id] ?: 0,
                )
            }
        )
    }

    companion object {
        private const val TAG = "PluginLoader"
        private const val BUNDLED_FILE_NAME = "it.lingua"
    }
}
