package com.prism.music

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import com.prism.music.data.SavedWhilePlaying
import com.prism.music.data.model.Song
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/** #159: what counts as saved whole, against a real song cache's spans. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedWhilePlayingTest {
    private lateinit var cache: SimpleCache
    private val song = ByteArray(50_000) { (it % 251).toByte() }

    /** Leaving the page mid-scan isn't "nothing saved": the page stays as it was until it's back, and scans again. */
    @Test fun aScanCutShortByLeavingSaysNothing() = kotlinx.coroutines.runBlocking {
        var shown: List<Song>? = null
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            shown = SavedWhilePlaying.orNone { kotlinx.coroutines.awaitCancellation() }
        }
        job.cancel()
        job.join()
        assertEquals("still loading", null, shown)
    }

    @Test fun aScanThatFailsIsAnEmptyList() = kotlinx.coroutines.runBlocking {
        assertEquals(emptyList<Song>(), SavedWhilePlaying.orNone { error("no database") })
    }

    @Before fun open() {
        val app = RuntimeEnvironment.getApplication()
        cache = SimpleCache(java.io.File(app.cacheDir, "cache-${System.nanoTime()}"), NoOpCacheEvictor(), StandaloneDatabaseProvider(app))
    }

    @After fun close() = cache.release()

    /** Saves [length] bytes from [from]; with no [length], as a song played to its end is, which tells the cache its length. */
    private fun save(key: String, from: Long = 0, length: Long? = null) {
        val spec = DataSpec.Builder().setUri("https://example.invalid/$key").setKey(key).setPosition(from)
            .apply { if (length != null) setLength(length) }.build()
        CacheWriter(CacheDataSource(cache, ByteArrayDataSource(song)), spec, null, null).cache()
    }

    @Test fun aSongPlayedThroughIsWhole() {
        save("whole")
        assertTrue(SavedWhilePlaying.isWhole(cache, "whole"))
        assertEquals(setOf("whole"), SavedWhilePlaying.wholeKeys(cache))
    }

    @Test fun aSongWithOnlyItsFirstPartIsNot() {
        save("part", 0, 10_000)
        assertFalse(SavedWhilePlaying.isWhole(cache, "part"))
        assertEquals(emptySet<String>(), SavedWhilePlaying.wholeKeys(cache))
    }

    @Test fun aSongThatLostASpanIsNot() {
        save("holed", 0, 25_000)
        save("holed", 25_000, 25_000)
        AppContainer.markWhole(cache, "holed", song.size.toLong())
        assertTrue("whole first", SavedWhilePlaying.isWhole(cache, "holed"))
        // Prism's evictor takes spans one at a time, so a whole song can lose part of itself and keep its known length.
        cache.removeSpan(cache.getCachedSpans("holed").first())
        assertTrue("the cache still knows the length", androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata("holed")) > 0)
        assertFalse(SavedWhilePlaying.isWhole(cache, "holed"))
    }

    @Test fun allTheBytesWithNoKnownLengthAreNotTakenOnTrust() {
        save("unsure", 0, song.size.toLong())
        assertFalse(SavedWhilePlaying.isWhole(cache, "unsure"))
        AppContainer.markWhole(cache, "unsure", song.size.toLong())
        assertTrue("Load ahead records it", SavedWhilePlaying.isWhole(cache, "unsure"))
    }

    @Test fun aSongWithAVersionPickedCountsByThatVersionsBytes() {
        val picks = mapOf("a" to "aVersion", "b" to "bVersion")
        // "a" plays as its version, saved whole: it counts, though its own bytes aren't here.
        // "b" plays as its version, not saved: its own whole bytes wouldn't play, so it doesn't.
        assertEquals(setOf("a", "aVersion"), SavedWhilePlaying.songIds(setOf("aVersion", "b"), picks))
        assertEquals(setOf("c"), SavedWhilePlaying.songIds(setOf("c"), emptyMap()))
    }

    @Test fun filesOnThePhoneAndFeedsAreNotListed() {
        val ids = SavedWhilePlaying.songIds(setOf("song", Song.LOCAL_PREFIX + "1", Song.RSS_PREFIX + "2"), emptyMap())
        assertEquals(setOf("song"), ids)
    }
}
