package com.prism.music

import com.prism.music.playback.KeyDetector
import com.prism.music.playback.MixTempo
import com.prism.music.playback.Tempo
import com.prism.music.playback.VideoSync
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Where a song scan's time goes (the analysis after decoding), on a 4-minute song at the scan's ~11 kHz. */
class ScanCostTest {
    @Test
    fun timeEachStep() {
        val rate = 11025
        val n = rate * 240
        val rnd = java.util.Random(1)
        // A 120 BPM kick plus an A-minor chord and a little noise.
        val pcm = FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val beat = (t % 0.5)
            val kick = if (beat < 0.05) sin(2 * PI * 60 * t) * (1 - beat / 0.05) else 0.0
            (0.3 * kick + 0.1 * (sin(2 * PI * 220 * t) + sin(2 * PI * 261.6 * t) + sin(2 * PI * 329.6 * t)) + 0.02 * rnd.nextGaussian()).toFloat()
        }
        repeat(2) { round ->
            fun <T> time(name: String, f: () -> T): T { val s = System.nanoTime(); val r = f(); println("[cost] round $round $name: ${(System.nanoTime() - s) / 1_000_000} ms"); return r }
            time("loudness") { MixTempo.integratedLoudness(pcm, rate) }
            val bands = time("bandFeatures") { VideoSync.bandFeatures(pcm, rate) }
            time("tempo") { Tempo.analyze(bands, (rate / 100) * 1000.0 / rate) }
            val key = time("key") { KeyDetector.detect(pcm, rate) }
            println("[cost] key ${key?.name}")
        }
    }
}
