package com.prism.music.ui.theme

import android.graphics.Bitmap

private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

/**
 * A soft blur done in software, so it looks the same on every Android version (the system's
 * blur needs Android 12). Meant for small pictures that are then drawn large; [passes] box blurs
 * approximate a gaussian. [radius] defaults to a twelfth of the shorter side.
 */
fun boxBlur(src: Bitmap, passes: Int, radius: Int = (minOf(src.width, src.height) / 12).coerceAtLeast(2)): Bitmap {
    val w = src.width; val h = src.height
    if (w < 2 || h < 2) return src
    val a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
    val t = IntArray(a.size)
    fun pass(s: IntArray, d: IntArray, horizontal: Boolean) {
        val major = if (horizontal) w else h
        val minor = if (horizontal) h else w
        val win = radius * 2 + 1
        for (fixed in 0 until minor) {
            fun px(at: Int): Int { val p = at.coerceIn(0, major - 1); return if (horizontal) s[fixed * w + p] else s[p * w + fixed] }
            var rr = 0; var gg = 0; var bb = 0
            for (o in -radius..radius) { val c = px(o); rr += c shr 16 and 0xFF; gg += c shr 8 and 0xFF; bb += c and 0xFF }
            for (m in 0 until major) {
                d[if (horizontal) fixed * w + m else m * w + fixed] = argb(rr / win, gg / win, bb / win)
                val out = px(m - radius); val inn = px(m + radius + 1)
                rr += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
                gg += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
                bb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
    repeat(passes) { pass(a, t, true); pass(t, a, false) }
    return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
}
