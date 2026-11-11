package com.prism.music.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/*
 * The seasonal backgrounds (1.8.0): Harvest (Thanksgiving: fall leaves, a turkey, pies, pumpkins, gourds and wheat) and
 * Holiday (Christmas: snow, trees, ornaments, gifts, candy canes, stars and holly). Prism's own drawings, in code.
 *
 * The pictures sit in the page's corners and along its edges, and thin out toward the middle, where lists are read.
 * Each scene is built once per size ([scene]): what holds still is drawn once into a picture of the page, and a frame
 * draws that picture and then only what moves: leaves drift down tumbling, snow falls, ornaments sway, stars twinkle, in
 * whole loops so nothing jumps when time starts over. The clock ([drift]) holds still under the full player and while
 * Prism isn't on screen (no frames come then), and with Reduce motion or Performance mode.
 * Inks are blended into the page's own color as they're built (opaque, so overlapping parts don't show seams), and
 * fainter on Pure black's black page.
 */

/** One shape of a scene, its ink already blended for where it sits. */
private class Piece(val path: Path, val color: Color, val style: DrawStyle = Fill)

/** A soft light behind a scene. */
private class Glow(val brush: Brush, val center: Offset, val radius: Float)

/**
 * Something that falls (a leaf, a snow crystal), drawn from [shape] at the origin, [size] times over; [vein] on top.
 * It crosses the page [speed] times per loop, sways [swayK] times and turns [spin] times; [tumble] flips it over.
 */
private class Faller(
    val shape: Path, val color: Color, val style: DrawStyle, val vein: Path?, val veinColor: Color, val veinStyle: DrawStyle,
    val x0: Float, val y0: Float, val size: Float, val speed: Int, val swayAmp: Float, val swayK: Int, val phase: Float,
    val rot0: Float, val spin: Int, val tumble: Int,
)

/** A snowflake: a dot. */
private class Flake(val x0: Float, val y0: Float, val r: Float, val speed: Int, val swayAmp: Float, val swayK: Int, val phase: Float, val color: Color)

/** Shapes hung from [pivot] that sway [amp] degrees either way, [k] times per loop. */
private class Swing(val pivot: Offset, val pieces: List<Piece>, val amp: Float, val k: Int, val phase: Float)

/** A star that brightens and dims [k] times per loop. */
private class Twinkle(val path: Path, val color: Color, val k: Int, val phase: Float)

private class Scene(
    var glows: List<Glow>,
    var back: List<Piece>,
    val swings: List<Swing>,
    val twinkles: List<Twinkle>,
    var front: List<Piece>,
    val flakes: List<Flake>,
    val fallers: List<Faller>,
    /** How far a falling thing travels per loop, and how far above the page it starts. */
    val span: Float,
    val margin: Float,
) {
    /**
     * Everything that holds still (the page's color, its glows, the turkey, pies, trees, gifts and snow), drawn once into
     * a picture of the page; a frame draws it in one go and then only what moves.
     */
    lateinit var still: ImageBitmap
}

// ------------------------------------------------------------------------------------ Drawing

private const val TAU = 2f * PI.toFloat()

/** Thanksgiving's harvest, at animation time [t] (0..1). */
internal fun DrawScope.harvest(colors: List<Color>, dark: Boolean, t: Float, density: Float) =
    drawScene(scene(BackdropKind.HARVEST, colors, dark, density), t)

/** Christmas, at animation time [t] (0..1). */
internal fun DrawScope.holiday(colors: List<Color>, dark: Boolean, t: Float, density: Float) =
    drawScene(scene(BackdropKind.HOLIDAY, colors, dark, density), t)

private fun DrawScope.drawScene(s: Scene, t: Float) {
    drawImage(s.still)
    for (i in s.swings.indices) {
        val g = s.swings[i]
        rotate(g.amp * sin(TAU * (t * g.k + g.phase)), g.pivot) {
            for (j in g.pieces.indices) { val p = g.pieces[j]; drawPath(p.path, p.color, style = p.style) }
        }
    }
    for (i in s.twinkles.indices) {
        val tw = s.twinkles[i]
        drawPath(tw.path, tw.color, alpha = 0.3f + 0.7f * (0.5f + 0.5f * sin(TAU * (t * tw.k + tw.phase))))
    }
    val span = s.span
    for (i in s.flakes.indices) {
        val f = s.flakes[i]
        val y = ((f.y0 + t * span * f.speed) % span + span) % span - s.margin
        val x = f.x0 + f.swayAmp * sin(TAU * (t * f.swayK + f.phase))
        drawCircle(f.color, f.r, Offset(x, y))
    }
    for (i in s.fallers.indices) {
        val f = s.fallers[i]
        val y = ((f.y0 + t * span * f.speed) % span + span) % span - s.margin
        val x = f.x0 + f.swayAmp * sin(TAU * (t * f.swayK + f.phase))
        val flip = cos(TAU * (t * f.tumble + f.phase * 0.7f))
        translate(x, y) {
            rotate(f.rot0 + 360f * f.spin * t, Offset.Zero) {
                // Tumbling: the leaf turns over, never quite edge-on.
                scale(f.size * (if (f.tumble == 0) 1f else 0.25f + 0.75f * abs(flip)), f.size, Offset.Zero) {
                    drawPath(f.shape, f.color, style = f.style)
                    f.vein?.let { drawPath(it, f.veinColor, style = f.veinStyle) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ Building, once per size

private class SceneKey(val kind: BackdropKind, val w: Float, val h: Float, val d: Float, val dark: Boolean, val base: Color, val scene: Scene)

/** The last few scenes built (a page draws its background more than once, and Look cards draw small ones). */
private val scenes = ArrayDeque<SceneKey>()

/** A scene smaller than this (pixels) is a preview's. */
private const val SMALL_PIXELS = 400_000f

/**
 * The seasonal scenes' pictures: held only while a seasonal background is on screen somewhere ([enter] / [leave], from
 * `Design`), and let go when Prism is put away or Android asks for memory ([release]).
 */
internal object SeasonalScenes {
    private var users = 0

    fun enter() = synchronized(scenes) { users++ }

    fun leave() = synchronized(scenes) {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) scenes.clear()
    }

    fun release() = synchronized(scenes) { scenes.clear() }

    /** The bytes the kept pictures hold now. */
    fun heldBytes(): Long = synchronized(scenes) { scenes.sumOf { it.scene.still.width.toLong() * it.scene.still.height * 2 } }
}

private fun DrawScope.scene(kind: BackdropKind, colors: List<Color>, dark: Boolean, d: Float): Scene {
    val w = size.width; val h = size.height
    val base = colors[0]
    synchronized(scenes) {
        for (i in scenes.indices) {
            val k = scenes[i]
            if (k.kind == kind && k.w == w && k.h == h && k.d == d && k.dark == dark && k.base == base) return k.scene
        }
        val built = when (kind) {
            BackdropKind.HOLIDAY -> buildHoliday(w, h, d, colors, dark)
            else -> buildHarvest(w, h, d, colors, dark)
        }
        built.still = still(built, w, h, d, base)
        scenes.addFirst(SceneKey(kind, w, h, d, dark, base, built))
        // Each holds a picture of its page: at most two page-sized ones (the page; its top bar draws the same one), and
        // a few small ones for previews (Look cards, the background picker's tiles).
        var big = 0; var small = 0
        val it = scenes.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val large = s.w * s.h > SMALL_PIXELS
            if (large && ++big > 2 || !large && ++small > 4) it.remove()
        }
        return built
    }
}

/** How long the last scene's still parts took to draw (what each frame would cost without the picture); for tests. */
@Volatile internal var seasonalStillNanos = 0L

/** Draws [s]'s still parts once, at the page's size, and lets go of their shapes. */
private fun still(s: Scene, w: Float, h: Float, d: Float, base: Color): ImageBitmap {
    val start = System.nanoTime()
    // Opaque (the page's own color is in it), so two bytes a pixel.
    val bmp = ImageBitmap(kotlin.math.ceil(w).toInt().coerceAtLeast(1), kotlin.math.ceil(h).toInt().coerceAtLeast(1), ImageBitmapConfig.Rgb565)
    CanvasDrawScope().draw(Density(d), LayoutDirection.Ltr, Canvas(bmp), Size(w, h)) {
        drawRect(base)
        for (g in s.glows) drawCircle(g.brush, g.radius, g.center)
        for (p in s.back) drawPath(p.path, p.color, style = p.style)
        for (p in s.front) drawPath(p.path, p.color, style = p.style)
    }
    s.glows = emptyList(); s.back = emptyList(); s.front = emptyList()
    seasonalStillNanos = System.nanoTime() - start
    return bmp
}

/**
 * Draws shapes with a transform (place, turn, scale), so each drawing is made in its own small units and put where it
 * goes. Curves stay curves under it.
 */
private class Pen {
    val path = Path()
    private var a = 1f; private var b = 0f; private var c = 0f; private var dd = 1f; private var e = 0f; private var f = 0f

    fun at(x: Float, y: Float, deg: Float = 0f, sx: Float = 1f, sy: Float = sx): Pen {
        val r = deg * PI.toFloat() / 180f
        val cs = cos(r); val sn = sin(r)
        a = cs * sx; b = sn * sx; c = -sn * sy; dd = cs * sy; e = x; f = y
        return this
    }

    private fun px(x: Float, y: Float) = a * x + c * y + e
    private fun py(x: Float, y: Float) = b * x + dd * y + f
    fun m(x: Float, y: Float) = path.moveTo(px(x, y), py(x, y))
    fun l(x: Float, y: Float) = path.lineTo(px(x, y), py(x, y))
    fun q(x1: Float, y1: Float, x: Float, y: Float) = path.quadraticTo(px(x1, y1), py(x1, y1), px(x, y), py(x, y))
    fun cu(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
        path.cubicTo(px(x1, y1), py(x1, y1), px(x2, y2), py(x2, y2), px(x, y), py(x, y))
    fun z() = path.close()

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
        val k = 0.5523f
        m(cx + rx, cy)
        cu(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry)
        cu(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy)
        cu(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry)
        cu(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy)
        z()
    }

    fun circle(cx: Float, cy: Float, r: Float) = ellipse(cx, cy, r, r)

    fun poly(p: FloatArray) {
        m(p[0], p[1])
        var i = 2
        while (i < p.size) { l(p[i], p[i + 1]); i += 2 }
        z()
    }

    fun rect(l: Float, t: Float, r: Float, b: Float) = poly(floatArrayOf(l, t, r, t, r, b, l, b))
}

/** Builds a scene's shapes; inks are blended into [base] by how strong they should be where they sit. */
private class Builder(val w: Float, val h: Float, val u: Float, val base: Color, val strength: Float) {
    val back = ArrayList<Piece>()

    /** How strong a picture is at [x]: full at the page's sides, about a third in its middle. */
    fun edge(x: Float): Float {
        val ex = (abs(x / w - 0.5f) * 2f).coerceIn(0f, 1f)
        return 0.32f + 0.68f * ex.pow(1.3f)
    }

    fun ink(color: Color, k: Float = 1f): Color = lerp(base, color, (strength * k).coerceIn(0f, 1f))

    fun fill(color: Color, k: Float = 1f, into: MutableList<Piece> = back, draw: Pen.() -> Unit) {
        val p = Pen(); p.draw(); into += Piece(p.path, ink(color, k))
    }

    fun line(color: Color, width: Float, k: Float = 1f, into: MutableList<Piece> = back, cap: StrokeCap = StrokeCap.Round, effect: PathEffect? = null, draw: Pen.() -> Unit) {
        val p = Pen(); p.draw(); into += Piece(p.path, ink(color, k), Stroke(width, cap = cap, join = StrokeJoin.Round, pathEffect = effect))
    }
}

/** One unit: a dp on a phone-wide page, so the pictures keep their size on a tablet's wider page. */
private fun unit(w: Float, d: Float) = d * (w / d).coerceIn(260f, 480f) / 360f

private fun glow(color: Color, alpha: Float, cx: Float, cy: Float, r: Float) =
    Glow(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), Offset(cx, cy), r), Offset(cx, cy), r)

private fun pureBlack(base: Color) = base.luminance() < 0.002f

// ------------------------------------------------------------------------------------ Shapes, in their own units

/** A maple leaf, tip up, about 2 tall with its stem, centered on the origin. */
private fun Pen.maple() {
    val right = floatArrayOf(
        0f, 1.00f, 8f, 0.60f, 15f, 0.68f, 25f, 0.42f, 37f, 0.62f, 50f, 0.97f, 61f, 0.60f, 71f, 0.64f, 83f, 0.36f,
        96f, 0.50f, 110f, 0.68f, 124f, 0.40f, 142f, 0.24f, 165f, 0.15f,
    )
    fun x(i: Int, s: Float) = s * right[i + 1] * sin(right[i] * PI.toFloat() / 180f)
    fun y(i: Int) = -right[i + 1] * cos(right[i] * PI.toFloat() / 180f)
    m(0f, -1f)
    var i = 2
    while (i < right.size) { l(x(i, 1f), y(i)); i += 2 }
    l(0.035f, 0.62f); l(-0.035f, 0.62f)
    i = right.size - 2
    while (i >= 2) { l(x(i, -1f), y(i)); i -= 2 }
    z()
}

/** A maple leaf's veins, from its base to each lobe. */
private fun Pen.mapleVeins() {
    m(0f, 0.15f); l(0f, -0.82f)
    m(0f, 0.12f); l(0.66f, -0.56f); m(0f, 0.12f); l(-0.66f, -0.56f)
    m(0f, 0.14f); l(0.55f, 0.20f); m(0f, 0.14f); l(-0.55f, 0.20f)
}

/** An oak leaf, tip up, 2 tall with its stem: rounded lobes, widest past the middle. */
private fun Pen.oak() {
    val n = 40
    fun half(t: Float): Float {
        val env = sin(PI.toFloat() * t.pow(0.8f)).coerceAtLeast(0f).pow(0.75f)
        val lobes = 0.55f + 0.45f * abs(sin(PI.toFloat() * (t * 4.5f + 0.15f))).pow(0.55f)
        return 0.44f * env * lobes
    }
    fun y(t: Float) = -1f + 1.72f * t
    m(0f, -1f)
    for (i in 1..n) { val tt = i / n.toFloat(); l(half(tt), y(tt)) }
    l(0.03f, 1f); l(-0.03f, 1f)
    for (i in n downTo 1) { val tt = i / n.toFloat(); l(-half(tt), y(tt)) }
    z()
}

private fun Pen.oakVein() { m(0f, 0.95f); l(0f, -0.85f) }

/** An acorn hanging from its cap: the nut below the origin, about 1 across. */
private fun Builder.acorn(x: Float, y: Float, s: Float, deg: Float, nut: Color, cap: Color, into: MutableList<Piece> = back) {
    val k = edge(x)
    fill(nut, k, into) { at(x, y, deg, s); m(-0.42f, 0.05f); cu(-0.45f, 0.75f, -0.1f, 1.05f, 0f, 1.12f); cu(0.1f, 1.05f, 0.45f, 0.75f, 0.42f, 0.05f); z() }
    fill(cap, k, into) { at(x, y, deg, s); m(-0.5f, 0.12f); cu(-0.5f, -0.35f, 0.5f, -0.35f, 0.5f, 0.12f); q(0f, 0.22f, -0.5f, 0.12f); z() }
    line(cap, s * 0.09f, k, into) { at(x, y, deg, s); m(0f, -0.2f); q(0.05f, -0.42f, 0.18f, -0.5f) }
}

/** A five-pointed star of radius 1. */
private fun Pen.star(points: Int = 5, inner: Float = 0.45f) {
    val n = points * 2
    for (i in 0 until n) {
        val r = if (i % 2 == 0) 1f else inner
        val a = -PI.toFloat() / 2 + i * PI.toFloat() / points
        if (i == 0) m(r * cos(a), r * sin(a)) else l(r * cos(a), r * sin(a))
    }
    z()
}

/** A sparkle: four thin points, radius 1. */
private fun Pen.sparkle() {
    m(0f, -1f); q(0.12f, -0.12f, 1f, 0f); q(0.12f, 0.12f, 0f, 1f); q(-0.12f, 0.12f, -1f, 0f); q(-0.12f, -0.12f, 0f, -1f); z()
}

/**
 * A holly leaf, tip up, 2 long, its base at the origin (so it reaches from 0 to -2): spiky points with curved hollows
 * between them.
 */
private fun Pen.holly() {
    // The tip, the right side's points, and the base; the left side mirrors them.
    val xs = floatArrayOf(0f, 0.30f, 0.44f, 0.36f, 0.07f)
    val ys = floatArrayOf(-2f, -1.62f, -1.12f, -0.6f, -0.06f)
    fun hollow(i: Int, side: Float) {
        // Each hollow curves in toward the leaf's middle.
        q((xs[i - 1] + xs[i]) * 0.5f * 0.45f * side, (ys[i - 1] + ys[i]) * 0.5f, xs[i] * side, ys[i])
    }
    m(0f, -2f)
    for (i in 1 until xs.size) hollow(i, 1f)
    l(0f, 0f); l(-xs.last(), ys.last())
    for (i in xs.size - 1 downTo 1) {
        q((xs[i - 1] + xs[i]) * 0.5f * 0.45f * -1f, (ys[i - 1] + ys[i]) * 0.5f, -xs[i - 1], ys[i - 1])
    }
    z()
}

// ------------------------------------------------------------------------------------ Harvest

private object HarvestInk {
    val leafRed = Color(0xFF686868); val leafOrange = Color(0xFF919191); val leafGold = Color(0xFFB1B1B1)
    val leafBrown = Color(0xFF686868); val leafDeep = Color(0xFF4D4D4D)
    val twig = Color(0xFF444444)
    val pumpkin = Color(0xFF8E8E8E); val pumpkinLight = Color(0xFFA6A6A6); val pumpkinDark = Color(0xFF707070)
    val stem = Color(0xFF5B5B5B); val vine = Color(0xFF797979)
    val crust = Color(0xFFAFAFAF); val crustDark = Color(0xFF8A8A8A); val crustLight = Color(0xFFC7C7C7)
    val filling = Color(0xFF6D6D6D); val appleFill = Color(0xFF535353); val cream = Color(0xFFEDEDED)
    val dish = Color(0xFF717171); val dishDark = Color(0xFF545454)
    val body = Color(0xFF555555); val bodyLight = Color(0xFF757575); val wing = Color(0xFF414141)
    val featherRed = Color(0xFF636363); val featherOrange = Color(0xFF909090); val featherGold = Color(0xFFACACAC); val featherBrown = Color(0xFF5A5A5A)
    val beak = Color(0xFFB8B8B8); val wattle = Color(0xFF676767); val legs = Color(0xFFA5A5A5)
    val eyeWhite = Color(0xFFF7F7F7); val eye = Color(0xFF1E1E1E)
    val wheat = Color(0xFFB1B1B1); val wheatDark = Color(0xFF929292); val wheatStem = Color(0xFFA1A1A1)
    val gourd = Color(0xFFB6B6B6); val gourdGreen = Color(0xFF6A6A6A); val whitePumpkin = Color(0xFFE3E3E3); val whiteRib = Color(0xFFC0C0C0)
    val squash = Color(0xFF4E4E4E); val squashRib = Color(0xFF393939)
    val apple = Color(0xFF606060); val appleShine = Color(0xFF8B8B8B); val leafGreen = Color(0xFF7F7F7F)
    val nut = Color(0xFF838383); val nutCap = Color(0xFF515151)
    val leaves = listOf(leafRed, leafOrange, leafGold, leafBrown, leafDeep)
}

private fun buildHarvest(w: Float, h: Float, d: Float, colors: List<Color>, dark: Boolean): Scene {
    val (base, a, b, c) = colors
    val black = pureBlack(base)
    val u = unit(w, d)
    val strength = when { black -> 0.74f; dark -> 0.88f; else -> 0.86f }
    val k = Builder(w, h, u, base, strength)
    val I = HarvestInk
    val r = Random(1126)
    val glowK = if (black) 0.5f else 1f
    val glows = listOf(
        glow(a, (if (dark) 0.24f else 0.30f) * glowK, w * 0.92f, h * 0.06f, w * 0.75f),
        glow(b, (if (dark) 0.18f else 0.22f) * glowK, w * 0.06f, h * 0.95f, w * 0.7f),
        glow(c, (if (dark) 0.14f else 0.20f) * glowK, w * 0.98f, h * 0.88f, w * 0.6f),
    )
    // The scenes stand just above where the mini player and tab bar float; below them, fallen leaves.
    val ground = h - 118f * u

    // A branch in from the top-right corner, its leaves still on, and two acorns.
    k.line(I.twig, 3.6f * u) { m(w + 8f * u, 30f * u); cu(w - 50f * u, 34f * u, w - 100f * u, 60f * u, w - 160f * u, 96f * u) }
    k.line(I.twig, 2.2f * u) { m(w - 70f * u, 44f * u); q(w - 96f * u, 26f * u, w - 128f * u, 22f * u) }
    k.line(I.twig, 1.6f * u) { m(w - 118f * u, 75f * u); q(w - 130f * u, 96f * u, w - 128f * u, 118f * u) }
    data class OnBranch(val x: Float, val y: Float, val deg: Float, val s: Float, val maple: Boolean, val ink: Color)
    val onBranch = listOf(
        OnBranch(w - 18f * u, 52f * u, 168f, 20f, true, I.leafRed),
        OnBranch(w - 46f * u, 22f * u, -20f, 17f, false, I.leafGold),
        OnBranch(w - 64f * u, 66f * u, 150f, 22f, true, I.leafOrange),
        OnBranch(w - 104f * u, 28f * u, -60f, 15f, true, I.leafDeep),
        OnBranch(w - 100f * u, 86f * u, 190f, 19f, false, I.leafBrown),
        OnBranch(w - 142f * u, 20f * u, -100f, 14f, false, I.leafOrange),
        OnBranch(w - 156f * u, 106f * u, 210f, 17f, true, I.leafGold),
    )
    for (l in onBranch) {
        val ke = k.edge(l.x)
        k.fill(l.ink, ke) { at(l.x, l.y, l.deg, l.s * 1.3f * u); if (l.maple) maple() else oak() }
        k.line(lerp(l.ink, Color.Black, 0.3f), 0.9f * u, ke) { at(l.x, l.y, l.deg, l.s * 1.3f * u); if (l.maple) mapleVeins() else oakVein() }
    }
    k.acorn(w - 84f * u, 54f * u, 12f * u, 8f, I.nut, I.nutCap)
    k.acorn(w - 128f * u, 118f * u, 11f * u, -6f, I.nut, I.nutCap)
    // A leaf or two in the top-left corner, mostly off the page (the page's title sits there).
    k.fill(I.leafOrange, 0.85f) { at(4f * u, 6f * u, 130f, 20f * u); maple() }
    k.fill(I.leafBrown, 0.75f) { at(28f * u, -4f * u, 200f, 13f * u); oak() }

    // Wheat up the left side, behind the turkey.
    fun wheat(x: Float, lean: Float, tall: Float) {
        val ke = k.edge(x)
        val topX = x + lean * u; val topY = ground - tall * u
        k.line(I.wheatStem, 1.5f * u, ke) { m(x, ground + 6f * u); q(x + lean * 0.2f * u, ground - tall * 0.5f * u, topX, topY) }
        // The ear: kernels either side of the stalk's tip, smaller toward the top.
        val dx = lean / tall
        for (i in 0 until 8) {
            val f = i / 8f
            val cy = topY + (38f - f * 34f) * u
            val cx = topX - dx * (38f - f * 34f) * u
            val s = (5.2f - f * 2.2f) * u
            val tilt = atanDeg(dx)
            k.fill(if (i % 2 == 0) I.wheat else I.wheatDark, ke) { at(cx, cy, tilt - 28f, 1f); ellipse(-s * 0.55f, 0f, s * 0.42f, s) }
            k.fill(if (i % 2 == 0) I.wheatDark else I.wheat, ke) { at(cx, cy, tilt + 28f, 1f); ellipse(s * 0.55f, 0f, s * 0.42f, s) }
        }
        k.line(I.wheat, 0.8f * u, ke) { m(topX, topY); l(topX + lean * 0.06f * u, topY - 14f * u) }
    }
    wheat(10f * u, 4f, 196f); wheat(22f * u, 14f, 222f); wheat(36f * u, -2f, 178f); wheat(4f * u, 22f, 160f)

    // The turkey, bottom left: its tail fanned out behind it.
    turkey(k, 70f * u, ground, 150f * u)

    // Gourds at its feet: a bottle gourd, a little white pumpkin and an acorn squash.
    run {
        val x = 132f * u; val ke = k.edge(x)
        k.fill(I.gourd, ke) {
            at(x, ground, -8f, u)
            m(0f, 0f); cu(-22f, 0f, -26f, -26f, -14f, -38f); cu(-8f, -44f, -10f, -60f, -6f, -66f)
            cu(-3f, -72f, 5f, -72f, 7f, -66f); cu(10f, -58f, 8f, -44f, 14f, -38f); cu(26f, -26f, 22f, 0f, 0f, 0f); z()
        }
        k.fill(I.gourdGreen, ke) { at(x, ground, -8f, u); m(-18f, -10f); cu(-12f, -2f, 12f, -2f, 18f, -10f); cu(16f, -1f, 8f, 0f, 0f, 0f); cu(-8f, 0f, -16f, -1f, -18f, -10f); z() }
        k.line(I.stem, 2.2f * u, ke) { at(x, ground, -8f, u); m(0f, -70f); q(2f, -76f, 7f, -78f) }
    }
    run {
        val x = 104f * u; val ke = k.edge(x)
        k.fill(I.whitePumpkin, ke) { at(x, ground, 0f, u); ellipse(0f, -11f, 17f, 11f) }
        k.line(I.whiteRib, 1f * u, ke) { at(x, ground, 0f, u); m(-6f, -21f); q(-10f, -11f, -6f, -1f); m(6f, -21f); q(10f, -11f, 6f, -1f); m(0f, -22f); l(0f, 0f) }
        k.fill(I.stem, ke) { at(x, ground, 0f, u); rect(-2f, -27f, 2f, -20f) }
    }
    run {
        val x = 160f * u; val ke = k.edge(x) * 0.9f
        k.fill(I.squash, ke) { at(x, ground, 12f, u); m(0f, 0f); cu(-18f, 0f, -20f, -24f, -10f, -30f); q(0f, -36f, 10f, -30f); cu(20f, -24f, 18f, 0f, 0f, 0f); z() }
        k.line(I.squashRib, 1.2f * u, ke) { at(x, ground, 12f, u); m(-8f, -28f); q(-13f, -14f, -6f, -2f); m(8f, -28f); q(13f, -14f, 6f, -2f) }
        k.fill(I.leafGold, ke) { at(x, ground, 12f, u); ellipse(-4f, -10f, 3f, 5f) }
        k.fill(I.stem, ke) { at(x, ground, 12f, u); rect(-2f, -38f, 2f, -30f) }
    }

    // Bottom right: a pumpkin in the corner, an apple pie behind a pumpkin pie, and apples.
    pumpkin(k, w - 34f * u, ground + 10f * u, 86f * u)
    pumpkin(k, w - 96f * u, ground + 2f * u, 46f * u)
    pie(k, w - 120f * u, ground - 58f * u, 40f * u, apple = true)
    pie(k, w - 76f * u, ground - 26f * u, 46f * u, apple = false)
    apple(k, w - 168f * u, ground - 6f * u, 11f * u)
    apple(k, w - 186f * u, ground - 2f * u, 9.5f * u)

    // Fallen leaves along the foot of the page, thickest in the corners.
    run {
        var x = -10f * u
        while (x < w + 10f * u) {
            val ke = k.edge(x)
            val corner = ((abs(x / w - 0.5f) * 2f) - 0.35f).coerceAtLeast(0f) / 0.65f
            if (r.nextFloat() < 0.35f + corner * 0.6f) {
                val y = ground + 4f * u + r.nextFloat() * (h - ground) - corner * r.nextFloat() * 24f * u
                val ink = HarvestInk.leaves[r.nextInt(HarvestInk.leaves.size)]
                val maple = r.nextBoolean()
                val s = (7f + r.nextFloat() * 6f) * u
                val deg = r.nextFloat() * 360f
                k.fill(ink, ke * 0.9f) { at(x, y, deg, s); if (maple) maple() else oak() }
            }
            x += 5f * u
        }
    }

    // Leaves drifting down, mostly near the sides.
    val maple = Pen().apply { maple() }.path
    val oak = Pen().apply { oak() }.path
    val mapleVein = Pen().apply { mapleVeins() }.path
    val oakVein = Pen().apply { oakVein() }.path
    val veinStyle = Stroke(0.06f, cap = StrokeCap.Round)
    val count = (w / u / 360f * 20f).toInt().coerceIn(12, 30)
    val margin = 30f * u
    val fallers = List(count) { i ->
        // Two of every three near a side.
        val side = i % 3 != 2
        val x0 = if (side) { val e = r.nextFloat() * 0.26f; if (i % 2 == 0) w * e else w * (1f - e) } else w * (0.26f + r.nextFloat() * 0.48f)
        val ink = HarvestInk.leaves[r.nextInt(HarvestInk.leaves.size)]
        val m = r.nextFloat() < 0.55f
        val ke = k.edge(x0) * 0.95f
        Faller(
            if (m) maple else oak, k.ink(ink, ke), Fill, if (m) mapleVein else oakVein, k.ink(lerp(ink, Color.Black, 0.3f), ke), veinStyle,
            x0, r.nextFloat() * (h + margin * 2), (9f + r.nextFloat() * 7f) * u, 1 + r.nextInt(2), (10f + r.nextFloat() * 18f) * u,
            1 + r.nextInt(2), r.nextFloat(), r.nextFloat() * 360f, if (r.nextBoolean()) 1 else -1, 1 + r.nextInt(3),
        )
    }
    return Scene(glows, k.back, emptyList(), emptyList(), emptyList(), emptyList(), fallers, h + margin * 2, margin)
}

private fun atanDeg(slope: Float) = (kotlin.math.atan(slope.toDouble()) * 180.0 / PI).toFloat()

/** A cartoon turkey standing at ([x], [ground]), [tall] high: tail fanned behind, head to the right. */
private fun turkey(k: Builder, x: Float, ground: Float, tall: Float) {
    val I = HarvestInk
    val ke = k.edge(x)
    val s = tall / 150f
    val fanX = x; val fanY = ground - 70f * s
    // Two rows of tail feathers, outer ones longer.
    val outer = listOf(I.featherBrown, I.featherRed, I.featherOrange, I.featherGold, I.featherOrange, I.featherRed, I.featherBrown)
    val inner = listOf(I.featherGold, I.featherOrange, I.featherRed, I.featherOrange, I.featherGold)
    for (row in 0..1) {
        val list = if (row == 0) outer else inner
        val len = if (row == 0) 78f else 60f
        val n = list.size
        for (i in 0 until n) {
            val deg = -62f + 124f * i / (n - 1)
            val wid = if (row == 0) 15f else 13f
            k.fill(list[i], ke) {
                at(fanX, fanY, deg, s)
                m(0f, 0f); cu(-wid, -len * 0.35f, -wid * 0.9f, -len * 0.9f, 0f, -len); cu(wid * 0.9f, -len * 0.9f, wid, -len * 0.35f, 0f, 0f); z()
            }
            // A band near each feather's tip.
            k.line(lerp(list[i], Color.White, 0.35f), 1.6f * s, ke) {
                at(fanX, fanY, deg, s)
                m(-wid * 0.62f, -len * 0.78f); q(0f, -len * 0.72f, wid * 0.62f, -len * 0.78f)
            }
        }
    }
    // Legs and feet.
    for (lx in listOf(-9f, 9f)) {
        k.line(I.legs, 3f * s, ke) { at(x, ground, 0f, s); m(lx, -30f); l(lx, -2f); m(lx, -2f); l(lx - 7f, 2f); m(lx, -2f); l(lx + 7f, 2f); m(lx, -2f); l(lx, 3f) }
    }
    // Body, belly and wing.
    k.fill(I.body, ke) { at(x, ground, 0f, s); ellipse(0f, -58f, 34f, 32f) }
    k.fill(I.bodyLight, ke) { at(x, ground, 0f, s); ellipse(4f, -48f, 20f, 20f) }
    k.fill(I.wing, ke) { at(x, ground, 0f, s); m(-30f, -66f); cu(-20f, -82f, 4f, -70f, 2f, -52f); cu(-6f, -38f, -26f, -40f, -30f, -66f); z() }
    // Neck and head, the snood and wattle, the beak and an eye.
    k.fill(I.bodyLight, ke) { at(x, ground, 0f, s); m(14f, -80f); cu(14f, -96f, 16f, -110f, 26f, -114f); cu(38f, -116f, 42f, -104f, 38f, -96f); cu(34f, -90f, 32f, -82f, 30f, -72f); z() }
    k.fill(I.bodyLight, ke) { at(x, ground, 0f, s); circle(30f, -108f, 12f) }
    k.fill(I.wattle, ke) { at(x, ground, 0f, s); m(38f, -104f); cu(44f, -100f, 44f, -88f, 39f, -84f); cu(35f, -88f, 34f, -98f, 38f, -104f); z() }
    k.fill(I.beak, ke) { at(x, ground, 0f, s); poly(floatArrayOf(39f, -112f, 51f, -107f, 39f, -103f)) }
    k.fill(I.eyeWhite, ke) { at(x, ground, 0f, s); circle(31f, -112f, 4.2f) }
    k.fill(I.eye, ke) { at(x, ground, 0f, s); circle(32.5f, -112f, 2.2f) }
}

/** A pumpkin sitting at ([x], [bottom]), [wide] across. */
private fun pumpkin(k: Builder, x: Float, bottom: Float, wide: Float) {
    val I = HarvestInk
    val ke = k.edge(x)
    val s = wide / 100f
    val tall = 70f
    // Segments back to front: the outer ones darker.
    k.fill(I.pumpkinDark, ke) { at(x, bottom, 0f, s); ellipse(-28f, -tall / 2, 24f, tall / 2) }
    k.fill(I.pumpkinDark, ke) { at(x, bottom, 0f, s); ellipse(28f, -tall / 2, 24f, tall / 2) }
    k.fill(I.pumpkin, ke) { at(x, bottom, 0f, s); ellipse(-14f, -tall / 2 - 1f, 25f, tall / 2 + 1f) }
    k.fill(I.pumpkin, ke) { at(x, bottom, 0f, s); ellipse(14f, -tall / 2 - 1f, 25f, tall / 2 + 1f) }
    k.fill(I.pumpkinLight, ke) { at(x, bottom, 0f, s); ellipse(0f, -tall / 2 - 2f, 18f, tall / 2 + 2f) }
    // A highlight on the middle segment.
    k.fill(lerp(I.pumpkinLight, Color.White, 0.3f), ke) { at(x, bottom, 0f, s); ellipse(-5f, -tall * 0.68f, 4f, 9f) }
    // The stem, leaning, and a curl of vine.
    k.fill(I.stem, ke) { at(x, bottom, 0f, s); m(-5f, -tall); q(-6f, -tall - 12f, 2f, -tall - 20f); l(8f, -tall - 16f); q(2f, -tall - 10f, 5f, -tall); z() }
    k.line(I.vine, 1.8f * s, ke) { at(x, bottom, 0f, s); m(4f, -tall - 6f); cu(18f, -tall - 18f, 30f, -tall - 2f, 20f, -tall + 2f); cu(12f, -tall + 4f, 14f, -tall - 10f, 22f, -tall - 10f) }
}

/** A pie in its dish seen from a little above, centered at ([x], [y]), [radius] wide: pumpkin with cream, or a lattice apple pie. */
private fun pie(k: Builder, x: Float, y: Float, radius: Float, apple: Boolean) {
    val I = HarvestInk
    val ke = k.edge(x)
    val ry = radius * 0.42f
    val depth = radius * 0.22f
    // The dish: its side, then its rim.
    k.fill(I.dishDark, ke) { at(x, y, 0f, 1f); ellipse(0f, depth, radius * 0.9f, ry * 0.9f) }
    k.fill(I.dishDark, ke) { at(x, y, 0f, 1f); rect(-radius * 0.9f, 0f, radius * 0.9f, depth) }
    k.fill(I.dish, ke) { at(x, y, 0f, 1f); m(-radius * 1.02f, 0f); l(-radius * 0.9f, depth); l(radius * 0.9f, depth); l(radius * 1.02f, 0f); z() }
    // The crust's fluted edge.
    k.fill(I.crustDark, ke) { at(x, y, 0f, 1f); ellipse(0f, 0f, radius, ry) }
    k.fill(I.crust, ke) {
        at(x, y, 0f, 1f)
        val n = 20
        for (i in 0 until n) {
            val a = i * TAU / n
            circle(cos(a) * radius * 0.9f, sin(a) * ry * 0.9f - ry * 0.04f, radius * 0.12f)
        }
    }
    val fx = radius * 0.78f; val fy = ry * 0.74f
    if (!apple) {
        k.fill(I.filling, ke) { at(x, y, 0f, 1f); ellipse(0f, -ry * 0.04f, fx, fy) }
        k.fill(lerp(I.filling, Color.White, 0.18f), ke) { at(x, y, 0f, 1f); ellipse(-fx * 0.3f, -fy * 0.35f, fx * 0.35f, fy * 0.25f) }
        // A swirl of whipped cream.
        k.fill(I.cream, ke) { at(x, y, 0f, 1f); ellipse(0f, -ry * 0.12f, radius * 0.26f, radius * 0.11f) }
        k.fill(I.cream, ke) { at(x, y, 0f, 1f); ellipse(0f, -ry * 0.36f, radius * 0.18f, radius * 0.09f) }
        k.fill(I.cream, ke) { at(x, y, 0f, 1f); m(-radius * 0.08f, -ry * 0.5f); q(0f, -ry * 0.95f, radius * 0.08f, -ry * 0.5f); z() }
    } else {
        k.fill(I.appleFill, ke) { at(x, y, 0f, 1f); ellipse(0f, -ry * 0.04f, fx, fy) }
        // The lattice: strips across both ways, each cut to the filling's oval.
        val strip = radius * 0.11f
        k.fill(I.crustLight, ke) {
            at(x, y, 0f, 1f)
            for (i in -2..2) {
                val sx = i * fx * 0.36f
                val half = fy * kotlin.math.sqrt((1f - (sx / fx) * (sx / fx)).coerceAtLeast(0f))
                rect(sx - strip / 2, -ry * 0.04f - half, sx + strip / 2, -ry * 0.04f + half)
            }
        }
        k.fill(I.crust, ke) {
            at(x, y, 0f, 1f)
            for (i in -1..1) {
                val sy = i * fy * 0.5f
                val half = fx * kotlin.math.sqrt((1f - (sy / fy) * (sy / fy)).coerceAtLeast(0f))
                rect(-half, -ry * 0.04f + sy - strip * 0.42f, half, -ry * 0.04f + sy + strip * 0.42f)
            }
        }
    }
}

/** An apple sitting at ([x], [bottom]), [r] in radius, with its stem and a leaf. */
private fun apple(k: Builder, x: Float, bottom: Float, r: Float) {
    val I = HarvestInk
    val ke = k.edge(x)
    k.fill(I.apple, ke) {
        at(x, bottom - r, 0f, r)
        m(0f, -0.78f); cu(0.5f, -1.2f, 1.1f, -0.7f, 1f, 0f); cu(0.95f, 0.75f, 0.4f, 1.05f, 0f, 0.92f)
        cu(-0.4f, 1.05f, -0.95f, 0.75f, -1f, 0f); cu(-1.1f, -0.7f, -0.5f, -1.2f, 0f, -0.78f); z()
    }
    k.fill(I.appleShine, ke) { at(x, bottom - r, 0f, r); ellipse(-0.45f, -0.3f, 0.16f, 0.3f) }
    k.line(I.stem, r * 0.14f, ke) { at(x, bottom - r, 0f, r); m(0f, -0.75f); q(0.05f, -1.1f, 0.2f, -1.25f) }
    k.fill(I.leafGreen, ke) { at(x, bottom - r, 0f, r); m(0.1f, -1f); q(0.5f, -1.45f, 0.85f, -1.15f); q(0.5f, -0.85f, 0.1f, -1f); z() }
}

// ------------------------------------------------------------------------------------ Holiday

private class HolidayInk(dark: Boolean) {
    val pine = Color(0xFF505050); val pineMid = Color(0xFF616161); val pineLight = Color(0xFF767676)
    val trunk = Color(0xFF4B4B4B)
    val red = Color(0xFF5E5E5E); val redDark = Color(0xFF464646); val gold = Color(0xFFC3C3C3); val goldDark = Color(0xFF999999)
    val green = Color(0xFF696969); val greenDark = Color(0xFF4C4C4C)
    val snow = if (dark) Color(0xFFF4F4F4) else Color(0xFFFFFFFF)
    val snowEdge = if (dark) Color(0xFFD3D3D3) else Color(0xFFC8C8C8)
    val flake = if (dark) Color(0xFFF7F7F7) else Color(0xFFA5A5A5)
    val holly = Color(0xFF4E4E4E); val hollyLight = Color(0xFF696969); val berry = Color(0xFF666666)
    val thread = if (dark) Color(0xFFC5C5C5) else Color(0xFF909090)
    val shine = Color(0xFFFFFFFF)
    val star = Color(0xFFCECECE)
}

private fun buildHoliday(w: Float, h: Float, d: Float, colors: List<Color>, dark: Boolean): Scene {
    val (base, a, b, c) = colors
    val black = pureBlack(base)
    val u = unit(w, d)
    val strength = when { black -> 0.76f; dark -> 0.88f; else -> 0.88f }
    val k = Builder(w, h, u, base, strength)
    val I = HolidayInk(dark)
    val r = Random(1225)
    val glowK = if (black) 0.5f else 1f
    val glows = listOf(
        glow(c, (if (dark) 0.18f else 0.26f) * glowK, w * 0.88f, h * 0.05f, w * 0.7f),
        glow(b, (if (dark) 0.22f else 0.24f) * glowK, w * 0.05f, h * 0.9f, w * 0.75f),
        glow(a, (if (dark) 0.16f else 0.20f) * glowK, w * 0.98f, h * 0.92f, w * 0.6f),
    )
    // The scenes stand in snow just above where the mini player and tab bar float.
    val ground = h - 108f * u

    // Holly in the top-right corner.
    run {
        val x = w - 22f * u; val y = 20f * u
        for ((deg, ink) in listOf(250f to I.holly, 205f to I.hollyLight, 160f to I.holly)) {
            k.fill(ink) { at(x, y, deg, 19f * u); holly() }
            k.line(lerp(ink, Color.Black, 0.25f), 0.9f * u) { at(x, y, deg, 19f * u); m(0f, 0f); l(0f, -1.85f) }
        }
        for ((bx, by) in listOf(-4f to 2f, 4f to 4f, 0f to -4f)) {
            k.fill(I.berry) { at(x + bx * u, y + by * u, 0f, u); circle(0f, 0f, 4.4f) }
            k.fill(I.shine, 0.5f) { at(x + bx * u, y + by * u, 0f, u); circle(-1.4f, -1.4f, 1.2f) }
        }
    }

    // Ornaments hanging from the top edge, swaying, along the right of the page.
    val swings = ArrayList<Swing>()
    data class Bauble(val fx: Float, val len: Float, val rad: Float, val ink: Color, val kind: Int)
    val baubles = listOf(
        Bauble(0.58f, 64f, 10f, I.gold, 0), Bauble(0.69f, 102f, 13f, I.red, 0), Bauble(0.79f, 52f, 9f, I.green, 1),
        Bauble(0.88f, 84f, 11f, I.gold, 2), Bauble(0.50f, 36f, 7f, I.red, 0),
    )
    for ((i, o) in baubles.withIndex()) {
        val x = w * o.fx
        val ke = k.edge(x)
        val pieces = ArrayList<Piece>()
        val cy = o.len * u + o.rad * u
        k.line(I.thread, 0.8f * u, ke, pieces) { m(x, 0f); l(x, o.len * u) }
        k.fill(I.goldDark, ke, pieces) { rect(x - 3f * u, o.len * u - 1f * u, x + 3f * u, o.len * u + 3f * u) }
        when (o.kind) {
            // A teardrop.
            1 -> k.fill(o.ink, ke, pieces) {
                at(x, o.len * u + 2f * u, 0f, o.rad * u)
                m(0f, 0f); cu(1.1f, 0.5f, 1.1f, 1.7f, 0f, 2.6f); cu(-1.1f, 1.7f, -1.1f, 0.5f, 0f, 0f); z()
            }
            // A star.
            2 -> k.fill(o.ink, ke, pieces) { at(x, cy + 1f * u, 0f, o.rad * u * 1.25f); star() }
            else -> {
                k.fill(o.ink, ke, pieces) { circle(x, cy, o.rad * u) }
                // A band round its middle.
                k.line(lerp(o.ink, Color.White, 0.45f), 1.3f * u, ke, pieces) { m(x - o.rad * u * 0.98f, cy); q(x, cy + o.rad * u * 0.45f, x + o.rad * u * 0.98f, cy) }
            }
        }
        if (o.kind != 2) k.fill(I.shine, ke * 0.45f, pieces) { ellipse(x - o.rad * u * 0.35f, cy - o.rad * u * 0.35f, o.rad * u * 0.18f, o.rad * u * 0.28f) }
        swings += Swing(Offset(x, 0f), pieces, 3f + (i % 3), 1 + (i % 2), i * 0.23f)
    }

    // Bottom left: two trees, with gifts at their feet.
    tree(k, I, 132f * u, ground, 118f * u)
    tree(k, I, 56f * u, ground + 4f * u, 196f * u)
    gift(k, I, 104f * u, ground + 6f * u, 34f * u, 26f * u, I.red, I.gold)
    gift(k, I, 142f * u, ground + 8f * u, 26f * u, 20f * u, I.gold, I.red)

    // Bottom right: a stack of gifts and two candy canes.
    gift(k, I, w - 50f * u, ground + 6f * u, 56f * u, 38f * u, I.green, I.red)
    gift(k, I, w - 56f * u, ground - 34f * u, 38f * u, 30f * u, I.red, I.gold)
    gift(k, I, w - 104f * u, ground + 8f * u, 30f * u, 24f * u, I.snow, I.red)
    candyCane(k, I, w - 136f * u, ground + 6f * u, 84f * u, -10f)
    candyCane(k, I, w - 14f * u, ground - 4f * u, 70f * u, 14f)
    // A star over the stack.
    k.fill(I.star) { at(w - 34f * u, ground - 104f * u, 8f, 9f * u); star() }

    // Snow along the foot of the page, deepest in the corners.
    val front = ArrayList<Piece>()
    run {
        val p = Pen()
        p.m(0f, h)
        var x = 0f
        val step = 6f * u
        p.l(0f, driftAt(0f, w, ground, u))
        while (x <= w) { x += step; p.l(x.coerceAtMost(w), driftAt(x.coerceAtMost(w), w, ground, u)) }
        p.l(w, h); p.z()
        // Quieter on a dark page, where a bright band behind the tab bar would glare.
        val snowK = when { black -> 0.42f; dark -> 0.55f; else -> 1f }
        front += Piece(p.path, k.ink(I.snow, snowK))
        val edge = Pen()
        x = 0f
        edge.m(0f, driftAt(0f, w, ground, u))
        while (x <= w) { x += step; edge.l(x.coerceAtMost(w), driftAt(x.coerceAtMost(w), w, ground, u)) }
        front += Piece(edge.path, k.ink(I.snowEdge, snowK), Stroke(1.2f * u, cap = StrokeCap.Round))
    }

    // Stars twinkling across the top, more toward the sides.
    val twinkles = List(12) { i ->
        val x = if (i % 3 == 2) w * (0.3f + r.nextFloat() * 0.4f) else if (i % 2 == 0) w * r.nextFloat() * 0.3f else w * (0.7f + r.nextFloat() * 0.3f)
        val y = h * (0.04f + r.nextFloat() * 0.28f)
        val s = (2.5f + r.nextFloat() * 3.5f) * u
        Twinkle(Pen().apply { at(x, y, 0f, s); sparkle() }.path, k.ink(if (dark) I.star else I.goldDark, k.edge(x)), 1 + r.nextInt(3), r.nextFloat())
    }

    // Snow falling: dots everywhere (fainter in the middle), and a few crystals near the sides.
    val margin = 20f * u
    val area = (w / u) * (h / u)
    val flakes = List((area / 2800f).toInt().coerceIn(40, 160)) {
        val x0 = w * r.nextFloat()
        val near = r.nextFloat()
        Flake(
            x0, r.nextFloat() * (h + margin * 2), (0.9f + near * near * 2.1f) * u, 1 + r.nextInt(3),
            (4f + r.nextFloat() * 10f) * u, 1 + r.nextInt(2), r.nextFloat(), k.ink(I.flake, k.edge(x0) * (0.55f + 0.45f * near)),
        )
    }
    val fallers = List(8) { i ->
        val e = r.nextFloat() * 0.22f
        val x0 = if (i % 2 == 0) w * e else w * (1f - e)
        val s = (5f + r.nextFloat() * 4f) * u
        val shape = Pen().apply {
            for (arm in 0 until 6) {
                at(0f, 0f, arm * 60f, s)
                m(0f, 0f); l(0f, -1f)
                m(0f, -0.55f); l(0.28f, -0.78f); m(0f, -0.55f); l(-0.28f, -0.78f)
            }
        }.path
        Faller(
            shape, k.ink(I.flake, k.edge(x0)), Stroke(1.1f * u, cap = StrokeCap.Round), null, Color.Transparent, Fill,
            x0, r.nextFloat() * (h + margin * 2), 1f, 1, (6f + r.nextFloat() * 10f) * u, 1, r.nextFloat(), r.nextFloat() * 60f,
            if (r.nextBoolean()) 1 else -1, 0,
        )
    }
    return Scene(glows, k.back, swings, twinkles, front, flakes, fallers, h + margin * 2, margin)
}

/** The top of the snow at [x], about [ground]: lower in the middle, banked up in the corners. */
private fun driftAt(x: Float, w: Float, ground: Float, u: Float): Float {
    val e = abs(x / w - 0.5f) * 2f
    return ground + (22f - 30f * e * e * e) * u - 3.5f * u * sin(x / w * 5f * TAU + 0.6f)
}

/** A Christmas tree standing at ([x], [ground]), [tall] high: three tiers, a garland, baubles and a star. */
private fun tree(k: Builder, I: HolidayInk, x: Float, ground: Float, tall: Float) {
    val ke = k.edge(x)
    val s = tall / 200f
    k.fill(I.trunk, ke) { at(x, ground, 0f, s); rect(-9f, -26f, 9f, 0f) }
    val tiers = listOf(I.pine, I.pineMid, I.pineLight)
    for (i in 0 until 3) {
        val bottom = -22f - i * 50f
        val top = bottom - 88f + i * 6f
        val half = 66f - i * 16f
        k.fill(tiers[i], ke) {
            at(x, ground, 0f, s)
            m(0f, top); q(half * 0.3f, top + (bottom - top) * 0.55f, half, bottom)
            // A scalloped hem, bough by bough.
            val n = 4
            for (j in 1..n) {
                val x1 = half - j * (2 * half / n)
                q(x1 + half / n, bottom + 9f, x1, bottom)
            }
            q(-half * 0.3f, top + (bottom - top) * 0.55f, 0f, top)
            z()
        }
        // A garland across each tier.
        k.line(I.gold, 2f * s, ke) { at(x, ground, 0f, s); m(-half * 0.72f, bottom - 22f); q(0f, bottom - 6f, half * 0.62f, bottom - 30f) }
    }
    // Baubles.
    val spots = listOf(-40f to -40f, 24f to -32f, -10f to -60f, 44f to -50f, -28f to -92f, 16f to -84f, 2f to -118f, -16f to -134f, 18f to -150f)
    val inks = listOf(I.red, I.gold, I.snow, I.red, I.gold, I.red, I.snow, I.gold, I.red)
    for ((j, p) in spots.withIndex()) {
        k.fill(inks[j], ke) { at(x, ground, 0f, s); circle(p.first, p.second, 5.5f) }
    }
    // The star on top.
    k.fill(I.star, ke) { at(x, ground - 178f * s, 0f, 16f * s); star() }
}

/** A wrapped gift sitting at ([x], [bottom]), [wide] × [tall], in [paper] with a [ribbon] and a bow. */
private fun gift(k: Builder, I: HolidayInk, x: Float, bottom: Float, wide: Float, tall: Float, paper: Color, ribbon: Color) {
    val ke = k.edge(x)
    val l = x - wide / 2; val rr = x + wide / 2; val top = bottom - tall
    val lid = tall * 0.24f
    // An edge, so a white gift still shows on a snow-pale page.
    val edge = if (paper == I.snow) I.snowEdge else lerp(paper, Color.Black, 0.3f)
    val thin = wide * 0.025f
    k.fill(paper, ke) { rect(l, top + lid * 0.6f, rr, bottom) }
    k.line(edge, thin, ke) { rect(l, top + lid * 0.6f, rr, bottom) }
    k.fill(lerp(paper, Color.Black, 0.12f), ke) { rect(l - wide * 0.05f, top, rr + wide * 0.05f, top + lid) }
    k.line(edge, thin, ke) { rect(l - wide * 0.05f, top, rr + wide * 0.05f, top + lid) }
    val band = wide * 0.16f
    k.fill(ribbon, ke) { rect(x - band / 2, top, x + band / 2, bottom) }
    k.fill(ribbon, ke) { rect(l, top + lid + (tall - lid) * 0.42f, rr, top + lid + (tall - lid) * 0.42f + band * 0.8f) }
    // The bow: two loops and a knot.
    val bw = wide * 0.26f
    k.fill(ribbon, ke) { at(x, top, -28f, 1f); ellipse(-bw * 0.55f, -bw * 0.2f, bw * 0.55f, bw * 0.3f) }
    k.fill(ribbon, ke) { at(x, top, 28f, 1f); ellipse(bw * 0.55f, -bw * 0.2f, bw * 0.55f, bw * 0.3f) }
    k.fill(lerp(ribbon, Color.Black, 0.2f), ke) { circle(x, top - bw * 0.08f, bw * 0.2f) }
}

/** A candy cane standing at ([x], [bottom]), [tall] high, leaning [deg]: white with red stripes. */
private fun candyCane(k: Builder, I: HolidayInk, x: Float, bottom: Float, tall: Float, deg: Float) {
    val ke = k.edge(x)
    val s = tall / 100f
    val hook: Pen.() -> Unit = {
        at(x, bottom, deg, s)
        m(0f, 0f); l(0f, -78f); cu(0f, -104f, 30f, -104f, 30f, -78f); l(30f, -70f)
    }
    // An edge first, so the white still shows on a snow-pale page.
    k.line(I.snowEdge, 10.6f * s, ke, draw = hook)
    k.line(I.snow, 9f * s, ke, draw = hook)
    k.line(I.red, 9f * s, ke, cap = StrokeCap.Butt, effect = PathEffect.dashPathEffect(floatArrayOf(6f * s, 6f * s), 0f), draw = hook)
}
