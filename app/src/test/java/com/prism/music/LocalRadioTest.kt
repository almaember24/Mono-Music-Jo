package com.prism.music

import com.prism.music.data.db.mergeUploads
import com.prism.music.data.foryou.LocalRadio
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prism's own radio picks from the listener's music: what they play together first, varied, nothing left out. */
class LocalRadioTest {
    private fun song(id: String, artist: String) = Song(id, "Song $id", listOf(ArtistRef(artist)), durationSec = 200)
    private val min = 60_000L

    @Test
    fun songsPlayedTogetherAreCoListened() {
        val plays = listOf(
            LocalRadio.Play("a", 0), LocalRadio.Play("b", 4 * min), LocalRadio.Play("c", 8 * min),
            // A new sitting, hours later: "a" and "z" weren't played together.
            LocalRadio.Play("z", 5 * 60 * min),
        )
        val co = LocalRadio.coListen(plays)
        assertEquals(1.0, co["a"]!!["b"]!!, 1e-9)
        assertEquals(0.5, co["a"]!!["c"]!!, 1e-9)
        assertTrue("different sittings", co["a"]?.containsKey("z") != true)
    }

    @Test
    fun picksFollowWhatYouPlayTogether() {
        val seed = song("seed", "Daft Punk")
        val friend = song("friend", "Justice")   // played right after the seed, often
        val sameArtist = song("same", "Daft Punk")
        val stranger = song("stranger", "Somebody")
        val co = mapOf("seed" to mapOf("friend" to 4.0))
        val pool = LocalRadio.Pool(
            listOf(stranger, sameArtist, friend), co,
            affinity = { if (it.id == "stranger") 0.9 else 0.3 }, genreOf = { null },
        )
        val picks = LocalRadio.pick(listOf(seed), pool, emptySet(), 3)
        assertEquals("friend", picks.first().song.id)
        assertTrue(picks.first { it.song.id == "friend" }.related)
        assertFalse("a well-liked song with nothing to do with the seed only fills in", picks.first { it.song.id == "stranger" }.related)
        assertEquals("stranger", picks.last().song.id)
    }

    /** Reddit (after 1.7.8): an opera or rock radio kept bringing back the listener's most played pop. */
    @Test
    fun besideYouTubeOnlyWhatBelongsWithTheSeed() {
        val seed = song("aria", "Maria Callas")
        val opera = song("opera", "Maria Callas")
        val favourite = song("bye", "NSYNC")
        val pool = LocalRadio.Pool(listOf(favourite, opera), emptyMap(), affinity = { if (it.id == "bye") 1.0 else 0.2 }, genreOf = { null })
        val picks = LocalRadio.pick(listOf(seed), pool, emptySet(), 5)
        assertEquals(listOf("opera"), LocalRadio.besideYouTube(picks, onlyYours = false).map { it.song.id })
        assertEquals("with nothing else to play, the favourite still fills in", 2, LocalRadio.besideYouTube(picks, onlyYours = true).size)
    }

    @Test
    fun picksAreVariedAndLeaveOutWhatTheyShould() {
        val seed = song("seed", "A")
        val pool = LocalRadio.Pool(
            (1..6).map { song("a$it", "A") } + (1..3).map { song("b$it", "B") } + song("queued", "C"),
            emptyMap(), affinity = { 0.5 }, genreOf = { "Rock" },
        )
        val picks = LocalRadio.pick(listOf(seed), pool, setOf("queued"), 8).map { it.song }
        assertTrue(picks.none { it.id == "queued" })
        assertTrue("at most ${LocalRadio.PER_ARTIST} by one artist", picks.count { it.primaryArtist == "A" } <= LocalRadio.PER_ARTIST)
        assertTrue("not the seed's artist straight after it", picks.first().primaryArtist != "A")
        for (i in 1 until picks.size) assertTrue("same artist twice running at $i", picks[i].primaryArtist != picks[i - 1].primaryArtist)
    }

    @Test
    fun anotherUploadOfTheSameSongIsNotPickedTwice() {
        val seed = Song("beat1", "Beat It", listOf(ArtistRef("Michael Jackson")), durationSec = 258)
        val again = Song("beat2", "Beat It (Single Version)", listOf(ArtistRef("Michael Jackson")), durationSec = 258)
        val pyt1 = Song("pyt1", "P.Y.T.", listOf(ArtistRef("Michael Jackson")), durationSec = 239)
        val pyt2 = Song("pyt2", "P.Y.T.", listOf(ArtistRef("Michael Jackson")), durationSec = 239)
        val pool = LocalRadio.Pool(listOf(again, pyt1, pyt2), emptyMap(), affinity = { 0.5 }, genreOf = { null })
        val picks = LocalRadio.pick(listOf(seed), pool, emptySet(), 5).map { it.song.id }
        assertEquals(1, picks.size)
        assertTrue(picks.single().startsWith("pyt"))
    }

    @Test
    fun newSectionsGoInOnceAfterMadeForYou() {
        val layout = listOf(HomeSectionConfig(HomeSections.QUICK_PICKS, "Quick picks"), HomeSectionConfig(HomeSections.MADE_FOR_YOU, "Made for you"), HomeSectionConfig("x", "X"))
        val add = listOf(HomeSectionConfig(HomeSections.ON_REPEAT, "On repeat"), HomeSectionConfig(HomeSections.YOUR_MIXES, "Mixes from your music"))
        val out = HomeSections.withNewSections(layout, add, after = HomeSections.MADE_FOR_YOU)
        assertEquals(listOf(HomeSections.QUICK_PICKS, HomeSections.MADE_FOR_YOU, HomeSections.ON_REPEAT, HomeSections.YOUR_MIXES, "x"), out.map { it.key })
        assertEquals(out, HomeSections.withNewSections(out, add, after = HomeSections.MADE_FOR_YOU))
    }

    /** Replay counts a song once, however many uploads of it were played. */
    @Test
    fun replayMergesUploadsOfOneSong() {
        fun sp(id: String, title: String, artist: String, plays: Int, min: Long) =
            com.prism.music.data.db.SongPlays(id, plays, min * 60_000, title, artist, null, null, 200, null, artist)
        val merged = listOf(
            sp("a", "Beat It", "Michael Jackson", 17, 58), sp("b", "Choosin' Texas", "Ella Langley", 8, 29),
            sp("c", "Beat It (Single Version)", "Michael Jackson", 6, 21), sp("d", "Beat It", "Fall Out Boy", 2, 6),
        ).mergeUploads()
        assertEquals(listOf("a", "b", "d"), merged.map { it.songId })
        assertEquals(23, merged[0].plays)
        assertEquals(79 * 60_000L, merged[0].ms)
    }

    /** "Mixes from your music" is part of Made for you: one section, wherever the listener had it. */
    @Test
    fun yourMixesFoldIntoMadeForYou() {
        val both = listOf(HomeSectionConfig(HomeSections.MADE_FOR_YOU, "Made for you"), HomeSectionConfig("x", "X"), HomeSectionConfig(HomeSections.YOUR_MIXES, "Mixes from your music"))
        assertEquals(listOf(HomeSections.MADE_FOR_YOU, "x"), HomeSections.mergeYourMixes(both).map { it.key })
        val onlyOld = listOf(HomeSectionConfig("x", "X"), HomeSectionConfig(HomeSections.YOUR_MIXES, "Mixes from your music", style = com.prism.music.data.prefs.SectionStyle.GRID))
        val merged = HomeSections.mergeYourMixes(onlyOld)
        assertEquals(listOf("x", HomeSections.MADE_FOR_YOU), merged.map { it.key })
        assertEquals("Made for you", merged[1].title)
        assertEquals(com.prism.music.data.prefs.SectionStyle.GRID, merged[1].style)
        assertTrue(HomeSections.initial.none { it.key == HomeSections.YOUR_MIXES } && HomeSections.defaults.none { it.key == HomeSections.YOUR_MIXES })
    }

    /** The Replay card heads Home: moved up as the card, hidden stays hidden, missing gets added. */
    @Test
    fun replayCardGoesToTheTop() {
        val old = listOf(
            HomeSectionConfig(HomeSections.GREETING, "Shortcut tiles"), HomeSectionConfig("x", "X"),
            HomeSectionConfig(HomeSections.REPLAY, "Replay teaser", style = com.prism.music.data.prefs.SectionStyle.LIST),
        )
        val moved = HomeSections.replayOnTop(old)
        assertEquals(listOf(HomeSections.REPLAY, HomeSections.GREETING, "x"), moved.map { it.key })
        assertEquals(com.prism.music.data.prefs.SectionStyle.HERO, moved[0].style)
        val hidden = HomeSections.replayOnTop(listOf(HomeSectionConfig("x", "X"), HomeSectionConfig(HomeSections.REPLAY, "Replay teaser", visible = false)))
        assertEquals(false, hidden[0].visible)
        assertEquals(listOf(HomeSections.REPLAY, "x"), HomeSections.replayOnTop(listOf(HomeSectionConfig("x", "X"))).map { it.key })
        assertEquals(HomeSections.REPLAY, HomeSections.initial.first().key)
        assertEquals(HomeSections.REPLAY, HomeSections.defaults.first().key)
    }
}
