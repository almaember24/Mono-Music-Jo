package com.prism.music

import com.prism.music.playback.Tempo
import com.prism.music.playback.VideoSync
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Mix's tempo reading on real songs. Skipped unless decoded songs are in app/build/mix-pcm/: `mix_<id>_<rate>.f32`
 * files from a debug build (adb shell touch /sdcard/Android/data/com.prism.music/files/mix_dump, play the songs, pull
 * the files) plus `truth.csv` (`id,query,bpm`). Prints each song's reading against its known tempo.
 */
class MixTempoRealTest {
    private val dir = File("build/mix-pcm")

    @Test
    fun realSongs() {
        val truth = File(dir, "truth.csv").takeIf { it.exists() }?.readLines()?.drop(1)?.mapNotNull { l ->
            val p = l.split(','); if (p.size >= 3) p[0] to (p[1] to p[2].toDouble()) else null
        }?.toMap()
        assumeTrue("no decoded songs in ${dir.absolutePath}", truth != null && dir.listFiles { f -> f.name.endsWith(".f32") }?.isNotEmpty() == true)
        var right = 0; var steadyWrong = 0; var total = 0
        for (f in dir.listFiles { f -> f.name.endsWith(".f32") }!!.sortedBy { it.name }) {
            val m = Regex("mix_(.+)_(\\d+)\\.f32").matchEntire(f.name) ?: continue
            val id = m.groupValues[1]; val sr = m.groupValues[2].toInt()
            val (query, bpm) = truth!![id] ?: continue
            val bytes = f.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val pcm = FloatArray(bytes.size / 4) { bb.float }
            val t = Tempo.analyze(VideoSync.bandFeatures(pcm, sr), (sr / 100) * 1000.0 / sr, VideoSync.WINDOW / 2 * 1000.0 / sr)
            val got = t?.bpm ?: 0.0
            val octaves = if (got > 0) ln(got / bpm) / ln(2.0) else 9.0
            val ok = abs(octaves - Math.round(octaves)) < 0.02
            total++
            if (ok) right++ else if (t?.steady == true) steadyWrong++
            println("%-32s true %5.1f  read %6.1f  conf %4.1f  %s".format(query.take(32), bpm, got, t?.confidence ?: 0.0, if (ok) "ok" else if (t?.steady == true) "WRONG (steady!)" else "wrong (not steady)"))
        }
        println("right $right / $total, wrong but called steady: $steadyWrong")
    }
}
