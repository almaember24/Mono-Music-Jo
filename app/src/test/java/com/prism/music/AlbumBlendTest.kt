package com.prism.music

import com.prism.music.playback.blendsAcrossAlbum
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #247: an album's songs kept whole. As before an album playing through never blends; the option keeps them whole anywhere. */
class AlbumBlendTest {
    @Test fun anAlbumPlayingThroughNeverBlends() {
        assertFalse(blendsAcrossAlbum("A", "A", albumPlaying = true, keepAlbums = false, dj = false))
    }

    @Test fun twoSongsOfOneAlbumInAPlaylistBlendAsBefore() {
        assertTrue(blendsAcrossAlbum("A", "A", albumPlaying = false, keepAlbums = false, dj = false))
    }

    @Test fun keepAlbumsKeepsThemWholeAnywhere() {
        assertFalse(blendsAcrossAlbum("A", "A", albumPlaying = false, keepAlbums = true, dj = false))
        // Songs of different albums still blend.
        assertTrue(blendsAcrossAlbum("A", "B", albumPlaying = false, keepAlbums = true, dj = false))
        // A song with no album isn't held back.
        assertTrue(blendsAcrossAlbum(null, null, albumPlaying = false, keepAlbums = true, dj = false))
    }

    @Test fun aDjSetAlwaysBlends() {
        assertTrue(blendsAcrossAlbum("A", "A", albumPlaying = true, keepAlbums = true, dj = true))
    }
}
