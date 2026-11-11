package com.prism.music

import com.prism.music.playback.DjFlow
import com.prism.music.playback.DjSequencer
import com.prism.music.playback.DjTrack
import com.prism.music.playback.MusicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjSequencerTest {
    private fun t(id: String, tonic: Int, minor: Boolean = false, bpm: Double = 120.0, loud: Float = -9f, artist: String = id) =
        DjTrack(id, MusicKey(tonic, minor, 0.2f), bpm, loud, artist)

    @Test
    fun walksTheCamelotWheel() {
        // From C major (8B): G (9B), D (10B), A (11B) in a muddle, plus a tritone away (F♯, 2B). The set should step
        // round the wheel one hour at a time, the clash last.
        val start = t("c", 0)
        val set = DjSequencer.order(start, listOf(t("fs", 6), t("a", 9), t("g", 7), t("d", 2)), DjFlow.STEADY)
        assertEquals(listOf("g", "d", "a", "fs"), set.map { it.id })
    }

    @Test
    fun relativeMinorCountsAsNear() {
        val start = t("c", 0)
        val set = DjSequencer.order(start, listOf(t("eb", 3), t("am", 9, minor = true)), DjFlow.STEADY)
        assertEquals("am", set.first().id)
    }

    @Test
    fun tempoIsOctaveFree() {
        assertEquals(0.0, DjSequencer.tempoGap(85.0, 170.0)!!, 1e-9)
        assertTrue(DjSequencer.tempoGap(120.0, 128.0)!! < DjSequencer.tempoGap(120.0, 140.0)!!)
        // Same key throughout: the closer tempo goes first.
        val start = t("s", 0, bpm = 124.0)
        val set = DjSequencer.order(start, listOf(t("far", 0, bpm = 150.0), t("near", 0, bpm = 126.0), t("double", 0, bpm = 62.5)), DjFlow.STEADY)
        assertEquals("far", set.last().id)
    }

    @Test
    fun buildUpRisesAndWindDownFalls() {
        val tracks = (0 until 8).map { t("e$it", 0, loud = -16f + it * 1.5f) }.shuffled(java.util.Random(4))
        val start = t("start", 0, loud = -12f)
        val up = DjSequencer.order(start, tracks, DjFlow.BUILD).map { it.loudness!! }
        val down = DjSequencer.order(start, tracks, DjFlow.WIND_DOWN).map { it.loudness!! }
        assertEquals(up.sorted(), up)
        assertEquals(down.sortedDescending(), down)
    }

    @Test
    fun sameArtistIsntPlayedTwiceRunning() {
        val start = t("s", 0, artist = "X")
        val tracks = listOf(t("x1", 0, artist = "X"), t("x2", 0, artist = "X"), t("y1", 0, artist = "Y"), t("z1", 0, artist = "Z"))
        val set = DjSequencer.order(start, tracks, DjFlow.STEADY)
        val artists = listOf("X") + set.map { it.artist }
        for (i in 1 until artists.size) assertTrue("$artists", artists[i] != artists[i - 1])
    }

    @Test
    fun unclearKeysAreNeutral() {
        val unsure = DjTrack("u", MusicKey(6, false, 0.01f), 120.0, -9f, "u")
        val start = t("c", 0)
        // An unclear key is neither rewarded nor punished: it costs less than a clash, more than a neighbour.
        val clash = DjSequencer.step(start, t("fs", 6), 0.5, 0.5, 1, 1, DjFlow.STEADY)
        val neutral = DjSequencer.step(start, unsure, 0.5, 0.5, 1, 1, DjFlow.STEADY)
        val near = DjSequencer.step(start, t("g", 7), 0.5, 0.5, 1, 1, DjFlow.STEADY)
        assertTrue(near < neutral && neutral < clash)
    }

    /** A real queue from the phone (autoplay after "Blinding Lights"): Camelot code / BPM, "?" where it wasn't clear. */
    @Test
    fun realQueueAvoidsClashes() {
        val raw = "?/85 8A/84 12A/79 1A/75 1B/112 ?/110 8B/106 7B/140 7B/123 9B/120 8B/98 ?/99 1A/100 2A/101 ?/104 11B/104 11B/89 ?/95 5B/89 4B/126 2B/122 1B/125"
        val tracks = raw.split(' ').mapIndexed { i, s ->
            val (code, bpm) = s.split('/')
            val key = if (code == "?") null else (0 until 24).map { MusicKey(it % 12, it >= 12, 0.2f) }.first { it.camelot == code }
            DjTrack("t$i", key, bpm.toDouble(), null, "a$i")
        }
        fun clashes(seq: List<DjTrack>) = seq.zipWithNext().count { (a, b) -> a.key != null && b.key != null && MusicKey.clash(a.key, b.key) >= 1.0 }
        val set = DjSequencer.order(tracks[0], tracks.drop(1), DjFlow.STEADY)
        val whole = listOf(tracks[0]) + set
        println("set: " + whole.joinToString(" → ") { (it.key?.camelot ?: "?") + "/" + it.bpm!!.toInt() })
        println("clashes: as given ${clashes(tracks)}, as a set ${clashes(whole)}")
        assertTrue(clashes(whole) <= 1)
    }

    @Test
    fun aHundredSongsAreQuick() {
        val rnd = java.util.Random(1)
        val tracks = (0 until 100).map { t("s$it", rnd.nextInt(12), rnd.nextBoolean(), 90.0 + rnd.nextInt(60), -14f + rnd.nextFloat() * 8, "a${rnd.nextInt(30)}") }
        val t0 = System.nanoTime()
        val set = DjSequencer.order(tracks[0], tracks.drop(1), DjFlow.STEADY)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("100 songs ordered in $ms ms")
        assertEquals(99, set.toSet().size)
        assertTrue("took $ms ms", ms < 3000)
        // Better than the order they came in.
        val e = DjSequencer.energies(listOf(tracks[0]) + tracks.drop(1))
        val given = DjSequencer.total(tracks[0], tracks.drop(1), (0 until 99).toList(), e, DjFlow.STEADY)
        val ordered = DjSequencer.total(tracks[0], tracks.drop(1), set.map { s -> tracks.drop(1).indexOf(s) }, e, DjFlow.STEADY)
        println("cost: as given %.1f, as a set %.1f".format(given, ordered))
        assertTrue(ordered < given * 0.6)
    }
}
