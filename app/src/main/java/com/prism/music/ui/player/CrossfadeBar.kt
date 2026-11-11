package com.prism.music.ui.player

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.SemanticsPropertyKey
import com.prism.music.data.prefs.CrossfadeCurve
import com.prism.music.playback.BlendIn
import com.prism.music.playback.BlendMark
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The progress bar shaped by the crossfade (1.8.1, the owner's; an option, Customize Prism → Player → Progress bar).
 * From where the blend starts to the song's end the bar's height follows the outgoing volume, and in the next song it
 * grows in from nothing along the incoming volume, both by [CrossfadeCurve.gains]: Smooth tapers round, Straight in a
 * straight wedge, Gentle in an S, DJ cut stays full and then drops. Windows are fractions (0 … 1) of the song;
 * [NONE] where there's no fade.
 */
data class BlendShape(
    val curve: CrossfadeCurve,
    val outFrom: Float = NONE,
    val outTo: Float = NONE,
    val inFrom: Float = NONE,
    val inTo: Float = NONE,
) {
    val fadesOut: Boolean get() = outFrom >= 0f && outTo > outFrom
    val fadesIn: Boolean get() = inFrom >= 0f && inTo > inFrom

    /** The bar's height at [x] (0 … 1 of the song) as a share of its full height: [FLOOR] at the least, as the mockup. */
    fun height(x: Float): Float {
        var g = 1f
        if (fadesOut) g = minOf(g, when {
            x <= outFrom -> 1f
            x >= outTo -> 0f
            else -> curve.gains((x - outFrom) / (outTo - outFrom)).second
        })
        if (fadesIn) g = minOf(g, when {
            x <= inFrom -> 0f
            x >= inTo -> 1f
            else -> curve.gains((x - inFrom) / (inTo - inFrom)).first
        })
        return g.coerceAtLeast(FLOOR)
    }

    companion object {
        const val NONE = -1f
        /** The thinnest the bar gets where the volume is all but gone (the mockup's 1%): still a hairline there. */
        const val FLOOR = 0.01f

        /**
         * The shape of a [durationMs] song's bar: its end from [ending] (the blend after it, once its length is known)
         * and its start from [starting] (how it came in), each only when it's about this song ([songId]). Null when
         * neither applies, so the bar is drawn exactly as without the option.
         */
        fun of(songId: String?, durationMs: Long, ending: BlendMark?, starting: BlendIn?, curve: CrossfadeCurve): BlendShape? {
            if (songId == null || durationMs <= 0) return null
            val d = durationMs.toFloat()
            val out = ending?.takeIf { it.songId == songId && it.lengthMs > 0 && it.startMs in 0 until durationMs }
            val into = starting?.takeIf { it.songId == songId && it.lengthMs > 0 && it.inMs in 0 until durationMs }
            if (out == null && into == null) return null
            return BlendShape(
                curve,
                outFrom = out?.let { it.startMs / d } ?: NONE,
                outTo = out?.let { ((it.startMs + it.lengthMs) / d).coerceAtMost(1f) } ?: NONE,
                inFrom = into?.let { it.inMs / d } ?: NONE,
                inTo = into?.let { ((it.inMs + it.lengthMs) / d).coerceAtMost(1f) } ?: NONE,
            )
        }

        /** The knob riding the shape: its size at [height] (as the mockup's dot, r = 2.5 + 12·h of a 24-high bar, ≥ 3). */
        fun knobScale(height: Float): Float = maxOf(3f, 2.5f + 12f * height.coerceIn(0f, 1f)) / 14.5f
    }
}

/** Marks the progress bar when it's shaped by the crossfade: "out", "in" or "both" (in-app tests read it; not spoken). */
val CrossfadeShapeKey = SemanticsPropertyKey<String>("CrossfadeShape")

/** What [CrossfadeShapeKey] holds for [shape]. */
fun BlendShape.semanticsValue(): String = when {
    fadesOut && fadesIn -> "both"
    fadesIn -> "in"
    else -> "out"
}

/**
 * The shaped bar's outline in a [width] × [height] box: the shape's height at every pixel, within the bar's rounded ends
 * (so the parts at full height are the 1.8.0 bar exactly). Built once for each size and shape and then reused: drawing
 * a frame makes nothing new (the bar's height only changes while it grows under the finger).
 */
class BlendBarPath {
    val path = Path()
    private var width = -1f
    private var height = -1f
    private var shape: BlendShape? = null
    private var xs = FloatArray(0)
    private var halves = FloatArray(0)

    /** The outline for [shape] in a [w] × [h] box (the same [path], rebuilt only when one of them changed). */
    fun outline(shape: BlendShape, w: Float, h: Float): Path {
        if (shape == this.shape && w == width && h == height) return path
        this.shape = shape; width = w; height = h
        path.reset()
        if (w <= 0f || h <= 0f) return path
        // A sample every pixel; a run of equal heights (the flat parts) keeps only its two ends.
        val n = ceil(w).toInt() + 1
        if (xs.size < n) { xs = FloatArray(n); halves = FloatArray(n) }
        for (i in 0 until n) {
            val x = minOf(i.toFloat(), w)
            xs[i] = x
            halves[i] = halfAt(shape, x, w, h)
        }
        val mid = h / 2
        var started = false
        for (i in 0 until n) if (keeps(i, n)) {
            if (!started) { path.moveTo(xs[i], mid - halves[i]); started = true } else path.lineTo(xs[i], mid - halves[i])
        }
        for (i in n - 1 downTo 0) if (keeps(i, n)) path.lineTo(xs[i], mid + halves[i])
        path.close()
        return path
    }

    private fun keeps(i: Int, n: Int) = i == 0 || i == n - 1 || halves[i] != halves[i - 1] || halves[i] != halves[i + 1]

    companion object {
        /**
         * Half the bar's thickness [x] px along a [w] × [h] bar: the shape's height, inside the round ends (a half
         * circle of the bar's own height at each end, as [androidx.compose.ui.geometry.CornerRadius] (h / 2) draws them).
         */
        fun halfAt(shape: BlendShape, x: Float, w: Float, h: Float): Float {
            val r = h / 2
            val cap = when {
                x < r -> (r - x).let { d -> sqrt((r * r - d * d).coerceAtLeast(0f)) }
                x > w - r -> (x - (w - r)).let { d -> sqrt((r * r - d * d).coerceAtLeast(0f)) }
                else -> r
            }
            return minOf(shape.height(x / w) * r, cap)
        }
    }
}
