package com.prism.music

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.local.LocalLossless
import com.prism.music.data.local.OwnFiles
import com.prism.music.data.local.PlaySourceRule
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.SoundPrefs
import com.prism.music.download.LosslessSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #314 (1.8.1): your own MP3/AAC/Ogg files in place of the stream. Lossless files come first, then your own file, then
 * the stream ([PlaySourceRule]); a lossy file clearly worse than the stream isn't used; the match on their (often messy)
 * tags is strict; with the switch off nothing of it is considered (1.8.0's behaviour), and turning it off sends the
 * songs back to the stream at once.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class OwnFilesTest {
    private fun lossy(id: Int, title: String, artist: String, sec: Int, mime: String = "audio/mpeg", kbps: Int = 320, rate: Int = 0, name: String = "") =
        LocalLossless.Track(
            Uri.parse("content://media/external/audio/media/$id"), title, artist, sec * 1000L, mime, kbps * 125L * sec,
            name = name, modified = 1_700_000_000L + id, tagKbps = kbps, sampleRate = rate,
        )

    private fun lossless(id: Int, title: String, artist: String, sec: Int) =
        LocalLossless.Track(Uri.parse("content://media/external/audio/media/$id"), title, artist, sec * 1000L, "audio/flac", 30_000_000L)

    private fun song(title: String, artist: String, sec: Int, id: String = "vid${title.hashCode()}") =
        Song(id = id, title = title, artists = listOf(ArtistRef(artist, null)), durationSec = sec)

    private fun store(vararg tracks: LocalLossless.Track) =
        LocalLossless(ApplicationProvider.getApplicationContext()).apply { use(tracks.toList()) }

    // ------------------------------------------------------------ The preference rule

    @Test fun losslessThenYourFileThenTheStream() {
        val flac = lossless(1, "Get Lucky", "Daft Punk", 248)
        val mp3 = lossy(2, "Get Lucky", "Daft Punk", 248)
        val poor = lossy(3, "Get Lucky", "Daft Punk", 248, kbps = 96)
        assertSame(flac, PlaySourceRule.choose(listOf(mp3, flac)))
        assertSame(flac, PlaySourceRule.choose(listOf(flac, mp3)))
        assertSame(mp3, PlaySourceRule.choose(listOf(poor, mp3)))
        assertNull("only a file worse than the stream: the stream plays", PlaySourceRule.choose(listOf(poor)))
        assertEquals(listOf(2, 1, 0), listOf(flac, mp3, poor).map(PlaySourceRule::rank))
    }

    @Test fun aLossyFileClearlyWorseThanTheStreamIsntUsed() {
        assertTrue(PlaySourceRule.beatsStream("MP3", 320, 44_100))
        assertTrue(PlaySourceRule.beatsStream("AAC", 256, 48_000))
        assertTrue(PlaySourceRule.beatsStream("MP3", 128, 0))
        assertTrue("a 128 kbps file measured a little under", PlaySourceRule.beatsStream("MP3", 125, 44_100))
        assertFalse(PlaySourceRule.beatsStream("MP3", 112, 44_100))
        assertFalse(PlaySourceRule.beatsStream("AAC", 96, 44_100))
        assertTrue("Opus holds up at 96", PlaySourceRule.beatsStream("Opus", 96, 48_000))
        assertFalse(PlaySourceRule.beatsStream("Opus", 64, 48_000))
        assertFalse("a 32 kHz file has lost its highs", PlaySourceRule.beatsStream("MP3", 320, 32_000))
        assertFalse("22 kHz", PlaySourceRule.beatsStream("AAC", 256, 22_050))
        assertFalse("no bitrate known (no length)", PlaySourceRule.beatsStream("MP3", 0, 44_100))
        assertTrue("lossless files always beat the stream", PlaySourceRule.beatsStream(lossless(9, "x", "y", 100)))
    }

    // ------------------------------------------------------------ Formats and MIME types

    @Test fun formatsAndMimeTypes() {
        listOf("audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/ogg", "application/ogg", "audio/opus").forEach {
            assertTrue(it, OwnFiles.isLossyMime(it))
        }
        listOf("audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/aiff", "audio/alac", "video/mp4", "audio/midi").forEach {
            assertFalse(it, OwnFiles.isLossyMime(it))
        }
        assertTrue("the lossless query's types aren't in the lossy one", OwnFiles.LOSSY_MIMES.none { it.contains("flac") || it.contains("wav") || it.contains("aif") })
        assertEquals("MP3", OwnFiles.formatOf("audio/mpeg"))
        assertEquals("AAC", OwnFiles.formatOf("audio/mp4", "song.m4a"))
        assertEquals("Opus", OwnFiles.formatOf("audio/ogg", "song.opus"))
        assertEquals("Ogg", OwnFiles.formatOf("audio/ogg", "song.ogg"))
        assertNull(OwnFiles.formatOf("audio/flac"))
        val mp3 = lossy(5, "Song", "Artist", 200, name = "Song.mp3")
        assertEquals("MP3", mp3.format)
        assertEquals("MP3 · 320 kbps", OwnFiles.label(mp3))
        assertEquals("Your file · MP3 · 320 kbps", OwnFiles.source(mp3))
        // Lossless files keep 1.8.0's keys (their uri); your own files' keys change when the file does.
        val flac = lossless(6, "Song", "Artist", 200)
        assertEquals(flac.uri.toString(), flac.key)
        assertTrue(OwnFiles.isOwnKey(mp3.key) && mp3.key.endsWith("@${mp3.modified}"))
        assertFalse(OwnFiles.isOwnKey(flac.key))
        assertFalse(flac.lossy)
        assertEquals("FLAC", flac.format)
    }

    // ------------------------------------------------------------ Messy tags

    @Test fun messyTagsAreCleanedUp() {
        assertEquals(OwnFiles.Tags("Get Lucky", "Daft Punk"), OwnFiles.clean("03 - Daft Punk - Get Lucky", "<unknown>"))
        assertEquals(OwnFiles.Tags("Get Lucky", "Daft Punk"), OwnFiles.clean("Daft Punk - Get Lucky", "Daft Punk"))
        assertEquals(OwnFiles.Tags("Get Lucky", "Daft Punk"), OwnFiles.clean("07. Get Lucky", "Daft Punk"))
        assertEquals("numbers that are the title stay", "7 Rings", OwnFiles.clean("7 Rings", "Ariana Grande").title)
        assertEquals("1979", OwnFiles.clean("1979", "The Smashing Pumpkins").title)
        assertEquals("4.44", OwnFiles.clean("4.44", "Jay-Z").title)
        assertEquals("Intro", OwnFiles.clean("A1 - Intro", "The xx").title)
        assertEquals("", OwnFiles.clean("Track 1", "Unknown Artist").artist)
    }

    @Test fun matchingIsStrict() {
        val lucky = song("Get Lucky", "Daft Punk", 248)
        assertTrue(OwnFiles.sameSong("Get Lucky", "Daft Punk", 248_400, lucky))
        assertTrue(OwnFiles.sameSong("03 - Daft Punk - Get Lucky", "<unknown>", 249_000, lucky))
        assertTrue(OwnFiles.sameSong("Get Lucky (feat. Pharrell Williams)", "Daft Punk feat. Pharrell Williams", 247_000, lucky))
        assertTrue(OwnFiles.sameSong("Get Lucky", "Daft Punk; Pharrell Williams", 248_000, lucky))
        assertTrue("\"The\" and case don't matter", OwnFiles.sameSong("mr. brightside", "Killers", 222_000, song("Mr. Brightside", "The Killers", 222)))
        assertTrue("& or and", OwnFiles.sameSong("The Sound of Silence", "Simon and Garfunkel", 185_000, song("The Sound of Silence", "Simon & Garfunkel", 185)))
        // None of these may play for the song.
        assertFalse("no artist", OwnFiles.sameSong("Get Lucky", "<unknown>", 248_000, lucky))
        assertFalse("another artist", OwnFiles.sameSong("Get Lucky", "Daft Punk Tribute Band", 248_000, lucky))
        assertFalse("another song", OwnFiles.sameSong("Get Lucky Radio Edit Mix", "Daft Punk", 248_000, lucky))
        assertFalse("a title that only contains it", OwnFiles.sameSong("Lucky", "Daft Punk", 248_000, lucky))
        assertFalse("another length (the radio edit)", OwnFiles.sameSong("Get Lucky", "Daft Punk", 369_000, lucky))
        assertFalse("4 s off", OwnFiles.sameSong("Get Lucky", "Daft Punk", 252_500, lucky))
        assertFalse("no length", OwnFiles.sameSong("Get Lucky", "Daft Punk", 0, lucky))
        assertFalse("the song's length unknown", OwnFiles.sameSong("Get Lucky", "Daft Punk", 248_000, song("Get Lucky", "Daft Punk", 0)))
        assertFalse("never a video", OwnFiles.sameSong("Get Lucky", "Daft Punk", 248_000, lucky.copy(isVideo = true)))
    }

    @Test fun theLookupTakesOnlyTheSameSong() {
        val file = lossy(10, "01 - Get Lucky", "Daft Punk", 248)
        val results = listOf(
            song("Get Lucky", "Daft Punk", 248, "video").copy(isVideo = true),
            song("Get Lucky", "Some Cover Band", 248, "cover"),
            song("Get Lucky (Radio Edit)", "Daft Punk", 369, "edit"),
            song("Get Lucky", "Daft Punk", 249, "right"),
        )
        assertEquals("right", LosslessSync.pickOwn(file, results)?.id)
        assertNull(LosslessSync.pickOwn(file, results.dropLast(1)))
    }

    // ------------------------------------------------------------ Off = 1.8.0, and turning it off

    @Test fun offIsExactlyAsBefore() {
        val d = AppSettings()
        assertFalse("off by default", d.sound.ownLossyFiles)
        assertEquals(OwnFiles.Kinds(lossless = false, lossy = false), OwnFiles.kinds(d))
        assertEquals(OwnFiles.Kinds(lossless = true, lossy = false), OwnFiles.kinds(d.copy(lossless = true, losslessLocal = true)))
        assertEquals("your own files don't need lossless playback", OwnFiles.Kinds(false, true), OwnFiles.kinds(d.copy(sound = SoundPrefs(ownLossyFiles = true))))
        // With the switch off no lossy file is considered, even one that's there.
        val s = song("Get Lucky", "Daft Punk", 248)
        val mp3 = lossy(20, "Get Lucky", "Daft Punk", 248)
        val only = store(mp3)
        assertNull(only.match(s, lossless = true, lossy = false))
        only.linked = mapOf(s.id to mp3.uri)
        assertNull("not even the file sync tied to it", only.match(s, lossless = true, lossy = false))
        // Lossless files match as in 1.8.0: loose artist, ±4 s.
        val flac = lossless(21, "Get Lucky", "Daft Punk", 251)
        val both = store(mp3, flac)
        assertSame(flac, both.match(s, lossless = true, lossy = false))
        assertSame(flac, both.match(s.copy(artists = emptyList()), lossless = true, lossy = false))
        assertNull(both.match(s.copy(durationSec = 260), lossless = true, lossy = false))
    }

    @Test fun losslessWinsOverYourFileEvenWhenTheFileIsTied() {
        val s = song("Get Lucky", "Daft Punk", 248)
        val mp3 = lossy(30, "Get Lucky", "Daft Punk", 248)
        val flac = lossless(31, "Get Lucky", "Daft Punk", 248)
        val both = store(mp3, flac).apply { linked = mapOf(s.id to mp3.uri) }
        assertSame(flac, both.match(s, lossless = true, lossy = true))
        assertSame("lossless playback off: your file plays", mp3, both.match(s, lossless = false, lossy = true))
    }

    @Test fun turnedOffTheSongsGoBackToTheStream() {
        val s = song("Get Lucky", "Daft Punk", 248)
        val mp3 = lossy(40, "03 - Daft Punk - Get Lucky", "<unknown>", 248)
        val files = store(mp3)
        assertSame("matched by its cleaned tags", mp3, files.match(s, lossless = false, lossy = true))
        files.linked = mapOf(s.id to mp3.uri)
        assertSame(mp3, files.match(s, lossless = false, lossy = true))
        // The switch goes off: the player asks with lossy = false at once, before the rescan …
        assertNull(files.match(s, lossless = false, lossy = false))
        // … and the rescan without your files leaves nothing to match.
        files.use(emptyList()); files.linked = emptyMap()
        assertNull(files.match(s, lossless = true, lossy = true))
    }

    @Test fun aPoorFileIsNeverMatched() {
        val s = song("Get Lucky", "Daft Punk", 248)
        val poor = lossy(50, "Get Lucky", "Daft Punk", 248, kbps = 64)
        val files = store(poor).apply { linked = mapOf(s.id to poor.uri) }
        assertNull(files.match(s, lossless = true, lossy = true))
    }
}
