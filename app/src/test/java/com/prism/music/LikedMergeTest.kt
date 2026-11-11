package com.prism.music

import com.prism.music.data.LikedMerge
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.NormalizerProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub #111 (a liked song and its liked video show once) and #116 (Prism's own volume). */
class LikedMergeTest {
    private fun song(id: String, title: String, vararg artists: String) = Song(id, title, artists.map { ArtistRef(it) })
    private fun video(id: String, title: String, vararg artists: String) = Song(id, title, artists.map { ArtistRef(it) }, isVideo = true)

    @Test
    fun theVideoOfALikedSongGoes() {
        val liked = listOf(
            video("v1", "Feel Good Inc. (Official Video)", "Gorillaz"),
            song("s1", "Feel Good Inc. (feat. De La Soul)", "Gorillaz", "De La Soul"),
            video("v2", "Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers", "DaftPunkVEVO"),
            song("s2", "Get Lucky (feat. Pharrell Williams & Nile Rodgers)", "Daft Punk"),
        )
        assertEquals(listOf("s1", "s2"), LikedMerge.merge(liked).map { it.id })
    }

    @Test
    fun otherRecordingsAndOtherArtistsStayRemastersGo() {
        val liked = listOf(
            song("s1", "Creep", "Radiohead"),
            video("v1", "Creep (Live at Glastonbury)", "Radiohead"),
            video("v2", "Creep (Official Video)", "Stone Temple Pilots"),
            song("s2", "Yesterday - Remastered 2009", "The Beatles"),
            video("v3", "Yesterday (Official Video)", "The Beatles"),
        )
        val kept = LikedMerge.merge(liked).map { it.id }
        // A remaster is still the same recording, so its video goes.
        assertEquals(listOf("s1", "v1", "v2", "s2"), kept)
    }

    @Test
    fun aKnownPairGoesWhateverItsTitle() {
        val liked = listOf(song("s1", "Alright", "Kendrick Lamar"), video("v1", "Kendrick Lamar - Alright (short film)", "Kendrick Lamar"))
        assertEquals(listOf("s1"), LikedMerge.merge(liked, mapOf("s1" to "v1")).map { it.id })
        assertEquals(2, LikedMerge.merge(liked).size)
    }

    @Test
    fun noVideosNoChange() {
        val liked = listOf(song("s1", "One", "U2"), song("s2", "One", "Metallica"))
        assertTrue(LikedMerge.merge(liked) === liked)
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun ownVolumeLowersTheSamples() {
        val p = NormalizerProcessor()
        p.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(48_000, 2, androidx.media3.common.C.ENCODING_PCM_16BIT))
        p.flush()
        fun run(): Float {
            // 0.5 s of a steady 0.25 full-scale signal; the level at the end.
            val n = 48_000
            val buf = java.nio.ByteBuffer.allocateDirect(n * 2).order(java.nio.ByteOrder.nativeOrder())
            repeat(n) { buf.putShort((0.25f * 32767).toInt().toShort()) }
            buf.flip()
            p.queueInput(buf)
            val out = p.output
            var last = 0
            while (out.hasRemaining()) last = out.short.toInt()
            return last / 32767f
        }
        try {
            NormalizerProcessor.appVolume = 1f
            assertEquals(0.25f, run(), 0.002f)
            NormalizerProcessor.appVolume = 0.5f
            assertEquals(0.125f, run(), 0.002f)
        } finally {
            NormalizerProcessor.appVolume = 1f
        }
    }

    @Test
    fun ownVolumeIsSquaredAndClamped() {
        assertEquals(1f, NormalizerProcessor.volumeFor(1f), 0f)
        assertEquals(0.25f, NormalizerProcessor.volumeFor(0.5f), 1e-6f)
        assertEquals(0f, NormalizerProcessor.volumeFor(-1f), 0f)
        assertEquals(1f, NormalizerProcessor.volumeFor(3f), 0f)
    }
}
