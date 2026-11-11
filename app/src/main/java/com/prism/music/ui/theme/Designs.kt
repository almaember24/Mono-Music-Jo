package com.prism.music.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The "Designs" backgrounds: detailed illustrations (a starfield, contour lines, a low-poly mosaic,
 * bokeh, silk ribbons, a retro horizon) that stay calm enough to read over. Detail is strongest at
 * the top and thins out down the page, where lists sit thickest, and [BackdropLayer] lays the usual
 * wash over them. Every random choice is seeded, so a design looks the same each time. Light mode
 * isn't just the dark design in pastels where that looks dirty: Constellation becomes a star chart,
 * Horizon a dawn sky.
 *
 * Line-heavy designs (Contours, Silk) build their paths once per size and only slide them while
 * animating; a page draws its background more than once (behind the page and under the top bar).
 */
@Composable
internal fun Design(kind: BackdropKind, colors: List<Color>, dark: Boolean, motion: Boolean) {
    val base = colors[0]
    val periodMs = when (kind) {
        BackdropKind.CONSTELLATION -> 9_000
        BackdropKind.BOKEH -> 60_000
        BackdropKind.HORIZON -> 6_000
        BackdropKind.CONTOURS -> 40_000
        // Leaves drift down slowly; snow a little faster.
        BackdropKind.HARVEST -> 48_000
        BackdropKind.HOLIDAY -> 40_000
        else -> 30_000
    }
    val animated = motion && kind != BackdropKind.MOSAIC
    val t = drift(animated, periodMs, 0.3f)
    val density = LocalDensity.current.density
    if (kind == BackdropKind.HARVEST || kind == BackdropKind.HOLIDAY) {
        // The seasonal scenes keep a picture of the page: held while one shows, let go when Prism is put away or Android
        // asks for memory, and when no seasonal background is on screen any more.
        val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
        androidx.compose.runtime.DisposableEffect(Unit) {
            val trim = object : android.content.ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) SeasonalScenes.release()
                }
                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
                @Deprecated("Deprecated in Java") override fun onLowMemory() = SeasonalScenes.release()
            }
            SeasonalScenes.enter()
            app.registerComponentCallbacks(trim)
            onDispose { app.unregisterComponentCallbacks(trim); SeasonalScenes.leave() }
        }
        Canvas(Modifier.fillMaxSize()) { drawDesign(kind, colors, dark, t.value, density) }
        return
    }
    Canvas(Modifier.fillMaxSize().background(base)) { drawDesign(kind, colors, dark, t.value, density) }
}

/** Draws one design at animation time [t] (0..1); plain drawing, so tests can render it to an image. */
internal fun DrawScope.drawDesign(kind: BackdropKind, colors: List<Color>, dark: Boolean, t: Float, density: Float) {
    // The seasonal scenes are built once per size, page color included, and keep their own randoms (nothing made per frame).
    if (kind == BackdropKind.HARVEST) return harvest(colors, dark, t, density)
    if (kind == BackdropKind.HOLIDAY) return holiday(colors, dark, t, density)
    val (base, a, b, c) = colors
    drawRect(base)
    // A fresh generator with the same seed every frame: stars and facets stay put.
    val r = Random(kind.ordinal * 7919 + 17)
    when (kind) {
        BackdropKind.CONSTELLATION -> if (dark) constellation(r, a, b, c, t, density) else starChart(r, a, b, c, t, density)
        BackdropKind.CONTOURS -> contours(a, b, c, dark, t, density)
        BackdropKind.MOSAIC -> mosaic(r, base, a, b, c, dark)
        BackdropKind.BOKEH -> bokeh(r, a, b, c, dark, t, density)
        BackdropKind.SILK -> silk(a, b, c, dark, t, density)
        BackdropKind.HORIZON -> horizon(r, base, a, b, c, dark, t, density)
        else -> {}
    }
}

/** Fades detail out toward the bottom of the page. */
private fun DrawScope.fade(y: Float, floor: Float = 0.2f) = (1f - (y / size.height) * 0.8f).coerceIn(floor, 1f)

private fun DrawScope.glow(color: Color, alpha: Float, cx: Float, cy: Float, radius: Float) =
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), Offset(cx, cy), radius), radius, Offset(cx, cy))

/** Paths built once per design and size (screens draw the same background several times over). */
private val pathCache = object : LinkedHashMap<String, Any>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Any>) = size > 12
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> cached(key: String, build: () -> T): T = synchronized(pathCache) { pathCache.getOrPut(key, build) as T }

// ------------------------------------------------------------------------------------ Constellation

/** The constellations: a few bright stars joined by lines, in the upper part of the page. */
private fun DrawScope.figures(r: Random, d: Float): List<List<Offset>> {
    val w = size.width; val h = size.height
    return List(5) {
        val cx = w * (0.12f + r.nextFloat() * 0.76f)
        val cy = h * (0.06f + r.nextFloat() * 0.5f)
        var p = Offset(cx, cy)
        val stars = mutableListOf(p)
        repeat(3 + r.nextInt(3)) {
            val ang = r.nextFloat() * 6.28f
            val len = (28f + r.nextFloat() * 46f) * d
            p = Offset((p.x + cos(ang) * len).coerceIn(0f, w), (p.y + sin(ang) * len).coerceIn(0f, h))
            stars += p
        }
        stars
    }
}

private fun DrawScope.constellation(r: Random, star: Color, nebula: Color, nebula2: Color, t: Float, d: Float) {
    val w = size.width; val h = size.height
    glow(nebula, 0.34f, w * 0.18f, h * 0.22f, w * 0.75f)
    glow(nebula2, 0.22f, w * 0.88f, h * 0.55f, w * 0.65f)
    // A thin band of fainter stars, like a galaxy seen edge on.
    val band = Path().apply { moveTo(-w * 0.1f, h * 0.05f); lineTo(w * 1.1f, h * 0.42f) }
    drawPath(band, Brush.linearGradient(listOf(Color.Transparent, star.copy(alpha = 0.07f), Color.Transparent), Offset(0f, 0f), Offset(w, h * 0.5f)), style = Stroke(w * 0.22f))
    val twinkle = t * 2 * PI.toFloat()
    repeat(150) {
        val x = r.nextFloat() * w
        val y = r.nextFloat() * h
        val size = (0.5f + r.nextFloat() * r.nextFloat() * 1.6f) * d
        val phase = r.nextFloat() * 6.28f
        drawCircle(star.copy(alpha = 0.75f * (0.55f + 0.45f * sin(twinkle + phase)) * fade(y)), size, Offset(x, y))
    }
    figures(r, d).forEachIndexed { g, stars ->
        val f = fade(stars[0].y)
        val path = Path().apply { moveTo(stars[0].x, stars[0].y); stars.drop(1).forEach { lineTo(it.x, it.y) } }
        drawPath(path, star.copy(alpha = 0.28f * f), style = Stroke(0.8f * d))
        stars.forEachIndexed { i, s ->
            val pulse = 0.75f + 0.25f * sin(twinkle + g + i)
            glow(star, 0.35f * f * pulse, s.x, s.y, 7f * d)
            drawCircle(star.copy(alpha = 0.95f * f), 1.7f * d, s)
        }
    }
}

/**
 * Light mode's Constellation: a printed star chart rather than a night sky (dark specks on a pale page
 * just look like dust). Faint chart circles and meridians, the figures in ink, stars as small sparkles.
 */
private fun DrawScope.starChart(r: Random, ink: Color, wash: Color, wash2: Color, t: Float, d: Float) {
    val w = size.width; val h = size.height
    glow(wash, 0.30f, w * 0.15f, h * 0.18f, w * 0.8f)
    glow(wash2, 0.26f, w * 0.9f, h * 0.5f, w * 0.7f)
    // The chart: circles of declination around a pole off the top corner, and meridians from it.
    val pole = Offset(w * 1.05f, -h * 0.08f)
    for (i in 1..9) drawCircle(ink.copy(alpha = 0.07f), w * 0.22f * i, pole, style = Stroke(0.8f * d))
    for (i in 0..8) {
        val a = PI.toFloat() * (0.5f + i / 16f)
        val end = Offset(pole.x + cos(a) * h * 1.4f, pole.y + sin(a) * h * 1.4f)
        drawLine(Brush.linearGradient(listOf(ink.copy(alpha = 0.08f), Color.Transparent), pole, end), pole, end, 0.8f * d)
    }
    fun sparkle(c: Offset, size: Float, alpha: Float) {
        drawLine(ink.copy(alpha = alpha), Offset(c.x - size, c.y), Offset(c.x + size, c.y), 0.9f * d)
        drawLine(ink.copy(alpha = alpha), Offset(c.x, c.y - size), Offset(c.x, c.y + size), 0.9f * d)
        drawCircle(ink.copy(alpha = alpha), size * 0.32f, c)
    }
    val twinkle = t * 2 * PI.toFloat()
    // A light scatter of faint stars, fewer than at night.
    repeat(45) {
        val p = Offset(r.nextFloat() * w, r.nextFloat() * h * 0.8f)
        val phase = r.nextFloat() * 6.28f
        drawCircle(ink.copy(alpha = 0.22f * (0.6f + 0.4f * sin(twinkle + phase)) * fade(p.y)), (0.6f + r.nextFloat() * 0.6f) * d, p)
    }
    figures(r, d).forEachIndexed { g, stars ->
        val f = fade(stars[0].y)
        val path = Path().apply { moveTo(stars[0].x, stars[0].y); stars.drop(1).forEach { lineTo(it.x, it.y) } }
        drawPath(path, ink.copy(alpha = 0.30f * f), style = Stroke(0.9f * d))
        stars.forEachIndexed { i, s -> sparkle(s, (3.2f + 0.8f * sin(twinkle + g + i)) * d, 0.55f * f) }
    }
}

// ------------------------------------------------------------------------------------ Contours

/** One winding contour line: its path over two widths (so it can slide), and how to draw it. */
private class Contour(val path: Path, val y: Float, val index: Boolean)

private fun DrawScope.contours(line: Color, line2: Color, mark: Color, dark: Boolean, t: Float, d: Float) {
    val w = size.width; val h = size.height
    glow(line2, if (dark) 0.22f else 0.3f, w * 0.85f, h * 0.12f, w * 0.7f)
    // Whole waves per screen width, so sliding the lines by one width loops seamlessly.
    val lines = cached("contours:$w:$h:$d") {
        val step = 17f * d
        val out = mutableListOf<Contour>()
        var k = 0
        var y0 = -step * 3
        val tau = 2 * PI.toFloat()
        while (y0 < h + step * 3) {
            val path = Path()
            var x = 0f
            fun yAt(x: Float) = y0 + h * (0.045f * sin(tau * x / w + k * 0.13f) + 0.028f * sin(tau * x / w * 2f - k * 0.21f))
            path.moveTo(0f, yAt(0f))
            while (x <= 2 * w + 10f) { x += 10f; path.lineTo(x, yAt(x)) }
            out += Contour(path, y0, k % 5 == 0)
            y0 += step; k++
        }
        out
    }
    translate(left = -t * w) {
        for (c in lines) {
            val color = lerp(line, line2, (c.y / h).coerceIn(0f, 1f))
            val alpha = (if (dark) 0.16f else 0.24f) * (if (c.index) 1.8f else 1f) * fade(c.y, 0.25f)
            drawPath(c.path, color.copy(alpha = alpha), style = Stroke((if (c.index) 1.3f else 0.8f) * d))
        }
    }
    // A summit in the top corner: closed rings over the flowing lines, slowly turning (a third of a turn per loop,
    // which their three- and six-fold wobble repeats exactly).
    val cx = w * 0.78f; val cy = h * 0.16f
    val rings = cached("summit:$d") {
        (1..9).map { ring ->
            val rr = ring * 13f * d
            Path().apply {
                var a = 0f
                while (a <= 6.30f) {
                    val rad = rr * (1f + 0.12f * sin(3 * a + ring * 0.5f) + 0.06f * sin(6 * a - ring * 0.3f))
                    val px = cos(a) * rad; val py = sin(a) * rad * 0.8f
                    if (a == 0f) moveTo(px, py) else lineTo(px, py)
                    a += 0.08f
                }
                close()
            }
        }
    }
    translate(cx, cy) {
        rotate(t * 120f, Offset.Zero) {
            rings.forEachIndexed { i, p ->
                val ring = i + 1
                drawPath(p, line.copy(alpha = (if (dark) 0.3f else 0.36f) * (1f - ring / 12f)), style = Stroke((if (ring % 3 == 0) 1.3f else 0.8f) * d))
            }
        }
    }
    drawCircle(mark.copy(alpha = if (dark) 0.8f else 0.75f), 2.4f * d, Offset(cx, cy))
}

// ------------------------------------------------------------------------------------ Mosaic

private fun DrawScope.mosaic(r: Random, base: Color, a: Color, b: Color, c: Color, dark: Boolean) {
    val w = size.width; val h = size.height
    val cols = 6
    val cell = w / cols
    val rows = (h / cell).toInt() + 2
    // Jittered grid points, shared by neighbouring triangles so the facets meet.
    val pts = Array(rows + 1) { row -> Array(cols + 1) { col ->
        val jx = if (col == 0 || col == cols) 0f else (r.nextFloat() - 0.5f) * cell * 0.7f
        val jy = (r.nextFloat() - 0.5f) * cell * 0.7f
        Offset(col * cell + jx, row * cell + jy - cell * 0.5f)
    } }
    val strength = if (dark) 0.62f else 0.55f
    fun shade(center: Offset): Color {
        // A diagonal sweep through the three colors, each facet a little lighter or darker.
        val u = ((center.x / w) * 0.45f + (center.y / h) * 0.75f).coerceIn(0f, 1f)
        val tone = if (u < 0.5f) lerp(a, b, u * 2) else lerp(b, c, (u - 0.5f) * 2)
        val jitter = (r.nextFloat() - 0.5f) * 0.22f
        val shaded = if (jitter > 0) lerp(tone, Color.White, jitter) else lerp(tone, if (dark) Color.Black else base, -jitter)
        return lerp(base, shaded, strength * fade(center.y, 0.12f))
    }
    for (row in 0 until rows) for (col in 0 until cols) {
        val p00 = pts[row][col]; val p10 = pts[row][col + 1]; val p01 = pts[row + 1][col]; val p11 = pts[row + 1][col + 1]
        val flip = (row + col) % 2 == 0
        val tris = if (flip) listOf(listOf(p00, p10, p11), listOf(p00, p11, p01)) else listOf(listOf(p00, p10, p01), listOf(p10, p11, p01))
        for (tri in tris) {
            val path = Path().apply { moveTo(tri[0].x, tri[0].y); lineTo(tri[1].x, tri[1].y); lineTo(tri[2].x, tri[2].y); close() }
            val center = Offset((tri[0].x + tri[1].x + tri[2].x) / 3, (tri[0].y + tri[1].y + tri[2].y) / 3)
            drawPath(path, shade(center))
            drawPath(path, base.copy(alpha = if (dark) 0.35f else 0.45f), style = Stroke(1f))
        }
    }
}

// ------------------------------------------------------------------------------------ Bokeh

private fun DrawScope.bokeh(r: Random, a: Color, b: Color, c: Color, dark: Boolean, t: Float, d: Float) {
    val w = size.width; val h = size.height
    glow(a, if (dark) 0.25f else 0.3f, w * 0.2f, h * 0.15f, w * 0.8f)
    glow(c, if (dark) 0.2f else 0.26f, w * 0.95f, h * 0.7f, w * 0.7f)
    val palette = listOf(a, b, c)
    repeat(28) {
        val rad = (16f + r.nextFloat() * r.nextFloat() * 80f) * d
        val x0 = r.nextFloat() * w
        val y0 = r.nextFloat() * (h + rad * 2)
        // Whole laps per loop, so nothing jumps when the animation starts over.
        val speed = 1f + r.nextInt(2)
        val sway = r.nextFloat() * 6.28f
        // Slowly rising, wrapping round at the top.
        val span = h + rad * 2
        val y = ((y0 - t * span * speed) % span + span) % span - rad
        val x = x0 + sin(t * 6.28f + sway) * 14f * d
        val color = palette[r.nextInt(3)]
        val alpha = (if (dark) 0.07f else 0.10f) + r.nextFloat() * 0.12f
        val f = fade(y, 0.35f)
        drawCircle(color.copy(alpha = alpha * f), rad, Offset(x, y))
        drawCircle(color.copy(alpha = alpha * 1.6f * f), rad, Offset(x, y), style = Stroke(1.2f * d))
    }
}

// ------------------------------------------------------------------------------------ Silk

private fun DrawScope.silk(a: Color, b: Color, c: Color, dark: Boolean, t: Float, d: Float) {
    val w = size.width; val h = size.height
    glow(a, if (dark) 0.22f else 0.28f, w * 0.1f, h * 0.1f, w * 0.7f)
    data class Spec(val y: Float, val amp: Float, val waves: Int, val shift: Float, val strength: Float, val direction: Float)
    val specs = listOf(
        Spec(0.16f, 0.07f, 1, 0f, 1f, 1f),
        Spec(0.38f, 0.05f, 1, 2.1f, 0.8f, -1f),
        Spec(0.80f, 0.06f, 2, 4.0f, 0.7f, 1f),
    )
    val geometry = cached("silk:$w:$h:$d") {
        specs.map { s ->
            val count = 20
            List(count) { i ->
                val off = i * 3.2f * d
                val twist = sin(i / count.toFloat() * PI.toFloat())
                val path = Path()
                var x = 0f
                fun yAt(x: Float): Float {
                    val u = x / w * 2 * PI.toFloat() * s.waves
                    return h * s.y + h * s.amp * sin(u + s.shift) + off * (0.6f + 0.4f * cos(u * 2 + s.shift))
                }
                path.moveTo(0f, yAt(0f))
                while (x <= 2 * w + 12f) { x += 12f; path.lineTo(x, yAt(x)) }
                path to (0.4f + 0.6f * twist)
            }
        }
    }
    val pairs = listOf(listOf(a, b), listOf(b, c), listOf(c, a))
    geometry.forEachIndexed { n, threads ->
        val s = specs[n]
        val strength = (if (dark) 0.17f else 0.24f) * s.strength
        // The colors repeat every width too, so the slide loops without a seam.
        val shiftX = if (s.direction > 0) -t * w else (t - 1f) * w
        translate(left = shiftX) {
            for ((path, k) in threads) {
                val cols = pairs[n].map { it.copy(alpha = strength * k) }
                drawPath(path, Brush.horizontalGradient(cols + cols.first(), 0f, w, TileMode.Repeated), style = Stroke(1.1f * d))
            }
        }
    }
}

// ------------------------------------------------------------------------------------ Horizon

/**
 * A retro sun over a grid running off to the horizon. The horizon sits high, so the sun is up behind the
 * page's title rather than its list. Dark mode is a synthwave night; light mode a pastel dawn with a soft sky.
 */
private fun DrawScope.horizon(r: Random, base: Color, sun: Color, sun2: Color, grid: Color, dark: Boolean, t: Float, d: Float) {
    val w = size.width; val h = size.height
    val horizonY = h * 0.3f
    if (dark) {
        glow(sun2, 0.34f, w * 0.5f, horizonY, w * 0.9f)
        repeat(40) {
            val y = r.nextFloat() * horizonY * 0.85f
            drawCircle(grid.copy(alpha = 0.5f * r.nextFloat()), (0.5f + r.nextFloat()) * d, Offset(r.nextFloat() * w, y))
        }
    } else {
        // Dawn: the sky warms toward the horizon.
        drawRect(Brush.verticalGradient(listOf(grid.copy(alpha = 0.10f), sun2.copy(alpha = 0.22f), sun.copy(alpha = 0.18f)), 0f, horizonY),
            size = androidx.compose.ui.geometry.Size(w, horizonY))
        glow(sun, 0.30f, w * 0.5f, horizonY, w * 0.7f)
    }
    // The sun, banded across its lower half (the bands are cut out of the sun only).
    val sr = w * 0.22f
    val sc = Offset(w * 0.5f, horizonY - sr * 0.3f)
    drawCircle(Brush.verticalGradient(listOf(sun.copy(alpha = if (dark) 0.85f else 0.6f), sun2.copy(alpha = if (dark) 0.8f else 0.55f)), sc.y - sr, sc.y + sr), sr, sc)
    clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(sc, sr)) }) {
        for (i in 0 until 6) {
            val bandH = (1.6f + i * 1.3f) * d
            val y = sc.y + sr * (0.12f + i * 0.15f)
            if (y < horizonY) drawRect(if (dark) base else lerp(base, sun2, 0.12f), Offset(sc.x - sr, y), androidx.compose.ui.geometry.Size(sr * 2, bandH))
        }
    }
    // Ground: hide the sun below the horizon, then a grid running off toward it.
    drawRect(base, Offset(0f, horizonY), androidx.compose.ui.geometry.Size(w, h - horizonY))
    drawRect(Brush.verticalGradient(listOf(sun2.copy(alpha = if (dark) 0.16f else 0.12f), Color.Transparent), horizonY, horizonY + h * 0.4f),
        Offset(0f, horizonY), androidx.compose.ui.geometry.Size(w, h - horizonY))
    val gAlpha = if (dark) 0.34f else 0.26f
    drawLine(sun2.copy(alpha = gAlpha * 1.5f), Offset(0f, horizonY), Offset(w, horizonY), 1.2f * d)
    val lines = 16
    for (i in 0 until lines) {
        val u = ((i + t) / lines).coerceIn(0f, 1f)
        val y = horizonY + (h - horizonY) * u.pow(2.2f)
        drawLine(grid.copy(alpha = gAlpha * (0.25f + 0.75f * u) * fade(y, 0.3f)), Offset(0f, y), Offset(w, y), 1f * d)
    }
    val spokes = 16
    for (i in 0..spokes) {
        val bx = -w + (3 * w) * i / spokes
        drawLine(
            Brush.verticalGradient(listOf(grid.copy(alpha = gAlpha * 0.3f), grid.copy(alpha = gAlpha * 0.12f)), horizonY, h),
            Offset(w * 0.5f, horizonY), Offset(bx, h), 1f * d,
        )
    }
}
