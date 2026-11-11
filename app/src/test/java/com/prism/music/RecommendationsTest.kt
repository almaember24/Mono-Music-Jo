package com.prism.music

import com.prism.music.data.foryou.LocalRadio
import com.prism.music.data.foryou.MadeForYou
import com.prism.music.data.foryou.MixMaker
import com.prism.music.data.foryou.Variety
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Related
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Prism's mixes over a week (1.7.9, Reddit: "the daily mixes play the same songs", "the radio always plays NSYNC"):
 * a listener with six artists, one played far more than the others, and a YouTube that answers each seed with its
 * own radio, Related songs and similar artists. Each day's Daily Mix is measured: how much of it was in yesterday's,
 * how many different songs a week brings, how much of it is new, and how much any one artist takes. The way mixes
 * were made before 1.7.9 is kept here as the baseline.
 */
class RecommendationsTest {
    private fun song(id: String, artist: String) = Song(id, "Song $id", listOf(ArtistRef(artist)), durationSec = 200)

    // The listener's pop artists (NSYNC by far the most played) and their rock ones.
    private val pop = listOf("NSYNC", "Britney Spears", "Backstreet Boys")
    private val rock = listOf("Foo Fighters", "Green Day", "Blink-182")
    private val library = (pop + rock).flatMap { a -> (1..30).map { song("${a.take(3)}$it", a) } }
    // Songs out in the world the listener hasn't heard: 40 artists, 15 songs each.
    private val world = (1..40).flatMap { a -> (1..15).map { song("w$a-$it", "Artist $a") } }

    /** A plain, fixed YouTube: each seed's radio, Related and similar artists are its own slice of [world]. */
    private val sources = object : MixMaker.Sources {
        private fun slice(key: String, n: Int, offset: Int): List<Song> {
            val start = ((key.hashCode() and 0x7fffffff) + offset) % world.size
            return (0 until n).map { world[(start + it * 7) % world.size] }
        }
        override suspend fun radio(seed: Song) = slice(seed.id, 25, 0)
        override suspend fun related(seed: Song) = Related(
            slice(seed.id, 20, 101),
            (1..5).map { i -> val n = ((seed.id.hashCode() and 0x7fff) + i * 3) % 40 + 1; ArtistItem("UC$n", "Artist $n", "", null) },
        )
        override suspend fun artistTop(artist: ArtistItem) = world.filter { it.primaryArtist == artist.title }.take(6)
    }

    /** NSYNC's top five played again and again, the rest of the pop artists now and then. */
    private fun score(s: Song): Double = when {
        s.primaryArtist == "NSYNC" && s.id.drop(3).toInt() <= 5 -> 0.95
        s.primaryArtist == "NSYNC" -> 0.6
        s.primaryArtist in pop -> 0.35
        else -> 0.3
    }

    private class Served { val day = HashMap<String, Int>(); fun daysAgo(id: String, today: Int) = day[id]?.let { today - it } }

    private fun taste(served: Served, today: Int, turnedDown: Set<String> = emptySet()) = MixMaker.Taste(
        library.map { MixMaker.Known(it, score(it), servedDaysAgo = served.daysAgo(it.id, today)) },
        played = library.map { it.id }.toSet() + library.map { LocalRadio.sameSong(it) },
        refused = { s, _ -> s.id in turnedDown },
        servedDaysAgo = { served.daysAgo(it, today) },
    )

    private data class Week(val mixes: List<List<Song>>) {
        val overlap: Double get() = mixes.zipWithNext { a, b -> Variety.overlap(a.map { it.id }, b.map { it.id }) }.average()
        val distinct: Int get() = mixes.flatten().map { it.id }.toSet().size
        val newShare: Double get() = mixes.map { m -> m.count { it.id.startsWith("w") }.toDouble() / m.size }.average()
        val topArtistShare: Double get() = mixes.map { m -> m.groupingBy { it.primaryArtist }.eachCount().values.max().toDouble() / m.size }.average()
        val nsyncTop5Days: Int get() = mixes.count { m -> m.any { it.id == "NSY1" } }
        override fun toString() = "overlap with the day before %.0f%%, %d different songs in 7 mixes, %.0f%% new, busiest artist %.0f%% of a mix, NSYNC's #1 in %d of 7"
            .format(overlap * 100, distinct, newShare * 100, topArtistShare * 100, nsyncTop5Days)
    }

    private fun newWeek(turnedDown: Set<String> = emptySet()): Week = runBlocking {
        val served = Served()
        val maker = MixMaker(sources)
        val start = LocalDate.of(2026, 11, 2)
        val mixes = (0 until 7).map { d ->
            val today = d
            val recipe = MadeForYou.dailyRecipe("daily:1", pop.size)
            val made = maker.make(recipe, taste(served, today, turnedDown), start.plusDays(d.toLong()).toString()) { it.song.primaryArtist in pop }
            made.songs.forEach { served.day[it.id] = today }
            made.songs
        }
        Week(mixes)
    }

    /** Daily Mixes as 1.7.8 made them: the top 30 known (a ±15% daily shake), new songs from the same top 3's radios. */
    private fun oldWeek(): Week {
        val start = LocalDate.of(2026, 11, 2)
        val mixes = (0 until 7).map { d ->
            val day = start.plusDays(d.toLong()).toString()
            fun jitter(id: String) = 0.85 + 0.3 * ((id + day).hashCode().toLong().and(0xffff) / 65535.0)
            val own = library.filter { it.primaryArtist in pop }.sortedByDescending { score(it) * jitter(it.id) }.take(30)
            val fresh = own.take(3).flatMap { runBlocking { sources.radio(it) } }.distinctBy { it.id }
                .sortedByDescending { 0.5 * jitter(it.id) }.take(20)
            MadeForYou.interleave(own, fresh, 50) { it.primaryArtist }
        }
        return Week(mixes)
    }

    @Test fun aWeekOfDailyMixesKeepsChanging() {
        val before = oldWeek()
        val after = newWeek()
        println("[recs] before 1.7.9: $before")
        println("[recs] 1.7.9:        $after")
        assertTrue("a mix shares at most about a third with the day before ($after)", after.overlap <= 0.35)
        assertTrue("and far less than before ($before)", after.overlap < before.overlap / 2)
        assertTrue("a week brings many more different songs", after.distinct >= before.distinct * 2)
        assertEquals("half of each mix is new", 0.5, after.newShare, 0.08)
        assertTrue("no artist takes over a mix", after.topArtistShare <= 0.35)
        assertTrue("the favourite still comes back now and then, not every day", after.nsyncTop5Days in 1..5)
    }

    @Test fun aSongTurnedDownStaysOut() {
        val skipped = setOf("w1-1", "w5-3", "NSY2")
        val week = newWeek(turnedDown = skipped)
        assertFalse(week.mixes.flatten().any { it.id in skipped })
    }

    @Test fun mixesSpreadTheirArtists() {
        val week = newWeek()
        for (m in week.mixes) {
            m.zipWithNext().forEach { (a, b) -> assertTrue("${a.primaryArtist} twice running", a.primaryArtist != b.primaryArtist) }
            val most = m.groupingBy { it.primaryArtist }.eachCount().maxBy { it.value }
            assertTrue("$most", most.value <= MadeForYou.dailyRecipe("x", pop.size).perArtist)
        }
    }

    @Test fun restAndSampling() {
        assertEquals(1.0, Variety.rest(0, null, null), 1e-9)
        assertTrue("served yesterday rests", Variety.rest(0, null, 1) < 0.3)
        assertTrue("heard an hour ago rests", Variety.rest(1, 1.0, null) < 0.2)
        assertTrue("on repeat rests", Variety.rest(6, 72.0, null) < 0.2)
        // Weighted draws: heavier items come up more often, every item can.
        val items = (1..10).toList()
        val counts = IntArray(11)
        repeat(2000) { seed -> Variety.sample(items, 3, seed.toLong()) { it.toDouble() }.forEach { counts[it]++ } }
        assertTrue("heavier more often", counts[10] > counts[1] * 3)
        assertTrue("the lightest still comes up", counts[1] > 0)
        assertEquals("the same seed draws the same", Variety.sample(items, 4, 42) { it.toDouble() }, Variety.sample(items, 4, 42) { it.toDouble() })
    }
}
