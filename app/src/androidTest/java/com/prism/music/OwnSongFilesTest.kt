package com.prism.music

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteOrder

/**
 * #314 (1.8.1): "Use your own song files". An AAC (.m4a) file on the phone, matched to a song, plays from the file
 * while the switch is on ("AAC · … kbps, from your file" in Downloads and on the player); off (as in 1.8.0) it isn't
 * even looked at, and turning it off sends the song back to the stream at once. The match is injected
 * ([com.prism.music.download.LosslessSync.lookUpForTest]): tests have no account or network for YouTube Music's search.
 */
@RunWith(AndroidJUnit4::class)
class OwnSongFilesTest {
    private val files = mutableListOf<Uri>()
    private val c get() = Fixture.container
    private val song = Song(id = "tstown000001", title = "Prism own tone", artists = listOf(ArtistRef("Prism Test Band", null)), durationSec = 20)

    @After fun cleanUp() {
        runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { c.player.stop() } }
        c.losslessSync.lookUpForTest = null
        files.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    @Test fun yourFilePlaysInPlaceOfTheStreamOnlyWhileTheSwitchIsOn() {
        assumeTrue("the media store takes files from an app from Android 10", android.os.Build.VERSION.SDK_INT >= 29)
        Fixture.ready()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        // The test makes sound: the media volume stays at 0.
        ui.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        ui.grantRuntimePermission(Fixture.context.packageName, perm)
        // A muxed .m4a carries no artist tag, so the file is named "Artist - Title" the way many untagged files are:
        // a file with no artist never matches.
        files += aacTone("Prism Test Band - ${song.title}", song.durationSec)
        c.losslessSync.lookUpForTest = { t -> if (t.title.endsWith(song.title)) song else null }
        Fixture.launch()

        // Off, as in 1.8.0: the file isn't even looked at.
        runBlocking { c.losslessSync.sync() }
        assertTrue("no lossy file considered", c.localLossless.tracks.none { it.lossy })
        assertNull(c.losslessSync.songs.value[song.id])

        // On: it's matched, shows in Downloads with its quality, and plays from the file.
        c.settings.setOwnLossyFiles(true)
        waitFor(10_000) { c.settings.current.sound.ownLossyFiles }
        runBlocking { c.losslessSync.sync() }
        waitFor(20_000, {
            val store = Fixture.context.contentResolver.query(files[0], arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, "bitrate"), null, null, null)
                ?.use { q -> if (q.moveToFirst()) (0 until q.columnCount).joinToString { q.getString(it) ?: "null" } else "none" }
            "store: $store; own found ${c.localLossless.ownFound.value}; scanned: " +
                c.localLossless.tracks.joinToString { "${it.title}/${it.artist}/${it.mime}/${it.bitrateKbps}k/${it.sampleRate}Hz/${it.durationMs}ms" }
        }) {
            c.losslessSync.songs.value[song.id]?.second?.lossy == true
        }
        val track = c.losslessSync.songs.value.getValue(song.id).second
        assertEquals("AAC", track.format)
        waitFor(10_000) { c.downloadState.value[song.id]?.ownFile != null }
        assertTrue(c.downloadState.value.getValue(song.id).ownFile!!.startsWith("AAC · "))

        play()
        waitFor(20_000) { position() > 1_500 }
        assertTrue("plays from the file: ${c.streamDetails[song.id]?.first}", c.streamDetails[song.id]?.first?.startsWith("Your file · AAC") == true)
        waitFor(10_000) { com.prism.music.ui.player.qualityText(c.nerdStats.value).endsWith("from your file") }

        // Off again: out of Downloads at once, and the song is the stream's again.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { c.player.stop() }
        c.settings.setOwnLossyFiles(false)
        waitFor(20_000) { c.losslessSync.songs.value[song.id] == null && c.downloadState.value[song.id] == null }
        assertNull(c.localLossless.match(song, lossless = false, lossy = false))
        c.streamDetails.remove(song.id)
        play()
        Thread.sleep(4_000)
        assertFalse("not from the file any more", c.streamDetails[song.id]?.first?.startsWith("Your file") == true)
    }

    private fun play() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Own files test"))
    }

    private fun position(): Long {
        var at = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync { at = c.player.position }
        return at
    }

    /**
     * An AAC (.m4a) file of [seconds] of quiet noise in Music/, as the phone's media store lists it under [title]: made
     * with the phone's own AAC encoder at 44.1 kHz, 192 kbps (noise, so the encoder spends every bit, and the file's
     * quality beats the stream's as one of your own files should).
     */
    private fun aacTone(title: String, seconds: Int): Uri {
        val rate = 44_100
        val channels = 2
        val out = java.io.File(Fixture.context.cacheDir, "own-tone.m4a").apply { delete() }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val total = rate.toLong() * seconds
        var fed = 0L
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        val random = java.util.Random(314)
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                    val frames = minOf(buf.capacity() / (2 * channels).toLong(), total - fed, 1024L).toInt()
                    if (frames <= 0) {
                        codec.queueInputBuffer(i, 0, 0, fed * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        repeat(frames * channels) { buf.putShort((random.nextInt(8_000) - 4_000).toShort()) }
                        codec.queueInputBuffer(i, 0, frames * 2 * channels, fed * 1_000_000L / rate, 0)
                        fed += frames
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
            } else if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && track >= 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buf, info)
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release()
        muxer.stop(); muxer.release()

        val resolver = Fixture.context.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$title.m4a")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(out.readBytes()) }
        resolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        out.delete()
        // Listed once the media store has read its length.
        waitFor(20_000) {
            resolver.query(uri, arrayOf(MediaStore.Audio.Media.DURATION), null, null, null)?.use { it.moveToFirst() && it.getLong(0) > 0 } == true
        }
        return uri
    }

    private fun waitFor(timeoutMs: Long, what: () -> String = { "" }, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not ready after $timeoutMs ms ${what()}")
            Thread.sleep(200)
        }
    }
}
