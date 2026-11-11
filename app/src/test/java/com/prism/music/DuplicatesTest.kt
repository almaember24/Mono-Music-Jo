package com.prism.music

import com.prism.music.data.Duplicates
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The duplicate finder (1.8.3): the same recording found twice, other versions kept apart. */
class DuplicatesTest {
    private fun s(id: String, title: String, artist: String = "The Killers", sec: Int = 222, video: Boolean = false) =
        Song(id, title, listOf(ArtistRef(artist)), durationSec = sec, isVideo = video)

    @Test fun theSameRecording() {
        val song = s("a", "Mr. Brightside")
        assertTrue("its video", Duplicates.same(song, s("v", "The Killers - Mr. Brightside (Official Music Video)", sec = 223, video = true)))
        assertTrue("another upload", Duplicates.same(song, s("b", "Mr. Brightside [Official Audio]", "TheKillersVEVO", sec = 221)))
        assertTrue("the same id", Duplicates.same(song, song.copy(title = "anything")))
        assertTrue("capitals and accents", Duplicates.same(s("c", "Beyoncé", "X"), s("d", "BEYONCE", "x")))
    }

    @Test fun otherSongsAndVersionsAreKeptApart() {
        val song = s("a", "Mr. Brightside")
        assertFalse("live", Duplicates.same(song, s("l", "Mr. Brightside (Live)")))
        assertFalse("remix", Duplicates.same(song, s("r", "Mr. Brightside - Jacques Lu Cont Remix")))
        assertFalse("sped up", Duplicates.same(song, s("u", "Mr. Brightside (Sped Up)")))
        assertFalse("acoustic", Duplicates.same(song, s("k", "Mr. Brightside [Acoustic]")))
        assertFalse("longer by more than 2 s", Duplicates.same(song, s("x", "Mr. Brightside", sec = 230)))
        assertFalse("another artist's song of that name", Duplicates.same(song, s("o", "Mr. Brightside", "Someone Else")))
        assertFalse("no length known", Duplicates.same(song, s("n", "Mr. Brightside", sec = 0)))
    }

    @Test fun groupsKeepTheSongNotTheVideo() {
        val video = s("v", "The Killers - Mr. Brightside (Official Music Video)", sec = 223, video = true)
        val song = s("a", "Mr. Brightside")
        val other = s("h", "Human", sec = 249)
        val live = s("l", "Mr. Brightside (Live)", sec = 223)
        val again = s("b", "Mr. Brightside (Audio)", sec = 222)
        val groups = Duplicates.find(listOf(video, other, song, live, again))
        assertEquals(1, groups.size)
        assertEquals("a", groups.single().keep.id)
        assertEquals(listOf("v", "b"), groups.single().extras.map { it.id })
        assertTrue("nothing twice, nothing found", Duplicates.find(listOf(song, other, live)).isEmpty())
    }

    @Test fun quickOnALongPlaylist() {
        val many = (0 until 5_000).map { s("s$it", "Song $it", "Artist ${it % 200}", sec = 180 + it % 60) } + s("d", "Song 42", "Artist 42", sec = 223)
        val t = System.nanoTime()
        val groups = Duplicates.find(many)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("duplicates in 5,001 songs: $ms ms")
        assertEquals(listOf("d"), groups.single().extras.map { it.id })
        assertTrue("under 500 ms: $ms", ms < 500)
    }
}
