package com.prism.music

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import com.prism.music.download.SMART_DOWNLOAD_SIZES
import com.prism.music.download.SmartCacheEvictor
import com.prism.music.download.formatGb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * "Space to use" at 256 and 512 MB (1.8.3, D42): offered first on the slider, and the song cache keeps within them,
 * picks included (the 256 MB floor for songs being played shrinks with a space under 1 GB).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmallSpaceTest {
    private val mb = 1_048_576L

    @Test fun twoSmallSizesComeFirst() {
        assertEquals(listOf("256 MB", "512 MB", "1 GB"), SMART_DOWNLOAD_SIZES.take(3).map(::formatGb))
        assertEquals("the default stays", 5f, com.prism.music.data.prefs.AppSettings().offlineGb)
    }

    @Test fun theRoomKeepsWithinASmallSpace() {
        // 512 MB, picks full (three quarters): the cache has the quarter, not the 256 MB floor.
        assertEquals(128 * mb, SmartCacheEvictor.limit(512 * mb - 384 * mb, 512 * mb))
        assertEquals(64 * mb, SmartCacheEvictor.limit(256 * mb - 192 * mb, 256 * mb))
        // No picks: all of it.
        assertEquals(512 * mb, SmartCacheEvictor.limit(512 * mb, 512 * mb))
        // 1 GB and up: as before (the floor is 256 MB).
        assertEquals(256 * mb, SmartCacheEvictor.limit(100 * mb, 5 * 1024 * mb))
        assertEquals(256 * mb, SmartCacheEvictor.limit(-50 * mb, 1024 * mb))
    }

    @Test fun theCacheTrimsToTheSpace() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "small-space-${System.nanoTime()}")
        // Scaled down 1024 times: a "512 MB" space is 512 KB, songs of 100 KB.
        var total = 512 * 1024L
        var picks = 0L
        val evictor = SmartCacheEvictor({ total - picks }, { false }, { total })
        val cache = SimpleCache(dir, evictor, StandaloneDatabaseProvider(context))
        try {
            fun put(key: String) {
                val len = 100 * 1024L
                val hole = cache.startReadWrite(key, 0, len)
                val f = cache.startFile(key, 0, len)
                f.writeBytes(ByteArray(len.toInt()))
                cache.commitFile(f, len)
                cache.releaseHoleSpan(hole)
            }
            for (i in 0 until 12) put("song$i")
            assertTrue("within 512: ${cache.cacheSpace}", cache.cacheSpace <= total)
            assertTrue("full use of it: ${cache.cacheSpace}", cache.cacheSpace >= total - 100 * 1024)
            assertTrue("the newest stay", cache.keys.contains("song11"))

            picks = total * 3 / 4
            evictor.trim(cache)
            assertTrue("picks full: the cache has the quarter (${cache.cacheSpace})", cache.cacheSpace <= total / 4)

            total = 256 * 1024L; picks = 0
            evictor.trim(cache)
            assertTrue(cache.cacheSpace <= total)
        } finally {
            cache.release()
            dir.deleteRecursively()
        }
    }
}
