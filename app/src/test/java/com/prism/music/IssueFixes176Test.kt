package com.prism.music

import android.net.Uri
import com.prism.music.data.local.LocalMusic
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.stream.isOnAir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub issues fixed for 1.7.6. (Robolectric: local tracks carry an android.net.Uri.) */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class IssueFixes176Test {
    /** #128: a recorded broadcast (podcast episode) isn't a live stream; one on air is. Values as YouTube sent them. */
    @Test
    fun recordedBroadcastsAreNotLive() {
        // A 24/7 radio on air: isLive, live content, an HLS manifest, no length.
        assertTrue(isOnAir(isLive = true, liveContent = true, hasHls = true, lengthSec = 0))
        // A podcast episode streamed live once: live content and a manifest still, but a length and no isLive.
        assertFalse(isOnAir(isLive = false, liveContent = true, hasHls = true, lengthSec = 3620))
        // On air but answered without isLive by some client: no length gives it away.
        assertTrue(isOnAir(isLive = false, liveContent = true, hasHls = true, lengthSec = 0))
        assertFalse(isOnAir(isLive = false, liveContent = false, hasHls = false, lengthSec = 200))
    }

    /** H1: your own Home sections: a pinned item opens its page, a renamed section goes by its new name. */
    @Test
    fun ownHomeSections() {
        val album = com.prism.music.data.model.AlbumItem("MPREb_x", "Thriller", "Album • Michael Jackson", null, "OLAK5uy_y")
        val pin = com.prism.music.ui.screens.OwnSections.pinOf(album)!!
        assertEquals("pin:album:MPREb_x", pin.key)
        assertEquals(com.prism.music.ui.Routes.album("MPREb_x"), com.prism.music.ui.screens.OwnSections.pageOf(pin.key))
        assertTrue(com.prism.music.ui.screens.OwnSections.isOwn(com.prism.music.data.prefs.HomeSections.yearKey(2025)))
        assertEquals("Thriller", pin.titled())
        assertEquals("Mine", pin.copy(name = "Mine").titled())
        assertEquals("Quick picks", com.prism.music.data.prefs.HomeSectionConfig("local:quick_picks", "Quick picks").titled("Quick picks"))
    }

    private fun track(id: Long, albumId: Long, album: String?, artist: String) = LocalMusic.Track(
        Song(id = Song.LOCAL_PREFIX + id, title = "Song $id", artists = listOf(ArtistRef(artist)), album = album?.let { AlbumRef(it) }),
        Uri.parse("content://media/external/audio/media/$id"), albumId, 1, 2020, 0, "audio/mpeg", 1,
    )

    /** #129: songs Android puts under one album that aren't one album read their own covers; real albums keep one. */
    @Test
    fun mixedAlbumsAreFound() {
        val mixed = LocalMusic.mixedAlbums(listOf(
            // A real album, and a compilation: one cover each is right.
            track(1, 10, "Homogenic", "Björk"), track(2, 10, "Homogenic", "Björk"),
            // Untagged songs from one folder, filed by Android as one album.
            track(3, 20, null, "Björk"), track(4, 20, null, "Radiohead"),
            // A downloader's catch-all album name over different artists.
            track(5, 30, "Downloads", "Björk"), track(6, 30, "Downloads", "Daft Punk"),
            // Different album names under one id.
            track(7, 40, "OK Computer", "Radiohead"), track(8, 40, "Kid A", "Radiohead"),
            // A single song is its own album whatever it says.
            track(9, 50, null, "Moby"),
        ))
        assertEquals(setOf(20L, 30L, 40L), mixed)
    }
}
