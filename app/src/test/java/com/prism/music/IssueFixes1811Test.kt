package com.prism.music

import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.BehaviorPrefs
import com.prism.music.data.prefs.DeckPart
import com.prism.music.data.prefs.MenuAction
import com.prism.music.data.prefs.MenuGroup
import com.prism.music.data.prefs.PlayerButton
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.data.prefs.PlayerPart
import com.prism.music.data.prefs.PlayerParts
import com.prism.music.data.prefs.ViewLook
import com.prism.music.data.prefs.menuFolds
import com.prism.music.data.prefs.menuFromPlayer
import com.prism.music.data.prefs.menuLayout
import com.prism.music.data.prefs.shows
import com.prism.music.playback.QueueEdit
import com.prism.music.ui.player.VOLUME_ROW_HEIGHT
import com.prism.music.ui.player.deckSpacing
import com.prism.music.ui.player.drawsNavBand
import com.prism.music.ui.player.lyricLineLift
import com.prism.music.ui.player.navBandColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.1.1's player round: #345, #341, #329, the queue's Undo (Reddit), #250 on Android 15+. */
class IssueFixes1811Test {

    // ------------------------------------------------------------------ #345 "Show lyrics" in the ⋯ menu

    /** The Lyrics button off the player's row: the menu has "Show lyrics" (as in 1.8.0). */
    private val noLyricsButton = AppSettings(playerButtons = PlayerButton.DEFAULT.filter { it != PlayerButton.LYRICS })

    @Test fun showLyricsFoldsAwayWhenTheLyricsOpenAnotherWay() {
        val d = noLyricsButton
        assertTrue(MenuAction.OPEN_LYRICS in d.menuFromPlayer())
        // The current lyric is on the player (as it ships) and opens them: "Show lyrics" may go under More.
        assertTrue(d.shows(PlayerPart.LYRIC_LINE))
        assertEquals(setOf(MenuAction.OPEN_LYRICS), d.menuFolds())
        val hidden = PlayerParts(menuHidden = setOf(MenuAction.OPEN_LYRICS))
        val all = MenuAction.entries.filter { !it.fromPlayer } + MenuAction.OPEN_LYRICS
        val folded = hidden.menuLayout(all, d.menuFolds())
        assertEquals(listOf(MenuAction.OPEN_LYRICS), folded.more)
        assertFalse(folded.groups.any { (_, actions) -> MenuAction.OPEN_LYRICS in actions })
        // Shown (not picked away): in Playing, first, as before.
        assertEquals(MenuAction.OPEN_LYRICS, PlayerParts().menuLayout(all, d.menuFolds()).groups.first { it.first == MenuGroup.PLAYING }.second.first())
    }

    @Test fun showLyricsStaysWhileItsTheOnlyWayToTheLyrics() {
        val noStrip = noLyricsButton.copy(lyricsOnPlayer = false)
        assertTrue("nothing else opens the lyrics", noStrip.menuFolds().isEmpty())
        val hidden = PlayerParts(menuHidden = setOf(MenuAction.OPEN_LYRICS, MenuAction.STATS))
        val l = hidden.menuLayout(listOf(MenuAction.OPEN_LYRICS, MenuAction.STATS), noStrip.menuFolds())
        assertEquals("only what may fold is under More", listOf(MenuAction.STATS), l.more)
        assertEquals(MenuAction.OPEN_LYRICS, l.groups.single().second.single())
        // A cover gesture that opens them, or a swipe up on the controls, is another way too.
        assertEquals(setOf(MenuAction.OPEN_LYRICS), noStrip.copy(coverLongPress = PlayerGesture.LYRICS).menuFolds())
        assertEquals(setOf(MenuAction.OPEN_LYRICS), noStrip.copy(behavior = BehaviorPrefs(panelSwipes = true)).menuFolds())
        // …but not a gesture on a cover that isn't there.
        val noCover = noStrip.copy(coverLongPress = PlayerGesture.LYRICS, view = ViewLook(parts = PlayerParts(hidden = setOf(PlayerPart.COVER))))
        assertTrue(noCover.menuFolds().isEmpty())
        // The other actions the player brings never fold.
        assertFalse(MenuAction.OPEN_QUEUE.foldable || MenuAction.PREVIOUS.foldable)
        assertTrue(MenuAction.entries.filter { !it.fromPlayer }.all { it.foldable })
    }

    // ------------------------------------------------------------------ #341 centered lyrics over Ribbons

    @Test fun aLongSungLineIsLiftedClearOfTheBar() {
        // A short page (the controls with Ribbons take more room), the line settling 42% down, three rows tall.
        val page = 600f
        val settle = page * 0.42f
        assertEquals("a line that fits stays where it settles", 0f, lyricLineLift(settle, 120f, page), 0.01f)
        val lift = lyricLineLift(settle, 330f, page)
        assertTrue(lift > 0f)
        assertEquals("it ends where the page's foot starts to fade", page * 0.86f, settle - lift + 330f, 0.01f)
        // Never past the page's head: a line taller than the page starts at its top fade.
        assertEquals(settle - page * 0.08f, lyricLineLift(settle, 900f, page), 0.01f)
        // Near the top, nothing to lift into.
        assertEquals(0f, lyricLineLift(30f, 900f, page), 0.01f)
    }

    // ------------------------------------------------------------------ #329 even gaps in the deck

    @Test fun aHiddenVolumeBarLeavesNoHole() {
        val all = DeckPart.entries
        val hidden = deckSpacing(all, volumeShown = false, small = false)
        assertFalse(DeckPart.VOLUME in hidden.parts)
        assertEquals(hidden.parts.size - 1, hidden.gaps.size)
        // Its room (the bar and the gap under it) is shared out, so the deck keeps its height and nothing jumps.
        val shown = deckSpacing(all, volumeShown = true, small = false)
        assertEquals(
            (shown.gaps.fold(0.dp) { a, b -> a + b } + VOLUME_ROW_HEIGHT).value,
            (hidden.gaps.fold(0.dp) { a, b -> a + b } + hidden.below).value, 0.01f,
        )
        // …evenly: each gap grows by the same, none is a hole (it was 50 dp over the buttons, 8 dp over the controls).
        val base = listOf(0.dp, 8.dp, 12.dp)
        val grown = hidden.gaps.zip(base) { g, b -> g - b }
        assertTrue(grown.all { kotlin.math.abs((it - grown.first()).value) < 0.01f })
        assertTrue(hidden.gaps.max() - hidden.gaps.min() < 30.dp)
        // Shown, the deck is as it was; Small over the lyrics as it was.
        assertEquals(listOf(0.dp, 8.dp, 12.dp, 6.dp), shown.gaps)
        assertEquals(listOf(2.dp, 2.dp), deckSpacing(listOf(DeckPart.SCRUBBER, DeckPart.TRANSPORT, DeckPart.BUTTONS), false, small = true).gaps)
        // Only the controls left: the room goes under them.
        val alone = deckSpacing(listOf(DeckPart.TRANSPORT, DeckPart.VOLUME), volumeShown = false, small = false)
        assertEquals(listOf(DeckPart.TRANSPORT), alone.parts)
        assertEquals(VOLUME_ROW_HEIGHT, alone.below)
    }

    // ------------------------------------------------------------------ The queue's Undo

    @Test fun undoPutsASongBackBesideTheSongsItWasWith() {
        // a (playing) b c d, c taken out: it goes back after b.
        assertEquals(2, QueueEdit.undoSpot(listOf("a", "b", "d"), 0, after = "b", before = "d", ahead = true, near = 2))
        // b went too: before d.
        assertEquals(1, QueueEdit.undoSpot(listOf("a", "d"), 0, after = "b", before = "d", ahead = true, near = 2))
        // A song in the queue twice: the one nearest where it was.
        assertEquals(5, QueueEdit.undoSpot(listOf("a", "b", "x", "y", "b", "z"), 0, after = "b", before = "z", ahead = true, near = 5))
    }

    /** Reddit (1.8.1.1): "Undo does nothing", shuffled. The song before it had finished: it went back among the played. */
    @Test fun aSongStillToComeNeverGoesBackAmongThePlayed() {
        // a b (playing) c: b was taken out while a played; a has finished since, c plays now.
        assertEquals(2, QueueEdit.undoSpot(listOf("a", "c"), 1, after = "a", before = "c", ahead = true, near = 1))
        // Neither neighbour left (a radio started over): right after the playing song.
        assertEquals(1, QueueEdit.undoSpot(listOf("p", "q", "r"), 0, after = "gone1", before = "gone2", ahead = true, near = 0))
        // A played song taken out goes back among the played, before the playing one.
        assertEquals(1, QueueEdit.undoSpot(listOf("a", "c", "d"), 1, after = "a", before = "c", ahead = false, near = 1))
        assertEquals(0, QueueEdit.undoSpot(listOf("c", "d"), 0, after = "gone", before = "c", ahead = false, near = 1))
        // Nothing playing (the queue emptied around it): where it was.
        assertEquals(1, QueueEdit.undoSpot(listOf("a", "c"), -1, after = "zz", before = "yy", ahead = true, near = 1))
    }

    // ------------------------------------------------------------------ #250 on Android 15+

    @Test fun theNavBandIsDrawnByThePlayerFromAndroid15() {
        assertFalse("below 15 the window's color is the band", drawsNavBand(34, on = true))
        assertTrue(drawsNavBand(35, on = true))
        assertTrue(drawsNavBand(37, on = true))
        assertFalse("off: no band", drawsNavBand(37, on = false))
        assertNotEquals(navBandColor(light = true), navBandColor(light = false))
        assertEquals(0.5f, navBandColor(light = false).alpha, 0.01f)
    }

    /** The update check's way round GitHub's API limit: the version from where "latest" redirects to. */
    @Test fun theLatestVersionIsReadFromTheReleasePagesRedirect() {
        val read = com.prism.music.data.UpdateChecker.Companion::versionFromLatestRedirect
        assertEquals("1.8.1", read("https://github.com/Yigder/Prism/releases/tag/v1.8.1"))
        assertEquals("1.8.1.1", read("https://github.com/Yigder/Prism/releases/tag/v1.8.1.1"))
        assertEquals(null, read("https://github.com/Yigder/Prism/releases"))
        assertEquals(null, read("https://github.com/login"))
        assertEquals(null, read(null))
    }

    @Test fun aHotfixCountsAsNewerThanItsRelease() {
        assertTrue(com.prism.music.data.UpdateChecker.isNewer("1.8.1.1", "1.8.1"))
        assertFalse(com.prism.music.data.UpdateChecker.isNewer("1.8.1", "1.8.1.1"))
        assertFalse(com.prism.music.data.UpdateChecker.isNewer("1.8.1.1", "1.8.1.1"))
    }
}
