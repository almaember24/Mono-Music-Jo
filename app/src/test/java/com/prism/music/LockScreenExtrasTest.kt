package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LockLayout
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.playback.QueueEntry
import com.prism.music.playback.nextRepeatMode
import com.prism.music.ui.lockscreen.LockLyricLines
import com.prism.music.ui.lockscreen.LockOutput
import com.prism.music.ui.lockscreen.LockScreenActions
import com.prism.music.ui.lockscreen.LockScreenContent
import com.prism.music.ui.lockscreen.LockScreenLayout
import com.prism.music.ui.lockscreen.LockScreenRules
import com.prism.music.ui.lockscreen.LockTags
import com.prism.music.ui.player.LyricsLook
import com.prism.music.ui.player.withWords
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTheme
import com.prism.music.ui.theme.boxBlur
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
 * The lock screen's repeat button and read-only "Up next" (1.8.3, D47, Reddit): each layout with both on, on a 360 dp
 * phone (the narrowest): everything fits, the button cycles, Up next can't be touched. Pictures: build/lockscreen/extras-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class LockScreenExtrasTest {
    @get:Rule val compose = createComposeRule()

    private fun picture(): Bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3A4A8C.toInt()) }

    private val lines = listOf("First test line", "Second test line", "Third test line").mapIndexed { i, text ->
        LyricLine(i * 5_000L, text, (i + 1) * 5_000L).withWords()
    }

    private fun song(i: Int) = Song("s$i", "Test song $i", listOf(ArtistRef("Test artist $i")))

    @Test fun repeatCyclesOffAllOne() {
        assertEquals(Player.REPEAT_MODE_ALL, nextRepeatMode(Player.REPEAT_MODE_OFF))
        assertEquals(Player.REPEAT_MODE_ONE, nextRepeatMode(Player.REPEAT_MODE_ALL))
        assertEquals(Player.REPEAT_MODE_OFF, nextRepeatMode(Player.REPEAT_MODE_ONE))
    }

    @Test fun upNextFollowsThePlayOrder() {
        val queue = (0 until 6).map { QueueEntry(it, song(it), "s$it#1") }
        // Shuffled: the order they'll play, not the queue's.
        assertEquals(
            listOf("Test song 4 · Test artist 4", "Test song 1 · Test artist 1", "Test song 5 · Test artist 5"),
            LockScreenRules.upNextLines(queue, listOf(4, 1, 5, 2), hideSong = false),
        )
        assertEquals("the end of the queue", listOf("Test song 5 · Test artist 5"), LockScreenRules.upNextLines(queue, listOf(5), hideSong = false))
        assertEquals("a hidden song hides what's next too", emptyList<String>(), LockScreenRules.upNextLines(queue, listOf(4, 1), hideSong = true))
        assertTrue(AppSettings().behavior.let { !it.lockScreenRepeat && !it.lockScreenUpNext })
    }

    private fun show(layout: LockLayout, repeat: Int, onRepeat: () -> Unit = {}) {
        val art = picture()
        val clock = mutableStateOf(7_000L)
        val content = LockScreenContent(
            title = "Test title", artist = "Test artist",
            frost = boxBlur(art, 3).asImageBitmap(),
            cover = { m, shape -> Image(art.asImageBitmap(), null, m.clip(shape), contentScale = ContentScale.Crop) },
            coverShape = RoundedCornerShape(14.dp),
            playing = true, liked = true,
            output = LockOutput(PrismIcons.Headphones, "Test headphones"),
            position = { 84_000L }, durationMs = 202_000L, volume = { 0.6f }, time = "9:41", date = "WEDNESDAY, NOVEMBER 4",
            lyrics = if (layout == LockLayout.LYRICS) { m -> LockLyricLines(lines, clock, LyricsLook.Classic, null, m) } else null,
            repeat = repeat,
            upNext = (1..3).map { "Test song $it · Test artist $it" },
        )
        val actions = LockScreenActions({}, {}, {}, {}, {}, {}, {}, {}, onRepeat = onRepeat)
        compose.setContent {
            PrismTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(top = 28.dp, bottom = 20.dp)) { LockScreenLayout(layout, content, actions) }
            }
        }
        compose.waitForIdle()
    }

    private fun checkAndSave(layout: LockLayout) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val repeat = compose.onAllNodes(hasTestTag(LockTags.REPEAT), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("$layout: one repeat button", 1, repeat.size)
        val r = repeat.single().boundsInRoot
        assertTrue("$layout: repeat is on the screen ($r in $root)", r.left >= root.left && r.right <= root.right && r.width > 0)
        // Next and repeat don't sit on each other.
        val next = compose.onAllNodes(hasTestTag(LockTags.NEXT), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        assertTrue("$layout: repeat and next apart ($next, $r)", !r.deflate(1f).overlaps(next.deflate(1f)))
        assertTrue("$layout: Up next shows", compose.onAllNodes(hasTestTag(LockTags.UP_NEXT), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // Up next is only read: the controls stay the only things to touch.
        val touchable = SemanticsMatcher("takes a touch") { n -> listOf(SemanticsActions.OnClick, SemanticsActions.OnLongClick, SemanticsActions.SetProgress).any { it in n.config } }
        compose.onAllNodes(touchable, useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)
            assertTrue("$layout: something else can be touched: $tag", tag in LockTags.touchable)
        }
        val dir = File("build/lockscreen").apply { mkdirs() }
        File(dir, "extras-${layout.name.lowercase()}.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun cover() { show(LockLayout.COVER, Player.REPEAT_MODE_ALL); checkAndSave(LockLayout.COVER) }
    @Test fun fullCover() { show(LockLayout.FULL, Player.REPEAT_MODE_ONE); checkAndSave(LockLayout.FULL) }
    @Test fun lyrics() { show(LockLayout.LYRICS, Player.REPEAT_MODE_ALL); checkAndSave(LockLayout.LYRICS) }
    @Test fun quiet() { show(LockLayout.QUIET, Player.REPEAT_MODE_OFF); checkAndSave(LockLayout.QUIET) }

    @Test fun theButtonAsksToCycle() {
        var asked = 0
        show(LockLayout.COVER, Player.REPEAT_MODE_OFF) { asked++ }
        compose.onAllNodes(hasTestTag(LockTags.REPEAT), useUnmergedTree = true)[0].performClick()
        assertEquals(1, asked)
    }

    @Test fun offShowsNeither() {
        show(LockLayout.COVER, Player.REPEAT_MODE_OFF)
        // (The content above has both; the default content has neither.)
        val plain = LockScreenContent("t", "a", null, { _, _ -> }, RoundedCornerShape(4.dp), playing = true, time = "9:41", date = "d")
        assertEquals(null, plain.repeat)
        assertTrue(plain.upNext.isEmpty())
    }
}
