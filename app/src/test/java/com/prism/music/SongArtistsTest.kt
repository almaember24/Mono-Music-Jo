package com.prism.music

import com.prism.music.data.ArtworkStore
import com.prism.music.data.db.SongPlays
import com.prism.music.data.db.mainArtist
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Songs keep their exact artists when stored, and saved covers are told apart (batch 2 of the 1.7 fixes). */
class SongArtistsTest {
    @Test
    fun aNameWithACommaStaysWhole() {
        val song = Song("abc", "EARFQUAKE", listOf(ArtistRef("Tyler, The Creator", "UCtyler"), ArtistRef("Playboi Carti")))
        val e = song.toEntity()
        assertEquals("Tyler, The Creator", e.primaryArtist)
        assertEquals(song.artists, e.toSong().artists)
        assertEquals("Tyler, The Creator, Playboi Carti", e.artistName)
    }

    @Test
    fun noArtistIsStoredAsNone() {
        val e = Song("local:3", "Track 01").toEntity()
        assertEquals("", e.primaryArtist)
        assertEquals("Unknown artist", e.artistName) // what it shows
        assertTrue(e.toSong().artists.isEmpty())
    }

    @Test
    fun oldRewindSnapshotsStillFindTheMainArtist() {
        fun plays(artist: String, primary: String = "") = SongPlays("x", 1, 1, "t", artist, null, null, 0, null, primary)
        assertEquals("The Kid LAROI", plays("The Kid LAROI, Justin Bieber").mainArtist)
        assertEquals("", plays("Unknown artist").mainArtist)
        assertEquals("Tyler, The Creator", plays("Tyler, The Creator", "Tyler, The Creator").mainArtist)
    }

    @Test
    fun everyMixKeepsItsOwnCover() {
        val supermix = "https://music.youtube.com/image/mixart?r=EnsKAhgBEgQIARAB"
        val mix1 = "https://music.youtube.com/image/mixart?r=EnsKAhgBEgQIAhAB"
        assertNotEquals(ArtworkStore.keyOf(supermix), ArtworkStore.keyOf(mix1))
        // Google's own image addresses are still one cover at any size, and a video's thumbnails one per video.
        assertEquals(
            ArtworkStore.keyOf("https://lh3.googleusercontent.com/abc123=w544-h544-l90-rj"),
            ArtworkStore.keyOf("https://lh3.googleusercontent.com/abc123=w1080-h1080-l90-rj"),
        )
        assertEquals(
            ArtworkStore.keyOf("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=abc"),
            ArtworkStore.keyOf("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"),
        )
    }
}
