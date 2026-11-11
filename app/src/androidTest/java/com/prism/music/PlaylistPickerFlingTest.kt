package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #401: on a fast swipe through Add to playlist's list, the sheet glitched (OnePlus 11). What was left of an upward
 * fling once the list hit its end went to the sheet, which was pushed up past its top and bounced back (the song menu
 * had the same, #147). With 40 playlists, two fast flings up: the sheet's top never goes above where it settled.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistPickerFlingTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val made = mutableListOf<String>()

    @After fun cleanUp() {
        compose.mainClock.autoAdvance = true
        made.forEach { runCatching { c.prismLibrary.delete(it) } }
    }

    private fun sheetTop(): Float =
        compose.onAllNodes(hasTestTag("playlistPicker"), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInWindow.top

    @Test fun fastFlingsDontPushTheSheetPastItsTop() {
        Fixture.ready()
        val songs = Fixture.songs(1, prefix = "Fling song")
        Fixture.downloaded(songs)
        (1..40).forEach { i -> made += Fixture.prismPlaylist("Fling list ${i.toString().padStart(2, '0')}", if (i % 3 == 0) songs else emptyList()) }
        Fixture.launch()
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://downloads"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        compose.waitForText("Fling song 1", 15_000)
        compose.onAllNodes(hasContentDescription("More") and hasClickAction()).onFirst().performClick()
        compose.clickWhenFound(hasText("Add to playlist") and hasClickAction())
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("playlistPickerList")).fetchSemanticsNodes().isNotEmpty() }
        // Newest first: list 40 is at the top, 01 far down and not yet drawn.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Fling list", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val settled = sheetTop()

        // Two fast flings up through the list, watched frame by frame.
        compose.mainClock.autoAdvance = false
        var highest = settled
        repeat(2) {
            compose.onAllNodes(hasTestTag("playlistPickerList")).onFirst().performTouchInput {
                swipeUp(startY = bottom - 4f, endY = top + 4f, durationMillis = 60)
            }
            repeat(120) {
                compose.mainClock.advanceTimeByFrame()
                highest = minOf(highest, sheetTop())
            }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("the sheet went above its top: settled at $settled, reached $highest", highest >= settled - 1f)
    }
}
