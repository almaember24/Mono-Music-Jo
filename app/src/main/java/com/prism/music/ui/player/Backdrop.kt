package com.prism.music.ui.player

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.data.model.hiRes
import com.prism.music.ui.theme.boxBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

val PlayerFallbackBackdrop = Color(0xFF060606)

/** #320 (1.8.1): the White player background (a soft white, so a white cover still has an edge). */
val PlayerWhiteBackdrop = Color(0xFFF7F7F7)

/**
 * #320 (1.8.1): the player's type and controls are drawn white for its dark backgrounds; over the White one they're
 * drawn through this, which turns light into dark and dark into light while keeping each color's hue (an inversion
 * with the hue turned back round): white type reads near-black, a white play button turns black with a white
 * glyph, the split light keeps its colors. [light] is set by the player while it shows the White background (not over
 * the lyrics or the queue, which keep their dark blurred cover).
 */
object PlayerInk {
    var light by androidx.compose.runtime.mutableStateOf(false)

    /** Inverts lightness (255 − c), then turns hue round 180° (W3C's hue matrix at 180°), so hues stay. */
    internal val matrix = floatArrayOf(
        0.574f, -1.43f, -0.144f, 0f, 255f,
        -0.426f, -0.43f, -0.144f, 0f, 255f,
        -0.426f, -1.43f, 0.856f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )
    private val paint by lazy {
        androidx.compose.ui.graphics.Paint().apply {
            colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(matrix))
        }
    }

    /** What [color] reads as over the White background. */
    fun over(color: Color): Color {
        val m = matrix
        val r = color.red * 255; val g = color.green * 255; val b = color.blue * 255
        fun ch(i: Int) = ((m[i] * r + m[i + 1] * g + m[i + 2] * b + m[i + 4]) / 255f).coerceIn(0f, 1f)
        return Color(ch(0), ch(5), ch(10), color.alpha)
    }

    /**
     * Draws what's in it as it reads over the White background while [on] (read as it draws, so a change only redraws).
     * Shadows and glows a little outside it are kept.
     */
    fun Modifier.inked(on: () -> Boolean = { light }): Modifier = this.drawWithContent {
        if (!on()) { drawContent(); return@drawWithContent }
        val reach = 96.dp.toPx()
        drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(-reach, -reach, size.width + reach, size.height + reach), paint)
        drawContent()
        drawContext.canvas.restore()
    }
}

/**
 * #309 (1.8.1): "Show background" goes below what it could before, on down to a fully dark player. Stored values from
 * 0 to 1 draw exactly as they always did; from 0 down to this one a black veil comes over the background, solid here.
 * The slider shows the whole stretch as 0-100%, so its 0% is fully dark.
 */
const val BACKDROP_SHOW_MIN = -0.25f

/** How much of the black veil a stored "Show background" [show] puts over the player's background (0 = none, 1 = solid). */
fun backdropBlackout(show: Float): Float = (show / BACKDROP_SHOW_MIN).coerceIn(0f, 1f)

/** Where on the slider (0..1) a stored "Show background" [show] sits. */
fun backdropShowOnSlider(show: Float): Float = ((show - BACKDROP_SHOW_MIN) / (1f - BACKDROP_SHOW_MIN)).coerceIn(0f, 1f)

/** The stored "Show background" for a place [at] (0..1) on the slider. */
fun backdropShowFromSlider(at: Float): Float = BACKDROP_SHOW_MIN + at.coerceIn(0f, 1f) * (1f - BACKDROP_SHOW_MIN)

/** The cover reduced to a tiny, smoothly interpolated color field. */
@Immutable
class ArtworkMesh internal constructor(internal val image: ImageBitmap)

private const val GRID = 6
private const val TEX = 32
private const val VIBRANCE = 1.12f
private const val FLOOR = 0.045f

private val meshCache = object : LinkedHashMap<String, ArtworkMesh>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ArtworkMesh>) = size > 64
}
private val blurCache = object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 8
}

internal suspend fun decodeSmall(context: android.content.Context, url: String, px: Int): Bitmap? {
    val req = ImageRequest.Builder(context).data(hiRes(url, 544)).size(px).allowHardware(false).build()
    return (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap()
}

@Composable
fun rememberArtworkMesh(url: String?): ArtworkMesh? {
    val context = LocalContext.current
    val held = remember { mutableStateOf<ArtworkMesh?>(null) }
    var mesh by remember(url) { mutableStateOf(url?.let(meshCache::get) ?: held.value) }
    LaunchedEffect(mesh) { held.value = mesh }
    LaunchedEffect(url) {
        if (url == null || meshCache[url] != null) return@LaunchedEffect
        buildMesh(context, url)?.let { mesh = it }
    }
    return mesh
}

/** Works out and keeps [url]'s mesh. On the main thread (the cache isn't shared), with the work off it. */
private suspend fun buildMesh(context: android.content.Context, url: String): ArtworkMesh? {
    repeat(3) { attempt ->
        if (attempt > 0) kotlinx.coroutines.delay(1500)
        val bmp = runCatching { decodeSmall(context, url, 120) }.getOrNull()
        if (bmp != null) {
            val built = withContext(Dispatchers.Default) { meshOf(bmp, url.hashCode()) }
            if (built != null) meshCache[url] = built
            return built
        }
    }
    return null
}

/**
 * The player slides in and out, with no fade on top (#354, #343). A fade draws the whole player into a picture of its own
 * every frame and then draws that on the screen, which on a phone's graphics chip was most of the stutter: an A16 had six
 * frames over 34 ms each time the player opened, whatever was on the player (Performance mode or not, blur or not). The
 * player covers the whole screen, so the fade was barely seen.
 */
internal fun playerSlideIn(reduceMotion: Boolean): androidx.compose.animation.EnterTransition =
    androidx.compose.animation.slideInVertically(tween(if (reduceMotion) 260 else 420)) { it }

internal fun playerSlideOut(reduceMotion: Boolean): androidx.compose.animation.ExitTransition =
    androidx.compose.animation.slideOutVertically(tween(if (reduceMotion) 240 else 360)) { it }

/**
 * The size the player's cover is decoded at (#86: whatever size it's first laid out at), no bigger than the screen shows
 * it: the screen's short side, or its long side for a full-bleed picture (cropped to fill it). A Redmi A3's 720 px screen
 * has no use for 1200 px, which is near three times the memory to fill and hand to the graphics chip.
 */
internal fun playerCoverPx(widthPx: Int, heightPx: Int, fullBleed: Boolean): Int =
    minOf(1200, if (fullBleed) maxOf(widthPx, heightPx) else minOf(widthPx, heightPx))

internal fun playerCoverRequest(context: android.content.Context, url: String?, fullBleed: Boolean): ImageRequest {
    val m = context.resources.displayMetrics
    return ImageRequest.Builder(context).data(url).size(playerCoverPx(m.widthPixels, m.heightPixels, fullBleed)).build()
}

/**
 * Gets what the player shows for a song ready while it isn't open (#354, a Redmi A3: "a noticeable single stutter" opening
 * the player, as the artwork loads): the cover at the player's size, in memory, and the backdrop worked out from it. The
 * player then has them in its first frame rather than taking them mid-slide. Only while Prism is on screen (nothing
 * here runs for a song that changes with the phone in a pocket). On the main thread, the work off it.
 */
internal suspend fun warmPlayerArt(context: android.content.Context, art: String, fullBleed: Boolean, meshToo: Boolean, blurStrength: Float) {
    if (meshToo && meshCache[art] == null) buildMesh(context, art)
    val step = blurStep(blurStrength)
    val key = blurKey(art, step)
    if (blurCache[key] == null) buildBlur(context, art, step)?.let { blurCache[key] = it }
    // Last: a picture with one address whatever the size (a file's cover) keeps one copy in memory, the last one asked for.
    SingletonImageLoader.get(context).execute(playerCoverRequest(context, hiRes(art, 1200), fullBleed))
}

/**
 * The player's backdrop: the mesh hung from [seam] (where the artwork's bottom
 * edge sits) and stretched over everything below, its first row held above.
 */
@Composable
fun ArtworkMeshBackdrop(
    mesh: ArtworkMesh?,
    modifier: Modifier = Modifier,
    seam: Dp = 0.dp,
    /**
     * Settings → Player → Background (1.7.4): [speed] 0 holds it still (as before), up to 1 it drifts and breathes
     * slowly; [intensity] its colors' strength (1 as before); [blur] how soft (1 as before).
     */
    speed: Float = 0f,
    intensity: Float = 1f,
    blur: Float = 1f,
    /**
     * The system's blur over the mesh (Android 12+), redone every frame it's drawn. Off on a TV (1.8.0): the mesh is a
     * smoothly scaled texture already, and a TV's GPU spends its frames on focus moves instead.
     */
    liveBlur: Boolean = true,
) {
    var shown by remember { mutableStateOf(mesh) }
    var incoming by remember { mutableStateOf<ArtworkMesh?>(null) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(mesh) {
        val next = mesh ?: return@LaunchedEffect
        incoming?.let { shown = it }
        incoming = null
        val current = shown
        if (next === current) return@LaunchedEffect
        if (current == null) { shown = next; return@LaunchedEffect }
        incoming = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        shown = next
        incoming = null
    }
    // The drift moves the drawn layer only (scaled up a little so its edges never show): what's drawn and blurred
    // inside it stays as it is, so it costs the GPU a transform a frame, not a redraw.
    val move = meshDrift(speed).takeUnless { com.prism.music.ui.theme.LocalUi.current.reduceMotion }
    val drift = if (move == null) null else rememberInfiniteTransition(label = "meshDrift").animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(move.loopMs, easing = LinearEasing)),
        label = "drift",
    )
    val tone = remember(intensity) {
        if (kotlin.math.abs(intensity - 1f) < 0.01f) null
        else androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(intensity) })
    }
    Canvas(
        modifier
            .fillMaxSize()
            .background(PlayerFallbackBackdrop)
            .graphicsLayer {
                val t = drift?.value ?: return@graphicsLayer
                val m = move ?: return@graphicsLayer
                val s = m.scale + 0.04f * kotlin.math.sin(t * 2)
                scaleX = s; scaleY = s
                translationX = size.width * m.reachX * kotlin.math.cos(t)
                translationY = size.height * m.reachY * kotlin.math.sin(t)
            }
            .then(if (liveBlur && Build.VERSION.SDK_INT >= 31) Modifier.blur(32.dp * blur.coerceIn(0.3f, 2f)) else Modifier),
    ) {
        val seamY = seam.toPx().coerceIn(0f, size.height)
        shown?.let { drawMesh(it, seamY, 1f, tone) }
        incoming?.let { drawMesh(it, seamY, fade.value, tone) }
        // Aura × Spectrum (1.7): the cover's colors glow, deepening under the controls so the white type reads.
        drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.08f), 0.55f to Color.Black.copy(alpha = 0.22f), 1f to Color.Black.copy(alpha = 0.42f)))
    }
}

/**
 * How the color mesh drifts at [speed] (0 still … 1 lively): one loop in [loopMs], reaching [reachX] / [reachY] of the
 * screen to the sides and up and down, drawn [scale] times its size (breathing a little round it) so its edges never
 * show. Null holds it still.
 */
internal data class MeshDrift(val loopMs: Int, val reachX: Float, val reachY: Float, val scale: Float)

/**
 * #284 (1.8.1): at 100% one loop took 48 s and went 4% of the way across, too slow and small to see as movement. Every
 * step of the slider is now three times as quick (16 s a loop at 100%), and the livelier it is the farther it goes (up
 * to 9% across).
 */
internal fun meshDrift(speed: Float): MeshDrift? {
    if (speed <= 0.01f) return null
    val v = speed.coerceIn(0.1f, 1f)
    val reachX = 0.04f + 0.05f * v
    val reachY = 0.03f + 0.04f * v
    // Twice the farthest reach plus the breathing (±0.04) and a little more, so the drifted layer still covers the screen.
    val scale = 1f + 2f * maxOf(reachX, reachY) + 0.08f
    return MeshDrift((16_000 / v).roundToInt(), reachX, reachY, scale)
}

/**
 * [ArtworkMeshBackdrop] as it rests on a TV (no drift, no live blur, no seam): the same fill, mesh and deepening,
 * for the TV to make once into a picture (1.8.0).
 */
internal fun DrawScope.drawStillMesh(mesh: ArtworkMesh) {
    drawRect(PlayerFallbackBackdrop)
    drawMesh(mesh, 0f, 1f)
    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.08f), 0.55f to Color.Black.copy(alpha = 0.22f), 1f to Color.Black.copy(alpha = 0.42f)))
}

private fun DrawScope.drawMesh(mesh: ArtworkMesh, seamY: Float, alpha: Float, tone: androidx.compose.ui.graphics.ColorFilter? = null) {
    if (alpha <= 0.001f) return
    val img = mesh.image
    val w = size.width.roundToInt()
    if (seamY > 0.5f) drawImage(img, IntOffset.Zero, IntSize(img.width, 1), IntOffset.Zero, IntSize(w, seamY.roundToInt()), alpha = alpha, colorFilter = tone, filterQuality = FilterQuality.Low)
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(0, seamY.roundToInt()), IntSize(w, (size.height - seamY).roundToInt()), alpha = alpha, colorFilter = tone, filterQuality = FilterQuality.Low)
}

private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

/**
 * The player's type is always white, so a pale cover (A Night at the Opera, most white sleeves) can't give a pale
 * backdrop: each cell's lightness comes down until its luminance is under [CEILING], hue and saturation kept.
 */
internal fun ceiled(hsl: FloatArray): Int {
    var c = ColorUtils.HSLToColor(hsl)
    if (ColorUtils.calculateLuminance(c) <= CEILING) return c
    var lo = 0f; var hi = hsl[2]
    repeat(12) {
        val mid = (lo + hi) / 2
        if (ColorUtils.calculateLuminance(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], mid))) > CEILING) hi = mid else lo = mid
    }
    c = ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], lo))
    return c
}

/** White over this luminance (before the backdrop's own darkening) reads at about 4.5:1 or better. */
internal const val CEILING = 0.18

/** The blurred cover gets at least a third black on top, which takes 0.40 down to about 0.16. */
internal const val BLUR_CEILING = 0.40

/** Darkens [bmp]'s pixels brighter than [ceiling] (relative luminance) to it, keeping their hue; in place. */
internal fun capLuminance(bmp: Bitmap, ceiling: Double): Bitmap {
    val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
    val w = out.width; val h = out.height
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) {
        val p = px[i]
        val lum = ColorUtils.calculateLuminance(p)
        if (lum <= ceiling) continue
        // Luminance goes roughly with the 2.2th power of the channels.
        val k = Math.pow(ceiling / lum, 1 / 2.2).toFloat()
        fun ch(sh: Int) = (((p shr sh) and 0xFF) * k).roundToInt().coerceIn(0, 255)
        px[i] = (p and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

private fun meshOf(src: Bitmap, seed: Int): ArtworkMesh? {
    val w = src.width
    val h = src.height
    if (w < 1 || h < 1) return null
    val cols = GRID.coerceAtMost(w)
    val rows = GRID.coerceAtMost(h)
    val n = rows * cols
    val r = LongArray(n); val g = LongArray(n); val b = LongArray(n); val cnt = IntArray(n)
    val line = IntArray(w)
    for (y in 0 until h) {
        src.getPixels(line, 0, w, 0, y, w, 1)
        // Flipped vertically: row 0 is the artwork's bottom edge.
        val base = ((h - 1 - y) * rows / h) * cols
        for (x in 0 until w) {
            val cell = base + x * cols / w
            val p = line[x]
            r[cell] += (p shr 16) and 0xFF; g[cell] += (p shr 8) and 0xFF; b[cell] += p and 0xFF
            cnt[cell]++
        }
    }
    val grid = IntArray(n) { i ->
        val c = cnt[i].coerceAtLeast(1)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(argb((r[i] / c).toInt(), (g[i] / c).toInt(), (b[i] / c).toInt()), hsl)
        hsl[1] = (hsl[1] * VIBRANCE).coerceAtMost(1f)
        hsl[2] = hsl[2].coerceAtLeast(FLOOR)
        ceiled(hsl)
    }
    // Shift (and maybe mirror) every row below the seam row so the backdrop never
    // reads as a reflection of the cover, while keeping each cell's neighbours.
    val rnd = Random(seed)
    val mirror = rnd.nextBoolean()
    val shift = rnd.nextInt(cols)
    val arranged = grid.copyOf()
    for (row in 1 until rows) for (x in 0 until cols) {
        val s = if (mirror) cols - 1 - x else x
        arranged[row * cols + x] = grid[row * cols + (s + shift) % cols]
    }
    val tex = IntArray(TEX * TEX)
    fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    fun lerp(a: Int, c: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) + ((((c shr sh) and 0xFF) - ((a shr sh) and 0xFF)) * t)).roundToInt().coerceIn(0, 255)
        return argb(ch(16), ch(8), ch(0))
    }
    for (ty in 0 until TEX) {
        val fy = (ty + 0.5f) / TEX * rows - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, rows - 1); val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val wy = smooth(fy - y0)
        for (tx in 0 until TEX) {
            val fx = (tx + 0.5f) / TEX * cols - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cols - 1); val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val wx = smooth(fx - x0)
            tex[ty * TEX + tx] = lerp(lerp(arranged[y0 * cols + x0], arranged[y0 * cols + x1], wx), lerp(arranged[y1 * cols + x0], arranged[y1 * cols + x1], wx), wy)
        }
    }
    return ArtworkMesh(Bitmap.createBitmap(tex, TEX, TEX, Bitmap.Config.ARGB_8888).asImageBitmap())
}

/**
 * A small, pre-blurred copy of the cover for the lyrics and queue views; [strength] (Settings → Player → Background,
 * 1.7.4) makes it softer or clearer than 1, as before.
 */
@Composable
fun rememberFullArtworkBlur(url: String?, strength: Float = 1f): ImageBitmap? {
    val context = LocalContext.current
    val step = blurStep(strength)
    val key = url?.let { blurKey(it, step) }
    var image by remember(key) { mutableStateOf(key?.let(blurCache::get)) }
    LaunchedEffect(key) {
        if (url == null || key == null || image != null) return@LaunchedEffect
        val blurred = buildBlur(context, url, step) ?: return@LaunchedEffect
        blurCache[key] = blurred
        image = blurred
    }
    return image
}

private fun blurStep(strength: Float) = (strength.coerceIn(0.4f, 1.8f) * 10).roundToInt()
private fun blurKey(url: String, step: Int) = if (step == 10) url else "$url@$step"

private suspend fun buildBlur(context: android.content.Context, url: String, step: Int): ImageBitmap? {
    val bmp = runCatching { decodeSmall(context, url, 128) }.getOrNull() ?: return null
    return withContext(Dispatchers.Default) {
        capLuminance(boxBlur(bmp, 3, ((minOf(bmp.width, bmp.height) / 12f) * step / 10f).roundToInt().coerceAtLeast(1)), BLUR_CEILING)
    }.asImageBitmap()
}

@Composable
fun FullArtworkBlurBackdrop(image: ImageBitmap?, modifier: Modifier = Modifier, scrim: Float = 1f) {
    val a by animateFloatAsState(if (image != null) 1f else 0f, tween(320), label = "blurImg")
    Box(modifier.fillMaxSize().background(PlayerFallbackBackdrop)) {
        image?.let {
            val painter = remember(it) { BitmapPainter(it) }
            Image(painter, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = a; scaleX = 1.15f; scaleY = 1.15f })
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.34f * scrim), 0.55f to Color.Black.copy(alpha = 0.48f * scrim), 1f to Color.Black.copy(alpha = (0.64f * scrim).coerceAtMost(0.9f)),
            ))
        }
    }
}

