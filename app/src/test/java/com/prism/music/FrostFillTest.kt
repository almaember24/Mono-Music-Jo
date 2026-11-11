package com.prism.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.GlassLook
import com.prism.music.data.prefs.ViewLook
import com.prism.music.ui.components.frostFill
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalIsDark
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #326: the Frost slider reaches the fill of chips and round buttons, but it isn't always there to move: a Look shared with
 * "more glass" and Frost at 0 would leave them with no fill and no way to bring it back, so where the slider can't be
 * reached the fill is the usual one.
 */
abstract class FrostFillCase {
    @get:Rule val compose = createComposeRule()

    protected fun alphaWith(settings: AppSettings): Float {
        var seen = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides settings, LocalIsDark provides true) { seen = frostFill() }
        }
        compose.waitForIdle()
        return seen.alpha
    }

    /** As the Look shared would set it, with Glass off. */
    protected val shared = AppSettings(frostedGlass = false, view = ViewLook(glass = GlassLook(frost = 0f, more = true)))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FrostFillBelowAndroid12Test : FrostFillCase() {
    @Test fun noSliderBelowAndroid12() = assertEquals(0.10f, alphaWith(shared.copy(liquidGlass = true)), 0.003f)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FrostFillTest : FrostFillCase() {
    @Test fun noSliderWithGlassOff() = assertEquals(0.10f, alphaWith(shared), 0.003f)

    @Test fun theSliderCountsWhereItsThere() {
        assertEquals(0f, alphaWith(shared.copy(frostedGlass = true)), 0.003f)
    }

    @Test fun halfwayIsHalf() {
        val half = AppSettings(liquidGlass = true, view = ViewLook(glass = GlassLook(frost = 0.5f, more = true)))
        assertEquals(0.05f, alphaWith(half), 0.003f)
    }

    /** Off the page's glass, the slider has nothing to do with it. */
    @Test fun withoutMoreGlassItsTheUsualFill() {
        val apart = AppSettings(liquidGlass = true, view = ViewLook(glass = GlassLook(frost = 0f, more = false)))
        assertEquals(0.10f, alphaWith(apart), 0.003f)
    }
}
