package com.prism.music.playback.sing

/**
 * Which way Sing tells voices apart on this device (1.8.x, "Sing on as many devices as possible"), from how fast each
 * network way has gone here: seconds of song separated a second, with the player sharing the device all the while.
 * Measured on 50 songs with their real parts (MUSDB18's excerpts), the light way's backing comes within 0.1 dB of the
 * full way's (11.6 against 11.7 dB, median) in about two thirds of the time, and the quick way's 7.2 dB is still well
 * clear of the song as it is (4.5 dB); so the full way where it's well ahead, the light way where it keeps up, the quick
 * way where neither does or the engine can't run.
 */
object SingPace {
    /** A way keeps up when it's this much faster than the song plays (room for the player, and for slower moments). */
    const val KEEPS_UP = 1.3
    /** The full way, only a little better than the light one at half again the work, once it's this far ahead. */
    const val FULL_FROM = 2.0
    /** How much faster the light way goes than the full one, till both have been timed here (1.5–1.6 on the A16). */
    const val LIGHT_GAIN = 1.5

    /**
     * The way for the next stretch of song: [engine] says the networks can run here at all; [full] and [light] are
     * their speeds here, null till timed. Nothing timed yet: the full way, timed as it goes.
     */
    fun choose(engine: Boolean, full: Double?, light: Double?): Separation {
        if (!engine) return Separation.QUICK
        val f = full ?: light?.div(LIGHT_GAIN) ?: return Separation.FULL
        val l = light ?: (f * LIGHT_GAIN)
        return when {
            f >= FULL_FROM -> Separation.FULL
            l >= KEEPS_UP -> Separation.LIGHT
            else -> Separation.QUICK
        }
    }

    /** A new timing [speed] taken into the [kept] one, recent ones counting most. */
    fun blend(kept: Double?, speed: Double): Double = kept?.let { 0.6 * it + 0.4 * speed } ?: speed

    /** How fast [seconds] of song done in [ms] is. */
    fun speed(seconds: Double, ms: Long): Double = seconds * 1000 / ms.coerceAtLeast(1)
}
