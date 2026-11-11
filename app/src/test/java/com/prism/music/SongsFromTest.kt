package com.prism.music

import com.prism.music.data.PlaylistSongs
import com.prism.music.data.SongsFrom
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library → Songs' "From" (1.8.3, #382): liked songs plus chosen playlists, once each; the choice kept as text; whole
 * playlists read once and kept.
 */
class SongsFromTest {
    private fun s(id: String) = Song(id, "Song $id", listOf(ArtistRef("Artist")))

    @Test fun likedFirstThenEachPlaylistOnceEach() {
        val liked = listOf(s("a"), s("b"))
        val gym = listOf(s("c"), s("a"), s("d"))
        val road = listOf(s("d"), s("e"))
        val from = SongsFrom(true, listOf("gym", "road"))
        assertEquals(listOf("a", "b", "c", "d", "e"), SongsFrom.merge(liked, listOf(gym, road), from).map { it.id })
        assertEquals("without liked songs", listOf("c", "a", "d", "e"), SongsFrom.merge(liked, listOf(gym, road), from.copy(liked = false)).map { it.id })
        assertEquals("a playlist with repeats", listOf("c"), SongsFrom.merge(emptyList(), listOf(listOf(s("c"), s("c"))), SongsFrom(false, listOf("x"))).map { it.id })
    }

    @Test fun theChoiceIsKeptAsText() {
        val from = SongsFrom(false, listOf("PLabc", "prism_123"))
        assertEquals(from, SongsFrom.decode(from.encode()))
        assertEquals(SongsFrom(true, listOf("PLabc")), SongsFrom.decode(SongsFrom(true, listOf("PLabc")).encode()))
        assertTrue("nothing kept: liked songs, as before", SongsFrom.decode(null).isDefault)
        assertTrue(SongsFrom.decode("").isDefault)
        assertTrue("nothing at all chosen falls back to liked songs", SongsFrom.decode("|").isDefault)
        assertEquals("repeats and blanks dropped", listOf("a", "b"), SongsFrom.decode("liked|a,,b,a").playlists)
        assertFalse(SongsFrom(true, listOf("a")).isDefault)
    }

    @Test fun aPlaylistIsReadOnceUntilRefreshed() = runBlocking {
        var reads = 0
        val lists = PlaylistSongs { id -> reads++; if (id == "bad") error("offline") else listOf(s("$id-1")) }
        assertEquals("x-1", lists.songs("x")!!.single().id)
        lists.songs("x")
        assertEquals(1, reads)
        lists.refresh("x")
        lists.songs("x")
        assertEquals(2, reads)
        assertNull("a playlist that won't load shows nothing, no crash", lists.songs("bad"))
    }
}
