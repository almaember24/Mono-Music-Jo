package com.prism.music.playback

import kotlin.math.exp

/**
 * A song's energy, 0 (calm) … 1 (energetic), from what the song scans already compute (1.8.3, no extra pass): how loud
 * it is, how clear and strong its beat is, and how bright its sound is. Rough on purpose: it sorts songs for smart
 * playlists, and DJ mode / Run mode use it only once real libraries show it sorts sensibly.
 */
object Energy {
    /** Loudness counts most, then the beat, then brightness. */
    private const val LOUD_WEIGHT = 0.5
    private const val BEAT_WEIGHT = 0.3
    private const val BRIGHT_WEIGHT = 0.2

    /** −20 LUFS (quiet, acoustic) … −6 LUFS (a loud modern master). */
    internal fun loudPart(lufs: Float): Double = ((lufs + 20.0) / 14.0).coerceIn(0.0, 1.0)

    /** [Tempo.confidence] 1.5 (hardly a beat) … 8 (a hard, steady one); no beat found at all counts as none. */
    internal fun beatPart(confidence: Double?): Double = confidence?.let { ((it - 1.5) / 6.5).coerceIn(0.0, 1.0) } ?: 0.0

    /** [brightness] 0.2 (dull, bass-heavy) … 0.6 (bright); pink noise (equal energy in every band) is 0.5. */
    internal fun brightPart(brightness: Double): Double = ((brightness - 0.2) / 0.4).coerceIn(0.0, 1.0)

    /**
     * The song's energy; null without its loudness. [brightness] is null for songs measured before 1.8.3 (it wasn't
     * kept): the other two then count in the same proportion.
     */
    fun of(lufs: Float?, confidence: Double?, brightness: Double?): Float? {
        if (lufs == null || lufs.isNaN()) return null
        var sum = LOUD_WEIGHT * loudPart(lufs) + BEAT_WEIGHT * beatPart(confidence)
        var weights = LOUD_WEIGHT + BEAT_WEIGHT
        if (brightness != null && !brightness.isNaN()) { sum += BRIGHT_WEIGHT * brightPart(brightness); weights += BRIGHT_WEIGHT }
        return (sum / weights).toFloat()
    }

    /**
     * Where the sound's energy sits across [bands] ([VideoSync.bandFeatures]: log energy per log-spaced band, per
     * frame), 0 (all in the lowest band) … 1 (all in the highest): the bands' average energy, weighted by place.
     */
    fun brightness(bands: Array<FloatArray>): Double? {
        if (bands.size < 2) return null
        val frames = bands.minOf { it.size }
        if (frames == 0) return null
        var weighted = 0.0
        var all = 0.0
        for (b in bands.indices) {
            var e = 0.0
            val x = bands[b]
            for (t in 0 until frames) e += exp(x[t].toDouble())
            weighted += b * e
            all += e
        }
        if (all <= 0.0) return null
        return weighted / all / (bands.size - 1)
    }
}
