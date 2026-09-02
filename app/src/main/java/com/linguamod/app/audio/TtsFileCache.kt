package com.linguamod.app.audio

import com.linguamod.app.plugin.AnswerMatcher
import java.io.File
import java.security.MessageDigest

/**
 * File cache for synthesized TTS audio (Stage 3 §1): `dir/<sha1>.wav` keyed by
 * §9-normalized text, capped at [cap] files, least-recently-used evicted first.
 * Pure JVM (unit-testable); the caller writes the file, then [commit]s it.
 */
class TtsFileCache(private val dir: File, private val cap: Int = DEFAULT_CAP) {

    /** key -> file, access-ordered (LinkedHashMap accessOrder = LRU). */
    private val index = object : LinkedHashMap<String, File>(16, 0.75f, true) {}

    init {
        dir.mkdirs()
        // Seed from disk, oldest first so recency order survives process restarts.
        dir.listFiles { f -> f.extension == "wav" }
            ?.sortedBy { it.lastModified() }
            ?.forEach { index[it.nameWithoutExtension] = it }
        evictIfNeeded()
    }

    fun keyFor(text: String): String = sha1(AnswerMatcher.normalize(text))

    /** Cache hit: returns the file and marks it most-recently-used. */
    @Synchronized
    fun get(text: String): File? {
        val key = keyFor(text)
        val f = index[key] ?: return null
        if (!f.exists()) {
            index.remove(key)
            return null
        }
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    /** Target path for a new synthesis (not yet in the index — call [commit]). */
    fun fileFor(text: String): File = File(dir, "${keyFor(text)}.wav")

    /** Registers a freshly synthesized file, evicting LRU entries beyond [cap]. */
    @Synchronized
    fun commit(text: String): File {
        val key = keyFor(text)
        val f = File(dir, "$key.wav")
        index[key] = f
        evictIfNeeded()
        return f
    }

    @Synchronized
    fun size(): Int = index.size

    private fun evictIfNeeded() {
        while (index.size > cap) {
            val eldest = index.entries.first()
            index.remove(eldest.key)
            eldest.value.delete()
        }
    }

    companion object {
        const val DEFAULT_CAP = 500

        private fun sha1(s: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
