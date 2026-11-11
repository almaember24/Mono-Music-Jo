package com.prism.music

import android.os.Bundle
import android.provider.MediaStore
import com.prism.music.playback.VoiceRequest
import com.prism.music.playback.VoiceSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Play … on Prism" (1.8.1, Reddit: Android Auto's voice button did nothing). What the Assistant sends (the words, and
 * the voice extras saying what kind of thing it heard) becomes what to play, for each kind it can say.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoiceSearchTest {
    private fun parse(query: String?, focus: String? = null, title: String? = null, artist: String? = null, album: String? = null, genre: String? = null, playlist: String? = null) =
        VoiceSearch.parse(query, focus, title, artist, album, genre, playlist)

    @Test fun nothingNamedCarriesOn() {
        assertEquals(VoiceRequest.Resume, parse(null))
        assertEquals(VoiceRequest.Resume, parse(""))
        assertEquals(VoiceRequest.Resume, parse("  ", VoiceSearch.FOCUS_ANY))
        // "Play music on Prism" sends no words with the "any kind" focus.
        assertEquals(VoiceRequest.Resume, parse("on Prism", VoiceSearch.FOCUS_ANY))
    }

    @Test fun anArtistPlaysTheArtist() {
        assertEquals(VoiceRequest.Artist("Taylor Swift"), parse("taylor swift", VoiceSearch.FOCUS_ARTIST, artist = "Taylor Swift"))
        // The focus without the artist's own extra: the words are the name.
        assertEquals(VoiceRequest.Artist("Daft Punk"), parse("Daft Punk", VoiceSearch.FOCUS_ARTIST))
    }

    @Test fun anAlbumPlaysTheAlbum() {
        assertEquals(VoiceRequest.Album("Midnights", "Taylor Swift"), parse("midnights by taylor swift", VoiceSearch.FOCUS_ALBUM, album = "Midnights", artist = "Taylor Swift"))
        assertEquals(VoiceRequest.Album("Random Access Memories", null), parse("Random Access Memories", VoiceSearch.FOCUS_ALBUM))
    }

    @Test fun aSongIsLookedUpByTitleAndArtist() {
        assertEquals(VoiceRequest.Song("Anti-Hero Taylor Swift"), parse("anti hero by taylor swift", VoiceSearch.FOCUS_SONG, title = "Anti-Hero", artist = "Taylor Swift"))
        assertEquals(VoiceRequest.Song("Get Lucky"), parse("Get Lucky", VoiceSearch.FOCUS_SONG))
    }

    @Test fun aGenreIsLookedUpAsASong() {
        assertEquals(VoiceRequest.Song("jazz"), parse("jazz", VoiceSearch.FOCUS_GENRE, genre = "jazz"))
    }

    @Test fun aPlaylistIsLookedUpByName() {
        assertEquals(VoiceRequest.Playlist("Road trip"), parse("road trip playlist", VoiceSearch.FOCUS_PLAYLIST, playlist = "Road trip"))
        assertEquals(VoiceRequest.Playlist("workout"), parse("workout", VoiceSearch.FOCUS_PLAYLIST))
    }

    @Test fun yourLikedSongsAndDownloads() {
        assertEquals(VoiceRequest.Liked(), parse("my liked songs"))
        assertEquals(VoiceRequest.Liked(), parse("liked songs", VoiceSearch.FOCUS_PLAYLIST, playlist = "liked songs"))
        assertEquals(VoiceRequest.Liked(), parse("my favorites"))
        assertEquals(VoiceRequest.Liked(true), parse("shuffle my liked songs"))
        assertEquals(VoiceRequest.Downloads(), parse("my downloads on Prism"))
    }

    @Test fun anythingElseIsSearchsTopResult() {
        assertEquals(VoiceRequest.Top("bohemian rhapsody"), parse("bohemian rhapsody"))
        assertEquals(VoiceRequest.Top("bohemian rhapsody"), parse("bohemian rhapsody", VoiceSearch.FOCUS_ANY))
        // What the Assistant sometimes leaves in.
        assertEquals(VoiceRequest.Top("blinding lights"), parse("play blinding lights on Prism"))
        assertEquals(VoiceRequest.Top("the weeknd"), parse("shuffle the weeknd").let { (it as VoiceRequest.Top).copy(shuffle = false) })
        assertTrue(parse("shuffle the weeknd").shuffle)
    }

    @Test fun thePartsAloneAreEnough() {
        assertEquals(VoiceRequest.Album("Midnights", "Taylor Swift"), parse(null, album = "Midnights", artist = "Taylor Swift"))
        assertEquals(VoiceRequest.Artist("Adele"), parse(null, artist = "Adele"))
        assertEquals(VoiceRequest.Song("Hello Adele"), parse(null, title = "Hello", artist = "Adele"))
    }

    @Test fun theExtrasComeFromABundle() {
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_ARTIST, "Adele")
            putInt("unrelated", 3)
        }
        assertEquals(VoiceRequest.Artist("Adele"), VoiceSearch.parse("adele", extras))
        // The phone's activity passes only the voice extras on to the service.
        val copied = VoiceSearch.extrasOf(extras)!!
        assertEquals(setOf(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.EXTRA_MEDIA_ARTIST), copied.keySet())
        assertNull(VoiceSearch.extrasOf(Bundle()))
    }

    @Test fun playlistNamesMatchAsSpoken() {
        assertEquals(3, VoiceSearch.names("Road Trip", "road trip"))
        assertEquals(3, VoiceSearch.names("Road trip", "my road trip playlist"))
        assertEquals(2, VoiceSearch.names("Road trip 2026", "road trip"))
        assertEquals(0, VoiceSearch.names("Workout", "road trip"))
        assertEquals(3, VoiceSearch.names("Café del Mar", "cafe del mar"))
    }
}
