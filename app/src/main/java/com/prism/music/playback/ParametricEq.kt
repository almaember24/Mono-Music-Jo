package com.prism.music.playback

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One band of a parametric EQ, in EqualizerAPO / AutoEq terms. */
@Serializable
data class EqFilter(val type: Type, val hz: Double, val gainDb: Double, val q: Double) {
    enum class Type { PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS }
}

/**
 * A headphone's correction curve: what AutoEq's "ParametricEQ.txt" holds (a preamp and up to ten filters),
 * plus where it came from.
 */
@Serializable
data class EqProfile(
    val name: String,
    val preampDb: Double,
    val filters: List<EqFilter>,
    /** Who measured it ("oratory1990", "crinacle on GRAS 43AG-7"…), or "Imported". */
    val source: String = "",
) {
    companion object {
        private val FILTER = Regex(
            """Filter\s*\d*\s*:\s*ON\s+([A-Z]+)(?:\s+\d+\s*dB)?\s+Fc\s+([\d.]+)\s*Hz(?:\s+Gain\s+([-+\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?""",
            RegexOption.IGNORE_CASE,
        )
        private val PREAMP = Regex("""Preamp\s*:\s*([-+\d.]+)\s*dB""", RegexOption.IGNORE_CASE)

        /**
         * Reads a ParametricEQ.txt (AutoEq) or an EqualizerAPO config: "Preamp: -6.2 dB" and lines like
         * "Filter 1: ON PK Fc 105 Hz Gain 2.5 dB Q 0.70". Filters that are OFF, or of a kind Prism can't apply, are
         * left out. Null when there's no filter in it at all.
         */
        fun parse(name: String, text: String, source: String = ""): EqProfile? {
            val preamp = PREAMP.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val filters = FILTER.findAll(text).mapNotNull { m ->
                val type = when (m.groupValues[1].uppercase()) {
                    "PK", "PEQ", "PEAK" -> EqFilter.Type.PEAK
                    "LS", "LSC", "LSQ", "LOWSHELF" -> EqFilter.Type.LOW_SHELF
                    "HS", "HSC", "HSQ", "HIGHSHELF" -> EqFilter.Type.HIGH_SHELF
                    "LP", "LPQ" -> EqFilter.Type.LOW_PASS
                    "HP", "HPQ" -> EqFilter.Type.HIGH_PASS
                    else -> return@mapNotNull null
                }
                val hz = m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val gain = m.groupValues[3].toDoubleOrNull() ?: 0.0
                // Shelves without a Q are the usual gentle ones (0.707); a peak always has one.
                val q = m.groupValues[4].toDoubleOrNull()?.takeIf { it > 0 } ?: 0.707
                EqFilter(type, hz, gain, q)
            }.toList()
            if (filters.isEmpty()) return null
            return EqProfile(name, preamp, filters, source)
        }
    }
}

/** A biquad's coefficients (normalized by a0), RBJ Audio EQ Cookbook. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    /** Gain in dB at [hz] for a sample rate [rate]: for tests and drawing the curve. */
    fun responseDb(hz: Double, rate: Int): Double {
        val w = 2 * PI * hz / rate
        val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2; val ni = -(b1 * sw + b2 * s2)
        val dr = 1 + a1 * cw + a2 * c2; val di = -(a1 * sw + a2 * s2)
        return 20 * log10(hypot(nr, ni) / hypot(dr, di))
    }

    companion object {
        /** [f] at a sample rate [rate], its gain scaled by [strength] (0..1). Null when it can't be applied there. */
        fun of(f: EqFilter, rate: Int, strength: Double = 1.0): Biquad? {
            // A band at or past the top of what this sample rate can carry can't be drawn there.
            if (f.hz >= rate * 0.49) return null
            val gain = f.gainDb * strength
            val a = 10.0.pow(gain / 40)
            val w0 = 2 * PI * f.hz / rate
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * f.q)
            val (b0, b1, b2, a0, a1, a2) = when (f.type) {
                EqFilter.Type.PEAK -> listOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
                EqFilter.Type.LOW_SHELF -> {
                    val s = 2 * sqrt(a) * alpha
                    listOf(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s),
                        (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s)
                }
                EqFilter.Type.HIGH_SHELF -> {
                    val s = 2 * sqrt(a) * alpha
                    listOf(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s),
                        (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s)
                }
                // Pass filters aren't scaled by strength (half a cut-off doesn't mean anything); at 0 they're left out.
                EqFilter.Type.LOW_PASS -> if (strength <= 0) return null else
                    listOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
                EqFilter.Type.HIGH_PASS -> if (strength <= 0) return null else
                    listOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        private operator fun <T> List<T>.component6() = this[5]
    }
}

/** A whole profile's response in dB at [hz] (preamp included): the curve the EQ screen draws. */
fun EqProfile.responseDb(hz: Double, rate: Int = 48_000, strength: Double = 1.0): Double =
    preampDb * strength + filters.mapNotNull { Biquad.of(it, rate, strength) }.sumOf { it.responseDb(hz, rate) }
