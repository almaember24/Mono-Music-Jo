package com.prism.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.model.Shelf
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.tv.LocalTvScreen
import com.prism.music.ui.tv.TAG_LYRICS
import com.prism.music.ui.tv.TAG_QUEUE
import com.prism.music.ui.tv.TV_START_WAIT_MS
import com.prism.music.ui.tv.TvHomeShelves
import com.prism.music.ui.tv.TvLink
import com.prism.music.ui.tv.TvLyricsPlace
import com.prism.music.ui.tv.TvNow
import com.prism.music.ui.tv.TvQueuePanel
import com.prism.music.ui.tv.TvRoute
import com.prism.music.ui.tv.TvSing
import com.prism.music.ui.tv.TvSong
import com.prism.music.ui.tv.TvStarting
import com.prism.music.ui.tv.TvTheme
import com.prism.music.ui.tv.tvLinkFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prism TV, the 1.8.0 review's findings on the screens: each one's fix, held to here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvReviewTest {
    @get:Rule val compose = createComposeRule()

    private fun focusedOn(tag: String) =
        compose.onAllNodes(hasTestTag(tag) and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private val now = TvNow("Glass Horizon", "Mira Vale", null, null, 0.3f, "1:00", "3:30", playing = false, hasLyrics = true)

    /** Lyrics found after the page opened (almost every time): focus stays on them, OK and picking a line work. */
    @Test fun lyricsThatComeLaterKeepTheFocus() {
        var lines by mutableStateOf(emptyList<LyricLine>())
        compose.setContent {
            TvTheme(AppSettings()) {
                CompositionLocalProvider(LocalTvScreen provides "lyrics") {
                    TvLyricsPlace(now, lines, 0, androidx.compose.runtime.remember { mutableLongStateOf(0L) }, loading = lines.isEmpty(), unavailable = false, source = null, accent = null) {}
                }
            }
        }
        compose.waitUntil(5_000) { focusedOn("$TAG_LYRICS/lines") }
        lines = (0 until 8).map { LyricLine(it * 4_000L, "Line ${it + 1}") }
        compose.waitUntil(5_000) { focusedOn("$TAG_LYRICS/lines") }
        // The song changes: looking again, and back.
        lines = emptyList()
        compose.waitUntil(5_000) { focusedOn("$TAG_LYRICS/lines") }
        lines = (0 until 3).map { LyricLine(it * 4_000L, "Other ${it + 1}") }
        compose.waitUntil(5_000) { focusedOn("$TAG_LYRICS/lines") }
    }

    /** The queue opens on the next song however far down it is (focus never left on the hidden Queue button). */
    @Test fun theQueueOpensOnTheNextSongFarDown() {
        val songs = (0 until 60).map { TvSong("s$it", "Song $it", "Artist", "3:00") }
        compose.setContent {
            TvTheme(AppSettings()) {
                CompositionLocalProvider(LocalTvScreen provides "player/queue") {
                    Box(Modifier.fillMaxSize()) { TvQueuePanel(null, songs, 41, {}) }
                }
            }
        }
        compose.waitUntil(5_000) { focusedOn("$TAG_QUEUE/42") }
    }

    /** A page opened for a song still starting (a video's stream): it waits, and only then gives up. */
    @Test fun aPageWaitsForItsSongToStart() {
        var gaveUp = false
        compose.mainClock.autoAdvance = false
        compose.setContent { TvTheme(AppSettings()) { TvStarting { gaveUp = true } } }
        compose.mainClock.advanceTimeBy(TV_START_WAIT_MS - 1_000)
        assertFalse("gave up while the song could still be starting", gaveUp)
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue(gaveUp)
    }

    /** The Sing check cut short (Settings left, Prism closed): Sing goes back off, and the check isn't stuck. */
    @Test fun aSingCheckCutShortPutsSingBack() = runBlocking {
        var on = false
        val before = TvSing.state.value
        val job = launch {
            TvSing.checking(wasOn = false, setOn = { on = it }) { delay(60_000); TvSing.State.READY }
        }
        delay(100)
        assertTrue(on)
        assertEquals(TvSing.State.CHECKING, TvSing.state.value)
        job.cancel(CancellationException("left Settings"))
        job.join()
        assertFalse(on)
        assertEquals(before, TvSing.state.value)
        // Run through: Sing back as it was (on, here), and the answer.
        on = true
        assertEquals(TvSing.State.SLOW, TvSing.checking(wasOn = true, setOn = { on = it }) { TvSing.State.SLOW })
        assertTrue(on)
    }

    /** Home's shelves are one account's: another account, or none, never sees them; signing out clears them. */
    @Test fun homesShelvesAreTheAccountsOwn() {
        val shelves = listOf(Shelf("Quick picks", items = emptyList()))
        TvHomeShelves.keep("alex@example.com", shelves, now = 1_000)
        assertEquals(shelves to true, TvHomeShelves.of("alex@example.com", now = 2_000))
        assertEquals(emptyList<Shelf>() to false, TvHomeShelves.of("sam@example.com", now = 2_000))
        assertEquals(emptyList<Shelf>() to false, TvHomeShelves.of(null, now = 2_000))
        // Stale after half an hour (shown, then refreshed).
        assertEquals(shelves to false, TvHomeShelves.of("alex@example.com", now = 1_000 + 31 * 60_000L))
        TvHomeShelves.clear()
        assertEquals(emptyList<Shelf>() to false, TvHomeShelves.of("alex@example.com", now = 2_000))
    }

    /** Links opened on a TV open their song or page there (they were lost before). */
    @Test fun linksOpenOnTheTv() {
        fun of(s: String) = tvLinkFor(android.net.Uri.parse(s))
        assertEquals(TvLink.Song("dQw4w9WgXcQ"), of("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM"))
        assertEquals(TvLink.Song("dQw4w9WgXcQ"), of("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("PL123", ((of("https://music.youtube.com/playlist?list=PL123") as TvLink.Page).route as TvRoute.Collection).item.id)
        assertEquals("MPREb_x", ((of("https://music.youtube.com/browse/MPREb_x") as TvLink.Page).route as TvRoute.Collection).item.id)
        assertEquals(TvLink.Page(TvRoute.Artist("UCabc")), of("https://music.youtube.com/channel/UCabc"))
        assertEquals(TvLink.Page(TvRoute.Player), of("prism://player"))
        // Places only the phone has.
        assertNull(of("prism://blends"))
        assertNull(of("prism://pair?h=10.0.0.2&p=1&c=123456"))
        assertNull(of("https://music.youtube.com/library"))
    }
}
