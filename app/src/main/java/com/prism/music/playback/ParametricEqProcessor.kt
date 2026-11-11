package com.prism.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Headphone correction: a profile's filters (AutoEq's ParametricEQ, [EqProfile]) applied to the music, in double
 * precision, to every channel. A new profile (or strength, or headphones) crossfades in over 20 ms, so a change never
 * clicks; with none, the audio passes straight through untouched.
 */
@UnstableApi
class ParametricEqProcessor : BaseAudioProcessor() {
    private class Setting(val profile: EqProfile?, val strength: Double)

    @Volatile private var wanted = Setting(null, 1.0)
    private var applied: Setting? = null
    private var rate = 48_000
    private var channels = 2
    private var float = false

    /** The filters in use, and the ones being faded out (after a change). */
    private var chain: Chain? = null
    private var fading: Chain? = null
    private var fadeLeft = 0
    private var fadeLength = 1

    /** The profile now applied (for stats for nerds), or null. */
    val active: EqProfile? get() = wanted.profile

    /** Sets the correction ([profile] null = none) at [strength] 0..1. Any thread. */
    fun configure(profile: EqProfile?, strength: Float) {
        wanted = Setting(profile, strength.coerceIn(0f, 1f).toDouble())
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        float = enc == C.ENCODING_PCM_FLOAT
        fadeLength = (rate * 0.02).roundToInt().coerceAtLeast(1)
        applied = null
        chain = null
        fading = null
        return inputAudioFormat
    }

    /** One profile's biquads for every channel, with their running state (transposed direct form II). */
    private class Chain(setting: Setting, rate: Int, private val channels: Int) {
        private val gain = 10.0.pow((setting.profile?.preampDb ?: 0.0) * setting.strength / 20)
        private val filters = setting.profile?.filters.orEmpty().mapNotNull { Biquad.of(it, rate, setting.strength) }.toTypedArray()
        private val z1 = Array(channels) { DoubleArray(filters.size) }
        private val z2 = Array(channels) { DoubleArray(filters.size) }
        val empty = filters.isEmpty() && gain == 1.0

        fun process(x: Double, ch: Int): Double {
            var y = x * gain
            val s1 = z1[ch]; val s2 = z2[ch]
            for (k in filters.indices) {
                val f = filters[k]
                val out = f.b0 * y + s1[k]
                s1[k] = f.b1 * y - f.a1 * out + s2[k]
                s2[k] = f.b2 * y - f.a2 * out
                y = out
            }
            return y
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val want = wanted
        if (want !== applied) {
            // Fade from what's playing to the new filters (none → filters, filters → none, or one to another).
            val next = Chain(want, rate, channels)
            fading = chain ?: Chain(Setting(null, 1.0), rate, channels)
            chain = next
            fadeLeft = fadeLength
            applied = want
        }
        val current = chain
        if (fadeLeft <= 0 && (current == null || current.empty)) {
            replaceOutputBuffer(bytes).put(inputBuffer).flip()
            return
        }
        val t0 = if (AudioDiagnostics.enabled) System.nanoTime() else 0L
        val out = replaceOutputBuffer(bytes)
        val frameBytes = (if (float) 4 else 2) * channels
        val frames = bytes / frameBytes
        for (f in 0 until frames) {
            val fade = if (fadeLeft > 0) fadeLeft.toDouble() / fadeLength else 0.0
            for (ch in 0 until channels) {
                val x = if (float) inputBuffer.float.toDouble() else inputBuffer.short / 32768.0
                var y = current!!.process(x, ch)
                val old = fading
                if (fade > 0 && old != null) y = y * (1 - fade) + old.process(x, ch) * fade
                if (float) out.putFloat(y.toFloat())
                else out.putShort((y * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            if (fadeLeft > 0 && --fadeLeft == 0) fading = null
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
        if (t0 != 0L) AudioDiagnostics.processed("headphone EQ", t0, frames, rate)
    }

    override fun onFlush() {
        // A seek: the filters' memory of the old position goes, the profile stays.
        applied = null
        chain = null
        fading = null
        fadeLeft = 0
    }

    override fun onReset() {
        onFlush()
    }
}
