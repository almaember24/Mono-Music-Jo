package com.prism.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.theme.AppFonts
import com.prism.music.ui.tv.LocalTvFocusMemory
import com.prism.music.ui.tv.LocalTvScreen
import com.prism.music.ui.tv.TAG_LYRICS
import com.prism.music.ui.tv.TvFocusMemory
import com.prism.music.ui.tv.TvLyrics
import com.prism.music.ui.tv.TvLyricsScreen
import com.prism.music.ui.tv.TvNow
import com.prism.music.ui.tv.TvTheme
import com.prism.music.ui.tv.tvLyricsAxisChoice
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** Lyrics on the TV as on the phone's lyrics page (1.8.0): the bolder font, gliding lines, background vocals, breaks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvLyricsViewTest {
    @get:Rule val compose = createComposeRule()

    private val now = TvNow("A song", "An artist", null, null, 0.2f, "0:20", "3:00", playing = true, hasLyrics = true)

    /** Made-up lines, word-timed; the second has background vocals, the fifth is an instrumental break. */
    private val lines = (0 until 12).map { i ->
        val t = i * 4_000L
        val words = listOf(LyricWord(t, t + 1_500, "Line "), LyricWord(t + 1_500, t + 3_000, "number ${i + 1}"))
        when (i) {
            1 -> LyricLine(t, "Line number 2", t + 3_500, words, background = listOf(LyricWord(t + 500, t + 2_000, "echo "), LyricWord(t + 2_000, t + 3_000, "echo")))
            4 -> LyricLine(t, "", t + 3_500, isGap = true)
            else -> LyricLine(t, "Line number ${i + 1}", t + 3_500, words)
        }
    }

    @Test fun theLyricsTypefacesWeightAxisIsUsed() {
        // "Grows bolder" swells word by word only with a typeface on a weight axis: Prism's own fonts have one.
        val choice = tvLyricsAxisChoice(AppSettings())
        assertNotNull(choice)
        assertNotNull(AppFonts.axis(choice!!))
    }

    private fun bounds(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    /**
     * The owner: "they should flow smoother". The clock running (every frame) and the song's progress moving (twice a
     * second) don't compose the lines again: only the sung line's own drawing follows the clock.
     */
    @Test fun theLinesAreNotComposedAgainFrameByFrame() {
        val clock = mutableLongStateOf(4_200L)
        var shown by androidx.compose.runtime.mutableStateOf(now)
        compose.setContent {
            TvTheme(AppSettings()) {
                val memory = remember { TvFocusMemory() }
                CompositionLocalProvider(LocalTvFocusMemory provides memory, LocalTvScreen provides "lyrics") {
                    TvLyricsScreen(shown, TvLyrics(lines, 1, 0L, null), clock)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        val lists = com.prism.music.ui.tv.TvLyricsCounts.lists
        val composed = com.prism.music.ui.tv.TvLyricsCounts.lines
        // A second of frames through the sung line, and the progress moving on twice.
        repeat(60) { f ->
            clock.longValue = 4_200L + f * 16L
            if (f == 20 || f == 50) shown = shown.copy(fraction = shown.fraction + 0.01f, played = "0:2$f")
            compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
        }
        assertTrue("lines composed again: ${com.prism.music.ui.tv.TvLyricsCounts.lines - composed}", com.prism.music.ui.tv.TvLyricsCounts.lines == composed)
        assertTrue("the list composed again: ${com.prism.music.ui.tv.TvLyricsCounts.lists - lists}", com.prism.music.ui.tv.TvLyricsCounts.lists == lists)
    }

    @Test fun theSungLineGlidesIntoPlaceWithItsBackgroundVocalsAndBreaks() {
        var current by mutableIntStateOf(1)
        compose.setContent {
            TvTheme(AppSettings()) {
                val memory = remember { TvFocusMemory() }
                CompositionLocalProvider(LocalTvFocusMemory provides memory, LocalTvScreen provides "lyrics") {
                    val clock = remember { mutableLongStateOf(4_800L) }
                    TvLyricsScreen(now, TvLyrics(lines, current, 0L, null), clock)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        // Background vocals under their line.
        assertNotNull("background vocals shown", bounds("$TAG_LYRICS/backing/1"))
        val list = bounds("$TAG_LYRICS/list")!!
        val settleY = bounds("$TAG_LYRICS/line/1")!!.top
        // A later line: it glides to where the sung line settles (the list moved, not a jump of the window).
        current = 3
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        val third = bounds("$TAG_LYRICS/line/3")!!
        assertTrue("line 4 settled at the sung line's place (${third.top} vs $settleY)", abs(third.top - settleY) < 4f)
        assertTrue(third.top > list.top)
        // The instrumental break has its mark.
        assertNotNull(bounds("$TAG_LYRICS/gap/4"))
    }
}
