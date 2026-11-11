package com.prism.music

import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.HomeHeader
import com.prism.music.data.prefs.LyricsBackground
import com.prism.music.data.prefs.LyricsStyle
import com.prism.music.data.prefs.PlayerBackground
import com.prism.music.data.prefs.PlayerLayout
import com.prism.music.data.prefs.VisualizerStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Performance mode (1.7.6): what it draws lighter, and what it leaves exactly as the listener set it. */
class PerformanceModeTest {
    private val heavy = AppSettings(
        frostedGlass = true, liquidGlass = true, frostedPages = true, backdrops = mapOf("HOME" to "CONSTELLATION"),
        canvasEnabled = true, visualizer = VisualizerStyle.GLOW, meshSpeed = 0.8f, playerBackground = PlayerBackground.BLUR,
        lyricsLook = com.prism.music.data.prefs.LyricsLookPrefs(
            style = LyricsStyle.GLOW, blurAmount = 1.5f, background = LyricsBackground.CANVAS, spacing = 1.4f,
        ),
        homeHeader = HomeHeader.PICTURE, playerLayout = PlayerLayout.VINYL,
    )

    @Test
    fun offChangesNothing() {
        assertSame(heavy, heavy.effective())
    }

    @Test
    fun onDrawsLighter() {
        val e = heavy.copy(performanceMode = true).effective()
        assertFalse(e.frostedGlass || e.liquidGlass || e.frostedPages || e.canvasEnabled)
        assertTrue(e.backdrops.isEmpty())
        assertTrue(e.reduceMotion)
        // Your own photo stays (1.8.1, Reddit: "only a black background now"): a still picture is cheap to draw.
        val photo = heavy.copy(performanceMode = true, backdrops = mapOf("HOME" to "PHOTO|/data/p.jpg", "SEARCH" to "SILK", "PLAYER" to "PHOTO|/data/q.jpg")).effective()
        assertEquals(mapOf("HOME" to "PHOTO|/data/p.jpg", "PLAYER" to "PHOTO|/data/q.jpg"), photo.backdrops)
        assertEquals(VisualizerStyle.OFF, e.visualizer)
        assertEquals(0f, e.meshSpeed)
        assertEquals(PlayerBackground.MESH, e.playerBackground)
        // 1.8.2: the Cover gradient stays, with nothing in it that's live: one still shader, not a blur.
        assertEquals(PlayerBackground.GRADIENT, heavy.copy(performanceMode = true, playerBackground = PlayerBackground.GRADIENT).effective().playerBackground)
        // 1.8.0: Glow's halo goes; its sweep (no layer, no blur) stays.
        assertEquals(false, e.lyricsLook.haloNow)
        assertEquals(com.prism.music.data.prefs.LyricsMotion.SWEEP, e.lyricsLook.motionNow)
        assertEquals(0f, e.lyricsBlurAmount)
        assertEquals(LyricsBackground.PLAYER, e.lyricsBackground)
        assertEquals(HomeHeader.GREETING, e.homeHeader)
    }

    @Test
    fun featuresAndLayoutStay() {
        val e = heavy.copy(performanceMode = true).effective()
        assertEquals(PlayerLayout.VINYL, e.playerLayout)
        assertEquals(1.4f, e.lyricsSpacing)
        assertEquals(heavy.homeLayout, e.homeLayout)
        assertEquals(heavy.navTabs, e.navTabs)
    }
}
