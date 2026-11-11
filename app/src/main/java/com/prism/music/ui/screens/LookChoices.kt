package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.GlassKind
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.tr

/** Glass (1.8.0): Liquid glass, its style and Frosted glass as one choice (they interacted: liquid overrides frosted). */
enum class GlassChoice(val label: String) {
    OFF(tr(R.string.off)), FROSTED(tr(R.string.frosted_glass)),
    LIQUID_REGULAR(tr(R.string.liquid_glass) + " · " + tr(R.string.regular)), LIQUID_CLEAR(tr(R.string.liquid_glass) + " · " + tr(R.string.glass_clear)),
}

/** Changing screens (1.8.0): "Fades between screens" and "Slide between screens" (#208) as one choice. */
enum class ScreenChange(val label: String) { FADE(tr(R.string.fade)), SLIDE(tr(R.string.slide)), NONE(tr(R.string.none)) }

/**
 * Customize Prism's merged choices, over the settings Prism already keeps (liquidGlass + glassKind + frostedGlass,
 * screenFades + pageSlide), so nothing is migrated; LookChoices are read the same way glassPath reads them.
 */
object LookChoices {
    fun glass(s: AppSettings): GlassChoice = when {
        s.liquidGlass -> if (s.glassKind == GlassKind.CLEAR) GlassChoice.LIQUID_CLEAR else GlassChoice.LIQUID_REGULAR
        s.frostedGlass -> GlassChoice.FROSTED
        else -> GlassChoice.OFF
    }

    /** liquidGlass, its kind (null = leave), frostedGlass (null = leave: under liquid it changes nothing). */
    data class GlassValues(val liquid: Boolean, val kind: GlassKind?, val frosted: Boolean?)

    fun glassValues(g: GlassChoice): GlassValues = when (g) {
        GlassChoice.OFF -> GlassValues(false, null, false)
        GlassChoice.FROSTED -> GlassValues(false, null, true)
        GlassChoice.LIQUID_REGULAR -> GlassValues(true, GlassKind.REGULAR, null)
        GlassChoice.LIQUID_CLEAR -> GlassValues(true, GlassKind.CLEAR, null)
    }

    fun setGlass(prefs: SettingsRepository, g: GlassChoice) {
        val v = glassValues(g)
        prefs.setGlassChoice(v.liquid, v.kind, v.frosted)
    }

    /** Slide only ever worked with fades on, so fades off reads as None whatever pageSlide says. */
    fun screenChange(s: AppSettings): ScreenChange = when {
        !s.behavior.screenFades -> ScreenChange.NONE
        s.behavior.pageSlide -> ScreenChange.SLIDE
        else -> ScreenChange.FADE
    }

    /** screenFades, pageSlide. */
    fun screenChangeValues(c: ScreenChange): Pair<Boolean, Boolean> = when (c) {
        ScreenChange.FADE -> true to false
        ScreenChange.SLIDE -> true to true
        ScreenChange.NONE -> false to false
    }

    fun setScreenChange(prefs: SettingsRepository, c: ScreenChange) {
        val (fades, slide) = screenChangeValues(c)
        prefs.setPageSlide(slide)
        prefs.setScreenFades(fades)
    }
}
