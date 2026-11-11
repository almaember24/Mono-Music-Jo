package com.prism.music

import androidx.compose.ui.graphics.Color
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.GlassLook
import com.prism.music.data.prefs.ViewLook
import com.prism.music.ui.theme.clearTint
import com.prism.music.ui.theme.frostedBlurDp
import com.prism.music.ui.theme.frostedWash
import com.prism.music.ui.theme.glassSnap
import com.prism.music.ui.theme.glassValues
import com.prism.music.ui.theme.liquidWhitePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #252, #326: the sliders at their defaults draw what 1.8.1 drew, and Performance mode turns all of it off. */
class GlassLookTest {
    @Test fun aFreshInstallDrawsAsBefore() {
        val g = AppSettings().view.glass
        assertEquals(1f, g.blur)
        assertEquals(1f, g.frost)
        assertFalse(g.more)
        // Frosted Glass: the 28 dp and the veil it has always had.
        assertEquals(28f, frostedBlurDp(g.blur, thin = false))
        assertEquals(0.55f, frostedWash(dark = true, g.frost, thin = false))
        assertEquals(0.62f, frostedWash(dark = false, g.frost, thin = false))
        // Liquid Glass at Frost 100% has no value set from here (GlassSurface only sets one away from 1), and if it did,
        // Haze's own: these are read from its code, so a newer Haze with other numbers fails here.
        assertEquals(-0.22f, liquidWhitePoint(dark = true, clear = false, g.frost))
        assertEquals(0.55f, liquidWhitePoint(dark = false, clear = false, g.frost))
    }

    @Test fun theSlidersDoWhatTheySay() {
        assertEquals(56f, frostedBlurDp(2f, thin = false))
        assertEquals(0f, frostedWash(true, 0f, thin = false))
        // Never fully solid: it's still glass.
        assertEquals(0.95f, frostedWash(false, 2f, thin = false))
        assertEquals(0f, liquidWhitePoint(true, false, 0f), 0.0001f)
        assertEquals(-0.44f, liquidWhitePoint(true, false, 2f))
        assertEquals(0.95f, liquidWhitePoint(false, false, 2f))
    }

    /** Close to 100% is 100%, so a slider that's been moved can go back to exactly Prism's own. */
    @Test fun aSliderLandsOnItsDefault() {
        assertEquals(1f, glassSnap(1.03f))
        assertEquals(1f, glassSnap(0.97f))
        assertEquals(0.9f, glassSnap(0.9f))
        assertEquals(1.2f, glassSnap(1.2f))
    }

    /** #252: "regular and clear look basically the same". */
    @Test fun clearIsThinnerThanRegularEverywhere() {
        // On a flat bar, which used to draw both the same.
        assertTrue(frostedBlurDp(1f, thin = true) < frostedBlurDp(1f, thin = false))
        for (dark in listOf(true, false)) assertTrue(frostedWash(dark, 1f, thin = true) < frostedWash(dark, 1f, thin = false))
        // On a floating bar, in dark mode, it no longer has a heavier veil than Regular.
        assertEquals(Color.Transparent, clearTint(dark = true, frost = 1f))
        assertTrue(kotlin.math.abs(glassValues(true, clear = true).whitePoint) < kotlin.math.abs(glassValues(true, clear = false).whitePoint))
        assertTrue(kotlin.math.abs(glassValues(false, clear = true).whitePoint) < kotlin.math.abs(glassValues(false, clear = false).whitePoint))
    }

    @Test fun performanceModePaysNothing() {
        val set = AppSettings(
            liquidGlass = true, performanceMode = true,
            view = ViewLook(glass = GlassLook(blur = 2f, frost = 0.2f, more = true)),
        )
        val e = set.effective()
        assertEquals(GlassLook(), e.view.glass)
        assertFalse(e.liquidGlass || e.frostedGlass)
        assertFalse(e.glassEverywhere)
        // The settings pages are given the stored settings: the look stays off there too.
        assertFalse(set.glassEverywhere)
        assertTrue(set.copy(performanceMode = false).glassEverywhere)
    }
}
