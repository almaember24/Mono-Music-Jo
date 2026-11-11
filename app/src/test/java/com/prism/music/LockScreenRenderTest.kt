package com.prism.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.BehaviorPrefs
import com.prism.music.data.prefs.LockLayout
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.lockscreen.HiddenCover
import com.prism.music.ui.lockscreen.LockLyricLines
import com.prism.music.ui.lockscreen.LockOutput
import com.prism.music.ui.lockscreen.LockScreenActions
import com.prism.music.ui.lockscreen.LockScreenContent
import com.prism.music.ui.lockscreen.LockScreenLayout
import com.prism.music.ui.lockscreen.LockScreenSample
import com.prism.music.ui.lockscreen.LockTags
import com.prism.music.ui.player.LyricsLook
import com.prism.music.ui.player.withWords
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTheme
import com.prism.music.ui.theme.boxBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The four lock screen layouts drawn for real, in dark (build/lockscreen/), and what each lets you touch. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class LockScreenRenderTest {
    @get:Rule val compose = createComposeRule()

    /** A made-up cover, for the picture only: two colors and a disc. */
    private fun picture(): Bitmap {
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 512f, 0xFF1B1464.toInt(), 0xFFFF9A5A.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 512f, 512f, p)
        p.shader = null
        p.color = 0xFFFFD27A.toInt()
        canvas.drawCircle(256f, 300f, 90f, p)
        return b
    }

    private val noActions = LockScreenActions({}, {}, {}, {}, {}, {}, {}, {})

    private fun content(
        art: Bitmap,
        lyrics: (@Composable (Modifier) -> Unit)? = null,
        showLike: Boolean = true,
        showVolume: Boolean = true,
        title: String = "Test title",
        artist: String = "Test artist",
        hidden: Boolean = false,
    ) = LockScreenContent(
        title = title, artist = artist,
        frost = if (hidden) null else boxBlur(art, 3).asImageBitmap(),
        cover = { m, shape ->
            if (hidden) HiddenCover(m, shape)
            else Image(art.asImageBitmap(), null, m.clip(shape), contentScale = ContentScale.Crop)
        },
        coverShape = RoundedCornerShape(14.dp),
        playing = true, liked = true, showLike = showLike, showVolume = showVolume,
        output = LockOutput(PrismIcons.Headphones, "Test headphones"),
        position = { 84_000L }, durationMs = 202_000L, volume = { 0.6f }, time = "9:41", date = "WEDNESDAY, NOVEMBER 4",
        lyrics = lyrics,
    )

    private val lines = listOf("First test line", "Second test line", "Third test line", "Fourth test line").mapIndexed { i, text ->
        LyricLine(i * 5_000L, text, (i + 1) * 5_000L).withWords()
    }

    private fun show(layout: LockLayout, content: LockScreenContent, settings: AppSettings = AppSettings(themeMode = ThemeMode.DARK)) {
        compose.setContent {
            PrismTheme(settings, artworkColor = null) {
                // The phone's system bars aren't here: leave their room.
                Box(Modifier.fillMaxSize().background(Color.Black).padding(top = 28.dp, bottom = 20.dp)) {
                    LockScreenLayout(layout, content, noActions)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(name: String): Bitmap {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/lockscreen").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shot
    }

    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Everything that takes a tap or a drag must be one of the tagged controls. */
    private fun assertOnlyTheControlsTakeATouch() {
        val touchable = SemanticsMatcher("takes a touch") { node ->
            listOf(SemanticsActions.OnClick, SemanticsActions.OnLongClick, SemanticsActions.SetProgress, SemanticsActions.ScrollBy, SemanticsActions.SetText)
                .any { it in node.config }
        }
        val nodes = compose.onAllNodes(touchable, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("nothing can be touched?", nodes.isNotEmpty())
        for (n in nodes) {
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)
            assertTrue("something else can be touched: tag=$tag ${n.config}", tag in LockTags.touchable)
        }
    }

    @Test fun cover() {
        show(LockLayout.COVER, content(picture()))
        val a = save("cover")
        listOf(LockTags.COVER, LockTags.LIKE, LockTags.PREVIOUS, LockTags.PLAY, LockTags.NEXT, LockTags.SEEK, LockTags.VOLUME).forEach {
            assertTrue("$it is missing", tagged(it))
        }
        assertOnlyTheControlsTakeATouch()
        assertTrue(a.width > 0)
    }

    @Test fun fullCover() {
        show(LockLayout.FULL, content(picture()))
        save("full")
        listOf(LockTags.COVER, LockTags.LIKE, LockTags.PLAY, LockTags.SEEK, LockTags.VOLUME).forEach { assertTrue("$it is missing", tagged(it)) }
        assertOnlyTheControlsTakeATouch()
    }

    @Test fun lyrics() {
        val clock = mutableStateOf(7_000L)
        show(LockLayout.LYRICS, content(picture(), lyrics = { m -> LockLyricLines(lines, clock, LyricsLook.Classic, null, m) }))
        save("lyrics")
        listOf(LockTags.COVER, LockTags.PLAY, LockTags.SEEK).forEach { assertTrue("$it is missing", tagged(it)) }
        assertOnlyTheControlsTakeATouch()
    }

    @Test fun quiet() {
        show(LockLayout.QUIET, content(picture()))
        save("quiet")
        listOf(LockTags.COVER, LockTags.PLAY, LockTags.SEEK).forEach { assertTrue("$it is missing", tagged(it)) }
        assertOnlyTheControlsTakeATouch()
    }

    @Test fun volumeAndHeartAreChoices() {
        show(LockLayout.COVER, content(picture(), showLike = false, showVolume = false))
        assertFalse(tagged(LockTags.LIKE))
        assertFalse(tagged(LockTags.VOLUME))
        assertTrue(tagged(LockTags.PLAY))
    }

    @Test fun hiddenSong() {
        show(LockLayout.COVER, content(picture(), showLike = false, title = "Now playing", artist = "", hidden = true))
        save("hidden")
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("Test artist")).fetchSemanticsNodes().size)
    }

    /** The settings page's examples: the same layouts as small phones, not touchable at all. */
    @Test fun settingsCards() {
        val s = AppSettings(themeMode = ThemeMode.DARK, behavior = BehaviorPrefs())
        compose.setContent {
            PrismTheme(s, artworkColor = null) {
                androidx.compose.foundation.layout.Row(Modifier.background(Color.Black).padding(8.dp)) {
                    LockLayout.entries.forEach { LockScreenSample(it, null, width = 90.dp, modifier = Modifier.padding(4.dp)) }
                }
            }
        }
        compose.waitForIdle()
        save("cards")
        assertEquals(0, compose.onAllNodes(hasTestTag(LockTags.PLAY), useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
