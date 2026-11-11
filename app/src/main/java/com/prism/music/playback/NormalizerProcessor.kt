package com.prism.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Volume normalization inside Prism's own audio chain, so it works on every
 * output (Bluetooth included) and offline. Each song gets a gain from its
 * loudness; quiet songs are lifted as well as loud ones lowered, and a peak
 * limiter keeps the lifted ones from clipping.
 */
@UnstableApi
class NormalizerProcessor : BaseAudioProcessor() {
    @Volatile private var targetGain = 1f
    /** How long a change takes (s): quick before a song's sound starts, slow once it's playing. */
    @Volatile private var glideSec = 0.1f
    private var gain = 1f
    /** [appVolume] as it glides (~30 ms), apart from the song's gain, which can take seconds. */
    private var vol = 1f
    private var env = 0f
    private var rate = 48_000
    private var float = false
    private var attack = 0f
    private var release = 0f

    /**
     * Sets the song's gain in dB; it glides there over ~100 ms, or over [glideSec] seconds. A song already playing changes
     * slowly (Reddit 1.7: "a hiccup near the start, like it switches to another version": its measured loudness landing a
     * few seconds in moved the volume by several dB in a tenth of a second).
     */
    fun setGainDb(db: Float, glideSec: Float = 0.1f) {
        this.glideSec = glideSec.coerceAtLeast(0.01f)
        targetGain = 10f.pow(db / 20f)
    }

    companion object {
        /**
         * Prism's own volume (GitHub #116: "Prism's own volume" under the player), on top of each song's gain in every
         * player (the crossfade's ending song too), so the phone's media volume isn't touched. 1 is full, as before.
         */
        @Volatile var appVolume = 1f

        /** The sleep timer's fade-out (1.7.8, A5), on top of [appVolume]: 1 except while it fades. */
        @Volatile var fadeVolume = 1f

        /** A slider's place (0 … 1) as a volume: squared, so the slider's middle sounds like half as loud. */
        fun volumeFor(slider: Float): Float = slider.coerceIn(0f, 1f).let { it * it }

        /**
         * The gain (dB) that brings a song measured at [lufs] to the [target] level; [peakDb]: its loudest sample (dBFS).
         * #210 (1.8.0): a quiet song is lifted as far as its peaks have room (up to the limiter's -1 dB ceiling) plus the
         * [LIMITED_DB] the limiter may take off them, so a quiet recording ends up as loud as a loud master and a soft
         * passage with loud hits isn't flattened; without a peak figure, [LIMITED_DB] at most, as before 1.8.0 (when every
         * song was lifted 6 dB at most and lowered 12 at most, which left big gaps).
         */
        fun gainFor(target: Float, lufs: Float, peakDb: Float? = null): Float {
            val want = target - lufs
            if (want <= 0f) return want.coerceAtLeast(-MAX_CUT_DB)
            val room = peakDb?.let { CEILING_DB - it + LIMITED_DB } ?: LIMITED_DB
            return minOf(want, room, MAX_LIFT_DB).coerceAtLeast(0f)
        }

        /** The limiter's ceiling (dBFS): 0.89, as [queueInput] has it. */
        private const val CEILING_DB = -1f
        /** How much the limiter may take off a lifted song's peaks. */
        private const val LIMITED_DB = 6f
        /** At most this much up (a very quiet recording's hiss stays down) or down. */
        private const val MAX_LIFT_DB = 20f
        private const val MAX_CUT_DB = 24f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate
        float = enc == C.ENCODING_PCM_FLOAT
        attack = 1f - exp(-1f / (0.0008f * rate))
        release = 1f - exp(-1f / (0.15f * rate))
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val target = targetGain
        val volTarget = appVolume * fadeVolume
        if (target == 1f && volTarget == 1f && abs(gain - 1f) < 1e-4f && abs(vol - 1f) < 1e-4f && env < 0.89f) {
            gain = 1f
            vol = 1f
            out.put(inputBuffer)
            out.flip()
            return
        }
        val t0 = if (AudioDiagnostics.enabled) System.nanoTime() else 0L
        // Per stereo frame (the gain moves every other sample).
        val glide = 1f - exp(-1f / (glideSec * rate))
        val volGlide = 1f - exp(-1f / (0.03f * rate))
        val ceiling = 0.89f // -1 dBFS
        val samples = bytes / (if (float) 4 else 2)
        for (s in 0 until samples) {
            if (s % 2 == 0) {
                gain += (target - gain) * glide
                vol += (volTarget - vol) * volGlide
            }
            val x = (if (float) inputBuffer.float else inputBuffer.short / 32768f) * gain * vol
            // Peak limiter: fast attack, slow release, never above the ceiling.
            val a = abs(x)
            // Instant attack: the gain is cut on the very sample that would go over.
            env = if (a > env) a else env + (a - env) * release
            var y = if (env > ceiling) x * (ceiling / env) else x
            if (y > 0.99f) y = 0.99f else if (y < -0.99f) y = -0.99f
            if (float) out.putFloat(y) else out.putShort((y * 32767f).roundToInt().toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
        if (t0 != 0L) AudioDiagnostics.processed("normalizer", t0, samples / inputAudioFormat.channelCount.coerceAtLeast(1), rate)
    }

    override fun onReset() {
        env = 0f
    }
}
