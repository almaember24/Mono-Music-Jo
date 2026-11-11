package com.prism.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.GlassKind
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizePoint
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass

/** The backdrop that glass surfaces in the current subtree refract. */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

internal enum class GlassPath { LIQUID, FROSTED, SOLID }

/** Android 12: the first with real blur. */
private const val BLUR_SDK = 31

/**
 * How [GlassSurface] draws: Liquid Glass and Frosted need something behind to sample ([haze]) and Android 12's blur.
 * Without blur, Clear glass showed everything behind it sharp, so below Android 12 there's no glass: the surface is
 * solid (1.8.1.1), and nothing behind it needs copying ([glassSamplesThePage]).
 */
internal fun glassPath(sdk: Int, liquid: Boolean, frosted: Boolean, haze: Boolean, refract: Boolean = true): GlassPath = when {
    !haze || sdk < BLUR_SDK -> GlassPath.SOLID
    liquid && refract -> GlassPath.LIQUID
    liquid || frosted -> GlassPath.FROSTED
    else -> GlassPath.SOLID
}

/**
 * Whether anything will draw glass, which is the only reason to copy the whole page into a layer on every change of it
 * (hazeSource). With glass off (Performance mode: #343) or below Android 12 that was work for nothing.
 */
internal fun glassSamplesThePage(sdk: Int, liquid: Boolean, frosted: Boolean): Boolean = sdk >= BLUR_SDK && (liquid || frosted)

/**
 * Whether the tab bar and mini player sit wholly behind the keyboard, so nobody can see them. Their glass cost a Galaxy
 * A16's GPU about 10 ms of every frame there, for each letter typed (#355). A floating keyboard takes no room ([keyboardPx] 0).
 */
internal fun hiddenByKeyboard(keyboardPx: Int, heightPx: Float): Boolean = keyboardPx > 0 && keyboardPx >= heightPx

/**
 * What Regular and Clear are set to (Haze 2.0.1's own styles, read from its code). Regular: a 20-25 dp blur and a veil of 22%
 * black (55% white in light mode). Clear: a 1.25 dp blur, 18% black (17% white), stronger bending at the edges and a
 * brighter rim. Haze chooses between its dark and light values from the phone's dark mode, not from Prism's theme, so
 * [glassValues] gives it Prism's when the two differ.
 *
 * And what they look like (#252, on a Galaxy A16 with Android 16 and an Android 13 emulator): behind Regular colors spread
 * further the higher the radius, but small things like text stay a faint sharp outline at every radius from 6 to 40 dp. Behind
 * Clear colors keep their edges and small things soften. Why Regular keeps that outline isn't known: Haze's refraction detail
 * (already 0) and an opaque background made no difference.
 */
internal data class GlassValues(
    val specular: Float, val edgeShadow: Float, val ambient: Float, val contrast: Float, val whitePoint: Float, val chroma: Float,
)

internal fun glassValues(dark: Boolean, clear: Boolean): GlassValues = when {
    clear && dark -> GlassValues(0.42f, 0.25f, 0.22f, 0.12f, -0.18f, 1f)
    clear -> GlassValues(0.55f, 0.10f, 0.42f, 0.08f, 0.17f, 1.05f)
    dark -> GlassValues(0.38f, 0.32f, 0.08f, 0.08f, -0.22f, 1.1f)
    else -> GlassValues(0.48f, 0.14f, 0.15f, 0f, 0.55f, 1.5f)
}

/** Frosted Glass's blur, in dp; [thin] is Liquid Glass · Clear on a flat bar, which keeps what's behind it nearly sharp. */
internal fun frostedBlurDp(blur: Float, thin: Boolean): Float = if (thin) 6f else 28f * blur

/**
 * What's behind Frosted Glass is sampled at half size (Haze's own default is 0.79) on its usual blur: at 28 dp the two look the
 * same, and an A16's graphics chip does up to a sixth less for each frame on a playlist (Home: a little). A [thin] frost keeps
 * what's behind it nearly sharp, so it keeps the finer sampling.
 */
internal fun frostedInput(thin: Boolean): dev.chrisbanes.haze.HazePerformanceMode =
    if (thin) dev.chrisbanes.haze.HazePerformanceMode.Default else dev.chrisbanes.haze.HazePerformanceMode.Performance

/** Frosted Glass's own veil at the Frost slider's [frost] (1 = 0.55 dark, 0.62 light, as it has always been). */
internal fun frostedWash(dark: Boolean, frost: Float, thin: Boolean): Float =
    ((if (thin) (if (dark) 0.24f else 0.30f) else if (dark) 0.55f else 0.62f) * frost).coerceAtMost(0.95f)

/** Liquid Glass's veil toward black or white at the Frost slider's [frost]. */
internal fun liquidWhitePoint(dark: Boolean, clear: Boolean, frost: Float): Float =
    (glassValues(dark, clear).whitePoint * frost).coerceIn(-0.95f, 0.95f)

/**
 * Prism's own tint over Liquid Glass · Clear. None in dark mode since 1.8.2: 12% black over Clear's 18% made it darker than
 * Regular's 22%, so over a soft background the two came out about the same (#252).
 */
internal fun clearTint(dark: Boolean, frost: Float): Color =
    if (dark) Color.Transparent else Color.White.copy(alpha = (0.10f * frost).coerceAtMost(0.9f))

/** A slider's value, landing on Prism's own 100% when it's let go close to it. */
internal fun glassSnap(v: Float): Float = if (kotlin.math.abs(v - 1f) < 0.04f) 1f else v

/** Haze's Regular optics, its blur [scale] times as strong. */
private fun regularOptics(scale: Float) = GlassDefaults.optics.copy(
    blurRadius = OpticalSizeValue.Responsive(
        OpticalSizePoint(64.dp, 20.dp * scale), OpticalSizePoint(176.dp, 24.dp * scale), OpticalSizePoint(220.dp, 25.dp * scale),
    ),
)

/**
 * A surface that renders as refractive Liquid Glass when enabled in
 * Appearance settings, or as a soft translucent card otherwise.
 *
 * @param onMedia true for glass sitting over artwork/video (uses lighter, clearer optics)
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    tint: Color = Color.Unspecified,
    onMedia: Boolean = false,
    elevation: Dp = 10.dp,
    /**
     * The surface is part of a page (inside the NavHost, which is the haze source itself). Haze can't draw glass
     * that samples its own source, so the search box came out with no background at all with Liquid or Frosted
     * Glass on (issue #5): in a page it's frosted with the page's own fill instead.
     */
    inPage: Boolean = false,
    /**
     * False for flat, edge-to-edge bars (the docked tab bar): frosted even with Liquid Glass on. As a lens it bent
     * everything behind it across the tabs and showed its edges at the screen's sides (#273, 1.7.9.3).
     */
    refract: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = LocalAppSettings.current
    val haze = LocalHazeState.current.takeUnless { inPage }
    val dark = LocalIsDark.current
    if (inPage && com.prism.music.ui.components.LocalFrosted.current) {
        Box(
            modifier
                .shadow(elevation * 0.5f, shape, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.22f))
                .clip(shape)
                .background(if (tint.isSpecified) tint else if (onMedia) Color.Black.copy(alpha = 0.35f) else com.prism.music.ui.components.frostFill(1.4f))
                .rim(Color.White.copy(alpha = if (dark || onMedia) 0.18f else 0.6f), Color.White.copy(alpha = 0.03f), shape, all = refract),
            content = content,
        )
    } else if (glassPath(android.os.Build.VERSION.SDK_INT, settings.liquidGlass, settings.frostedGlass, haze != null, refract) == GlassPath.LIQUID) {
        val clear = onMedia || settings.glassKind == GlassKind.CLEAR
        val look = settings.view.glass
        val phoneDark = (androidx.compose.ui.platform.LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val style = remember(shape, tint, clear, dark, phoneDark, onMedia, look.blur, look.frost) {
            val baseStyle = if (clear) GlassStyle.clear else GlassStyle.regular
            baseStyle.then {
                shape(shape)
                // Prism's theme, where the phone's dark mode says otherwise.
                if (dark != phoneDark) glassValues(dark, clear).let { v ->
                    specularIntensity(v.specular)
                    edgeShadow(Color.Black.copy(alpha = v.edgeShadow))
                    ambientResponse(v.ambient)
                    contrast(v.contrast)
                    whitePoint(v.whitePoint)
                    chromaMultiplier(v.chroma)
                }
                if (tint.isSpecified) tint(tint)
                else if (onMedia) tint(Color.Black.copy(alpha = 0.12f))
                else {
                    if (clear) clearTint(dark, look.frost).let { if (it.alpha > 0f) tint(it) }
                    // #252: the Frost slider. The bar's own Background (Light, Solid) and glass over a picture keep theirs.
                    if (look.frost != 1f) whitePoint(liquidWhitePoint(dark, clear, look.frost))
                }
                if (!clear && look.blur != 1f) optics(regularOptics(look.blur))
            }
        }
        Box(
            modifier
                .shadow(elevation * 0.6f, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .hazeGlass(input = HazeInput.Backdrop(haze), style = style)
                .clip(shape),
            content = content,
        )
    } else if (glassPath(android.os.Build.VERSION.SDK_INT, settings.liquidGlass, settings.frostedGlass, haze != null, refract) == GlassPath.FROSTED) {
        val scheme = MaterialTheme.colorScheme
        val look = settings.view.glass
        // Liquid Glass reaches this path only on a flat bar (the docked tab bar). Regular and Clear drew exactly the same
        // here, so on that bar there was nothing to tell them apart (#252): Clear's is a thin frost.
        val thin = settings.liquidGlass && settings.glassKind == GlassKind.CLEAR
        val wash = when {
            tint.isSpecified -> tint
            onMedia -> Color.Black.copy(alpha = 0.28f)
            else -> scheme.surfaceContainerHigh.copy(alpha = frostedWash(dark, look.frost, thin))
        }
        val blurDp = frostedBlurDp(look.blur, thin)
        val style = remember(wash, blurDp) {
            HazeBlurStyle {
                blurRadius(blurDp.dp)
                noiseFactor(0.12f)
                colorEffects(listOf(HazeColorEffect.tint(wash)))
            }
        }
        Box(
            modifier
                .shadow(elevation * 0.6f, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(shape)
                .hazeBlur(input = HazeInput.Backdrop(haze), style = style, performanceMode = frostedInput(thin))
                .rim(Color.White.copy(alpha = if (dark || onMedia) 0.18f else 0.55f), Color.White.copy(alpha = 0.03f), shape, all = refract),
            content = content,
        )
    } else {
        val scheme = MaterialTheme.colorScheme
        // Opaque: with no glass, rows scrolling under the mini player read through even a 3% see-through fill.
        // Glass asked for where there's no blur (Android 11 or older): a see-through tint thick enough to read over.
        val noBlur = android.os.Build.VERSION.SDK_INT < BLUR_SDK && (settings.liquidGlass || settings.frostedGlass)
        val fill = if (onMedia) Color.Black.copy(alpha = if (noBlur) 0.55f else 0.35f) else scheme.surfaceContainerHigh
        Box(
            modifier
                .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                .clip(shape)
                .background(if (tint.isSpecified) (if (noBlur) tint.copy(alpha = tint.alpha.coerceAtLeast(0.88f)) else tint) else fill)
                .rim(Color.White.copy(alpha = if (dark || onMedia) 0.16f else 0.6f), Color.White.copy(alpha = 0.02f), shape, all = refract),
            content = content,
        )
    }
}

/**
 * The glass's lit edge: brightest at the top ([top]), fading to [bottom] down its sides. Not [all] (a flat bar from one
 * side of the screen to the other, the docked tab bar), only the top edge's hairline: down the screen's sides the edge
 * showed as two bright lines (#290).
 */
private fun Modifier.rim(top: Color, bottom: Color, shape: RoundedCornerShape, all: Boolean): Modifier =
    if (all) border(0.6.dp, Brush.verticalGradient(listOf(top, bottom)), shape)
    else drawWithContent {
        drawContent()
        drawLine(top, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 0.6.dp.toPx())
    }
