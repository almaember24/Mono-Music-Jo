package com.prism.music

import com.prism.music.data.imports.MatchStatus
import com.prism.music.data.imports.TrackMatcher
import com.prism.music.data.imports.TrackToMatch
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bring your history (1.8.1): how sure the matcher is decides what the listener sees. Within 3 s and the same title and
 * artist: sure. 3–10 s off, or another version (live, acoustic, remix…): needs a look. Nothing like it: not found.
 * Karaoke, covers and the like never stand for the song.
 */
class TrackMatcherTest {
    private fun song(id: String, title: String, artist: String, sec: Int, album: String? = null, explicit: Boolean = false, video: Boolean = false) =
        Song(id, title, listOf(ArtistRef(artist)), album?.let { AlbumRef(it) }, sec, explicit = explicit, isVideo = video)

    private fun judge(t: TrackToMatch, vararg c: Song) = TrackMatcher.judge(t, c.toList())

    private val blinding = TrackToMatch("Blinding Lights", "The Weeknd", "After Hours", durationMs = 200_040)

    @Test fun sameSongWithinThreeSecondsIsSure() {
        val r = judge(blinding, song("cover", "Blinding Lights", "Some Cover Band", 200), song("real", "Blinding Lights", "The Weeknd", 201, "After Hours"))
        assertEquals(MatchStatus.SURE, r.status)
        assertEquals("real", r.song?.id)
    }

    @Test fun threeToTenSecondsOffNeedsALook() {
        val r = judge(blinding, song("radio", "Blinding Lights", "The Weeknd", 207))
        assertEquals(MatchStatus.REVIEW, r.status)
        assertEquals("radio", r.candidates.first().id)
    }

    @Test fun theClosestLengthWinsAmongVersionsOfTheSameTitle() {
        val r = judge(blinding, song("single", "Blinding Lights", "The Weeknd", 222), song("album", "Blinding Lights", "The Weeknd", 200))
        assertEquals(MatchStatus.SURE, r.status)
        assertEquals("album", r.song?.id)
    }

    @Test fun anotherVersionNeedsALook() {
        val live = TrackToMatch("Hotel California - Live On MTV, 1994", "Eagles", durationMs = 427_000)
        val r = judge(live, song("studio", "Hotel California", "Eagles", 391))
        assertEquals(MatchStatus.REVIEW, r.status)
        val studio = TrackToMatch("Hotel California", "Eagles", durationMs = 391_000)
        assertEquals(MatchStatus.REVIEW, judge(studio, song("live", "Hotel California (Live)", "Eagles", 427)).status)
        assertEquals(MatchStatus.SURE, judge(live, song("live", "Hotel California (Live on MTV, 1994)", "Eagles", 427)).status)
    }

    @Test fun aRemasterOfTheSameLengthIsTheSameRecording() {
        val t = TrackToMatch("Bohemian Rhapsody - Remastered 2011", "Queen", durationMs = 354_320)
        assertEquals(MatchStatus.SURE, judge(t, song("b", "Bohemian Rhapsody", "Queen", 355)).status)
        assertEquals("four seconds off isn't the same master", MatchStatus.REVIEW, judge(t, song("b", "Bohemian Rhapsody", "Queen", 358)).status)
    }

    @Test fun knockOffsNeverStandForTheSong() {
        val r = judge(blinding, song("k", "Blinding Lights (Karaoke Version)", "The Weeknd", 200), song("s", "Blinding Lights (Sped Up)", "The Weeknd", 200))
        assertEquals(MatchStatus.NONE, r.status)
        assertNull(r.song)
    }

    @Test fun nothingLikeItIsNotFound() {
        assertEquals(MatchStatus.NONE, judge(blinding, song("x", "Starboy", "The Weeknd", 230)).status)
        assertEquals(MatchStatus.NONE, judge(blinding).status)
    }

    @Test fun withoutALengthTheFirstThatFitsIsSureAndTheExplicitOneIsPreferred() {
        val t = TrackToMatch("HUMBLE.", "Kendrick Lamar")
        val r = judge(t, song("clean", "HUMBLE.", "Kendrick Lamar", 177), song("explicit", "HUMBLE.", "Kendrick Lamar", 177, explicit = true))
        assertEquals(MatchStatus.SURE, r.status)
        assertEquals("explicit", r.song?.id)
        // Shorter than its longest play: an edit, not the song.
        val heard = t.copy(atLeastMs = 200_000)
        assertEquals(MatchStatus.NONE, judge(heard, song("edit", "HUMBLE.", "Kendrick Lamar", 150)).status)
    }

    @Test fun featuredArtistsAndAccentsDontGetInTheWay() {
        val t = TrackToMatch("Get Lucky (feat. Pharrell Williams & Nile Rodgers)", "Daft Punk", durationMs = 369_000)
        assertEquals(MatchStatus.SURE, judge(t, song("g", "Get Lucky", "Daft Punk", 369)).status)
        val beyonce = TrackToMatch("Halo", "Beyonce", durationMs = 261_000)
        assertEquals(MatchStatus.SURE, judge(beyonce, song("h", "Halo", "Beyoncé", 262)).status)
        val the = TrackToMatch("Mr. Brightside", "Killers", durationMs = 222_000)
        assertEquals(MatchStatus.SURE, judge(the, song("m", "Mr. Brightside", "The Killers", 223)).status)
    }

    @Test fun theRightTitleByAnotherNameForTheArtistNeedsALook() {
        val t = TrackToMatch("Lemon", "米津玄師", durationMs = 255_000)
        val r = judge(t, song("l", "Lemon", "Kenshi Yonezu", 255))
        assertEquals(MatchStatus.REVIEW, r.status)
        assertEquals("l", r.song?.id)
    }

    @Test fun musicVideosAreNeverSure() {
        assertEquals(MatchStatus.REVIEW, judge(blinding, song("v", "Blinding Lights", "The Weeknd", 200, video = true)).status)
    }

    @Test fun takeoutNamesItsVideo() = runBlocking {
        val m = TrackMatcher { listOf(song("other", "Blinding Lights", "The Weeknd", 200), song("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 201, "After Hours")) }
        val r = m.match(TrackToMatch("Blinding Lights", "The Weeknd", youtubeId = "fHI8X4OXluQ"))
        assertEquals(MatchStatus.SURE, r.status)
        assertEquals("After Hours", r.song?.album?.title)
        // Not among the results: still that video, as Takeout named it.
        val alone = TrackMatcher { emptyList() }.match(TrackToMatch("Song", "Artist", youtubeId = "AAAAAAAAAAA"))
        assertEquals("AAAAAAAAAAA", alone.song?.id)
    }

    @Test fun theIsrcIsAskedFirstWhenKnown() = runBlocking {
        val asked = ArrayList<String>()
        val m = TrackMatcher { q -> asked += q; if (q == "USUG11904206") listOf(song("i", "Blinding Lights", "The Weeknd", 200)) else emptyList() }
        val r = m.match(blinding.copy(isrc = "USUG11904206"))
        assertEquals("i", r.song?.id)
        assertEquals(listOf("USUG11904206"), asked)
        assertEquals("Get Lucky Daft Punk", TrackMatcher.queryFor(TrackToMatch("Get Lucky (feat. Pharrell Williams)", "Daft Punk feat. Pharrell Williams")))
    }

    @Test fun withoutALengthAnotherRemasterOrASubtitleIsntSure() {
        // Hey Jude's 2015 remaster isn't its 2009 one.
        val jude = TrackToMatch("Hey Jude - Remastered 2015", "The Beatles")
        assertEquals(MatchStatus.REVIEW, judge(jude, song("2009", "Hey Jude (Remastered 2009)", "The Beatles", 430), song("1", "Hey Jude", "The Beatles", 426)).status)
        assertEquals(MatchStatus.SURE, judge(jude.copy(durationMs = 426_400), song("2009", "Hey Jude (Remastered 2009)", "The Beatles", 430), song("1", "Hey Jude", "The Beatles", 426)).status)
        // "(You Are My Destiny)": the Pussycat Dolls' song, not the film's.
        val jai = TrackToMatch("Jai Ho", "A.R. Rahman", "Slumdog Millionaire")
        assertEquals(MatchStatus.REVIEW, judge(jai, song("pcd", "Jai Ho! (You Are My Destiny) (feat. Nicole Scherzinger)", "A. R. Rahman", 223)).status)
        // Where it's from isn't a subtitle.
        assertEquals(MatchStatus.SURE, judge(TrackToMatch("Kesariya", "Arijit Singh"), song("k", "Kesariya (From \"Brahmastra\")", "Arijit Singh", 269)).status)
    }
}