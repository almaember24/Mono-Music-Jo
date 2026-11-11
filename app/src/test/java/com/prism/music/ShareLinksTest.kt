package com.prism.music

import com.prism.music.data.ShareLinks
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Links Prism shares, and the ones shared to it from other apps. */
class ShareLinksTest {
    @Test
    fun linksForWhatPrismShares() {
        assertEquals("https://music.youtube.com/watch?v=abc", ShareLinks.song("abc"))
        assertEquals("https://music.youtube.com/browse/MPREb_1", ShareLinks.of(AlbumItem("MPREb_1", "A", "", null)))
        assertEquals("https://music.youtube.com/playlist?list=PL1", ShareLinks.of(PlaylistItem("VLPL1", "P", "", null)))
        assertEquals("https://music.youtube.com/channel/UC1", ShareLinks.of(ArtistItem("UC1", "Artist", "", null)))
        // Mixes are made for whoever opens them: nothing to share.
        assertNull(ShareLinks.of(PlaylistItem("RDAMVMabc", "Mix", "", null, isMix = true)))
    }

    @Test
    fun linksSharedToPrism() {
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", ShareLinks.findIn("Check this out https://youtu.be/dQw4w9WgXcQ?si=xyz"))
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", ShareLinks.findIn("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"))
        assertEquals("https://music.youtube.com/watch?v=abc123", ShareLinks.findIn("https://m.youtube.com/shorts/abc123"))
        assertEquals("https://music.youtube.com/playlist?list=PLx", ShareLinks.findIn("(https://youtube.com/playlist?list=PLx)"))
        assertEquals("https://music.youtube.com/channel/UCabc", ShareLinks.findIn("https://www.youtube.com/channel/UCabc"))
        // YouTube Music links go through as they are.
        assertEquals("https://music.youtube.com/browse/MPREb_1", ShareLinks.findIn("Listen: https://music.youtube.com/browse/MPREb_1."))
        assertNull(ShareLinks.findIn("https://example.com/watch?v=abc"))
        assertNull(ShareLinks.findIn("no links here"))
        assertNull(ShareLinks.findIn(null))
    }
}
