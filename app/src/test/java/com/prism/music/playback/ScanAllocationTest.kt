package com.prism.music.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.math.sin

/**
 * What a song scan's steps allocate. #373's log: a collection every half second with 24-42 MB of large arrays each while
 * a song scan and a video sync ran. Bytes, not milliseconds, so it holds on any machine. Prints the figures.
 */
class ScanAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun <T> bytes(name: String, block: () -> T): Long {
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        block()
        val used = threads.getThreadAllocatedBytes(id) - before
        println("%-28s %8.2f MB".format(name, used / 1_048_576.0))
        return used
    }

    /** Four minutes of something like music at the scan's rate. */
    private fun song(rate: Int = RATE, seconds: Int = 240) = FloatArray(rate * seconds) { i ->
        val t = i.toDouble() / rate
        val beat = (t * 2) % 1.0
        (0.4 * sin(2 * PI * 110 * t) * (1 - beat) + 0.2 * sin(2 * PI * 440 * t) + 0.1 * sin(2 * PI * 1760 * t) * (if (beat < 0.1) 1 else 0)).toFloat()
    }

    @Test fun aSongScanAllocatesLittleBesideTheAudioItself() {
        val pcm = song()
        val pcmMb = pcm.size * 4L
        val loud = bytes("integratedLoudness") { MixTempo.integratedLoudness(pcm, RATE) }
        val bands = VideoSync.bandFeatures(pcm, RATE)
        val features = bytes("bandFeatures") { VideoSync.bandFeatures(pcm, RATE) }
        val tempo = bytes("Tempo.analyze") { Tempo.analyze(bands, 10.0) }
        val key = bytes("KeyDetector.detect") { KeyDetector.detect(pcm, RATE) }
        val wave = bytes("SongWaves.outline") { com.prism.music.data.SongWaves.outline(pcm) }
        println("%-28s %8.2f MB".format("the audio itself", pcmMb / 1_048_576.0))
        assertTrue("loudness needs next to nothing beside the audio: $loud bytes", loud < 2 * 1_048_576)
        assertTrue("band features: $features bytes", features < 3 * 1_048_576)
        assertTrue("tempo: $tempo bytes", tempo < 8 * 1_048_576)
        assertTrue("key: $key bytes", key < 12 * 1_048_576)
        assertTrue("outline: $wave bytes", wave < 1 * 1_048_576)
    }

    @Test fun aVideoSyncAllocatesLittleBesideTheAudioItself() {
        val pcm = song(seconds = 190)
        val song = VideoSync.bandFeatures(pcm.copyOf(RATE * 70), RATE)
        val video = VideoSync.bandFeatures(pcm, RATE)
        VideoSync.log = {}
        val align = bytes("VideoSync.align") { VideoSync.align(song, video, 10.0) }
        assertTrue("the match: $align bytes", align < 8 * 1_048_576)
    }

    private companion object { const val RATE = 12_000 }
}
