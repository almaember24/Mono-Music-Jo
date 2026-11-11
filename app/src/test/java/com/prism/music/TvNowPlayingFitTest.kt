package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.tv.TAG_PLAYER
import com.prism.music.ui.tv.TvAction
import com.prism.music.ui.tv.TvActionPanel
import com.prism.music.ui.tv.TvFit
import com.prism.music.ui.tv.TvNavFrame
import com.prism.music.ui.tv.TvNow
import com.prism.music.ui.tv.TvNowPlayingScreen
import com.prism.music.ui.tv.TvQueuePanel
import com.prism.music.ui.tv.TvSection
import com.prism.music.ui.tv.TvSong
import com.prism.music.ui.tv.TvTheme
import com.prism.music.ui.tv.tvFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Reddit (u/TRK88PL, 1.8.1): "when you open the sidebar in the Now Playing view, the song title gets slightly cut off at
 * the bottom", and the photo showed "Up next" overlapping. Now playing at a TV's size (960x540 dp at most TVs' setting,
 * 853x480 where a TV draws bigger), a two-line title and every row, with the side panels open and shut: nothing leaves
 * the screen or runs into the next row. The pictures go to build/tv-fit/ to be looked at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvNowPlayingFitTest {
    @get:Rule val compose = createComposeRule()

    private val out = File("build/tv-fit").apply { mkdirs() }

    private val long = "A Very Long Song Title That Runs Over Two Whole Lines On The Television (Remastered Deluxe Anniversary Edition 2011)"
    private fun now(title: String) = TvNow(
        title, "Mira Vale, The Lanterns and Ines Calder", "Tidewater (Deluxe Edition)", null, 0.38f, "1:24", "3:41", playing = true, liked = true,
        hasLyrics = true, hasVideo = true, next = "Northern Static · The Lanterns",
    )

    private var screen by mutableStateOf<Pair<String, @Composable () -> Unit>?>(null)
    private var look by mutableStateOf(AppSettings())
    /** The TV's own font size, under Prism's text size (TvTheme multiplies them). */
    private var systemFont by mutableStateOf(1f)
    private var started = false

    private fun show(id: String, content: @Composable () -> Unit) {
        if (!started) {
            started = true
            compose.setContent {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, systemFont)) {
                    TvTheme(look) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF060609))) {
                            screen?.let { (key, draw) -> androidx.compose.runtime.key(key) { draw() } }
                        }
                    }
                }
            }
        }
        screen = id to content
        repeat(8) { compose.mainClock.advanceTimeBy(120); Thread.sleep(20) }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$id.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val noop = {}

    @Composable
    private fun player(n: TvNow) = TvNowPlayingScreen(n, noop, noop, noop, noop, noop, noop, noop, noop, noop, noop, onSeek = {})

    private fun node(tag: String): SemanticsNode = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().first()

    private fun textBounds(text: String): Rect =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    /** Every part of Now playing: inside the screen's 27 dp margin (a pixel's grace), and each above the next. */
    private fun assertFits(what: String, n: TvNow) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val parts = listOf(
            "title" to textBounds(n.title.take(12)),
            "artist" to textBounds(n.artist.take(12)),
            "progress" to node("$TAG_PLAYER/progress").boundsInRoot,
            "play" to node("$TAG_PLAYER/playpause").boundsInRoot,
            "lyrics" to node("$TAG_PLAYER/lyrics").boundsInRoot,
            "up next" to node("$TAG_PLAYER/upnext").boundsInRoot,
        )
        // In pixels at 2 px a dp.
        val margin = 27 * 2f
        for ((name, r) in parts) {
            assertTrue("$what: $name starts above the screen's margin ($r)", r.top >= root.top + margin - 2)
            assertTrue("$what: $name runs past the screen's margin ($r, screen $root)", r.bottom <= root.bottom - margin + 2)
        }
        for ((a, b) in parts.zipWithNext()) assertTrue("$what: ${a.first} ${a.second} runs into ${b.first} ${b.second}", a.second.bottom <= b.second.top + 2)
        // The whole row of buttons on the screen, none squeezed (the Video button's word wrapped at the largest text).
        val lyrics = node("$TAG_PLAYER/lyrics").boundsInRoot
        val video = node("$TAG_PLAYER/video").boundsInRoot
        val more = node("$TAG_PLAYER/more").boundsInRoot
        assertEquals("$what: Video's button is as high as Lyrics' ($video, $lyrics)", lyrics.height, video.height, 2f)
        assertTrue("$what: ⋯ runs past the screen's margin ($more, screen $root)", more.right <= root.right - 48 * 2f + 2)
        assertTrue("$what: ⋯ is squeezed ($more)", more.width >= 30f && more.height >= 30f)
    }

    private val songs = (1..8).map { TvSong("$it", "Song number $it of the queue", "Mira Vale", "3:41") }
    private val actions = listOf(TvAction("Go to album", PrismIcons.Album) {}, TvAction("Mira Vale", PrismIcons.Person) {}, TvAction("Sing", PrismIcons.Mic) {})

    @Test fun aTwoLineTitleFits() {
        val n = now(long)
        show("two-lines") { player(n) }
        assertFits("two lines", n)
    }

    @Test fun itFitsWithTheSidePanelsOpen() {
        val n = now(long)
        show("queue-open") { Box(Modifier.fillMaxSize()) { player(n); TvQueuePanel("Mix 1", songs, 0, {}) } }
        assertFits("queue open", n)
        show("more-open") { Box(Modifier.fillMaxSize()) { player(n); TvActionPanel(long, actions) } }
        assertFits("⋯ open", n)
        // The panel's own heading: within the screen, and the panel's first action below it.
        val heads = compose.onAllNodes(hasText(long.take(12), substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        val head = heads.last().boundsInRoot
        val first = node("tv_actions/0").boundsInRoot
        assertTrue("the panel's title ($head)", head.top >= 0 && head.bottom > head.top)
        assertTrue("the panel's title $head runs into its first action $first", head.bottom <= first.top)
    }

    @Test fun itFitsAtTheLargestTextSize() {
        val n = now(long)
        look = AppSettings(textScale = 1.25f)
        show("large-text") { player(n) }
        assertFits("large text", n)
    }

    /** A TV drawing bigger than most (853x480 dp), at Prism's largest text on the TV's own larger font. */
    @Config(qualifiers = "w853dp-h480dp-land-xhdpi")
    @Test fun itFitsATvThatDrawsBigger() {
        val n = now(long)
        show("big-tv") { player(n) }
        assertFits("853x480", n)
        look = AppSettings(textScale = 1.25f)
        systemFont = 1.15f
        show("big-tv-large-text") { player(n) }
        assertFits("853x480, large text", n)
    }

    @Test fun aShortTitleStaysAsItWas() {
        val n = now("Glass Horizon")
        show("one-line") { player(n) }
        assertFits("one line", n)
    }

    /**
     * The report's own words: the sidebar open, the song's title under "Now playing" cut off at the bottom (the item's
     * height is fixed, and Prism's two lines ran out of it). Both lines whole, at the usual text size and the largest.
     * The picture's in build/tv-fit/nav-open.png.
     */
    @Test fun theSideNavigationsNowPlayingShowsItsTitleWhole() {
        fun check(what: String) {
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val item = node("tv_nav/now").boundsInRoot
            assertTrue("$what: Now playing's row ($item)", item.bottom <= root.bottom && item.height > 0)
            for (text in listOf("Now Playing", long.take(12))) {
                val line = compose.onAllNodes(hasText(text, substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().first()
                val results = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
                line.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                val laid = results.first()
                assertTrue("$what: \"$text\" has no room for its line's height (${laid.multiParagraph.height} in ${laid.size.height})", laid.multiParagraph.height <= laid.size.height)
                // Nothing of it clipped by what holds it: what shows is as high as the text.
                assertEquals("$what: \"$text\" is cut off (${line.boundsInRoot}, ${line.size})", line.size.height.toFloat(), line.boundsInRoot.height, 1f)
                assertTrue("$what: \"$text\" ${line.boundsInRoot} runs out of its row $item", line.boundsInRoot.bottom <= item.bottom + 1 && line.boundsInRoot.top >= item.top - 1)
            }
        }
        show("nav-open") { TvNavFrame(TvSection.HOME, {}, focusNav = true, now = long to null) { Box(Modifier.fillMaxSize()) } }
        check("usual text")
        look = AppSettings(textScale = 1.25f)
        systemFont = 1.15f
        show("nav-open-large-text") { TvNavFrame(TvSection.HOME, {}, focusNav = true, now = long to null) { Box(Modifier.fillMaxSize()) } }
        check("large text")
    }

    @Test fun theFitsRules() {
        // Room to spare: a plain column.
        assertEquals(TvFit(1f, 1f), tvFit(300, 100, 500))
        // Short of room: the gaps give way first.
        val closed = tvFit(300, 100, 350)
        assertEquals(0.5f, closed.gaps, 0.001f)
        assertEquals(1f, closed.scale, 0.001f)
        // The gaps as far as they go: then the whole is drawn smaller, to the room there is.
        val small = tvFit(400, 100, 350)
        assertEquals(0.35f, small.gaps, 0.001f)
        assertEquals(350f, (400 + 100 * small.gaps) * small.scale, 0.5f)
    }
}
