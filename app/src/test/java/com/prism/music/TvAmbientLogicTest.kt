package com.prism.music

import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.tv.ambientAllowed
import com.prism.music.ui.tv.ambientDrift
import com.prism.music.ui.tv.ambientDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Prism TV, slice 7: when the ambient screen comes, how far it drifts, and desk mode off by default. */
class TvAmbientLogicTest {
    @Test fun theAmbientScreenComesOnlyWhenTheRoomIsQuietAndMusicPlays() {
        val three = 3 * 60_000L
        assertTrue(ambientDue(playing = true, allowed = true, minutes = 3, idleMs = three))
        assertFalse(ambientDue(playing = true, allowed = true, minutes = 3, idleMs = three - 1))
        // Paused: never (the TV may sleep then).
        assertFalse(ambientDue(playing = false, allowed = true, minutes = 3, idleMs = 10 * three))
        // Switched off (0), or not allowed (a phone without desk mode, or not charging).
        assertFalse(ambientDue(playing = true, allowed = true, minutes = 0, idleMs = 10 * three))
        assertFalse(ambientDue(playing = true, allowed = false, minutes = 3, idleMs = 10 * three))
    }

    /** The review (1.8.0): "Never" kept a TV's screen on for good; a video or Sing could be covered. */
    @Test fun neverMeansTheTvsOwnScreensaverAndVideosAndSingAreNeverCovered() {
        assertTrue(ambientAllowed(onTv = true, minutes = 3, deskMode = false, charging = false, video = false, singing = false))
        // Never: not allowed at all, so the screen isn't kept on (the TV's screensaver comes as usual).
        assertFalse(ambientAllowed(onTv = true, minutes = 0, deskMode = false, charging = false, video = false, singing = false))
        assertFalse(ambientAllowed(onTv = true, minutes = 3, deskMode = false, charging = false, video = true, singing = false))
        assertFalse(ambientAllowed(onTv = true, minutes = 3, deskMode = false, charging = false, video = false, singing = true))
        // A phone: only in desk mode while charging.
        assertTrue(ambientAllowed(onTv = false, minutes = 3, deskMode = true, charging = true, video = false, singing = false))
        assertFalse(ambientAllowed(onTv = false, minutes = 3, deskMode = true, charging = false, video = false, singing = false))
        assertFalse(ambientAllowed(onTv = false, minutes = 3, deskMode = false, charging = true, video = false, singing = false))
    }

    /** The owner: the TV's own screensaver covered a playing video. A video or Sing playing keeps a TV's screen on. */
    @Test fun aVideoOrSingPlayingKeepsTheTvAwake() {
        // Music with the ambient screen allowed: on; paused: the screen may sleep.
        assertTrue(com.prism.music.ui.tv.keepScreenOn(playing = true, allowed = true, onTv = true, video = false, singing = false))
        assertFalse(com.prism.music.ui.tv.keepScreenOn(playing = false, allowed = true, onTv = true, video = false, singing = false))
        // A video or Sing on a TV (the ambient screen isn't allowed over them): on while playing, not paused.
        assertTrue(com.prism.music.ui.tv.keepScreenOn(playing = true, allowed = false, onTv = true, video = true, singing = false))
        assertTrue(com.prism.music.ui.tv.keepScreenOn(playing = true, allowed = false, onTv = true, video = false, singing = true))
        assertFalse(com.prism.music.ui.tv.keepScreenOn(playing = false, allowed = false, onTv = true, video = true, singing = false))
        // Ambient set to Never on a TV, just music: the TV's own screensaver, as before.
        assertFalse(com.prism.music.ui.tv.keepScreenOn(playing = true, allowed = false, onTv = true, video = false, singing = false))
        // A phone isn't kept on here for a video (its player does that itself).
        assertFalse(com.prism.music.ui.tv.keepScreenOn(playing = true, allowed = false, onTv = false, video = true, singing = false))
        // The ambient screen still never comes over them.
        assertFalse(ambientDue(playing = true, allowed = ambientAllowed(true, 3, false, false, video = true, singing = false), minutes = 3, idleMs = 10 * 60_000L))
    }

    @Test fun itDriftsAFewDpAndKeepsMoving() {
        val points = (0 until 600 step 7).map { ambientDrift(it * 1_000L) }
        assertTrue(points.all { abs(it.x.value) <= 14f && abs(it.y.value) <= 10f })
        // It moves (burn-in safe): it isn't at one place for long.
        assertTrue(points.map { it.x.value.toInt() to it.y.value.toInt() }.distinct().size > 20)
    }

    @Test fun deskModeIsOffAndTheTvAmbientScreenOnByDefault() {
        val tv = AppSettings().tv
        assertFalse(tv.deskMode)
        assertEquals(3, tv.ambientMinutes)
        assertEquals(1f, tv.lyricsScale, 0f)
    }
}
