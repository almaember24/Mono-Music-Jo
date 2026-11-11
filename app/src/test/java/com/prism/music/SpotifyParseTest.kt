package com.prism.music

import com.prism.music.SpotifyFixtures.antiHero
import com.prism.music.SpotifyFixtures.blinding
import com.prism.music.SpotifyFixtures.creep
import com.prism.music.data.spotify.SpotifyParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Every Web API answer the Spotify connection reads (1.8.1), in the reference's own shapes. */
class SpotifyParseTest {
    @Test fun theProfile() {
        val p = SpotifyParse.profile(SpotifyFixtures.me)
        assertEquals("samr", p.id)
        assertEquals("Sam Rivera", p.name)
        // No display name: the id stands in.
        assertEquals("u2", SpotifyParse.profile("""{"id":"u2","display_name":null}""").name)
    }

    @Test fun aTrackKeepsWhatMatchingNeeds() {
        val t = SpotifyParse.track(kotlinx.serialization.json.Json.parseToJsonElement(antiHero))!!
        assertEquals("spotify:track:0V3wPSX9ygBnCm8psDIegu", t.uri)
        assertEquals("Anti-Hero", t.title)
        assertEquals(listOf("Taylor Swift"), t.artists)
        assertEquals("Midnights", t.album)
        assertEquals(200_690L, t.durationMs)
        assertEquals("USUG12205736", t.isrc)
        assertTrue(t.explicit)
        // The mid-sized cover.
        assertEquals("https://i.scdn.co/image/300-0V3wPSX9ygBnCm8psDIegu", t.image)
        // Without an ISRC (external_ids is empty).
        assertNull(SpotifyParse.track(kotlinx.serialization.json.Json.parseToJsonElement(SpotifyFixtures.track("x", "No Code", "A", "B", 1000, null)))!!.isrc)
    }

    @Test fun recentlyPlayed() {
        val plays = SpotifyParse.recentlyPlayed(SpotifyFixtures.recentlyPlayed(blinding to "2026-10-30T20:44:04.589Z", creep to "2026-10-30T21:00:00Z"))
        assertEquals(2, plays.size)
        assertEquals("Blinding Lights", plays[0].track.title)
        assertEquals(Instant.parse("2026-10-30T20:44:04.589Z").toEpochMilli(), plays[0].playedAt)
        assertEquals("GBAYE9200070", plays[1].track.isrc)
    }

    @Test fun topItems() {
        val tracks = SpotifyParse.topTracks(SpotifyFixtures.page(listOf(blinding, creep)))
        assertEquals(listOf("Blinding Lights", "Creep"), tracks.map { it.title })
        val artists = SpotifyParse.topArtists(SpotifyFixtures.topArtists)
        assertEquals(listOf("The Weeknd", "Radiohead"), artists.map { it.name })
        assertEquals("https://i.scdn.co/image/w320", artists[0].image)
        assertNull(artists[1].image)
    }

    @Test fun playlistsWithTheirCountUnderItems() {
        val page = SpotifyParse.playlists(SpotifyFixtures.page(
            listOf(SpotifyFixtures.playlist("p1", "Road trip", "samr", 12), SpotifyFixtures.playlist("p2", "Today's Top Hits", "spotify", 50)),
            next = "https://api.spotify.com/v1/me/playlists?offset=50&limit=50", total = 70,
        ))
        assertEquals(2, page.items.size)
        assertEquals("https://api.spotify.com/v1/me/playlists?offset=50&limit=50", page.next)
        assertEquals(70, page.total)
        val p = page.items[0]
        assertEquals("p1", p.id); assertEquals("Road trip", p.name); assertEquals("samr", p.ownerId)
        assertEquals("s1", p.snapshot); assertEquals(12, p.total)
        assertEquals("spotify", page.items[1].ownerId)
        // An answer from before February 2026 (only "tracks") still gives the count.
        val old = SpotifyParse.playlists("""{"items":[{"id":"p3","name":"Old","owner":{"id":"samr"},"snapshot_id":"z","tracks":{"href":"","total":7}}],"next":null}""")
        assertEquals(7, old.items[0].total)
    }

    @Test fun playlistItemsLeaveOutEpisodesAndLocalFiles() {
        val page = SpotifyParse.playlistItems(SpotifyFixtures.page(listOf(
            SpotifyFixtures.playlistItem(blinding), SpotifyFixtures.episodeItem, SpotifyFixtures.localItem,
            // An older answer's "track".
            SpotifyFixtures.playlistItem(creep, newShape = false),
            """{"added_at":null,"is_local":false,"item":null}""",
        )))
        assertEquals(5, page.items.size)
        assertEquals(listOf("Blinding Lights", null, null, "Creep", null), page.items.map { it?.title })
    }

    @Test fun likedSongsWithWhenTheyWereLiked() {
        val page = SpotifyParse.savedTracks(SpotifyFixtures.page(listOf(SpotifyFixtures.saved(blinding, "2025-06-01T08:00:00Z"))))
        assertEquals(1, page.items.size)
        assertEquals(Instant.parse("2025-06-01T08:00:00Z").toEpochMilli(), page.items[0].addedAt)
        assertEquals("USUG11904206", page.items[0].track.isrc)
    }

    @Test fun searchAndErrors() {
        assertEquals(listOf("Creep"), SpotifyParse.searchTracks(SpotifyFixtures.search(creep)).map { it.title })
        assertEquals(emptyList<Any>(), SpotifyParse.searchTracks("""{"tracks":{"items":[]}}"""))
        assertEquals("Insufficient client scope", SpotifyParse.errorMessage("""{"error":{"status":403,"message":"Insufficient client scope"}}"""))
        assertTrue(SpotifyParse.quotaExceeded("""{"error":{"status":429,"message":"Too many requests","reason":"QUOTA_EXCEEDED"}}"""))
        assertEquals("s9" to "Renamed", SpotifyParse.playlistSnapshot("""{"snapshot_id":"s9","name":"Renamed"}"""))
    }
}
