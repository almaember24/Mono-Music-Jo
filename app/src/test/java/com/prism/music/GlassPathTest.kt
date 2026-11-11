package com.prism.music

import com.prism.music.ui.theme.GlassPath
import com.prism.music.ui.theme.glassPath
import com.prism.music.ui.theme.glassSamplesThePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.7.9's check, Android 9: glass needs Android 12's blur. Below it, Clear glass showed Home's tiles sharp through the
 * tab bar and mini player; since 1.8.1.1 surfaces are solid there and the page isn't copied. Android 12+ is unchanged.
 */
class GlassPathTest {
    @Test fun liquidGlassWithBlur() = assertEquals(GlassPath.LIQUID, glassPath(31, liquid = true, frosted = false, haze = true))

    /** 1.8.2.1: up to a sixth off the graphics chip's frame on an A16, on the look most people have. */
    @Test fun frostSamplesAtHalfSizeExceptAThinOne() {
        assertEquals(dev.chrisbanes.haze.HazePerformanceMode.Performance, com.prism.music.ui.theme.frostedInput(thin = false))
        assertEquals(dev.chrisbanes.haze.HazePerformanceMode.Default, com.prism.music.ui.theme.frostedInput(thin = true))
    }

    @Test fun noGlassBelowAndroid12() {
        for (sdk in 26..30) {
            assertEquals("sdk $sdk", GlassPath.SOLID, glassPath(sdk, liquid = true, frosted = false, haze = true))
            assertEquals("sdk $sdk", GlassPath.SOLID, glassPath(sdk, liquid = false, frosted = true, haze = true))
            assertEquals("sdk $sdk", GlassPath.SOLID, glassPath(sdk, liquid = true, frosted = false, haze = true, refract = false))
        }
    }

    /** #273 (1.7.9.3): the docked tab bar is flat glass, not a lens, whatever the glass setting. */
    @Test fun flatBarsAreFrostedEvenWithLiquidGlass() {
        assertEquals(GlassPath.FROSTED, glassPath(36, liquid = true, frosted = false, haze = true, refract = false))
        assertEquals(GlassPath.LIQUID, glassPath(36, liquid = true, frosted = false, haze = true, refract = true))
    }

    @Test fun theRestIsAsBefore() {
        assertEquals(GlassPath.FROSTED, glassPath(34, liquid = false, frosted = true, haze = true))
        assertEquals(GlassPath.SOLID, glassPath(34, liquid = false, frosted = false, haze = true))
        assertEquals(GlassPath.SOLID, glassPath(34, liquid = true, frosted = true, haze = false))
    }

    /** #343 + Android 9's event storm: the page is copied into a layer only for glass that will sample it. */
    @Test fun thePageIsCopiedOnlyForGlass() {
        assertTrue(glassSamplesThePage(31, liquid = true, frosted = false))
        assertTrue(glassSamplesThePage(34, liquid = false, frosted = true))
        // Glass off, as Performance mode turns it.
        assertFalse(glassSamplesThePage(34, liquid = false, frosted = false))
        for (sdk in 26..30) assertFalse("sdk $sdk", glassSamplesThePage(sdk, liquid = true, frosted = true))
    }

    /** #355: the tab bar and mini player aren't drawn only while the keyboard covers all of them. */
    @Test fun theBarsGoOnlyWhenTheKeyboardCoversThemWhole() {
        assertTrue(com.prism.music.ui.theme.hiddenByKeyboard(900, 200f))
        assertTrue(com.prism.music.ui.theme.hiddenByKeyboard(200, 200f))
        // Still coming up, or a small bar for a hardware keyboard.
        assertFalse(com.prism.music.ui.theme.hiddenByKeyboard(120, 200f))
        // No keyboard, or a floating one, which takes no room.
        assertFalse(com.prism.music.ui.theme.hiddenByKeyboard(0, 200f))
        assertFalse(com.prism.music.ui.theme.hiddenByKeyboard(0, 0f))
    }

    /** Every surface that samples the page finds it copied: the two agree on every setting and Android version. */
    @Test fun glassIsOnlyDrawnWhereThePageIsCopied() {
        for (sdk in 26..36) for (liquid in listOf(false, true)) for (frosted in listOf(false, true)) for (refract in listOf(false, true)) {
            val sampled = glassPath(sdk, liquid, frosted, haze = true, refract = refract) != GlassPath.SOLID
            assertEquals("sdk $sdk liquid $liquid frosted $frosted refract $refract", glassSamplesThePage(sdk, liquid, frosted), sampled)
        }
    }
}
