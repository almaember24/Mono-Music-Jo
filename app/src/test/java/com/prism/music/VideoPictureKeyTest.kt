package com.prism.music

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * GitHub #411: a music video stopped at ~1:08 with "Skipping atom with length > 2147483647", and again at once on every
 * retry with fresh links, though its streams read fine there (VideoLegLiveTest). The picture was streamed under the key
 * its download is saved by, so the part of a download already in Downloads (one made at another quality, Data saver on
 * mobile data) was read where it reached and the stream went on from there: two files joined into one nothing could read.
 * Songs had the same fixed in 1.7.9 (the `streamed:` mark). A picture that isn't saved whole now streams on its own.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPictureKeyTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "prism-picture-${System.nanoTime()}")
    private val cache = SimpleCache(dir, NoOpCacheEvictor(), StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()))
    private val uri = Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback?id=o-test&itag=137")

    @After fun tearDown() {
        cache.release()
        dir.deleteRecursively()
    }

    /** What Downloads holds: the first [n] bytes of [file], saved under [key]. */
    private fun saved(key: String, file: ByteArray, n: Int) {
        // As a download saves it: read through the cache, which keeps what passes.
        val writer = CacheDataSource(cache, ByteArrayDataSource(file), FileDataSource(), CacheDataSink(cache, C.LENGTH_UNSET.toLong()), 0, null)
        writer.open(DataSpec.Builder().setUri(uri).setKey(key).setPosition(0).setLength(n.toLong()).build())
        val buf = ByteArray(4096)
        while (writer.read(buf, 0, buf.size) != C.RESULT_END_OF_INPUT) Unit
        writer.close()
    }

    /** Reads it the way the player's picture leg does: Downloads first (never written to), then the stream [streamed]. */
    private fun play(key: String, streamed: ByteArray): ByteArray {
        val source = CacheDataSource(cache, ByteArrayDataSource(streamed), FileDataSource(), null, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR, null)
        source.open(DataSpec.Builder().setUri(uri).setKey(key).build())
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        source.close()
        return out.toByteArray()
    }

    @Test fun aPictureSavedInPartIsNotJoinedToTheStream() {
        // Saved at 356p before (itag 135), streamed now at 800p (itag 137).
        val other = ByteArray(30_000) { 1 }
        val stream = ByteArray(50_000) { 2 }
        saved(com.prism.music.AppContainer.pictureKey("52Gg9CqhbP8", whole = true), other, 20_000)
        assertArrayEquals("the picture came from two files", stream, play(com.prism.music.AppContainer.pictureKey("52Gg9CqhbP8", whole = false), stream))
    }

    @Test fun aPictureSavedWholeStillPlaysFromDownloads() {
        val file = ByteArray(30_000) { (it % 7).toByte() }
        saved(com.prism.music.AppContainer.pictureKey("52Gg9CqhbP8", whole = true), file, file.size)
        assertArrayEquals(file, play(com.prism.music.AppContainer.pictureKey("52Gg9CqhbP8", whole = true), ByteArray(file.size) { 9 }))
    }
}
