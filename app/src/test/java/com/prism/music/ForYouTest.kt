package com.prism.music

import com.prism.music.data.db.ReleaseEntity
import com.prism.music.data.foryou.MadeForYou
import com.prism.music.data.foryou.MadeForYou.Play
import com.prism.music.data.foryou.ReleaseRadar
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

// Robolectric: the words come from strings.xml (1.7.9).
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class ForYouTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    /** [sittings] listening sessions, each playing the given artists a few times in a row. */
    private fun listen(vararg sittings: List<String>, daysAgo: Int = 1): List<Play> {
        val out = ArrayList<Play>()
        var t = now - daysAgo * 86_400_000L
        for (s in sittings) {
            for (a in s) repeat(3) { out += Play("$a-${it}", a, t); t += 4 * minute }
            t += 5 * 60 * minute
        }
        return out
    }

    @Test
    fun artistsThatGoTogetherShareAMix() {
        val rap = listOf("Kendrick Lamar", "SZA", "J. Cole")
        val rock = listOf("Nirvana", "Foo Fighters", "Pearl Jam")
        val plays = listen(rap, rock, rap, rock, rap, rock)
        val genres = (rap.map { it to "Hip-hop" } + rock.map { it to "Rock" }).toMap()
        val clusters = MadeForYou.clusters(plays, genres, now)
        assertEquals(2, clusters.size)
        assertEquals(rap.toSet(), clusters.first { "SZA" in it.artists }.artists.toSet())
        assertEquals(rock.toSet(), clusters.first { "Nirvana" in it.artists }.artists.toSet())
    }

    @Test
    fun playedTogetherCountsWithoutGenres() {
        // No genres known: the sittings alone keep the two groups apart.
        val a = listOf("A1", "A2", "A3"); val b = listOf("B1", "B2", "B3")
        val clusters = MadeForYou.clusters(listen(a, b, a, b, a, b, a, b), emptyMap(), now)
        assertTrue(clusters.size >= 2)
        assertTrue(clusters.none { c -> c.artists.any { it.startsWith("A") } && c.artists.any { it.startsWith("B") } })
    }

    @Test
    fun aRarelyPlayedArtistDoesntGetAMix() {
        val plays = listen(listOf("Big", "Big2"), listOf("Big", "Big2"), listOf("Big", "Big2"), listOf("Big", "Big2")) +
            Play("x", "Once", now - 86_400_000L)
        val clusters = MadeForYou.clusters(plays, mapOf("Big" to "Pop", "Big2" to "Pop", "Once" to "Jazz"), now)
        assertTrue(clusters.none { "Once" in it.artists })
    }

    @Test
    fun interleaveMixesTwoNewInFiveAndSplitsArtists() {
        val known = (1..30).map { "k$it" to "artist${it % 3}" }
        val fresh = (1..20).map { "f$it" to "new$it" }
        val mix = MadeForYou.interleave(known, fresh, 50) { it.second }
        assertEquals(50, mix.size)
        assertEquals(20, mix.count { it.first.startsWith("f") })
        for (i in 1 until mix.size) assertTrue("artist twice at $i", mix[i].second != mix[i - 1].second)
        // Fewer new songs than places for them: known songs fill in.
        val few = MadeForYou.interleave(known, fresh.take(2), 20) { it.second }
        assertEquals(20, few.size)
    }

    @Test
    fun timeOfDay() {
        assertEquals(MadeForYou.TimeBucket.LATE, MadeForYou.TimeBucket.of(2))
        assertEquals(MadeForYou.TimeBucket.MORNING, MadeForYou.TimeBucket.of(7))
        assertEquals(MadeForYou.TimeBucket.NIGHT, MadeForYou.TimeBucket.of(23))
        (0..23).forEach { MadeForYou.TimeBucket.of(it) }
        assertEquals("Your Tuesday late-night hip-hop", MadeForYou.timeTitle("Tuesday", MadeForYou.TimeBucket.LATE, "Hip-hop"))
        assertEquals("Your Sunday morning mix", MadeForYou.timeTitle("Sunday", MadeForYou.TimeBucket.MORNING, null))
        assertEquals("A, B, C and more", MadeForYou.namesLine(listOf("A", "B", "C", "D")))
    }

    @Test
    fun aMixIsNamedByItsGenreOnlyWhenMostOfItIsThatGenre() {
        // The owner's "Friday morning indie": 15 rock, 10 indie, 3 hip-hop, 2 unknown → rock (15 of 28).
        val friday = List(15) { "Rock" } + List(10) { "Indie & alternative" } + List(3) { "Hip-hop" } + listOf(null, null)
        assertEquals("Rock", MadeForYou.mainGenre(friday))
        // No genre holds more than half: no genre in the name.
        assertEquals(null, MadeForYou.mainGenre(List(10) { "Rock" } + List(10) { "Indie & alternative" } + List(5) { "Pop" }))
        // Too little known to say.
        assertEquals(null, MadeForYou.mainGenre(List(4) { "Rock" } + List(10) { null }))
        assertEquals(null, MadeForYou.mainGenre(emptyList()))
    }

    @Test
    fun discographyFromAnArtistPage() {
        val page = ArtistPage(
            "UC1", "Sabrina Carpenter", null, null, null,
            listOf(
                Shelf("Top songs", items = listOf(SongItem(Song("s1", "Espresso")))),
                Shelf("Alben", items = listOf(AlbumItem("MPREb_a", "Man's Best Friend", "2025", null), AlbumItem("MPREb_b", "Short n' Sweet", "2024", null))),
                Shelf("Singles", items = listOf(AlbumItem("MPREb_c", "Bring Your Love", "Single • 2026", null), AlbumItem("MPREb_d", "Espresso EP", "EP • 2024", null))),
            ),
            null, null,
        )
        val d = ReleaseRadar.discography(page)
        assertEquals(listOf("MPREb_a", "MPREb_b", "MPREb_c", "MPREb_d"), d.map { it.id })
        assertEquals("Album" to 2025, d[0].kind to d[0].year)
        assertEquals("Single" to 2026, d[2].kind to d[2].year)
        assertEquals("EP" to 2024, d[3].kind to d[3].year)
    }

    @Test
    fun titlesMatchDeezersSpelling() {
        assertEquals(ReleaseRadar.norm("Espresso"), ReleaseRadar.norm("Espresso EP"))
        assertEquals(ReleaseRadar.norm("Man’s Best Friend"), ReleaseRadar.norm("Man's Best Friend (Explicit)"))
        assertEquals(ReleaseRadar.norm("Bring Your Love"), ReleaseRadar.norm("Bring Your Love - Single"))
        assertFalse(ReleaseRadar.norm("Short n' Sweet") == ReleaseRadar.norm("Sweet"))
    }

    @Test
    fun recentIsByDateElseFirstSeen() {
        val today = LocalDate.of(2026, 10, 18)
        fun r(date: String?, seenDaysAgo: Long) = ReleaseEntity(
            "id", "a", "A", "T", "Single", 2026, date, null, null,
            today.minusDays(seenDaysAgo).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), baseline = false,
        )
        assertTrue(ReleaseRadar.isRecent(r("2026-10-16", 100), today, 14))
        assertFalse(ReleaseRadar.isRecent(r("2026-09-01", 1), today, 14))
        assertTrue(ReleaseRadar.isRecent(r(null, 3), today, 14))
        assertFalse(ReleaseRadar.isRecent(r(null, 30), today, 14))
        // Announced ahead of its date (a pre-release single): counts.
        assertTrue(ReleaseRadar.isRecent(r("2026-10-23", 0), today, 14))
    }
}
