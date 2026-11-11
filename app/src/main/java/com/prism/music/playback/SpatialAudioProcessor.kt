package com.prism.music.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Prism Spatial: a software spatializer for stereo music, so it works on any
 * phone and any headphones (no Dolby or Spatializer hardware needed).
 *
 *  - Mid/side widening opens the stereo stage (bass stays centred, so it keeps its punch).
 *  - Head-shadow crossfeed (each ear hears the other channel ~0.3 ms later and
 *    low-passed, as from real speakers) pulls the sound out of your head.
 *  - Early reflections and a diffuse, decorrelated room tail place it in a space.
 *  - Loudness is matched to the dry signal, so switching it on never just sounds quieter.
 *
 * Changes glide in over ~50 ms, so toggling never clicks.
 */
@UnstableApi
class SpatialAudioProcessor : BaseAudioProcessor() {
    @Volatile private var target = 0f
    private var wet = 0f
    private var rate = 48_000
    private var float = false

    // Bass/treble split of the side signal (one-pole low-pass at ~180 Hz)
    private var sideLp = 0f
    private var bassA = 0f

    // Crossfeed
    private var xfDelay = FloatArray(0)
    private var xfPos = 0
    private var xfLen = 1
    private var lpL = 0f
    private var lpR = 0f
    private var lpA = 0f

    // Early reflections (taps into one shared history)
    private var hist = FloatArray(0)
    private var histPos = 0
    private val tapMs = floatArrayOf(7.1f, 11.3f, 17.9f, 23.4f, 31.7f, 41.2f, 53.9f)
    private val tapGain = floatArrayOf(0.42f, 0.36f, 0.30f, 0.25f, 0.20f, 0.16f, 0.12f)
    private var taps = IntArray(0)
    private var refLp = 0f
    private var refA = 0f

    // Room tail: four damped feedback combs per ear (slightly different lengths, so the ears
    // hear different, decorrelated reverb) followed by two all-pass diffusers.
    private val combMs = floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f)
    private val spreadMs = 0.52f
    private var combs = Array(0) { FloatArray(0) }
    private var combPos = IntArray(0)
    private var combDamp = FloatArray(0)
    private val apMs = floatArrayOf(5.0f, 1.7f)
    private var aps = Array(0) { FloatArray(0) }
    private var apPos = IntArray(0)

    /** Diagnostics: frames processed with the effect engaged, and when that was last logged. */
    @Volatile var framesProcessed = 0L
        private set
    private var lastLog = 0L

    val engaged: Boolean get() = wet > 0.01f || target > 0f

    fun configure(enabled: Boolean, amount: Float) {
        target = if (enabled) amount.coerceIn(0f, 1f) else 0f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (inputAudioFormat.channelCount != 2 || (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT)) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        rate = inputAudioFormat.sampleRate
        float = enc == C.ENCODING_PCM_FLOAT
        xfLen = (rate * 0.00027f).roundToInt().coerceAtLeast(1)
        lpA = onePole(rate, 700f)
        refA = onePole(rate, 4500f)
        bassA = onePole(rate, 180f)
        taps = IntArray(tapMs.size) { (tapMs[it] / 1000f * rate).roundToInt() }
        combs = Array(combMs.size * 2) { i ->
            val ms = combMs[i % combMs.size] + if (i >= combMs.size) spreadMs else 0f
            FloatArray((ms / 1000f * rate).roundToInt().coerceAtLeast(1))
        }
        aps = Array(apMs.size * 2) { i ->
            val ms = apMs[i % apMs.size] * if (i >= apMs.size) 1.13f else 1f
            FloatArray((ms / 1000f * rate).roundToInt().coerceAtLeast(1))
        }
        clearState()
        return inputAudioFormat
    }

    private fun onePole(sr: Int, hz: Float) = exp(-2.0 * Math.PI * hz / sr).toFloat()

    private fun clearState() {
        xfDelay = FloatArray(xfLen * 2)
        xfPos = 0
        hist = FloatArray((taps.maxOrNull() ?: 0) + 1)
        histPos = 0
        combs.forEach { it.fill(0f) }
        combPos = IntArray(combs.size)
        combDamp = FloatArray(combs.size)
        aps.forEach { it.fill(0f) }
        apPos = IntArray(aps.size)
        lpL = 0f; lpR = 0f; refLp = 0f; sideLp = 0f
    }

    /** Four combs into two all-passes, for one ear ([ear] 0 = left, 1 = right). */
    private fun reverb(ear: Int, input: Float): Float {
        var sum = 0f
        val base = ear * combMs.size
        for (k in base until base + combMs.size) {
            val buf = combs[k]
            val p = combPos[k]
            val y = buf[p]
            combDamp[k] = y + (combDamp[k] - y) * 0.3f
            buf[p] = input + combDamp[k] * 0.80f
            combPos[k] = if (p + 1 == buf.size) 0 else p + 1
            sum += y
        }
        var x = sum * 0.25f
        val apBase = ear * apMs.size
        for (k in apBase until apBase + apMs.size) {
            val buf = aps[k]
            val p = apPos[k]
            val d = buf[p]
            val v = x + d * 0.5f
            buf[p] = v
            x = d - v * 0.5f
            apPos[k] = if (p + 1 == buf.size) 0 else p + 1
        }
        return x
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val frameBytes = if (float) 8 else 4
        val frames = bytes / frameBytes
        val goal = target
        // Fully off and settled: pass straight through.
        if (goal == 0f && wet < 1e-4f) {
            wet = 0f
            out.put(inputBuffer)
            out.flip()
            return
        }
        val t0 = if (AudioDiagnostics.enabled) System.nanoTime() else 0L
        framesProcessed += frames
        val now = System.currentTimeMillis()
        if (now - lastLog > 10_000) {
            lastLog = now
            runCatching { Log.i("PrismSpatial", "processing ${rate} Hz ${if (float) "float" else "16-bit"}, intensity ${(goal * 100).roundToInt()}%, ${framesProcessed / rate} s so far") }
        }
        val glide = 1f - exp(-1f / (0.05f * rate))
        for (f in 0 until frames) {
            wet += (goal - wet) * glide
            val l: Float
            val r: Float
            if (float) { l = inputBuffer.float; r = inputBuffer.float } else {
                l = inputBuffer.short / 32768f; r = inputBuffer.short / 32768f
            }
            val w = wet

            // Widening: treble side up to 2.4x, bass side left alone
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f
            sideLp = side + (sideLp - side) * bassA
            val wide = sideLp + (side - sideLp) * (1f + 1.4f * w)
            var ol = mid + wide
            var or = mid - wide

            // Crossfeed: delayed, low-passed opposite channel
            lpL = l + (lpL - l) * lpA
            lpR = r + (lpR - r) * lpA
            val i = xfPos * 2
            val dl = xfDelay[i]
            val dr = xfDelay[i + 1]
            xfDelay[i] = lpL
            xfDelay[i + 1] = lpR
            xfPos = (xfPos + 1) % xfLen
            ol += dr * 0.18f * w
            or += dl * 0.18f * w

            // Early reflections from a dulled mono feed, alternating sides
            refLp = mid + (refLp - mid) * refA
            hist[histPos] = refLp
            var el = 0f
            var er = 0f
            for (t in taps.indices) {
                var p = histPos - taps[t]
                if (p < 0) p += hist.size
                val v = hist[p] * tapGain[t]
                if (t % 2 == 0) { el += v; er -= v * 0.35f } else { er += v; el -= v * 0.35f }
            }
            histPos = (histPos + 1) % hist.size
            ol += el * 0.5f * w
            or += er * 0.5f * w

            // Diffuse room tail, different in each ear
            val feed = refLp * 0.5f + wide * 0.25f
            ol += reverb(0, feed) * 0.55f * w
            or += reverb(1, feed) * 0.55f * w

            // Keep loudness level with the dry signal, and never clip
            val g = 1f / (1f + 0.18f * w)
            ol = soft(ol * g)
            or = soft(or * g)
            if (float) { out.putFloat(ol); out.putFloat(or) } else {
                out.putShort((ol * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                out.putShort((or * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
        if (t0 != 0L) AudioDiagnostics.processed("spatial", t0, frames, rate)
    }

    private fun soft(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val over = a - 0.9f
        val y = 0.9f + over / (1f + over * 10f)
        return if (x < 0) -y else y
    }

    override fun onFlush() {
        clearState()
    }

    override fun onReset() {
        clearState()
        wet = 0f
    }
}
