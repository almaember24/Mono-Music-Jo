package com.prism.music.data

import android.graphics.Bitmap
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import coil3.transform.Transformation

/*
 * Square covers for videos (GitHub #92). YouTube's video thumbnails (i.ytimg.com/vi/…) are 4:3 frames with black bars
 * above and below a 16:9 picture, so a video played as a song showed those bars in its square cover: on the player, in
 * lists, the notification, the lock screen and Android Auto. With Settings → Player → "Square video covers" on, the
 * bars are trimmed and, wherever the cover is square, the middle of the picture is cut square. Wide places (a video's
 * 16:9 frame) only lose the bars.
 */

/** Adds [VideoFrameCrop] to every video thumbnail Prism loads while [active]. */
class VideoCoverInterceptor(private val active: () -> Boolean) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        if (!url.contains("ytimg.com/vi") || !active()) return chain.proceed()
        val request = chain.request.newBuilder().transformations(chain.request.transformations + VideoFrameCrop).build()
        return chain.withRequest(request).proceed()
    }
}

/** Trims a video frame's black bars, then cuts its middle square when it's to fill a square (or an unknown) size. */
object VideoFrameCrop : Transformation() {
    override val cacheKey: String = "prism.videoFrameCrop.1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 8 || h < 8) return input
        val pixels = IntArray(w * h).also { input.getPixels(it, 0, w, 0, 0, w, h) }
        val (l, t, r, b) = Letterbox.content(pixels, w, h).let { if (wantsSquare(size)) Letterbox.square(it) else it }
        if (l == 0 && t == 0 && r == w && b == h) return input
        return Bitmap.createBitmap(input, l, t, r - l, b - t)
    }

    /** A square slot, or one Coil doesn't know the shape of (Prism's covers are square unless shown as a video). */
    private fun wantsSquare(size: Size): Boolean {
        val w = (size.width as? Dimension.Pixels)?.px ?: return true
        val h = (size.height as? Dimension.Pixels)?.px ?: return true
        val ratio = w.toFloat() / h
        return ratio in 0.8f..1.25f
    }
}

/** Finding the picture inside a frame's black bars. Pure, so it's tested on the JVM ([com.prism.music.VideoCoverTest]). */
internal object Letterbox {
    /** Below this in every channel a pixel counts as black (JPEG leaves bars a little above 0). */
    private const val DARK = 30

    /**
     * The picture inside [w]×[h] ARGB [pixels]: left, top, right, bottom (right and bottom exclusive). Bars are taken off
     * evenly (a letterbox is), each side by the smaller of what's black at either edge, so a dark sky at the top of a
     * picture isn't mistaken for a bar; at most a third of the frame each way.
     */
    fun content(pixels: IntArray, w: Int, h: Int): IntArray {
        fun dark(p: Int) = (p shr 16 and 255) < DARK && (p shr 8 and 255) < DARK && (p and 255) < DARK
        // A line is a bar when nearly all of it (98%) is black.
        fun rowDark(y: Int): Boolean {
            var lit = 0
            for (x in 0 until w) if (!dark(pixels[y * w + x])) lit++
            return lit <= w / 50
        }
        fun colDark(x: Int, top: Int, bottom: Int): Boolean {
            var lit = 0
            for (y in top until bottom) if (!dark(pixels[y * w + x])) lit++
            return lit <= (bottom - top) / 50
        }
        var top = 0
        while (top < h / 3 && rowDark(top)) top++
        var bottom = 0
        while (bottom < h / 3 && rowDark(h - 1 - bottom)) bottom++
        val bar = minOf(top, bottom)
        val t = bar
        val b = h - bar
        var left = 0
        while (left < w / 3 && colDark(left, t, b)) left++
        var right = 0
        while (right < w / 3 && colDark(w - 1 - right, t, b)) right++
        val side = minOf(left, right)
        return intArrayOf(side, t, w - side, b)
    }

    /** The middle square of the picture [box] (left, top, right, bottom). */
    fun square(box: IntArray): IntArray {
        val (l, t, r, b) = box
        val side = minOf(r - l, b - t)
        val x = l + (r - l - side) / 2
        val y = t + (b - t - side) / 2
        return intArrayOf(x, y, x + side, y + side)
    }
}
