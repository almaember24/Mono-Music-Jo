package com.prism.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.prism.music.ui.theme.ColorMath
import com.prism.music.ui.theme.SpectrumPresets
import com.prism.music.ui.theme.contrastRatio
import com.prism.music.ui.theme.schemeFromSeed
import com.prism.music.ui.theme.withSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The color picker's arithmetic and the 1.7.2 color settings' effect on the theme (ColorUtils needs Robolectric). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class ColorTest {
    @Test
    fun readsColorCodes() {
        assertEquals(0xFF3A86FF.toInt(), ColorMath.parseHex("#3A86FF"))
        assertEquals(0xFF3A86FF.toInt(), ColorMath.parseHex("3a86ff"))
        assertEquals(0xFF3A86FF.toInt(), ColorMath.parseHex("  #3A 86 FF "))
        assertEquals(0xFF3388FF.toInt(), ColorMath.parseHex("#38F"))
        assertEquals(0x803A86FF.toInt(), ColorMath.parseHex("#803A86FF"))
        assertNull(ColorMath.parseHex(""))
        assertNull(ColorMath.parseHex("#12345"))
        assertNull(ColorMath.parseHex("#GG0000"))
        assertNull(ColorMath.parseHex("blue"))
        assertEquals("#3A86FF", ColorMath.toHex(0xFF3A86FF.toInt()))
        assertEquals("#803A86FF", ColorMath.toHex(0x803A86FF.toInt()))
    }

    @Test
    fun hsvAndHslRoundTrip() {
        val samples = listOf(0xFF3A86FF, 0xFFFF4D5E, 0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFFFFD84D, 0xFF0E7B6C, 0xFF7C5CFF, 0xFF010203).map { it.toInt() }
        for (c in samples) {
            val v = ColorMath.toHsv(c)
            assertEquals(ColorMath.toHex(c), ColorMath.toHex(ColorMath.fromHsv(v[0], v[1], v[2])))
            val l = ColorMath.toHsl(c)
            assertEquals(ColorMath.toHex(c), ColorMath.toHex(ColorMath.fromHsl(l[0], l[1], l[2])))
        }
        // Pure hues land where the wheel draws them.
        assertEquals(0xFFFF0000.toInt(), ColorMath.fromHsv(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), ColorMath.fromHsv(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), ColorMath.fromHsv(240f, 1f, 1f))
        assertEquals(0xFFFF0000.toInt(), ColorMath.fromHsv(360f, 1f, 1f))
    }

    @Test
    fun readabilityFollowsWcag() {
        val black = 0xFF000000.toInt(); val white = 0xFFFFFFFF.toInt()
        assertEquals(21.0, ColorMath.contrast(black, white), 0.01)
        assertEquals(ColorMath.Readability.AAA, ColorMath.readability(black, white))
        // #767676 on white is the classic 4.54:1.
        assertEquals(ColorMath.Readability.AA, ColorMath.readability(0xFF767676.toInt(), white))
        assertEquals(ColorMath.Readability.LARGE, ColorMath.readability(0xFF949494.toInt(), white))
        assertEquals(ColorMath.Readability.POOR, ColorMath.readability(0xFFFFE066.toInt(), white))
        // The picker's sums agree with the theme's contrast guard.
        assertEquals(contrastRatio(Color(0xFF3A86FF), Color.White).toDouble(), ColorMath.contrast(0xFF3A86FF.toInt(), white), 0.02)
    }

    @Test
    fun prismsOwnLightIsUnchanged() {
        assertSame(SpectrumPresets.PRISM_LIGHT, SpectrumPresets.light("PRISM", emptyList(), bands = false))
        // Unknown keys, and "your own" without colors, fall back to it.
        assertSame(SpectrumPresets.PRISM_LIGHT, SpectrumPresets.light("NOPE", emptyList(), bands = false))
        assertSame(SpectrumPresets.PRISM_LIGHT, SpectrumPresets.light("CUSTOM", listOf(0xFFFF0000.toInt()), bands = false))
        assertEquals(Color(0xFFFF4D5E), SpectrumPresets.PRISM_LIGHT.colors.first().second)
    }

    @Test
    fun everyLightReadsOnItsPages() {
        val dark = Color(0xFF0E0E12); val light = Color(0xFFFAFAFB)
        val lights = SpectrumPresets.all.filter { it.key != "PRISM" }.map { SpectrumPresets.light(it.key, emptyList(), false) } +
            SpectrumPresets.light("CUSTOM", listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFFF00.toInt()), false)
        for (l in lights) {
            l.colors.forEach { (_, c) -> assertTrue("$c on dark", contrastRatio(c, dark) >= 2.99f) }
            l.deep.forEach { (_, c) -> assertTrue("$c on light", contrastRatio(c, light) >= 2.99f) }
            l.hues.forEach { assertTrue("white icon on $it", contrastRatio(Color.White, it) >= 2.39f) }
            assertEquals(7, l.hues.size)
            assertEquals(l.ring.first(), l.ring.last())
        }
    }

    @Test
    fun bandsAreHardStripes() {
        val l = SpectrumPresets.light("OCEAN", emptyList(), bands = true)
        // Each color twice: where its band starts and where it ends.
        assertEquals(8, l.colors.size)
        assertEquals(l.colors[0].second, l.colors[1].second)
        assertEquals(l.colors[1].first, l.colors[2].first, 0.0001f)
        assertEquals(1f, l.colors.last().first, 0.0001f)
    }

    @Test
    fun exactShadeKeepsTheColorWhenItReads() {
        val blue = Color(0xFF3A86FF)
        val exact = schemeFromSeed(blue, dark = true, pureBlack = false, exact = true)
        assertEquals(blue.toArgb(), exact.primary.toArgb())
        // Not exact: Prism's own lighter tone, as before.
        val toned = schemeFromSeed(blue, dark = true, pureBlack = false)
        assertTrue(toned.primary.toArgb() != blue.toArgb())
        // A pale yellow on a light page can't be used as is: it's deepened until it reads.
        val yellow = schemeFromSeed(Color(0xFFFFE066), dark = false, pureBlack = false, exact = true)
        assertTrue(contrastRatio(yellow.primary, yellow.background) >= 4.49f)
        // Higher contrast asks more of it.
        val high = schemeFromSeed(blue, dark = false, pureBlack = false, exact = true, minRatio = 7.5f)
        assertTrue(contrastRatio(high.primary, high.background) >= 7.49f)
    }

    @Test
    fun backgroundTint() {
        val base = schemeFromSeed(Color(0xFF3A86FF), dark = true, pureBlack = false)
        val neutral = withSurfaces(base, 0f, 0f, dark = true, pureBlack = false)
        val bg = ColorMath.toHsl(neutral.background.toArgb())
        assertTrue("neutral has no color", bg[1] < 0.02f)
        val strong = withSurfaces(base, 140f, 3f, dark = true, pureBlack = false)
        val sb = ColorMath.toHsl(strong.surfaceContainer.toArgb())
        assertTrue("strong tint is green-ish: ${sb[0]}", abs(sb[0] - 140f) < 12f && sb[1] > 0.2f)
        // The accent and secondary text still read on the tinted page.
        assertTrue(contrastRatio(strong.onSurfaceVariant, strong.background) >= 4.49f)
        assertTrue(contrastRatio(strong.primary, strong.background) >= 4.49f)
        // Pure black stays black.
        assertEquals(Color.Black, withSurfaces(base, 140f, 3f, dark = true, pureBlack = true).background)
    }
}
