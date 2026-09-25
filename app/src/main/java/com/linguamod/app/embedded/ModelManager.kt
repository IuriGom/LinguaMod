package com.linguamod.app.embedded

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** State of one downloadable model pack. */
sealed interface PackState {
    data object NotInstalled : PackState
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : PackState
    data object Ready : PackState
    data class Failed(val reason: String) : PackState
}

/**
 * Stage 9: on-device model packs for the embedded open-source engines
 * (Piper TTS, Whisper STT). Models are downloaded once from static assets on
 * the project's own GitHub release — no accounts, no analytics, no third
 * party; SHA-256 verified before install. After install every feature works
 * fully offline forever.
 *
 * Each pack ships as a single .zip whose entries land under
 * `filesDir/models/<packId>/`.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Pack(
        val id: String,
        val fileName: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    /** Download state per pack id. */
    private val _states = MutableStateFlow<Map<String, PackState>>(emptyMap())
    val states: StateFlow<Map<String, PackState>> = _states

    private val mutex = Mutex()
    private val jobs = mutableMapOf<String, Boolean>()

    fun isReady(packId: String): Boolean =
        File(packDir(packId), READY_MARKER).exists()

    fun packDir(packId: String): File = File(context.filesDir, "models/$packId")

    fun stateOf(packId: String): PackState =
        if (isReady(packId)) PackState.Ready
        else _states.value[packId] ?: PackState.NotInstalled

    /**
     * Downloads + verifies + installs [pack] (idempotent). Safe to call from
     * multiple places: concurrent callers join the same install.
     * Returns true when the pack is ready afterwards.
     */
    suspend fun ensureInstalled(pack: Pack): Boolean {
        if (isReady(pack.id)) {
            setState(pack.id, PackState.Ready)
            return true
        }
        return mutex.withLock {
            if (isReady(pack.id)) {
                setState(pack.id, PackState.Ready)
                return@withLock true
            }
            // mark in-flight inside the lock so concurrent callers queue here
            if (jobs[pack.id] == true) {
                // another caller installed it while we waited for the lock
                return@withLock isReady(pack.id)
            }
            jobs[pack.id] = true
            try {
                install(pack)
            } finally {
                jobs.remove(pack.id)
            }
        }
    }

    private suspend fun install(pack: Pack): Boolean {
        val part = File(context.cacheDir, "${pack.fileName}.part")
        val zip = File(context.cacheDir, pack.fileName)
        return try {
            download(pack, part)
            part.renameTo(zip)
            val digest = sha256(zip)
            if (!digest.equals(pack.sha256, ignoreCase = true)) {
                setState(pack.id, PackState.Failed("checksum mismatch"))
                Log.e(TAG, "${pack.id}: sha256 $digest != ${pack.sha256}")
                zip.delete()
                return false
            }
            val dir = packDir(pack.id)
            dir.deleteRecursively()
            dir.mkdirs()
            unzip(zip, dir)
            zip.delete()
            File(dir, READY_MARKER).createNewFile()
            setState(pack.id, PackState.Ready)
            Log.i(TAG, "${pack.id} installed (${pack.sizeBytes / 1e6} MB)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "${pack.id} install failed", e)
            part.delete()
            setState(pack.id, PackState.Failed(e.message ?: "download failed"))
            false
        }
    }

    private suspend fun download(pack: Pack, dest: File) = withContext(Dispatchers.IO) {
        val url = "$RELEASE_BASE/${pack.fileName}"
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                fetch(url, pack, dest)
                return@withContext
            } catch (e: Exception) {
                lastError = e
                dest.delete()
                Log.w(TAG, "${pack.id} download attempt ${attempt + 1} failed", e)
                kotlinx.coroutines.delay(2_000L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("download failed")
    }

    private fun fetch(url: String, pack: Pack, dest: File) {
        dest.parentFile?.mkdirs() // cacheDir may not exist yet on a fresh install
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: pack.sizeBytes
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            setState(pack.id, PackState.Downloading(done, total))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun unzip(zip: File, destDir: File) {
        ZipInputStream(FileInputStream(zip)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(destDir, entry.name)
                // zip-slip guard
                if (!out.canonicalPath.startsWith(destDir.canonicalPath)) {
                    error("unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun setState(packId: String, state: PackState) {
        _states.value = _states.value + (packId to state)
    }

    companion object {
        private const val TAG = "ModelManager"
        private const val READY_MARKER = ".ready"

        /** Static release assets on the project's own repo — no third-party
         *  host, no tracking; the zip sha256 below pins the exact bytes. */
        private const val RELEASE_BASE =
            "https://github.com/IuriGom/LinguaMod/releases/download/v1.1-models"

        // Checksums are filled in from the exact zips uploaded to the release
        // (see tools/models/package_models.sh).
        val TTS_PACK = Pack(
            id = "tts-piper-it",
            fileName = "tts-piper-it.zip",
            sha256 = TTS_SHA256,
            sizeBytes = TTS_SIZE,
        )
        val STT_PACK = Pack(
            id = "stt-whisper-it",
            fileName = "stt-whisper-it.zip",
            sha256 = STT_SHA256,
            sizeBytes = STT_SIZE,
        )

        // Checksums/sizes of the exact zips on the v1.1-models release
        // (produced by tools/models/package_models.sh)
        private const val TTS_SHA256 = "aad10e0f02a08a7df7945772dbba5c1f896c9c4344b81247b9e9f65244de6b39"
        private const val STT_SHA256 = "09c15aa6d9865282df4ec0cea087bc7f1bf2648267c7ce082f1ae919147203d6"
        private const val TTS_SIZE = 22528818L
        private const val STT_SIZE = 60683293L
    }
}
