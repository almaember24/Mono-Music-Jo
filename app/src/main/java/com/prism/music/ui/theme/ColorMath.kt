package com.prism.music.ui.theme

import com.prism.music.R
import com.prism.music.tr
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Color arithmetic for the color picker (1.7.2), on plain ARGB ints so it's the same on the phone and in tests:
 * hex codes in and out, and HSV / HSL / RGB views of one color. Hues are 0–360, everything else 0–1.
 */
object ColorMath {
    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    /**
     * A typed color code: "#3A86FF", "3a86ff", "#38F" (each digit doubled), or "#AARRGGBB" (8 digits, alpha first, as
     * Android writes them). Spaces are ignored. Null when it isn't one.
     */
    fun parseHex(text: String): Int? {
        val t = text.trim().removePrefix("#").replace(" ", "")
        if (t.isEmpty() || t.any { it.digitToIntOrNull(16) == null }) return null
        return when (t.length) {
            3 -> (0xFF shl 24) or t.map { d -> d.digitToInt(16) * 17 }.let { (r, g, b) -> (r shl 16) or (g shl 8) or b }
            6 -> (0xFF shl 24) or t.toLong(16).toInt()
            8 -> t.toLong(16).toInt()
            else -> null
        }
    }

    /** "#3A86FF" (alpha left out when opaque, "#803A86FF" otherwise). */
    fun toHex(c: Int): String {
        val rgb = "%06X".format(c and 0xFFFFFF)
        return if (alpha(c) == 0xFF) "#$rgb" else "#%02X%s".format(alpha(c), rgb)
    }

    /** Hue, saturation, value. */
    fun toHsv(c: Int): FloatArray {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val h = when {
            d < 1e-6f -> 0f
            mx == r -> 60f * (((g - b) / d).mod(6f))
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, if (mx <= 0f) 0f else d / mx, mx)
    }

    fun fromHsv(h: Float, s: Float, v: Float, alpha: Int = 0xFF): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = s.coerceIn(0f, 1f); val vv = v.coerceIn(0f, 1f)
        val c = vv * ss
        val x = c * (1 - abs((hh / 60f).mod(2f) - 1))
        val m = vv - c
        val (r, g, b) = when ((hh / 60f).toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return argb(alpha, ((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    /** Hue, saturation, lightness. */
    fun toHsl(c: Int): FloatArray {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val l = (mx + mn) / 2f
        val s = if (d < 1e-6f) 0f else d / (1f - abs(2f * l - 1f))
        return floatArrayOf(toHsv(c)[0], s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    fun fromHsl(h: Float, s: Float, l: Float, alpha: Int = 0xFF): Int {
        val ll = l.coerceIn(0f, 1f); val ss = s.coerceIn(0f, 1f)
        // HSL → HSV: v = l + s·min(l, 1−l); s_v = 2(1 − l/v).
        val v = ll + ss * min(ll, 1 - ll)
        val sv = if (v <= 0f) 0f else 2f * (1f - ll / v)
        return fromHsv(h, sv, v, alpha)
    }

    /** WCAG relative luminance of an sRGB color. */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(red(c)) + 0.7152 * ch(green(c)) + 0.0722 * ch(blue(c))
    }

    /** WCAG 2 contrast ratio, 1…21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a) + 0.05; val lb = luminance(b) + 0.05
        return max(la, lb) / min(la, lb)
    }

    /** How readable text in one color is on another, by WCAG 2's thresholds for normal-size text. */
    enum class Readability(val label: String) { AAA(tr(R.string.very_easy_to_read)), AA(tr(R.string.easy_to_read)), LARGE(tr(R.string.ok_for_big_text)), POOR(tr(R.string.hard_to_read)) }

    fun readability(fg: Int, bg: Int): Readability {
        val r = contrast(fg, bg)
        return when {
            r >= 7.0 -> Readability.AAA
            r >= 4.5 -> Readability.AA
            r >= 3.0 -> Readability.LARGE
            else -> Readability.POOR
        }
    }

    /** [a] → [b] by [t]. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun m(x: Int, y: Int) = (x + (y - x) * u).roundToInt()
        return argb(m(alpha(a), alpha(b)), m(red(a), red(b)), m(green(a), green(b)), m(blue(a), blue(b)))
    }
}
