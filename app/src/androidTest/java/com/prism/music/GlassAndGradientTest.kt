package com.prism.music

import android.graphics.Bitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.prefs.GlassKind
import com.prism.music.data.prefs.GlassLook
import com.prism.music.data.prefs.PlayerBackground
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.player.GRADIENT_MIDDLE_AT
import com.prism.music.ui.player.GradientStops
import com.prism.music.ui.player.coverGradientOf
import com.prism.music.ui.player.stops
import com.prism.music.ui.screens.GlassChoice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * #232, #338: the Cover gradient really is what the player draws, in the cover's colors, and its slider sets the foot.
 * #252: the Glass page's sliders come and go with what they can do. #326: glass on more of the screen, on and off.
 * No network: a silent tone on the phone stands in for a song, and a one-color picture for its cover.
 */
@RunWith(AndroidJUnit4::class)
class GlassAndGradientTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var tone: android.net.Uri? = null

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        tone?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        runCatching { c.settings.setPerformanceMode(false) }
    }

    private fun onePictureCover(color: Int): Pair<String, Bitmap> {
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val file = File(Fixture.context.cacheDir, "gradient-test-cover.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.toURI().toString() to bmp
    }

    /** Opens the player on a paused tone that has [cover] for its cover. */
    private fun openPlayerOn(cover: String) {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone("Prism gradient tone", 60)
        Fixture.launch()
        runBlocking { c.localMusic.scan() }
        val song = c.localMusic.tracks.value.first { it.song.title == "Prism gradient tone" }.song.copy(thumbnail = cover)
        instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Gradient test")) }
        compose.waitForText("Prism gradient tone", 20_000)
        instrumentation.runOnMainSync { c.player.setPlaying(false) }
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("playerArtBox")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun near(a: Int, b: Int, by: Int) =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) <= by &&
            abs(((a shr 8) and 255) - ((b shr 8) and 255)) <= by &&
            abs((a and 255) - (b and 255)) <= by

    /** The screen at [x], [y] (0..1 across and down). */
    private fun pixel(x: Float, y: Float): Int {
        val map = compose.onRoot().captureToImage().toPixelMap()
        return map[(map.width * x).toInt().coerceAtMost(map.width - 1), (map.height * y).toInt().coerceAtMost(map.height - 1)].let {
            androidx.compose.ui.graphics.Color(it.value).let { color ->
                (0xFF shl 24) or ((color.red * 255).toInt() shl 16) or ((color.green * 255).toInt() shl 8) or (color.blue * 255).toInt()
            }
        }
    }

    /**
     * What the gradient's line (see `gradientPaint`) has at [x], [y] (0..1 across and down) on this screen. The margins are read,
     * where nothing of the player sits: its status bar strip darkens the very top and the cover fills the middle.
     */
    private fun expected(stops: GradientStops, x: Float, y: Float): Int {
        val map = compose.onRoot().captureToImage().toPixelMap()
        val w = map.width.toFloat()
        val h = map.height.toFloat()
        val dx = 0.72f * w
        val t = (((x - 0.14f) * w) * dx + (y * h) * h) / (dx * dx + h * h)
        return if (t < GRADIENT_MIDDLE_AT) ColorUtils.blendARGB(stops.top, stops.middle, t / GRADIENT_MIDDLE_AT)
        else ColorUtils.blendARGB(stops.middle, stops.foot, (t - GRADIENT_MIDDLE_AT) / (1f - GRADIENT_MIDDLE_AT))
    }

    private fun gradientShows(stops: GradientStops, x: Float, y: Float) = near(pixel(x, y), expected(stops, x, y), 8)

    @Test fun thePlayerDrawsTheCoversGradient() {
        Fixture.ready()
        val (cover, bmp) = onePictureCover(0xFF1F6F5C.toInt())
        runBlocking {
            c.settings.setPlayerBackground(PlayerBackground.GRADIENT)
            c.settings.setGradientDepth(1f)
        }
        val wanted = coverGradientOf(bmp)!!.stops(1f)
        openPlayerOn(cover)
        // The upper left and the lower right, once the cover is read and the fade is over.
        compose.waitUntil(10_000) { gradientShows(wanted, 0.02f, 0.2f) && gradientShows(wanted, 0.98f, 0.85f) }
        Fixture.screenshot("cover-gradient-dark-foot")
        // The slider darkens the foot and leaves the top alone.
        runBlocking { c.settings.setGradientDepth(0f) }
        val lighter = coverGradientOf(bmp)!!.stops(0f)
        assertEquals(wanted.top, lighter.top)
        assertFalse("the two depths have to differ for this to mean anything", near(expected(wanted, 0.98f, 0.85f), expected(lighter, 0.98f, 0.85f), 16))
        compose.waitUntil(5_000) { gradientShows(lighter, 0.98f, 0.85f) }
        Fixture.screenshot("cover-gradient-light-foot")
        // Lyrics open over it: a veil, nothing that needs a blur.
        compose.clickWhenFound(hasContentDescription(tr(R.string.lyrics)) and hasClickAction(), 15_000)
        compose.waitForIdle()
    }

    /** Performance mode leaves the gradient as the listener picked it: nothing about it is live. */
    @Test fun itStaysInPerformanceMode() {
        Fixture.ready()
        val (cover, bmp) = onePictureCover(0xFF8A2BE2.toInt())
        runBlocking {
            c.settings.setPlayerBackground(PlayerBackground.GRADIENT)
            c.settings.setPerformanceMode(true)
        }
        val wanted = coverGradientOf(bmp)!!.stops(c.settings.current.view.gradientDepth)
        openPlayerOn(cover)
        compose.waitUntil(10_000) { gradientShows(wanted, 0.02f, 0.2f) }
    }

    private fun openGlassPage() {
        compose.openSettings()
        compose.clickWhenFound(hasText(tr(R.string.customize_prism)) and hasClickAction(), 15_000)
        compose.clickWhenFound(hasText(tr(R.string.glass_backgrounds)) and hasClickAction(), 15_000)
        compose.waitForText(tr(R.string.glass_on_more_of_the_screen))
    }

    /** The whole text: "Frost" is also the start of "Frosted Glass". */
    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theSlidersAreThereWhenTheyCanDoSomething() {
        Fixture.ready()
        runBlocking { c.settings.setFrostedGlass(true) }
        Fixture.launch()
        openGlassPage()
        Fixture.screenshot("glass-page")
        if (android.os.Build.VERSION.SDK_INT < 31) {
            // Bars are solid below Android 12: it says so, in place of sliders that would do nothing.
            compose.waitForText(tr(R.string.glass_needs_android_12))
            assertFalse(shown(tr(R.string.glass_blur)))
            assertFalse(shown(tr(R.string.glass_frost)))
            return
        }
        compose.waitUntil(5_000) { shown(tr(R.string.glass_blur)) && shown(tr(R.string.glass_frost)) }
        // Regular, picked by its card: the same two.
        compose.onAllNodes(hasText(GlassChoice.LIQUID_REGULAR.label) and hasClickAction()).onFirst().performClick()
        compose.waitUntil(5_000) { c.settings.current.liquidGlass && c.settings.current.glassKind == GlassKind.REGULAR && shown(tr(R.string.glass_blur)) && shown(tr(R.string.glass_frost)) }
        // Clear has next to no blur to set.
        compose.onAllNodes(hasText(GlassChoice.LIQUID_CLEAR.label) and hasClickAction()).onFirst().performClick()
        compose.waitUntil(5_000) { c.settings.current.glassKind == GlassKind.CLEAR && !shown(tr(R.string.glass_blur)) }
        assertTrue(shown(tr(R.string.glass_frost)))
        // No glass at all: neither.
        compose.onAllNodes(hasText(GlassChoice.OFF.label) and hasClickAction()).onFirst().performClick()
        compose.waitUntil(5_000) { !c.settings.current.liquidGlass && !shown(tr(R.string.glass_frost)) }
    }

    @Test fun moreGlassGoesOnAndOffAndTheUndoBarStillWorks() {
        Fixture.ready()
        Fixture.launch()
        assertEquals("off on a fresh install", GlassLook(), c.settings.current.view.glass)
        openGlassPage()
        val offValues = c.settings.lookValues.value
        compose.onAllNodes(hasText(tr(R.string.glass_on_more_of_the_screen)) and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.waitUntil(5_000) { c.settings.current.glassEverywhere }
        // (lookValues is a flow of its own: it may not have this change yet.)
        compose.waitUntil(5_000) { c.settings.lookValues.value != offValues }
        // The Undo bar over a page is real glass with this on. A change on the page brings it up, and its button still answers.
        val before = c.settings.lookValues.value
        val other = if (c.settings.current.glassKind == GlassKind.CLEAR) GlassChoice.LIQUID_REGULAR else GlassChoice.LIQUID_CLEAR
        compose.onAllNodes(hasText(other.label) and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.waitForText(tr(R.string.undo))
        Fixture.screenshot("undo-bar-glass")
        compose.onAllNodes(hasText(tr(R.string.undo)) and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { c.settings.lookValues.value == before }
        assertTrue(c.settings.current.glassEverywhere)
        // Performance mode pays nothing for it, and keeps the choice for later.
        runBlocking { c.settings.setPerformanceMode(true) }
        compose.waitUntil(5_000) { c.settings.current.performanceMode }
        assertFalse(c.settings.current.glassEverywhere)
        assertEquals(GlassLook(), c.settings.current.view.glass)
    }
}
