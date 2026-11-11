package com.prism.music

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.SongVersions
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.7.9's check, "Woke Up Older": a song played as another version, then back as its own, stopped playing. Both
 * recordings were cached under the song's id, cleared at each pick while the player was still saving the one it had
 * been playing, so part of the other recording stayed under the song and it couldn't be read. Each recording now
 * keeps its own cached part: picking never touches the song's, and the version's goes under the version's own id.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SongVersionCacheTest {
    @Test fun eachRecordingKeepsItsOwnCachedPart() {
        Fixture.ready()
        val c = Fixture.container
        val song = Fixture.songs(1).first()
        val own = ByteArray(64 * 1024) { (it % 251).toByte() }
        // The song's own recording, saved as it played.
        CacheWriter(
            CacheDataSource(c.playerCache, ByteArrayDataSource(own)),
            DataSpec.Builder().setUri("prism://audio/${song.id}").setKey(song.id).build(), null, null,
        ).cache()
        assertTrue(c.playerCache.isCached(song.id, 0, own.size.toLong()))

        c.pickVersion(song, SongVersions.Pick("tst_other_1", "Test Artist · 3:01"))
        assertEquals("the version is saved under its own id", "tst_other_1", c.songCacheKey(song.id))
        // pickVersion's clean-up runs on its own threads.
        Thread.sleep(1_500)
        assertTrue("the song's own part is still there", c.playerCache.isCached(song.id, 0, own.size.toLong()))

        c.pickVersion(song, null)
        assertEquals(song.id, c.songCacheKey(song.id))
        Thread.sleep(1_500)
        assertTrue("back on its own recording, its part plays from the phone", c.playerCache.isCached(song.id, 0, own.size.toLong()))
    }

    /**
     * The phone's copy of the song after that: its cached part looked whole, so every tap read it from the phone, and
     * the reader threw ArrayIndexOutOfBounds ("Unexpected exception loading stream"), which nothing cleared. Now that
     * part goes, as an unreadable one does, and the song is asked for afresh.
     */
    @Test fun aCachedPartTheReaderTripsOverGoes() {
        Fixture.ready()
        Fixture.launch()
        val c = Fixture.container
        val song = Fixture.songs(1).first()
        // A whole song in the cache (a WAV the player reads), so it plays from there without the network.
        val wav = wav(60)
        CacheWriter(
            CacheDataSource(c.playerCache, ByteArrayDataSource(wav)),
            DataSpec.Builder().setUri("prism://audio/${song.id}").setKey(song.id).build(), null, null,
        ).cache()
        val player = c.player
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player.playQueue(listOf(song), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.OTHER, "Cache test"))
        }
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && (com.prism.music.playback.PlaybackServices.failForTest == null || player.currentSong.value?.id != song.id)) Thread.sleep(200)

        val tripped = ArrayIndexOutOfBoundsException("src.length=65536 srcPos=-401403408 dst.length=65536 dstPos=0 length=401403408")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            com.prism.music.playback.PlaybackServices.failForTest!!.invoke(
                androidx.media3.common.PlaybackException(
                    "Source error", androidx.media3.exoplayer.upstream.Loader.UnexpectedLoaderException(tripped),
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                ),
            )
        }
        val gone = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < gone && c.playerCache.getCachedBytes(song.id, 0, Long.MAX_VALUE) > 0) Thread.sleep(200)
        assertEquals("its cached part went", 0L, c.playerCache.getCachedBytes(song.id, 0, Long.MAX_VALUE))
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { player.stop() }
    }

    /**
     * A download still coming down isn't read mid-song: it may be another version's (picked, then back) or another
     * format's, and the song cache's part then followed it as one file. Here the download's start is noise and the
     * song cache holds the whole song: it plays from the song cache.
     */
    @Test fun aDownloadStillComingDownIsntRead() {
        Fixture.ready()
        Fixture.launch()
        val c = Fixture.container
        val song = Fixture.songs(1).first()
        val wav = wav(60)
        CacheWriter(
            CacheDataSource(c.playerCache, ByteArrayDataSource(wav)),
            DataSpec.Builder().setUri("prism://audio/${song.id}").setKey(song.id).build(), null, null,
        ).cache()
        // Its first 64 KB "downloaded" from something else, the rest not yet.
        val noise = ByteArray(64 * 1024) { ((it * 7919) % 256).toByte() }
        CacheWriter(
            CacheDataSource(c.downloadCache, ByteArrayDataSource(noise)),
            DataSpec.Builder().setUri("prism://audio/${song.id}").setKey(song.id).setLength(noise.size.toLong()).build(), null, null,
        ).cache()
        val player = c.player
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val watch = kotlinx.coroutines.MainScope().launch { player.errors.collect { errors += it } }
        instrumentation.runOnMainSync {
            player.playQueue(listOf(song), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.OTHER, "Download test"))
        }
        fun pos(): Long { var p = 0L; instrumentation.runOnMainSync { p = player.position }; return p }
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && pos() < 3_000) Thread.sleep(200)
        watch.cancel()
        assertTrue("plays from the song cache (at ${pos()} ms; errors $errors)", pos() >= 3_000 && player.currentSong.value?.id == song.id)
        instrumentation.runOnMainSync { player.stop() }
    }

    /**
     * #328: a version picked for the song playing shows in its place in the session (the notification, the car,
     * widgets) at once, while the queue still holds the song itself; "Use the original" puts it back.
     */
    @Test fun aPickedVersionShowsInTheSession() {
        Fixture.ready()
        Fixture.launch()
        val c = Fixture.container
        val song = Fixture.songs(1).first()
        CacheWriter(
            CacheDataSource(c.playerCache, ByteArrayDataSource(wav(60))),
            DataSpec.Builder().setUri("prism://audio/${song.id}").setKey(song.id).build(), null, null,
        ).cache()
        val player = c.player
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            player.playQueue(listOf(song), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.OTHER, "Version test"))
        }
        fun title(): String? { var t: String? = null; instrumentation.runOnMainSync { t = player.player?.currentMediaItem?.mediaMetadata?.title?.toString() }; return t }
        fun waitFor(want: String) { val end = System.currentTimeMillis() + 10_000; while (System.currentTimeMillis() < end && title() != want) Thread.sleep(200) }
        waitFor(song.title)
        val version = com.prism.music.data.model.Song(
            "tst_other_1", "${song.title} (Studio)", listOf(com.prism.music.data.model.ArtistRef("Test Artist")),
            com.prism.music.data.model.AlbumRef("Test Album", "MPREb_test"), 181,
        )
        try {
            c.pickVersion(song, SongVersions.Pick(version.id, "Test Artist · Test Album · 3:01", version))
            waitFor(version.title)
            assertEquals("the session shows the version", version.title, title())
            assertEquals("the queue still holds the song", song.id, player.currentSong.value?.id)
            assertEquals(version.title, com.prism.music.data.SongVersions.shownOf(song).title)
        } finally {
            c.pickVersion(song, null)
        }
        waitFor(song.title)
        assertEquals("back as itself", song.title, title())
        instrumentation.runOnMainSync { player.stop() }
    }

    /** A WAV of [seconds] of silence the player reads (8 kHz mono). */
    private fun wav(seconds: Int): ByteArray {
        val rate = 8_000
        val samples = rate * seconds
        return java.io.ByteArrayOutputStream().apply {
            fun le32(v: Int) = write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            write("RIFF".toByteArray()); le32(36 + samples * 2); write("WAVEfmt ".toByteArray())
            le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
            write("data".toByteArray()); le32(samples * 2); write(ByteArray(samples * 2))
        }.toByteArray()
    }
}
