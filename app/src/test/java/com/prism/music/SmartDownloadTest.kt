package com.prism.music

import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.download.PlayLikelihood
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartDownloadTest {
    private val day = 86_400_000L
    private val now = System.currentTimeMillis()

    private fun song(id: String, artist: String) = Song(id, id, listOf(ArtistRef(artist)), durationSec = 200)

    @Test
    fun ranksSongsByHowLikelyYouAreToPlayThem() {
        val songs = listOf(
            song("favourite", "Band A"),     // played a lot lately, always to the end
            song("skipped", "Band B"),       // played as often, but skipped after a few seconds
            song("oldFlame", "Band C"),      // loved months ago, not since
            song("likedNew", "Band A"),      // liked, never played, by your top artist
            song("stranger", "Band Z"),      // nothing to go on
        ).associateBy { it.id }
        val events = buildList {
            repeat(12) { add(PlayEventEntity(songId = "favourite", timestamp = now - it * day / 2, playedMs = 200_000)) }
            repeat(12) { add(PlayEventEntity(songId = "skipped", timestamp = now - it * day / 2, playedMs = 15_000)) }
            repeat(12) { add(PlayEventEntity(songId = "oldFlame", timestamp = now - (100 + it) * day, playedMs = 200_000)) }
        }
        val model = PlayLikelihood(events, songs, liked = setOf("likedNew"), now = now)
        val s = songs.mapValues { model.score(it.value) }
        println("[smart] " + s.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${"%.2f".format(it.value)}" })
        assertTrue(s["favourite"]!! > s["likedNew"]!!)
        assertTrue(s["likedNew"]!! > s["skipped"]!!)
        assertTrue(s["favourite"]!! > s["oldFlame"]!!)
        assertTrue("a song you keep skipping is unlikely", s["skipped"]!! < 0.2)
        assertTrue(s.values.all { it in 0.0..1.0 })
        // A radio pick seeded from the favourite outranks one with no connection to you.
        val rec = model.score(song("rec", "Band Q"), recommendedFrom = s["favourite"]!!, rank = 0)
        assertTrue(rec > s["stranger"]!!)
    }
}
