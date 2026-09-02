package com.linguamod.app

import com.linguamod.app.audio.TtsFileCache
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 3 §1 TTS cache: `cacheDir/tts/<sha1-of-normalized-text>.wav`,
 * cap 500 files, LRU eviction, cache checked before synthesis.
 */
class TtsFileCacheTest {

    private lateinit var dir: File

    @Before
    fun setup() {
        dir = createTempDir("tts_cache_test")
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @Test
    fun `key is the sha1 of the NORMALIZED text`() {
        val cache = TtsFileCache(dir)
        // §9 normalization: case / punctuation / apostrophe variants collapse to one key
        assertEquals(cache.keyFor("Ciao, come stai?"), cache.keyFor("ciao come stai"))
        assertNotEquals(cache.keyFor("ciao"), cache.keyFor("ciao ciao"))
    }

    @Test
    fun `commit then get returns the file - get on missing text returns null`() {
        val cache = TtsFileCache(dir)
        assertNull(cache.get("ciao"))
        val f = cache.commit("ciao")
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(f, cache.get("ciao"))
    }

    @Test
    fun `get forgets entries whose file vanished from disk`() {
        val cache = TtsFileCache(dir)
        val f = cache.commit("ciao")
        f.writeBytes(byteArrayOf(1))
        f.delete()
        assertNull(cache.get("ciao"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `cap is enforced - the LRU (oldest untouched) entry is evicted`() {
        val cache = TtsFileCache(dir, cap = 10)
        val files = (0 until 10).map { i ->
            cache.commit("text $i").also { it.writeBytes(byteArrayOf(i.toByte())) }
        }
        assertEquals(10, cache.size())
        // touch "text 3" so it is no longer the LRU
        assertTrue(cache.get("text 3") != null)
        // one more insert → evicts "text 0" (oldest untouched), keeps "text 3"
        cache.commit("text 10").writeBytes(byteArrayOf(10))
        assertEquals(10, cache.size())
        assertFalse("LRU file must be deleted from disk", files[0].exists())
        assertNull(cache.get("text 0"))
        assertTrue(files[3].exists())
        assertTrue(cache.get("text 10") != null)
    }

    @Test
    fun `many inserts beyond the cap stay at the cap`() {
        val cache = TtsFileCache(dir, cap = 500)
        repeat(600) { i ->
            cache.commit("frase numero $i").writeBytes(byteArrayOf(1))
        }
        assertEquals(500, cache.size())
        assertEquals(500, dir.listFiles()!!.size)
        // the 100 oldest are gone
        assertNull(cache.get("frase numero 0"))
        assertNull(cache.get("frase numero 99"))
        assertTrue(cache.get("frase numero 599") != null)
    }

    @Test
    fun `a new cache instance rebuilds its index from existing files`() {
        val first = TtsFileCache(dir)
        first.commit("ciao").writeBytes(byteArrayOf(1))
        val second = TtsFileCache(dir)
        assertTrue(second.get("ciao") != null)
        assertEquals(1, second.size())
    }
}
