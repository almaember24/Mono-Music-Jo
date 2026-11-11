package com.prism.music

import com.prism.music.data.ArtistEssentials
import com.prism.music.data.ArtistYou
import com.prism.music.data.db.SongPlays
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "You and this artist" and their essentials, from a listener's plays. */
class ArtistListeningTest {
    private val artist = listOf(ArtistRef("Them", "UCthem"))
    private fun song(id: String, title: String = id, video: Boolean = false) = Song(id, title, artist, isVideo = video)
    private fun plays(id: String, title: String, n: Int, ms: Long) = SongPlays(id, n, ms, title, "Them", null, null, 200, "UCthem", "Them")

    @Test fun whatYouAndAnArtistAddUpTo() {
        val y = ArtistYou.from(listOf(plays("a", "A", 3, 600_000), plays("b", "B", 7, 1_400_000)), 12_345L)!!
        assertEquals(12_345L, y.firstPlayedAt)
        assertEquals(10, y.plays)
        assertEquals(2_000_000L, y.ms)
        assertEquals("B", y.topSongTitle)
        assertEquals(7, y.topSongPlays)
    }

    @Test fun twoUploadsOfOneSongAreOneTopSong() {
        val y = ArtistYou.from(listOf(plays("a", "A", 4, 400_000), plays("a2", "A (Remastered)", 4, 400_000), plays("b", "B", 6, 600_000)), 1L)!!
        assertEquals("A", y.topSongTitle.removeSuffix(" (Remastered)"))
        assertEquals(8, y.topSongPlays)
    }

    @Test fun noPlaysNoPanel() {
        assertNull(ArtistYou.from(emptyList(), null))
        assertNull(ArtistYou.from(emptyList(), 5L))
    }

    private val top = listOf(song("t1", "First"), song("t2", "Second"), song("t3", "Third"), song("t4", "Fourth"))

    @Test fun whatYouPlayMovesUp() {
        val picked = ArtistEssentials.pick(top, listOf(song("t3", "Third") to 12))
        assertEquals("Third", picked.first().title)
        assertEquals(listOf("Third", "First", "Second", "Fourth"), picked.map { it.title })
    }

    @Test fun yourOwnFavoritesJoinIn() {
        val picked = ArtistEssentials.pick(top, listOf(song("deep", "Deep Cut") to 20))
        assertEquals("Deep Cut", picked.first().title)
        assertTrue(picked.map { it.title }.containsAll(top.map { it.title }))
    }

    @Test fun anotherUploadOfATopSongCountsForIt() {
        val picked = ArtistEssentials.pick(top, listOf(song("other", "Fourth (Remastered 2011)") to 12))
        assertEquals("Fourth", picked.first().title)
        assertEquals("it isn't listed twice", 4, picked.size)
    }

    @Test fun aVideoCountsForItsSongAndNeverAsASong() {
        val forSong = ArtistEssentials.pick(top, listOf(song("v", "Third", video = true) to 12))
        assertEquals("t3", forSong.first().id)
        val alone = ArtistEssentials.pick(top, listOf(song("v", "Live Video", video = true) to 12, song("t4", "Fourth") to 1))
        assertTrue(alone.none { it.isVideo })
    }

    @Test fun fewPlaysShowNothing() {
        assertEquals(emptyList<Song>(), ArtistEssentials.pick(top, emptyList()))
        assertEquals(emptyList<Song>(), ArtistEssentials.pick(top, listOf(song("t3", "Third") to 2)))
    }

    @Test fun anOrderNoDifferentFromTopSongsShowsNothing() {
        assertEquals(emptyList<Song>(), ArtistEssentials.pick(top, listOf(song("t1", "First") to 10)))
    }

    @Test fun itStopsAtItsLimit() {
        val many = List(20) { song("t$it", "Song $it") }
        assertEquals(ArtistEssentials.SHOWN, ArtistEssentials.pick(many, listOf(song("t9", "Song 9") to 30)).size)
    }
}
