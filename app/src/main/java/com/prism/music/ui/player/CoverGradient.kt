package com.prism.music.ui.player

import com.prism.music.ui.theme.Mono
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * #232, #338 (1.8.2): the player's background as a solid gradient of the cover's own colors, nothing blurred. Its [lead]
 * color at the top, its [second] through the middle, and down to a deep tone of the lead at the foot; the line runs a little
 * off the vertical, so the two colors meet on a slant rather than in level bands.
 *
 * The player's type is always white, so every stop is kept dark enough for it: [TOP_CEILING] for white type where the cover
 * and the status bar sit, and [SMALL_TYPE_CONTRAST] lower down, where the small grey type is (`CoverGradientTest` measures
 * it, on pale and on very saturated covers).
 */
@Immutable
data class CoverGradient(val lead: Int, val second: Int)

/** The three colors drawn for [CoverGradient] at a depth. */
@Immutable
internal data class GradientStops(val top: Int, val middle: Int, val foot: Int)

/** White type reads at 4.5:1 or better over this. */
internal const val TOP_CEILING = CEILING

/**
 * What the middle and foot colors hold for white at 70%, the player's small type. A little over the 4.5:1 it has to read at,
 * since the slant brings some of the lighter top color into the left of the screen at the middle's height.
 */
private const val SMALL_TYPE_CONTRAST = 5.2

/** Hues further apart than this are opposites. */
private const val CLASH_DEGREES = 110f

/** Where the middle color sits, down the screen. */
internal const val GRADIENT_MIDDLE_AT = 0.46f

/** A cover with a hue but nearly no light still shows it: its color isn't let any darker than this. */
private const val COLORED_FLOOR = 0.16f

/** A swatch this small is a detail, not one of the cover's colors. */
private const val MIN_SHARE = 0.02f

/** Hues this far apart read as two colors. */
private const val OTHER_HUE_DEGREES = 30f

private fun hslOf(c: Int) = FloatArray(3).also { ColorUtils.colorToHSL(c, it) }

private fun scaled(c: Int, k: Float): Int {
    fun ch(sh: Int) = (((c shr sh) and 0xFF) * k).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * [c] dimmed, all its channels alike, until [fine] holds. Dimmed rather than lowered in HSL lightness, which keeps HSL's
 * saturation and so turned a pale pink cover hot pink: dimmed, it stays the dusty color it was.
 */
private fun dimmedUntil(c: Int, fine: (Int) -> Boolean): Int {
    if (fine(c)) return c
    var lo = 0f
    var hi = 1f
    repeat(14) {
        val mid = (lo + hi) / 2
        if (fine(scaled(c, mid))) lo = mid else hi = mid
    }
    return scaled(c, lo)
}

private fun under(c: Int, ceiling: Double): Int = dimmedUntil(c) { ColorUtils.calculateLuminance(it) <= ceiling }

/** White at 70%, the player's small type, over [c]. */
private fun smallTypeContrast(c: Int): Double =
    ColorUtils.calculateContrast(ColorUtils.compositeColors(0xB3FFFFFF.toInt(), c), c)

private fun forSmallType(c: Int): Int = dimmedUntil(c) { smallTypeContrast(it) >= SMALL_TYPE_CONTRAST }

private fun mixed(a: Int, b: Int, t: Float): Int = ColorUtils.blendARGB(a, b, t)

/** How much of a cover a swatch's color stands for, and how much it has to give: chosen for the gradient by this. */
private fun appeal(rgb: Int, share: Float): Float {
    val hsl = hslOf(rgb)
    val light = when {
        hsl[2] < 0.08f -> 0.3f
        hsl[2] > 0.93f -> 0.4f
        else -> 1f
    }
    return sqrt(share) * (0.2f + hsl[1]) * light
}

private fun hueApart(a: Float, b: Float): Float = abs(a - b).let { if (it > 180f) 360f - it else it }

/**
 * The cover's two colors from its swatches (color, how many pixels): the one that's both large and has the most color
 * leads, and the next that's clearly another hue comes second (the lead again when there's none: a one-color cover
 * is one color, deepening). Null with nothing to go on.
 */
internal fun coverGradientOf(swatches: List<Pair<Int, Int>>): CoverGradient? {
    val total = swatches.sumOf { it.second }.coerceAtLeast(1)
    val big = swatches.filter { it.second.toFloat() / total >= MIN_SHARE }.ifEmpty { swatches }
    val ranked = big.sortedByDescending { appeal(it.first, it.second.toFloat() / total) }
    val lead = ranked.firstOrNull()?.first ?: return null
    val leadHue = hslOf(lead)[0]
    val second = ranked.drop(1).firstOrNull {
        val hsl = hslOf(it.first)
        hsl[1] >= 0.18f && hueApart(hsl[0], leadHue) >= OTHER_HUE_DEGREES
    }?.first ?: lead
    return CoverGradient(lead, second)
}

/**
 * [coverGradientOf] for a picture. Palette's own filter is cleared: it leaves out oranges it takes for skin, so a teal and
 * orange cover came out all teal; near-black and near-white, which it also leaves out, are marked down by [appeal] instead.
 */
internal fun coverGradientOf(bmp: Bitmap): CoverGradient? =
    coverGradientOf(Palette.from(bmp).maximumColorCount(16).clearFilters().generate().swatches.map { Mono.gray(it.rgb or (0xFF shl 24)) to it.population })

/** The colors drawn at [depth] (0 nearly the same tone all the way down, 1 black at the foot). */
internal fun CoverGradient.stops(depth: Float): GradientStops {
    val d = depth.coerceIn(0f, 1f)
    val hsl = hslOf(lead)
    // A nearly black cover with a hue still shows it. By how far apart its channels are: HSL's saturation means little this
    // dark, and a plain black, a step off grey once Palette has rounded it, read as a color.
    val channels = listOf((lead shr 16) and 0xFF, (lead shr 8) and 0xFF, lead and 0xFF)
    if (channels.max() - channels.min() >= 16 && hsl[2] < COLORED_FLOOR) hsl[2] = COLORED_FLOOR
    val top = under(ColorUtils.HSLToColor(hsl), TOP_CEILING)
    // Opposite hues (blue and orange) go through mud as they darken: the second is taken half way back to the lead.
    val clash = hueApart(hslOf(second)[0], hslOf(lead)[0]) > CLASH_DEGREES
    val middle = scaled(forSmallType(if (clash) mixed(second, lead, 0.5f) else second), 1f - 0.45f * d)
    val foot = scaled(forSmallType(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1] * 0.85f, hsl[2] * 0.7f))), (1f - d).pow(1.2f))
    return GradientStops(top, middle, foot)
}

/**
 * The gradient's paint for a [w] × [h] screen. Dithered: a long gradient in dark colors shows bands otherwise, and
 * Compose's own brushes have no way to ask for it.
 */
private fun gradientPaint(top: Int, middle: Int, foot: Int, w: Float, h: Float): Paint = Paint(Paint.DITHER_FLAG).apply {
    shader = LinearGradient(
        w * 0.14f, 0f, w * 0.86f, h,
        intArrayOf(top, middle, foot), floatArrayOf(0f, GRADIENT_MIDDLE_AT, 1f), Shader.TileMode.CLAMP,
    )
}

/** [stops] over the whole of what's being drawn (the contact sheets and the small examples use it too). */
internal fun DrawScope.drawCoverGradient(stops: GradientStops) {
    val paint = gradientPaint(stops.top, stops.middle, stops.foot, size.width, size.height)
    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
}

private val gradientCache = object : LinkedHashMap<String, CoverGradient>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, CoverGradient>) = size > 64
}

/** The cover's gradient once it's worked out, the last song's until then. A null [url] does no work at all. */
@Composable
fun rememberCoverGradient(url: String?): CoverGradient? {
    val context = LocalContext.current
    val held = remember { mutableStateOf<CoverGradient?>(null) }
    var gradient by remember(url) { mutableStateOf(url?.let(gradientCache::get) ?: held.value) }
    LaunchedEffect(gradient) { held.value = gradient }
    LaunchedEffect(url) {
        if (url == null || gradientCache[url] != null) return@LaunchedEffect
        repeat(3) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(1500)
            val bmp = runCatching { decodeSmall(context, url, 120) }.getOrNull()
            if (bmp != null) {
                val built = withContext(Dispatchers.Default) { runCatching { coverGradientOf(bmp) }.getOrNull() }
                if (built != null) {
                    gradientCache[url] = built
                    gradient = built
                }
                return@LaunchedEffect
            }
        }
    }
    return gradient
}

/**
 * The background itself: three colors in one shader, built once and only built again while a new song's colors fade in. No
 * blur and no drifting. In a layer of its own, so that it's painted once and then only placed while the rest of the player
 * draws every frame (the lyric sweep, say). On a Galaxy A16, drawn this way, it costs the same as the mesh.
 */
@Composable
fun CoverGradientBackdrop(gradient: CoverGradient?, depth: Float, modifier: Modifier = Modifier) {
    val stops = gradient?.stops(depth)
    val quick = com.prism.music.ui.theme.LocalUi.current.reduceMotion
    val spec = tween<Color>(if (quick) 0 else 900, easing = FastOutSlowInEasing)
    val top by animateColorAsState(Color(stops?.top ?: PlayerFallbackBackdrop.toArgb()), spec, label = "gradientTop")
    val middle by animateColorAsState(Color(stops?.middle ?: PlayerFallbackBackdrop.toArgb()), spec, label = "gradientMiddle")
    val foot by animateColorAsState(Color(stops?.foot ?: PlayerFallbackBackdrop.toArgb()), spec, label = "gradientFoot")
    Spacer(
        modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
            val paint = gradientPaint(top.toArgb(), middle.toArgb(), foot.toArgb(), size.width, size.height)
            onDrawBehind { drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) } }
        },
    )
}
