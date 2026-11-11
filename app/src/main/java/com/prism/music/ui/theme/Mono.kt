package com.prism.music.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.LightStyle
import com.prism.music.data.prefs.SurfaceTint

/**
 * Monochrome Prism: every color the app draws is a grey. One switch for the whole thing.
 *
 * - [ENABLED] false gives the original colorful Prism back (nothing else needs to change).
 * - [GREY_PICTURES] also draws covers, artist photos and every other picture in greys, by running the whole UI through a
 *   saturation-0 filter ([monochrome]). Set it false to keep the pictures in color while the interface stays grey.
 */
object Mono {
    const val ENABLED = true
    const val GREY_PICTURES = true

    /** [argb] as the grey of the same brightness (Rec. 601 luma), alpha kept. */
    fun gray(argb: Int): Int {
        if (!ENABLED) return argb
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val y = (0.299f * r + 0.587f * g + 0.114f * b + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (y shl 16) or (y shl 8) or y
    }

    fun gray(c: Color): Color = if (!ENABLED) c else Color(gray(c.toArgb()))

    /** [Color.hsl] with no saturation: the lightness is kept, the hue and color are not. */
    fun hsl(h: Float, s: Float, l: Float, alpha: Float = 1f): Color = Color.hsl(h, if (ENABLED) 0f else s, l, alpha)
}

/**
 * The settings as the monochrome Prism has them: a neutral (white / ink) accent, white light instead of the spectrum,
 * grey surfaces, no color taken from the cover or the wallpaper. Applied where the settings are read and again in
 * [PrismTheme], so a saved color, a Look or a backup can't bring color back.
 */
fun AppSettings.monochrome(): AppSettings = if (!Mono.ENABLED) this else copy(
    colorSource = ColorSource.CUSTOM,
    accent = 0xFFFFFFFF.toInt(),
    accentLight = 0,
    accentExact = false,
    lightStyle = LightStyle.WHITE,
    spectrumPreset = "SILVER",
    spectrumStops = emptyList(),
    spectrumBands = false,
    surfaceTint = SurfaceTint.NEUTRAL,
    surfaceTintStrength = 0f,
    surfaceColor = 0xFF808080.toInt(),
    artworkVivid = false,
    pageColors = false,
)

private val greyPaint by lazy {
    Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
}

/** Draws this and everything inside it with no saturation (see [Mono.GREY_PICTURES]). */
fun Modifier.monochrome(): Modifier =
    if (!Mono.ENABLED || !Mono.GREY_PICTURES) this
    else drawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), greyPaint)
            drawContent()
            canvas.restore()
        }
    }
