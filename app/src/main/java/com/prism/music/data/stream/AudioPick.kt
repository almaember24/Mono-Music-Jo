package com.prism.music.data.stream

import com.prism.music.data.prefs.AudioQuality
import kotlin.math.abs

/**
 * Which of a song's audio streams to play, the way YouTube Music picks: Opus first at the same quality tier.
 * YouTube's nominal bitrates for Opus (251) and AAC (140) are both about 128–140 kbps, and for some songs AAC's is a
 * little higher. Taking the bigger number made those play as AAC where YouTube Music plays Opus, the better codec at
 * that bitrate (128 kbps Opus is about as good as 160–170 kbps AAC). So Opus counts for [OPUS_WORTH] times its bitrate.
 * High: the best by that measure (Opus 774 > AAC 141 > Opus 251 > AAC 140). Normal: the tier nearest 128 kbps, Opus
 * in it first. Low: the smallest, Opus if it's about as small.
 */
object AudioPick {
    data class Choice(val mime: String, val codec: String?, val kbps: Int, val original: Boolean = true) {
        val opus: Boolean get() = codec?.contains("opus", true) == true || (codec == null && mime.contains("webm"))
        /** YouTube's nominal figure (0 when it gave none: taken as 128). */
        val nominal: Int get() = kbps.takeIf { it > 0 } ?: 128
        val worth: Double get() = nominal * if (opus) OPUS_WORTH else 1.0
    }

    /** The index in [streams] to play, or null when there's none. A dubbed track is only used when there's nothing else. */
    fun pick(streams: List<Choice>, quality: AudioQuality): Int? {
        val usable = streams.indices.filter { streams[it].original }.ifEmpty { streams.indices.toList() }
        if (usable.isEmpty()) return null
        fun c(i: Int) = streams[i]
        return when (quality) {
            AudioQuality.HIGH -> usable.maxWith(compareBy<Int> { c(it).worth }.thenBy { if (c(it).opus) 1 else 0 })
            AudioQuality.NORMAL -> {
                val nearest = usable.minOf { abs(c(it).nominal - 128) }
                usable.filter { abs(c(it).nominal - 128) <= nearest + TIER_KBPS }
                    .minWith(compareBy<Int> { if (c(it).opus) 0 else 1 }.thenBy { abs(c(it).nominal - 128) })
            }
            AudioQuality.LOW -> {
                val smallest = usable.minOf { c(it).nominal }
                usable.filter { c(it).nominal <= smallest + TIER_KBPS / 2 }
                    .minWith(compareBy<Int> { if (c(it).opus) 0 else 1 }.thenBy { c(it).nominal })
            }
        }
    }

    const val OPUS_WORTH = 1.3
    /** Streams this close in bitrate are the same tier. */
    private const val TIER_KBPS = 24
}
