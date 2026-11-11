package com.prism.music

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.prism.music.data.AutoplayLength
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.DeckPart
import com.prism.music.playback.VideoOnData
import com.prism.music.ui.player.ControlsOnScroll
import com.prism.music.ui.player.CoverFlight
import com.prism.music.ui.player.LYRICS_RETURN_MS
import com.prism.music.ui.player.PlayerPage
import com.prism.music.ui.player.deckSpacing
import com.prism.music.ui.player.lyricsReturnDelayMs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of 1.8.2's player requests (#299 and on). */
class PlayerRequests182Test {
    private fun song(id: String, seconds: Int) = Song(id = id, title = id, durationSec = seconds)

    /** #299: no limit is what everyone has today, so it must leave the list alone. */
    @Test fun autoplayWithoutALimitAddsWhatItAlwaysDid() {
        val songs = listOf(song("short", 180), song("mix", 3 * 3600))
        assertEquals(songs, AutoplayLength.keep(songs, 0))
    }

    /** #299: long ones go, in order, and exactly the limit still fits. */
    @Test fun autoplayLeavesOutWhatRunsPastTheLimit() {
        val songs = listOf(song("a", 180), song("mix", 3 * 3600), song("exact", 10 * 60), song("just over", 10 * 60 + 1))
        assertEquals(listOf("a", "exact"), AutoplayLength.keep(songs, 10).map { it.id })
    }

    /** #299: an unknown length isn't a long song: losing it would make the queue run out for no reason. */
    @Test fun autoplayKeepsASongOfUnknownLength() {
        assertTrue(AutoplayLength.fits(song("unknown", 0), 5))
        assertFalse(AutoplayLength.fits(song("long", 5 * 60 + 1), 5))
    }

    /** #281: both switches and mobile data, and connected: no connection isn't mobile data, and the buffer can still play. */
    @Test fun videosAreOffOnlyOnMobileDataWithBothSwitches() {
        assertTrue(VideoOnData.off(chosen = true, dataSaver = true, online = true, metered = true))
        assertFalse("nobody who hasn't chosen it", VideoOnData.off(chosen = false, dataSaver = true, online = true, metered = true))
        assertFalse("it's part of Data saver", VideoOnData.off(chosen = true, dataSaver = false, online = true, metered = true))
        assertFalse("on Wi-Fi", VideoOnData.off(chosen = true, dataSaver = true, online = true, metered = false))
        assertFalse("not connected", VideoOnData.off(chosen = true, dataSaver = true, online = false, metered = true))
    }

    /** #281: a downloaded video costs no data. */
    @Test fun aDownloadedVideoStillPlays() {
        assertTrue(VideoOnData.stops(off = true, downloaded = false))
        assertFalse(VideoOnData.stops(off = true, downloaded = true))
        assertFalse(VideoOnData.stops(off = false, downloaded = false))
    }

    /** #280: what's kept comes back; an episode has no lyrics; nothing kept (or something odd) is the cover. */
    @Test fun thePlayerOpensOnTheLastPage() {
        assertEquals(PlayerPage.LYRICS, PlayerPage.opensOn("LYRICS", episode = false))
        assertEquals(PlayerPage.QUEUE, PlayerPage.opensOn("QUEUE", episode = true))
        assertEquals(PlayerPage.COVER, PlayerPage.opensOn("LYRICS", episode = true))
        assertEquals(PlayerPage.COVER, PlayerPage.opensOn(null, episode = false))
        assertEquals(PlayerPage.COVER, PlayerPage.opensOn("SOMETHING", episode = false))
        assertEquals(PlayerPage.QUEUE, PlayerPage.of(lyricsOpen = false, queueOpen = true))
        assertEquals(PlayerPage.LYRICS, PlayerPage.of(lyricsOpen = true, queueOpen = false))
        assertEquals(PlayerPage.COVER, PlayerPage.of(lyricsOpen = false, queueOpen = false))
    }

    /** #280: the nearer the sung line, the sooner they go back; far away, the usual time, and never longer. */
    @Test fun theLyricsComeBackSoonerWhenTheSungLineIsClose() {
        assertEquals(LYRICS_RETURN_MS, lyricsReturnDelayMs(30))
        assertEquals(LYRICS_RETURN_MS, 4_000L)
        val delays = (0..10).map { lyricsReturnDelayMs(it) }
        assertEquals("never quicker further away", delays.sorted(), delays)
        assertTrue(lyricsReturnDelayMs(1) < 1_000L)
    }

    /** #280: a nudge inside the longer distance leaves the controls alone; the usual distance is what it always was. */
    @Test fun aLongerDistanceLetsANudgeThrough() {
        var hid = 0
        val scroll = ControlsOnScroll(onHide = { hid++ }, onReveal = {})
        fun drag(px: Float) = scroll.onPreScroll(Offset(0f, px), NestedScrollSource.UserInput)
        assertEquals(40f, scroll.threshold, 0f)
        drag(-41f)
        assertEquals("as ever", 1, hid)
        runBlocking { scroll.onPreFling(Velocity.Zero) }

        scroll.threshold = 150f
        drag(-41f)
        assertEquals("a nudge", 1, hid)
        drag(-110f)
        assertEquals("a real swipe", 2, hid)
    }

    /** #293: a hidden volume bar keeps no room over a full-bleed picture, and the gaps close up; off, as #329 left it. */
    @Test fun aRoomyDeckClosesUp() {
        val all = listOf(DeckPart.SCRUBBER, DeckPart.TRANSPORT, DeckPart.VOLUME, DeckPart.BUTTONS)
        val tight = deckSpacing(all, volumeShown = false, small = false, tight = true)
        assertEquals(listOf(DeckPart.SCRUBBER, DeckPart.TRANSPORT, DeckPart.BUTTONS), tight.parts)
        assertEquals(listOf(4.dp, 6.dp), tight.gaps)
        assertEquals(0.dp, tight.below)
        val usual = deckSpacing(all, volumeShown = false, small = false)
        assertEquals(usual.parts, tight.parts)
        assertTrue("it's the volume bar's room that's given back", usual.gaps.sumOf { it.value.toDouble() } > tight.gaps.sumOf { it.value.toDouble() } + 30)
    }

    /** #223: only when asked for, and as the way in, never with Reduce motion (which Performance mode turns on). */
    @Test fun theCoverFliesBackOnlyWhenAsked() {
        assertTrue(CoverFlight.returnsWith(flight = true, back = true, reduceMotion = false))
        assertFalse(CoverFlight.returnsWith(flight = true, back = false, reduceMotion = false))
        assertFalse(CoverFlight.returnsWith(flight = false, back = true, reduceMotion = false))
        assertFalse(CoverFlight.returnsWith(flight = true, back = true, reduceMotion = true))
    }

    /** Everything from this round starts off, so nobody's player changes by itself. */
    @Test fun everyOptionStartsOff() {
        val s = AppSettings()
        assertEquals(0, s.behavior.autoplayMaxMinutes)
        assertFalse(s.behavior.noVideoOnData)
        assertFalse(s.behavior.gesturesUnderLyrics)
        assertFalse(s.behavior.playerRemembersPage)
        assertFalse(s.behavior.lyricsSteady)
        assertFalse(s.view.coverFlightBack)
        assertFalse(s.view.videoButtonsHide)
        assertFalse(s.view.bleedRoomy)
        assertFalse(s.view.queueLengths)
        assertFalse(s.view.queuePlayedMoves)
        assertFalse(s.view.queueTidy)
    }
}
