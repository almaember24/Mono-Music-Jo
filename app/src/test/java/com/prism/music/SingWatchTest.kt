package com.prism.music

import androidx.media3.common.Player
import com.prism.music.playback.sing.PcmDecoder
import com.prism.music.playback.sing.SingWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sing on a Google TV Streamer (1.8.1, Reddit): "Whenever I try to remove the vocals, the music stops playing and the
 * player gets stuck loading … I have to uninstall and reinstall the app to restore playback." What keeps that from
 * happening again: the watchdog's rules, Sing's decoder (never the one the player needs), the song cache never joining
 * two formats, and an engine that took Prism down not loaded again.
 */
class SingWatchTest {
    @Test fun aPlayerLoadingTooLongWhileSingWorksHasSingLetGo() {
        val w = SingWatch(12_000)
        assertFalse(w.check(stuck = true, nowMs = 1_000))
        assertFalse(w.check(stuck = true, nowMs = 12_999))
        assertEquals(11_999, w.stuckFor(12_999))
        assertTrue(w.check(stuck = true, nowMs = 13_000))
        // Playing again in between starts the count afresh.
        w.reset()
        assertFalse(w.check(stuck = true, nowMs = 20_000))
        assertFalse(w.check(stuck = false, nowMs = 25_000))
        assertEquals(0, w.stuckFor(25_000))
        assertFalse(w.check(stuck = true, nowMs = 30_000))
        assertFalse(w.check(stuck = true, nowMs = 41_999))
        assertTrue(w.check(stuck = true, nowMs = 42_000))
    }

    @Test fun stuckIsLoadingOrStoppedOnAnErrorWhileItShouldPlay() {
        assertTrue(SingWatch.stuck(playWhenReady = true, state = Player.STATE_BUFFERING, error = false))
        assertTrue(SingWatch.stuck(playWhenReady = true, state = Player.STATE_IDLE, error = true))
        // Paused by the listener, playing, stopped on purpose, or at the end: not stuck.
        assertFalse(SingWatch.stuck(playWhenReady = false, state = Player.STATE_BUFFERING, error = false))
        assertFalse(SingWatch.stuck(playWhenReady = true, state = Player.STATE_READY, error = false))
        assertFalse(SingWatch.stuck(playWhenReady = true, state = Player.STATE_IDLE, error = false))
        assertFalse(SingWatch.stuck(playWhenReady = true, state = Player.STATE_ENDED, error = false))
    }

    @Test fun onlyAPlayerStuckWhileSingWorksIsCounted() {
        assertTrue(SingWatch.counts(busy = true, playWhenReady = true, state = Player.STATE_BUFFERING, error = false))
        assertTrue(SingWatch.counts(busy = true, playWhenReady = true, state = Player.STATE_IDLE, error = true))
        // An error with Sing idle (offline, a 403) is the playback service's to retry or skip: Sing stays out of it.
        assertFalse(SingWatch.counts(busy = false, playWhenReady = true, state = Player.STATE_IDLE, error = true))
        assertFalse(SingWatch.counts(busy = false, playWhenReady = true, state = Player.STATE_BUFFERING, error = false))
    }

    @Test fun anEngineThatTookPrismDownTwiceIsntLoadedAgainOnThisVersion() {
        assertFalse(SingWatch.engineBroken(unfinished = 0, brokenOn = 0, version = 55))
        // Once could be Android ending Prism for memory as the engine loaded: tried again.
        assertFalse(SingWatch.engineBroken(unfinished = 1, brokenOn = 0, version = 55))
        assertTrue(SingWatch.engineBroken(unfinished = 2, brokenOn = 0, version = 55))
        assertTrue(SingWatch.engineBroken(unfinished = 0, brokenOn = 55, version = 55))
        // A new version tries it again.
        assertFalse(SingWatch.engineBroken(unfinished = 0, brokenOn = 55, version = 56))
    }

    @Test fun singDecodesWithASoftwareDecoderFirst() {
        // As a TV lists them: its own (one of a kind) first, Android's software one after.
        val tv = listOf("c2.mtk.aac.decoder" to false, "OMX.dolby.aac.decoder" to false, "c2.android.aac.decoder" to true)
        assertEquals(listOf("c2.android.aac.decoder", "c2.mtk.aac.decoder", "OMX.dolby.aac.decoder"), PcmDecoder.softwareFirst(tv))
        // Before Android 10 there's no "software only" flag: known by name.
        val old = listOf("OMX.MTK.AUDIO.DECODER.OPUS" to false, "OMX.google.opus.decoder" to false)
        assertEquals(listOf("OMX.google.opus.decoder", "OMX.MTK.AUDIO.DECODER.OPUS"), PcmDecoder.softwareFirst(old))
        assertTrue(PcmDecoder.isSoftwareName("c2.android.opus.decoder"))
        assertFalse(PcmDecoder.isSoftwareName("c2.dolby.ac4.decoder"))
    }

    @Test fun theSongCacheNeverJoinsTwoFormats() {
        // Nothing cached yet: any format.
        assertTrue(AppContainer.mayAddFormat(before = 0, itag = 251, cached = false))
        assertTrue(AppContainer.mayAddFormat(before = 140, itag = 251, cached = false))
        // The same format as what's there: added to.
        assertTrue(AppContainer.mayAddFormat(before = 251, itag = 251, cached = true))
        // Another format, or one never recorded: left alone.
        assertFalse(AppContainer.mayAddFormat(before = 140, itag = 251, cached = true))
        assertFalse(AppContainer.mayAddFormat(before = 0, itag = 251, cached = true))
    }
}
