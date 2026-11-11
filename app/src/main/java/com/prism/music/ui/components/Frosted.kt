package com.prism.music.ui.components

import com.prism.music.ui.theme.Mono
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.data.model.hiRes
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.boxBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Colors taken from a picture, for the pages that dress themselves in their artwork (artist,
 * album and playlist pages, like Apple Music's).
 */
@Immutable
data class ArtPalette(
    /** The liveliest color: buttons and links. */
    val vibrant: Color,
    /** What the picture is mostly made of. */
    val dominant: Color,
) {
    private fun hsl(c: Color) = FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) }
    private fun Color.withLightness(l: Float, satScale: Float = 1f): Color {
        val h = hsl(this); h[1] = (h[1] * satScale).coerceIn(0f, 1f); h[2] = l
        return Color(ColorUtils.HSLToColor(h))
    }

    /**
     * The picture's accent for its page (Appearance → Color → "Picture's color"), as deep and lively as Prism's own
     * accent: its liveliest color (else its main one) at a firm saturation and the lightness Prism's accents use. Null
     * when the picture has no color to give (a black-and-white cover): the page then keeps the listener's accent,
     * rather than a grey one that read as a faded version of it.
     */
    fun accent(dark: Boolean): Color? {
        val base = when {
            hsl(vibrant)[1] >= MIN_ACCENT_SATURATION -> vibrant
            hsl(dominant)[1] >= MIN_ACCENT_SATURATION -> dominant
            else -> return null
        }
        val h = hsl(base)
        h[1] = h[1].coerceIn(0.62f, 0.95f)
        h[2] = if (dark) 0.66f else 0.42f
        return Color(ColorUtils.HSLToColor(h))
    }

    companion object {
        /** Below this a picture's colors are greys (or nearly): no accent is taken from it. */
        const val MIN_ACCENT_SATURATION = 0.25f
    }

    /** The same palette in greys (White light). */
    fun grey(): ArtPalette {
        fun g(c: Color) = Color(ColorUtils.HSLToColor(hsl(c).also { it[1] = 0f }))
        return ArtPalette(g(vibrant), g(dominant))
    }

    /** The page color far below the picture, where the frost settles. */
    fun settle(dark: Boolean): Color = dominant.withLightness(if (dark) 0.09f else 0.93f, if (dark) 0.85f else 0.6f)

    /**
     * The colors the page settles into below its picture (#47), as on Apple Music: the picture's main color, a little
     * deeper toward the bottom, rather than a near-black (or white).
     */
    fun field(dark: Boolean): List<Color> {
        // A little richer since 1.7.2 (Reddit: "black, not colorful like before"); still deep enough for white text.
        return if (dark) listOf(dominant.withLightness(0.25f, 1.1f), dominant.withLightness(0.19f, 1.05f), dominant.withLightness(0.14f, 1f))
        else listOf(dominant.withLightness(0.84f, 0.85f), dominant.withLightness(0.89f, 0.75f), dominant.withLightness(0.93f, 0.65f))
    }
}

private val paletteCache = object : LinkedHashMap<String, ArtPalette>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ArtPalette>) = size > 48
}

/** A small, heavily blurred copy of a picture (blurred in software, so it works on every Android version). */
@Immutable
class FrostImage(val image: ImageBitmap)

private val frostCache = object : LinkedHashMap<String, FrostImage>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, FrostImage>) = size > 12
}

private suspend fun decodeSmall(context: android.content.Context, url: String, px: Int): Bitmap? {
    val req = ImageRequest.Builder(context).data(hiRes(url, 544)).size(px).allowHardware(false).build()
    return (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap()
}

@Composable
fun rememberArtPalette(url: String?): ArtPalette? {
    val context = LocalContext.current
    // The state outlives a change of [url] (produceState isn't reset by its keys), so each picture is looked up
    // afresh: checking `value != null` here kept the previous song's colors on Home (issue #3). The old colors
    // stay up until the new ones are ready, so nothing flashes.
    val palette by produceState(url?.let { synchronized(paletteCache) { paletteCache[it] } }, url) {
        val u = url ?: return@produceState
        synchronized(paletteCache) { paletteCache[u] }?.let { value = it; return@produceState }
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bmp = decodeSmall(context, u, 160) ?: return@runCatching null
                val p = Palette.from(bmp).maximumColorCount(16).generate()
                val dominant = (p.dominantSwatch ?: p.mutedSwatch ?: p.vibrantSwatch)?.rgb?.let { Color(Mono.gray(it or (0xFF shl 24))) } ?: return@runCatching null
                val vibrant = (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch)?.rgb?.let { Color(Mono.gray(it or (0xFF shl 24))) } ?: dominant
                ArtPalette(vibrant, dominant).also { synchronized(paletteCache) { paletteCache[u] = it } }
            }.getOrNull()
        }
    }
    return palette
}

@Composable
fun rememberFrost(url: String?): FrostImage? {
    val context = LocalContext.current
    // As [rememberArtPalette]: looked up for every new picture, the old frost held until the new one is ready.
    val frost by produceState(url?.let { synchronized(frostCache) { frostCache[it] } }, url) {
        val u = url ?: return@produceState
        synchronized(frostCache) { frostCache[u] }?.let { value = it; return@produceState }
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bmp = decodeSmall(context, u, 96) ?: return@runCatching null
                FrostImage(boxBlur(bmp, 3, (minOf(bmp.width, bmp.height) / 7).coerceAtLeast(3)).asImageBitmap())
                    .also { synchronized(frostCache) { frostCache[u] = it } }
            }.getOrNull()
        }
    }
    return frost
}

/** A fine grain over the frost, so large blurred areas read as frosted glass rather than flat color. */
private val grain: ImageBitmap by lazy {
    val n = 96
    val rnd = Random(7)
    val px = IntArray(n * n) { if (rnd.nextBoolean()) 0x22FFFFFF else 0x22000000 }
    Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private val saturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.35f) })
/** White light: the frost in greys, so color stays in the pictures themselves. */
private val greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * The Ambient frost's tone: the cover's colors kept, its brightness held to a deep tint on dark pages and a pale one on
 * light pages, so the page's text (and its secondary text, on panes too) reads at 4.5:1 or better whatever the cover:
 * a white or yellow cover no longer lights a dark page up behind white text, nor a black one a light page behind dark
 * text (AmbientContrastTest). [saturation] 0 is White light's greys.
 */
private fun ambientTone(dark: Boolean, saturation: Float, darkLevel: Float = 0.22f): ColorFilter {
    val k = if (dark) darkLevel else 0.17f
    val off = if (dark) 0f else (1f - k) * 255f
    val tone = ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, off, 0f, k, 0f, 0f, off, 0f, 0f, k, 0f, off, 0f, 0f, 0f, 1f, 0f))
    // Applied after the saturation (the tone's matrix times it).
    tone.timesAssign(ColorMatrix().apply { setToSaturation(saturation) })
    return ColorFilter.colorMatrix(tone)
}
private val ambientDark = ambientTone(dark = true, saturation = 1.35f)
private val ambientLight = ambientTone(dark = false, saturation = 1.35f)
private val ambientDarkGrey = ambientTone(dark = true, saturation = 0f)
private val ambientLightGrey = ambientTone(dark = false, saturation = 0f)
/**
 * Below an album, playlist or artist picture on a dark page: more of the picture's color than the app's own pages keep
 * (Reddit 1.7: "the playlist and album pages are black, not colorful like before"). Their text sits on the color
 * itself, with the wash below on top, and stays readable (AmbientContrastTest).
 */
private val pageDark = ambientTone(dark = true, saturation = 1.35f, darkLevel = PAGE_DARK_LEVEL)
private val pageDarkGrey = ambientTone(dark = true, saturation = 0f, darkLevel = PAGE_DARK_LEVEL)
internal const val PAGE_DARK_LEVEL = 0.32f

/**
 * A frosted page backdrop, after Apple Music's artist pages: [frost] (the hero picture, blurred)
 * sits exactly under the hero, which dissolves into it, and its foot then flows down the page,
 * settling into a tint of the picture. [heroScroll] is how far the hero has scrolled (px); the
 * picture inside the hero drifts at [parallax] of the scroll speed, and the backdrop follows it.
 */
@Composable
fun FrostedBackdrop(
    frost: FrostImage?,
    palette: ArtPalette?,
    heroHeight: Dp,
    heroScroll: () -> Float,
    modifier: Modifier = Modifier,
    parallax: Float = 0.5f,
    /** The picture's colors carry on down the page ([ArtPalette.field]), for artist, album and playlist pages (#47). */
    bleed: Boolean = false,
    /** The Ambient background of the app's own pages: its brightness held so their text reads ([ambientTone]). */
    ambient: Boolean = false,
    /** A backdrop that never scrolls ([heroScroll] is read as 0): drawn once into a picture, then shown ([BakedFrost]). */
    baked: Boolean = false,
) {
    val dark = LocalIsDark.current
    val settings = LocalAppSettings.current
    val pureBlack = settings.pureBlack
    val bg = MaterialTheme.colorScheme.background
    // Turned off in Appearance: the page's own background, as before.
    if (!settings.frostedPages) { Box(modifier.fillMaxSize().background(bg)); return }
    val shown by animateFloatAsState(if (frost != null) 1f else 0f, tween(500), label = "frost")
    val grainBrush = remember { ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)) }
    // Appearance → Color → Page background reaches the Ambient frost (1.7.2): Plain greys it, a tint colors it in that
    // tint, Automatic keeps the cover's own colors. Picture pages (artist, album, playlist) keep their picture's.
    val pageTint = if (!ambient) null else when (settings.surfaceTint) {
        com.prism.music.data.prefs.SurfaceTint.ACCENT -> MaterialTheme.colorScheme.primary
        com.prism.music.data.prefs.SurfaceTint.CUSTOM -> Color(settings.surfaceColor)
        else -> null
    }
    val plain = ambient && settings.surfaceTint == com.prism.music.data.prefs.SurfaceTint.NEUTRAL
    // From the settings in reach (not the app-wide token), so the Color page's examples show each choice's own frost.
    val mono = settings.lightStyle == com.prism.music.data.prefs.LightStyle.WHITE || plain
    val tintFilter = remember(pageTint, settings.surfaceTintStrength, dark) {
        pageTint?.let { ambientTintTone(dark, it, settings.surfaceTintStrength) }
    }
    if (baked) {
        BakedFrost(frost, if (tintFilter != null) null else palette, bg, heroHeight, parallax, dark, pureBlack, bleed, mono, ambient, tintFilter, grainBrush, modifier)
        return
    }
    Canvas(modifier.fillMaxSize()) {
        drawFrostedBackdrop(
            frost?.image, shown, if (tintFilter != null) null else palette, bg, heroHeight.toPx(), heroScroll(), parallax, dark, pureBlack,
            bleed, mono, ambient, tintFilter,
        )
        drawRect(grainBrush, alpha = if (dark) 0.5f else 0.35f)
    }
}

/**
 * [FrostedBackdrop] that stays put (a TV's, 1.8.0): the same drawing and grain, made once into a picture of its size
 * off the main thread whenever what it's made from changes, then shown as that picture (a new one fades in over the
 * old). A mid-range TV's GPU spent about 9 ms of every frame drawing the frost live, half of a 60 Hz frame's time.
 */
@Composable
private fun BakedFrost(
    frost: FrostImage?, palette: ArtPalette?, bg: Color, heroHeight: Dp, parallax: Float, dark: Boolean, pureBlack: Boolean,
    bleed: Boolean, mono: Boolean, ambient: Boolean, tintFilter: ColorFilter?, grainBrush: ShaderBrush, modifier: Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val picture by produceState<ImageBitmap?>(null, size, frost, palette, bg, heroHeight, parallax, dark, pureBlack, bleed, mono, ambient, tintFilter, density) {
        if (size.width <= 0 || size.height <= 0) return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                bakeFrost(size, density) {
                    drawFrostedBackdrop(
                        frost?.image, if (frost != null) 1f else 0f, palette, bg, heroHeight.toPx(), 0f, parallax, dark, pureBlack,
                        bleed, mono, ambient, tintFilter,
                    )
                    drawRect(grainBrush, alpha = if (dark) 0.5f else 0.35f)
                }
            }.getOrNull()
        } ?: value
    }
    androidx.compose.animation.Crossfade(
        picture, modifier.fillMaxSize().onSizeChanged { size = it }, animationSpec = tween(500), label = "frost",
    ) { p -> Canvas(Modifier.fillMaxSize()) { if (p != null) drawImage(p) else drawRect(bg) } }
}

/** [block] drawn into a new picture of [size] (software, so it can be made off the main thread). */
internal fun bakeFrost(size: IntSize, density: androidx.compose.ui.unit.Density, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): ImageBitmap {
    val bmp = ImageBitmap(size.width, size.height)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        density, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp),
        androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()), block,
    )
    return bmp
}

/**
 * The Ambient frost in one color ([tint], Page background → Accent tint or Your color): the cover's light and shade
 * kept, its colors swapped for the tint's ([strength] as the page tint's slider), then held to the page's tone so text
 * reads. Channels are only ever lowered, never lifted, so it's never brighter than the grey frost on a dark page.
 */
internal fun ambientTintTone(dark: Boolean, tint: Color, strength: Float): ColorFilter {
    val top = maxOf(tint.red, tint.green, tint.blue).coerceAtLeast(0.05f)
    val s = (0.45f + 0.25f * strength).coerceIn(0f, 1f)
    fun ch(v: Float) = 1f + s * (v / top - 1f)
    val k = if (dark) 0.22f else 0.17f
    // The tint also sits in the page's own floor, or it would barely show: on a light page the pale floor leans toward
    // it (a pastel, text still dark on it), on a dark page a faint glow of it is added (text still white on it).
    val lean = (0.10f + 0.08f * strength).coerceAtMost(0.4f)
    val glow = (0.02f + 0.02f * strength).coerceAtMost(0.08f)
    fun off(v: Float) = if (dark) 255f * glow * (v / top) else (1f - k) * 255f * (1f - lean * (1f - v / top))
    val tone = ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, off(tint.red), 0f, k, 0f, 0f, off(tint.green), 0f, 0f, k, 0f, off(tint.blue), 0f, 0f, 0f, 1f, 0f))
    tone.timesAssign(ColorMatrix(floatArrayOf(ch(tint.red), 0f, 0f, 0f, 0f, 0f, ch(tint.green), 0f, 0f, 0f, 0f, 0f, ch(tint.blue), 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    tone.timesAssign(ColorMatrix().apply { setToSaturation(0f) })
    return ColorFilter.colorMatrix(tone)
}

/**
 * [FrostedBackdrop]'s drawing (without its grain), apart so it can be drawn in tests. The hero's blurred picture, its
 * foot running on down the page, and the picture's colors ([ArtPalette.field]) settling in below that.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFrostedBackdrop(
    img: ImageBitmap?, shown: Float, palette: ArtPalette?, bg: Color, heroPx: Float, heroScroll: Float, parallax: Float, dark: Boolean, pureBlack: Boolean,
    bleed: Boolean,
    mono: Boolean = false,
    /** The Ambient background behind the app's own pages (no hero photo above it). */
    ambient: Boolean = false,
    /** The Ambient frost in the page tint's color ([ambientTintTone]); then it settles into the (tinted) page color. */
    tintTone: ColorFilter? = null,
) {
        // Below the picture, where a page's text sits, the frost is always held to the theme's tone (as the whole Ambient
        // background is); under the picture itself (which dissolves into it) it keeps the picture's full color.
        val toned = tintTone ?: if (dark) {
            if (ambient) (if (mono) ambientDarkGrey else ambientDark) else (if (mono) pageDarkGrey else pageDark)
        } else (if (mono) ambientLightGrey else ambientLight)
        val filter = if (ambient) toned else if (mono) greyscale else saturate
        @Suppress("NAME_SHADOWING") val palette = if (mono) palette?.grey() else palette
        val w = size.width
        val scroll = heroScroll.coerceIn(0f, heroPx)
        val heroBottom = heroPx - scroll
        val imgTop = -scroll * (1f - parallax)
        // Pure black keeps its deep settle; otherwise the picture's own colors carry the page (#47).
        val field = if (palette == null || pureBlack || !bleed) null else palette.field(dark)
        val settle = field?.last() ?: palette?.settle(dark) ?: bg
        drawRect(settle)
        if (img != null && shown > 0f) {
            // Centre-cropped to the hero's shape, as the photo above it is.
            val boxAspect = w / heroPx
            val imgAspect = img.width.toFloat() / img.height
            val (sw, sh) = if (imgAspect > boxAspect) (img.height * boxAspect) to img.height.toFloat() else img.width.toFloat() to (img.width / boxAspect)
            val sx = ((img.width - sw) / 2).roundToInt()
            val sy = ((img.height - sh) / 2).roundToInt()
            val srcSize = IntSize(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1))
            if (heroBottom > 0f) clipRect(bottom = heroBottom) {
                drawImage(img, IntOffset(sx, sy), srcSize, IntOffset(0, imgTop.roundToInt()), IntSize(w.roundToInt(), heroPx.roundToInt()), alpha = shown, colorFilter = filter, filterQuality = FilterQuality.Low)
                // The toned frost below the picture comes in gradually over the picture's lower part (where the picture
                // itself fades out), so there's no line where it ends: drawn into its own layer, faded in by a gradient.
                if (!ambient) {
                    val band = heroPx * 0.45f
                    // On whole pixels, so the layer's edge doesn't show as a line.
                    val bandTop = kotlin.math.floor(heroBottom - band).coerceAtLeast(0f)
                    val area = androidx.compose.ui.geometry.Rect(0f, bandTop, kotlin.math.ceil(w), kotlin.math.ceil(heroBottom))
                    drawContext.canvas.saveLayer(area, androidx.compose.ui.graphics.Paint())
                    drawImage(
                        img, IntOffset(sx, sy), srcSize, IntOffset(0, imgTop.roundToInt()), IntSize(w.roundToInt(), heroPx.roundToInt()),
                        alpha = shown, colorFilter = toned, filterQuality = FilterQuality.Low,
                    )
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = bandTop, endY = area.bottom),
                        topLeft = Offset(0f, area.top), size = androidx.compose.ui.geometry.Size(w, area.height), blendMode = BlendMode.DstIn,
                    )
                    drawContext.canvas.restore()
                }
            }
            val from = heroBottom.coerceAtLeast(0f)
            val below = (size.height - from).roundToInt()
            if (field != null) {
                // The picture carries on below itself, mirrored (#47): its foot meets its own reflection, so its colors
                // flow on down the page where they are in the picture, rather than as streaks or a flat color…
                if (below > 0) clipRect(top = from) {
                    scale(1f, -1f, pivot = Offset(0f, from)) {
                        drawImage(img, IntOffset(sx, sy), srcSize, IntOffset(0, imgTop.roundToInt()), IntSize(w.roundToInt(), heroPx.roundToInt()), alpha = shown, colorFilter = toned, filterQuality = FilterQuality.Low)
                    }
                }
                // …then settle into the picture's main color by the reflection's end, a little deeper toward the bottom.
                val mirrorEnd = (2 * from - imgTop).coerceAtLeast(from + 1f)
                drawRect(
                    Brush.verticalGradient(
                        0f to field[0].copy(alpha = 0f), 0.5f to field[0].copy(alpha = 0.55f), 1f to field[1],
                        startY = from, endY = mirrorEnd,
                    ),
                    topLeft = Offset(0f, from), size = androidx.compose.ui.geometry.Size(w, mirrorEnd - from),
                )
                if (mirrorEnd < size.height) drawRect(
                    Brush.verticalGradient(0f to field[1], 1f to field[2], startY = mirrorEnd, endY = size.height),
                    topLeft = Offset(0f, mirrorEnd),
                )
            } else {
                // The row of the picture at the hero's foot, drawn tall: its colors run on down the page.
                val frac = ((heroBottom - imgTop) / heroPx).coerceIn(0f, 1f)
                val row = (sy + frac * srcSize.height).roundToInt().coerceIn(sy, sy + srcSize.height - 1)
                if (below > 0) drawImage(
                    img, IntOffset(sx, (row - 1).coerceAtLeast(0)), IntSize(srcSize.width, 1),
                    IntOffset(0, from.roundToInt()), IntSize(w.roundToInt(), below),
                    alpha = shown, colorFilter = toned, filterQuality = FilterQuality.Low,
                )
                // …and settle into the page's tint further down.
                drawRect(
                    Brush.verticalGradient(0f to settle.copy(alpha = 0f), 1f to settle, startY = from, endY = from + size.height * 0.9f),
                    topLeft = Offset(0f, from),
                )
            }
        }
        // Keeps text readable: darker (or lighter) toward the bottom of the screen. Lighter-handed over the picture's
        // colors, which are kept dark (or light) enough to read on already.
        val wash = if (dark) Color.Black else Color.White
        val k = if (dark && pureBlack) 1.25f else 1f
        val (a0, a1, a2) = when {
            field == null -> if (dark) Triple(0.12f, 0.30f, 0.55f) else Triple(0.30f, 0.52f, 0.70f)
            dark -> Triple(0.10f, 0.22f, 0.32f)
            else -> Triple(0.24f, 0.40f, 0.52f)
        }
        drawRect(Brush.verticalGradient(0f to wash.copy(alpha = a0 * k), 0.45f to wash.copy(alpha = (a1 * k).coerceAtMost(0.9f)), 1f to wash.copy(alpha = (a2 * k).coerceAtMost(0.92f))))
}

/**
 * The frost behind the app's own pages (Home, Search, Library, Replay, Settings): the same
 * frosted glass as the artist and album pages, made from the cover of what's playing (or what
 * played last), so the whole app shares their look. It stays put while the page scrolls.
 */
@Composable
fun AmbientFrost(modifier: Modifier = Modifier) {
    val c = com.prism.music.ui.theme.LocalContainer.current
    val current by c.player.currentSong.collectAsState()
    val recent by c.library.recent.collectAsState()
    val url = current?.thumbnail ?: recent.firstOrNull()?.thumbnail
    val frost = rememberFrost(url)
    val palette = rememberArtPalette(url)
    FrostedBackdrop(frost, palette, AmbientFrostHeight, { 0f }, modifier, parallax = 0f, ambient = true)
}

/** How much of the top of a page the ambient frost's picture covers before it runs down the page. */
val AmbientFrostHeight = 360.dp

/** True on a page that sits on frosted glass: its panels, chips and buttons go see-through too. */
val LocalFrosted = compositionLocalOf { false }

/** The current page's backdrop, so a [ScrollEdge] can draw it again under the top bar. */
@Immutable
class PageBackdrop(val height: Dp, val draw: @Composable (Modifier) -> Unit)

val LocalPageBackdrop = compositionLocalOf<PageBackdrop?> { null }

/**
 * The fill of a card or panel: frosted glass (with its light top edge) on a frosted page,
 * [fallback] (the theme's container color by default) elsewhere.
 */
@Composable
fun Modifier.pane(shape: Shape, fallback: Color = Color.Unspecified): Modifier {
    if (LocalFrosted.current) {
        val dark = LocalIsDark.current
        // #326, "Glass on more of the screen": a light falling on it from above and a brighter edge, no blur. Only the
        // page's own background passes behind a card, and that's soft already, so a live blur wouldn't show but would cost.
        if (com.prism.music.ui.theme.LocalAppSettings.current.glassEverywhere) return clip(shape)
            .background(frostFill())
            .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.09f else 0.30f), 0.55f to Color.Transparent))
            .border(1.dp, glassEdge(), shape)
        return clip(shape).background(frostFill())
            .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.7f), Color.White.copy(alpha = 0.02f))), shape)
    }
    // On a plain page (frosted pages off): a soft card with the same light top edge.
    val scheme = MaterialTheme.colorScheme
    return clip(shape).background(if (fallback != Color.Unspecified) fallback else scheme.surfaceContainerHigh)
        .border(1.dp, glassEdge(), shape)
}

/** The fill of a feature card (a session code, the account, a call to action): a [pane]. Text on it uses [featureInk]. */
@Composable
fun Modifier.featurePane(shape: Shape): Modifier = pane(shape)

/** Text and icons on a [featurePane]. */
@Composable
fun featureInk(): Color = MaterialTheme.colorScheme.onSurface

/** A quiet fill for small controls: frosted on a frosted page, a faint ink tint (or [fallback]) elsewhere. */
@Composable
fun quietFill(fallback: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)): Color =
    if (LocalFrosted.current) frostFill(1.4f) else fallback

/**
 * A scroll edge under the status bar (and a [bar] below it) for pages on the app's own
 * backdrop: content melts away under it rather than meeting a hard line. Draws nothing when
 * the page has no backdrop to redraw.
 */
@Composable
fun TopScrollEdge(visible: () -> Float, bar: Dp = 0.dp) {
    val backdrop = LocalPageBackdrop.current ?: return
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    ScrollEdge(visible, top + bar + 34.dp, backdrop.height) { m -> backdrop.draw(m) }
}

/** Fades the bottom of whatever it's on to nothing, from [start] (0–1 down the height) to the foot. */
fun Modifier.dissolveBottom(start: Float = 0.5f): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Black, start to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

/** The see-through fill of frosted panels and buttons on a frosted page. */
@Composable
fun frostFill(strength: Float = 1f): Color {
    // #326: with "Glass on more of the screen" on, the Frost slider reaches the page's glass too; where the slider can't
    // be reached (Glass off, below Android 12) a value set earlier doesn't quietly thin the panels.
    val s = com.prism.music.ui.theme.LocalAppSettings.current
    val sliderHere = s.glassEverywhere && (s.liquidGlass || s.frostedGlass) && android.os.Build.VERSION.SDK_INT >= 31
    val k = strength * (if (sliderHere) s.view.glass.frost else 1f)
    return if (LocalIsDark.current) Color.White.copy(alpha = 0.10f * k) else Color.White.copy(alpha = 0.55f * k)
}

/** A pane of frosted glass for content on a frosted page: see-through, with a light top edge. */
@Composable
fun FrostedPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalIsDark.current
    Column(
        modifier.clip(shape).background(frostFill())
            .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.7f), Color.White.copy(alpha = 0.02f))), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

/**
 * The scroll edge of a frosted page, as iOS draws it: the page's own [backdrop] drawn again over
 * the top [height] of the screen, fading out downward, so content melts away under the bar rather
 * than meeting a hard edge. [pageHeight] is the page's full height, so the copy lines up exactly
 * with the backdrop behind; [visible] fades the edge in once the hero has scrolled away.
 */
@Composable
fun ScrollEdge(visible: () -> Float, height: Dp, pageHeight: Dp, backdrop: @Composable (Modifier) -> Unit) {
    if (pageHeight <= 0.dp) return
    Box(
        Modifier.fillMaxWidth().height(height).graphicsLayer { alpha = visible() }.clipToBounds().dissolveBottom(0.62f),
        contentAlignment = Alignment.TopStart,
    ) {
        backdrop(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).requiredHeight(pageHeight))
    }
}

/**
 * The app's theme with the page's accent swapped for the artwork's ([palette]), so its buttons,
 * links and playing marks wear the picture's color.
 */
@Composable
fun ArtworkAccent(palette: ArtPalette?, override: Color? = null, content: @Composable () -> Unit) {
    val dark = LocalIsDark.current
    val scheme = MaterialTheme.colorScheme
    // Appearance → Color → "Pages take their picture's color" (1.7.2); an artist's own color (picked on their page)
    // applies either way.
    val pageColors = com.prism.music.ui.theme.LocalAppSettings.current.pageColors
    // Always the same composition shape, palette or not: switching between calling content() bare and inside
    // MaterialTheme threw the whole subtree away when the palette arrived — a menu sheet being swiped down
    // was rebuilt mid-gesture (stuttered, or popped back up after being flung away).
    // White light with a white accent keeps every page neutral, so the artwork's color stays in the artwork.
    val themed = if (override != null) {
        val accent = com.prism.music.ui.theme.readableOn(override, scheme.background)
        scheme.copy(primary = accent, onPrimary = if (accent.luminance() > 0.5f) Color.Black else Color.White, surfaceTint = accent)
    } else {
        // White with a white accent keeps every page neutral, so the artwork's color stays in the artwork.
        val neutral = com.prism.music.ui.theme.PrismTokens.white && com.prism.music.ui.theme.isNeutralSeed(scheme.primary.toArgb())
        val fromPicture = if (palette == null || !pageColors || neutral) null
            else palette.accent(dark)
        // A picture with no color of its own (black and white) leaves the listener's accent in place.
        if (fromPicture == null) scheme else {
            val accent = com.prism.music.ui.theme.readableOn(fromPicture, scheme.background)
            val on = if (accent.luminance() > 0.5f) Color.Black else Color.White
            scheme.copy(primary = accent, onPrimary = on, surfaceTint = accent)
        }
    }
    // The page's own color, whether or not the page takes it as its accent: the playing song's mark can use it (Reddit,
    // 1.7.9.2, Color → "Playing song in the page's color").
    val pageColor = override?.let { com.prism.music.ui.theme.readableOn(it, scheme.background) }
        ?: palette?.accent(dark)?.let { com.prism.music.ui.theme.readableOn(it, scheme.background) }
    MaterialTheme(colorScheme = themed, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        androidx.compose.runtime.CompositionLocalProvider(LocalPageColor provides pageColor, content = content)
    }
}

/** The color of the page's picture (an album's cover, an artist's photo), or null off such pages. See [ArtworkAccent]. */
val LocalPageColor = androidx.compose.runtime.compositionLocalOf<Color?> { null }

private fun Color.luminance(): Float = ColorUtils.calculateLuminance(toArgb()).toFloat()
