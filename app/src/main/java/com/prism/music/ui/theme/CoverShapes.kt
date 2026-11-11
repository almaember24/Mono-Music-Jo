package com.prism.music.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.CoverShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Covers' shapes (1.7.7, Customize Prism → Icons & shapes): Prism's own drawings, no library. Each outline is a path
 * worked out for the size the cover is laid out at, so it's made once per size, not per frame.
 */
object CoverShapes {
    /** [shape] for a cover whose rounded version has corners of [rounded] (already scaled by the Corners setting). */
    fun of(shape: CoverShape, rounded: Dp): Shape = when (shape) {
        CoverShape.ROUNDED -> RoundedCornerShape(rounded)
        CoverShape.SQUARE -> RoundedCornerShape(1.5.dp)
        CoverShape.CIRCLE -> CircleShape
        CoverShape.SQUIRCLE -> squircle
        CoverShape.FLOWER -> flower
        CoverShape.COOKIE -> cookie
        CoverShape.ARCH -> arch
        CoverShape.SLANTED -> slanted
    }

    /**
     * The outline a shadow under [shape] is cast from. Flower and Cookie dip inwards, and Android's renderer can spend
     * seconds working out a concave outline's shadow (the 1.7.7 release check froze the player for over 5 s under a
     * Flower cover: an ANR in Skia's concave shadow tessellator). Their shadow is cast from a circle instead, which they
     * nearly fill; every other shape is convex and casts its own.
     */
    fun shadowOf(shape: Shape): Shape = if (shape === flower || shape === cookie) CircleShape else shape

    /** A superellipse (|x|⁴ + |y|⁴ = 1): a square gone soft all along its sides, as app icons are. */
    private val squircle = GenericShape { size, _ ->
        val n = 64
        val cx = size.width / 2; val cy = size.height / 2
        // Up to n - 1: close() joins the last point to the first (a repeated first point is an edge of no length).
        for (i in 0 until n) {
            val t = 2 * PI * i / n
            val c = cos(t); val s = sin(t)
            val x = cx + cx * sign(c) * abs(c).pow(0.5)
            val y = cy + cy * sign(s) * abs(s).pow(0.5)
            if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
        }
        close()
    }

    /** A round shape whose edge rises and falls [lobes] times by [depth] of its radius (flower, cookie). */
    private fun wavy(lobes: Int, depth: Float) = GenericShape { size, _ ->
        val n = lobes * 12
        val cx = size.width / 2; val cy = size.height / 2
        for (i in 0 until n) {
            val t = 2 * PI * i / n
            val r = 1f - depth + depth * cos(lobes * t).toFloat()
            val x = cx + cx * r * cos(t).toFloat()
            val y = cy + cy * r * sin(t).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private val flower = wavy(8, 0.11f)
    private val cookie = wavy(12, 0.045f)

    /** Round on top like a window's arch, square-ish at the bottom. */
    private val arch = GenericShape { size, _ ->
        val w = size.width; val h = size.height
        val r = w / 2
        val foot = w * 0.08f
        moveTo(0f, r)
        arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, w, w), 180f, 180f, false)
        lineTo(w, h - foot)
        quadraticTo(w, h, w - foot, h)
        lineTo(foot, h)
        quadraticTo(0f, h, 0f, h - foot)
        close()
    }

    /** Leaning a little to the right, its corners rounded. */
    private val slanted = GenericShape { size, _ ->
        val w = size.width; val h = size.height
        val lean = w * 0.1f
        roundedPolygon(listOf(Offset(lean, 0f), Offset(w, 0f), Offset(w - lean, h), Offset(0f, h)), w * 0.09f)
    }

    /** A polygon through [points] with each corner cut by [r] along its sides and curved through the corner. */
    private fun Path.roundedPolygon(points: List<Offset>, r: Float) {
        fun toward(a: Offset, b: Offset, d: Float): Offset {
            val v = b - a
            val len = v.getDistance().coerceAtLeast(1e-3f)
            return a + v * (d.coerceAtMost(len / 2) / len)
        }
        val n = points.size
        for (i in 0 until n) {
            val p = points[i]
            val prev = points[(i - 1 + n) % n]
            val next = points[(i + 1) % n]
            val a = toward(p, prev, r)
            val b = toward(p, next, r)
            if (i == 0) moveTo(a.x, a.y) else lineTo(a.x, a.y)
            quadraticTo(p.x, p.y, b.x, b.y)
        }
        close()
    }
}
