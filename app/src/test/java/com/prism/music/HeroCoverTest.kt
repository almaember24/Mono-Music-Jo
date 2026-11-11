package com.prism.music

import com.prism.music.ui.player.heroTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #244 (1.7.9.3): with full-bleed artwork, every new song dropped the cover to the square one while its picture loaded,
 * then grew it back. The cover stays up as long as there's a picture to show: this song's, or the last song's meanwhile.
 */
class HeroCoverTest {
    @Test fun theCoverStaysUpWhileTheNextPictureLoads() {
        // The next song's picture isn't in yet (loaded = false), but the last one is still there to show.
        assertTrue(heroTarget(heroMode = true, picture = true, canvas = false))
    }

    /** Reddit (1.7.9.3): "Shrink when paused" did nothing in Performance mode, which turns Reduce motion on. */
    @Test fun performanceModeKeepsTheShrink() {
        val stored = com.prism.music.data.prefs.AppSettings(performanceMode = true, artShrinkOnPause = true)
        assertTrue("Performance mode does force Reduce motion", stored.effective().reduceMotion)
        // What the player now asks: the listener's own choice, which is off here.
        org.junit.Assert.assertEquals(0.86f, com.prism.music.ui.player.pausedCoverScale(playing = false, shrinkWhenPaused = true, ownReduceMotion = stored.reduceMotion), 0.001f)
        org.junit.Assert.assertEquals(1f, com.prism.music.ui.player.pausedCoverScale(playing = false, shrinkWhenPaused = true, ownReduceMotion = true), 0.001f)
        org.junit.Assert.assertEquals(1f, com.prism.music.ui.player.pausedCoverScale(playing = true, shrinkWhenPaused = true, ownReduceMotion = false), 0.001f)
    }

    @Test fun nothingToShowYetOrNotFullBleed() {
        assertFalse("the very first picture hasn't arrived", heroTarget(heroMode = true, picture = false, canvas = false))
        assertTrue("an animated cover counts", heroTarget(heroMode = true, picture = false, canvas = true))
        assertFalse("not in full-bleed mode", heroTarget(heroMode = false, picture = true, canvas = true))
    }
}
