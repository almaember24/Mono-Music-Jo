package com.prism.music

import com.prism.music.data.innertube.MusicVideoMatch
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AudioQuality
import com.prism.music.data.stream.AudioPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opus first, as YouTube Music plays it; and a song's video found when YouTube doesn't link one (offline, saved answers). */
class StreamAndVideoChoiceTest {
    private fun opus(k: Int) = AudioPick.Choice("audio/webm", "opus", k)
    private fun aac(k: Int, he: Boolean = false) = AudioPick.Choice("audio/mp4", if (he) "mp4a.40.5" else "mp4a.40.2", k)

    // "Espresso" as YouTube answered on 2026-10-19: AAC 129k said more than Opus 128k, so it used to play as AAC.
    private val espresso = listOf(aac(48, he = true), aac(129), opus(49), opus(64), opus(128))

    @Test
    fun highPlaysOpusWhereAacOnlyLookedBigger() {
        assertEquals(4, AudioPick.pick(espresso, AudioQuality.HIGH))
    }

    @Test
    fun premium256StillBeatsEverything() {
        val list = espresso + aac(256) + opus(256)
        assertEquals("Opus 256 (774) first", list.lastIndex, AudioPick.pick(list, AudioQuality.HIGH))
        val noOpus256 = espresso + aac(256)
        assertEquals("AAC 256 (141) beats Opus 128", noOpus256.lastIndex, AudioPick.pick(noOpus256, AudioQuality.HIGH))
    }

    @Test
    fun normalAndLowTakeOpusInTheirTier() {
        assertEquals("Normal: the ~128 tier, Opus", 4, AudioPick.pick(espresso, AudioQuality.NORMAL))
        assertEquals("Low: the smallest, Opus", 2, AudioPick.pick(espresso, AudioQuality.LOW))
        assertEquals("AAC only: still plays", 1, AudioPick.pick(listOf(aac(48, he = true), aac(129)), AudioQuality.HIGH))
    }

    @Test
    fun aDubbedTrackIsOnlyALastResort() {
        val list = listOf(opus(128).copy(original = false), aac(129))
        assertEquals(1, AudioPick.pick(list, AudioQuality.HIGH))
    }

    private fun video(title: String, channel: String, seconds: Int, id: String) = Song(id, title, listOf(ArtistRef(channel)), durationSec = seconds, isVideo = true)

    // "Linger" by Royel Otis as YouTube Music lists it, and the video search's answer (2026-10-19).
    private val linger = Song("X0tQ404rwUk", "Linger (SiriusXM Session)", listOf(ArtistRef("Royel Otis", "UCs7rMxbqJnQX4BXLr86gP3w")), durationSec = 186)
    private val lingerSearch = listOf(
        video("Royel Otis — Linger (The Cranberries Cover) [Live @ SiriusXM]", "SiriusXM", 187, "JGUVB19e13s"),
        video("Royel Otis - Linger - English - lyrics", "VIBEBOA", 235, "GOtFNi7WY9c"),
        video("The Cranberries - Linger  (Royel Otis Version) (Lyrics)", "Lyricus-Jay", 185, "QAnz1aYc3x4"),
        video("LINGER - ROYEL OTIS/KARAOKE", "Triple K Karaoke", 187, "BeZafhi_gNY"),
        video("Derrick Rose Highlights (Linger - Royel Otis Cover)", "Ernesto Ramirez", 156, "50KqfetDY1A"),
        video("Royel Otis - Linger (SiriusXM Session)", "RadioDdt", 87, "ih4FCJKzsio"),
    )

    @Test
    fun lingerFindsSiriusXmsVideo() {
        assertEquals("JGUVB19e13s", MusicVideoMatch.pick(linger, lingerSearch))
    }

    @Test
    fun lyricAndKaraokeUploadsAreNeverTheVideo() {
        // Without SiriusXM's own upload, nothing else in that list is Linger's video.
        assertNull(MusicVideoMatch.pick(linger, lingerSearch.drop(1)))
    }

    @Test
    fun theArtistsOwnVideoComesFirstAndCoversStayOut() {
        val song = Song("s1", "Smells Like Teen Spirit", listOf(ArtistRef("Nirvana")), durationSec = 301)
        val videos = listOf(
            video("Nirvana - Smells Like Teen Spirit (Guitar Cover)", "Some Guitarist", 301, "cover"),
            video("Nirvana - Smells Like Teen Spirit (Live at Reading 1992)", "NirvanaVEVO", 290, "live"),
            video("Nirvana - Smells Like Teen Spirit (Official Music Video)", "NirvanaVEVO", 279, "official"),
        )
        assertEquals("official", MusicVideoMatch.pick(song, videos))
        assertNull("no cover or live take for a studio song", MusicVideoMatch.pick(song, videos.dropLast(1)))
    }

    @Test
    fun bassBoostedAndRetunedCopiesAreNeverTheVideo() {
        // "Bohemian Rhapsody" (2026-10-20): YouTube linked no video and the search's only exact-length upload naming
        // Queen was a bass-boosted copy, which Prism then played as the song's video.
        val song = Song("BSTsnWoslP4", "Bohemian Rhapsody", listOf(ArtistRef("Queen")), durationSec = 355)
        val videos = listOf(
            video("Bohemian Rhapsody (Live At Wembley Stadium / Saturday 12th July 1986)", "Queen", 360, "live"),
            video("Queen - Bohemian Rhapsody (Lyrics)", "7clouds Rock", 355, "lyrics"),
            video("(25-70Hz) Queen - Bohemian Rhapsody (Rebassed by Justin)", "Rebassed by Justin", 355, "rebassed"),
            video("Queen - Bohemian Rhapsody 432hz", "Hertz Patrol", 356, "432"),
            video("Queen - Bohemian Rhapsody", "Bass Boosted Nation", 355, "channel"),
        )
        assertNull(MusicVideoMatch.pick(song, videos))
    }

    @Test
    fun aLabelsUploadOfTheSameRecordingCounts() {
        val song = Song("s2", "Some Single", listOf(ArtistRef("New Band")), durationSec = 200)
        val videos = listOf(
            video("New Band - Some Single (Official Audio)", "New Band", 200, "audio"),
            video("New Band - Some Single (Official Video)", "Big Label Records", 202, "label"),
        )
        assertEquals("label", MusicVideoMatch.pick(song, videos))
        assertTrue(MusicVideoMatch.pick(song, videos.take(1)) == null)
    }
}
