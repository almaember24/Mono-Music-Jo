package com.prism.music

import com.prism.music.data.SongVersions
import com.prism.music.data.shown
import com.prism.music.playback.toMediaItem
import com.prism.music.playback.toSong
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** "Play a different version" (Reddit 1.7.9): which recordings are offered, and that a pick is kept and taken back. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SongVersionsTest {
    private fun song(id: String, title: String, artist: String, video: Boolean = false, album: String? = null) =
        Song(id, title, listOf(ArtistRef(artist)), album?.let { AlbumRef(it) }, 200, isVideo = video)

    @Test
    fun otherRecordingsOfTheSameTitleAreOffered() {
        val mine = song("a", "Night Drive", "The Band", album = "Roads")
        val results = listOf(
            mine,
            song("b", "Night Drive (Acoustic Guitar Cover)", "Some Guy"),
            song("c", "Night Drive - Remastered 2011", "The Band", album = "Roads (Deluxe)"),
            song("d", "Night Drive (Official Video)", "The Band", video = true),
            song("e", "Night Drives Home", "The Band"),
            song("c", "Night Drive - Remastered 2011", "The Band", album = "Roads (Deluxe)"),
        )
        // Not the song itself, nor another title; one of each; its own artist first, songs before videos.
        assertEquals(listOf("c", "d", "b"), SongVersions.candidates(mine, results).map { it.id })
    }

    @Test
    fun editsComeLastAndReuploadsAreNamed() {
        val mine = song("a", "Night Drive", "The Band", album = "Roads")
        val results = listOf(
            song("s", "Night Drive (Slowed + Reverb)", "The Band", video = true),
            song("n", "night drive - sped up//nightcore", "Someone", video = true),
            song("x", "Night Drive", "Another Band"),
            song("r", "Night Drive", "The Band", album = "Roads"),
        )
        assertEquals(listOf("r", "x", "s", "n"), SongVersions.candidates(mine, results).map { it.id })
        assertTrue(SongVersions.sameRecording(mine, results[3]))
        assertFalse(SongVersions.sameRecording(mine, results[2]))
    }

    @Test
    fun aPickIsKeptAndCanBeTakenBack() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("song_versions_test", 0)
        prefs.edit().clear().commit()
        val v = SongVersions(prefs)
        v.set("a", SongVersions.Pick("c", "The Band · Roads (Deluxe) · 3:20"))
        assertEquals("c", SongVersions(prefs).of("a"))
        assertEquals("The Band · Roads (Deluxe) · 3:20", SongVersions(prefs).picks.value["a"]?.label)
        v.set("a", null)
        assertNull(SongVersions(prefs).of("a"))
        // Picking the song itself is no pick.
        v.set("a", SongVersions.Pick("a", ""))
        assertNull(v.of("a"))
    }

    /** #328: a picked version shows in the song's place (cover, title, artists, album, length); the song stays itself. */
    @Test
    fun aPickedVersionShowsInTheSongsPlace() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("song_versions_test", 0)
        prefs.edit().clear().commit()
        app.getSharedPreferences("playlist_covers", 0).edit().clear().commit()
        val covers = com.prism.music.data.PlaylistCovers(app)
        val v = SongVersions(prefs)
        val video = Song("vid", "Night Drive (Official Video)", listOf(ArtistRef("TheBandVEVO")), null, 250, "https://i.ytimg.com/vi/vid/hqdefault.jpg", isVideo = true)
        val official = Song(
            "off", "Night Drive", listOf(ArtistRef("The Band", "UCband")), AlbumRef("Roads", "MPREb_roads"), 212,
            "https://lh3.googleusercontent.com/roads=w120-h120", explicit = true,
        )
        try {
            // Nothing picked: the song as it is, everywhere.
            assertEquals(video, v.shown(video))
            assertEquals(video.thumbnail, covers.art(video))
            v.set("vid", SongVersions.Pick("off", "The Band · Roads · 3:32", official))
            val shown = video.shown
            // Its id and what it is stay its own (likes, playlists and history name it); the rest is the version's.
            assertEquals("vid", shown.id)
            assertTrue(shown.isVideo)
            assertEquals("Night Drive", shown.title)
            assertEquals(official.artists, shown.artists)
            assertEquals(official.album, shown.album)
            assertEquals(212, shown.durationSec)
            assertTrue(shown.explicit)
            assertEquals(official.thumbnail, covers.art(video))
            assertEquals(official.thumbnail, com.prism.music.data.PlaylistCovers.artOf(video))
            // The session (notification, car, widgets) shows the version; the item still carries the song itself.
            val item = video.toMediaItem()
            assertEquals("Night Drive", item.mediaMetadata.title)
            assertEquals("The Band", item.mediaMetadata.artist)
            assertEquals("Roads", item.mediaMetadata.albumTitle)
            assertEquals("vid", item.mediaId)
            assertEquals(video, item.toSong())
            // Kept on the phone with the version's details.
            assertEquals(official, SongVersions(prefs).picks.value["vid"]?.song)
            SongVersions.shared = v
            // A cover picked for the song itself still wins over the version's.
            covers.useImage(covers.songKey("vid"), "https://example.org/mine.jpg")
            assertEquals("https://example.org/mine.jpg", covers.art(video))
            covers.reset(covers.songKey("vid"))
            // "Use the original" puts everything back.
            v.set("vid", null)
            assertEquals(video, v.shown(video))
            assertEquals(video.thumbnail, covers.art(video))
            assertEquals("Night Drive (Official Video)", video.toMediaItem().mediaMetadata.title)
        } finally {
            v.set("vid", null)
        }
    }

    /** A pick kept before 1.8.1.1 (id and label only) still plays, and shows as the song. */
    @Test
    fun anOlderPickShowsAsTheSong() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("song_versions_test", 0)
        prefs.edit().clear().putString("a", "c\tThe Band · Roads (Deluxe) · 3:20").commit()
        val v = SongVersions(prefs)
        try {
            val mine = song("a", "Night Drive", "The Band", album = "Roads")
            assertEquals("c", v.of("a"))
            assertNull(v.picks.value["a"]?.song)
            assertEquals(mine, v.shown(mine))
        } finally {
            v.set("a", null)
        }
    }

    /** #328's comment: a video playing as its own sound gets its song's cover and, when it has none, its album. */
    @Test
    fun aVideoPlayingAsItselfGetsItsSongsCoverAndAlbum() {
        val video = Song("vid", "Night Drive (Official Video)", listOf(ArtistRef("The Band")), null, 250, "https://i.ytimg.com/vi/vid/hqdefault.jpg", isVideo = true)
        val song = Song("s", "Night Drive", listOf(ArtistRef("The Band")), AlbumRef("Roads", "MPREb_roads"), 212, "https://lh3.googleusercontent.com/roads")
        val shown = com.prism.music.playback.VideoCover.withSong(video, song, song.thumbnail!!)
        assertEquals(song.thumbnail, shown.thumbnail)
        assertEquals(song.album, shown.album)
        // What plays is still the video: its title, artists, length and id.
        assertEquals(video.copy(thumbnail = song.thumbnail, album = song.album), shown)
        // An album of its own is kept.
        val withAlbum = video.copy(album = AlbumRef("Live", "MPREb_live"))
        assertEquals(withAlbum.album, com.prism.music.playback.VideoCover.withSong(withAlbum, song, song.thumbnail!!).album)
    }
}
