package com.prism.music

import com.prism.music.data.canvas.CanvasArtwork
import com.prism.music.ui.player.ClipState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #384 (1.8.3): the next song's animated cover never showed. The clip's state was made anew per song, but the player's
 * and the video view's listeners, set up once, kept writing the first song's. One [ClipState] now serves every clip,
 * so listeners bound at the start reach the clip showing now.
 */
class CanvasClipStateTest {
    private val a = CanvasArtwork("https://clips/a.mp4")
    private val b = CanvasArtwork("https://clips/b.mp4", fallbackUrl = "https://clips/b.m3u8")

    @Test fun listenersBoundForTheFirstClipShowTheNext() {
        val clip = ClipState(a)
        // Bound once, as CanvasPlayer's player and TextureView listeners are.
        val onSize: (Float, Int) -> Unit = { w, h -> clip.videoSize(w, h) }
        val onFrame: () -> Boolean = { clip.frame() }
        onSize(720f, 1280); assertTrue(onFrame()); assertTrue(clip.rendered)

        clip.show(b)
        assertEquals(b.url, clip.url)
        assertFalse("hidden until B's own frame", clip.rendered)
        assertFalse("a frame before B's size is known doesn't show it", onFrame())
        onSize(1080f, 1920)
        assertTrue("B's first frame shows it", onFrame())
        assertTrue(clip.rendered)
    }

    @Test fun theSameClipAgainChangesNothing() {
        val clip = ClipState(a)
        clip.videoSize(720f, 1280); clip.frame()
        clip.show(a.copy())
        assertTrue(clip.rendered)
    }

    /** Review of 1.8.3: an album's one clip for every track (another title, the same address) plays on, still shown. */
    @Test fun theSameClipUnderTheNextSongStaysShown() {
        val album = CanvasArtwork("https://clips/album.mp4", title = "Track 1")
        val clip = ClipState(album)
        clip.videoSize(720f, 1280); clip.frame()
        clip.show(album.copy(title = "Track 2"))
        assertTrue("nothing loads again to show it, so it mustn't hide", clip.rendered)
        assertEquals(album.url, clip.url)
    }

    @Test fun aFailedClipTriesItsOtherRenditionThenGivesUp() {
        val clip = ClipState(a)
        clip.show(b)
        clip.failed(); assertEquals(b.fallbackUrl, clip.url)
        clip.videoSize(1f, 1); clip.frame()
        clip.failed(); assertFalse(clip.rendered); assertEquals(b.fallbackUrl, clip.url)
    }
}
