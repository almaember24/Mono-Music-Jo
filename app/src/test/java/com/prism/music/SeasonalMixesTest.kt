package com.prism.music

import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.seasonal.HolidayMix
import com.prism.music.data.seasonal.HolidayMixMaker
import com.prism.music.data.seasonal.HolidayMixes
import com.prism.music.data.seasonal.SeasonalMixes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Opening a holiday mix fast (1.8.0): a mix that's opened is made at once (never behind a quiet network or the
 * background's work), Home's row makes its mixes ahead on Wi-Fi only, one at a time in the row's order, and asking
 * YouTube more at once finds exactly what asking one at a time does.
 */
class SeasonalMixesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.systemDefault()
    private val december = Clock.fixed(LocalDateTime.of(2026, 12, 10, 12, 0).atZone(zone).toInstant(), zone)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val christmas = HolidayMixes.byId("season:christmas_classics")!!
    private val shown = HolidayMixes.on(december.instant().atZone(zone).toLocalDate())

    @After fun stop() = scope.cancel()

    private fun songs(key: String, n: Int = 45) = (1..n).map { Song("$key-$it", "Song $it", listOf(ArtistRef("Artist $key $it")), durationSec = 200) }

    private fun seasonal(
        build: suspend (HolidayMix, String) -> List<Song>,
        quiet: suspend () -> Unit = {},
        wifi: () -> Boolean = { true },
    ) = SeasonalMixes(tmp.root, scope, build, quiet, wifi, aheadAfterMs = 0).also { it.clock = december }

    @Test fun aMixThatsOpenedDoesntWaitForAQuietNetwork() = runBlocking {
        // A song is forever starting: the background would wait, the listener doesn't.
        val s = seasonal({ m, _ -> songs(m.key) }, quiet = { awaitCancellation() })
        val mix = withTimeout(5_000) { s.mix(christmas.id) }
        assertNotNull(mix)
        assertEquals(45, mix!!.songs.size)
        // And it's kept: the next open needs no network.
        assertEquals(45, s.keptNow(christmas.id)?.songs?.size)
    }

    @Test fun theWeeklyRemakeWaitsButDoesntHoldUpAnotherMixBeingOpened() = runBlocking {
        val made = Collections.synchronizedList(ArrayList<String>())
        val quiet = CompletableDeferred<Unit>()
        val s = seasonal({ m, _ -> made += m.key; songs(m.key) }, quiet = { quiet.await() })
        // Christmas Classics kept two weeks ago.
        val old = SeasonalMixes.Kept(christmas.id, "2026-CHRISTMAS", System.currentTimeMillis() - 14 * 86_400_000L, songs("old"))
        File(tmp.root, christmas.key + ".json").writeText(Json.encodeToString(SeasonalMixes.Kept.serializer(), old))

        // The old one opens at once; its remake waits for the network to be quiet.
        assertEquals("old-1", withTimeout(5_000) { s.mix(christmas.id) }!!.songs.first().id)
        delay(200)
        assertTrue(made.isEmpty())
        // Meanwhile another mix opens straight away, ahead of the waiting remake.
        val other = shown.first { it.id != christmas.id }
        assertEquals(45, withTimeout(5_000) { s.mix(other.id) }!!.songs.size)
        assertEquals(listOf(other.key), made.toList())

        quiet.complete(Unit)
        withTimeout(5_000) { while (s.keptNow(christmas.id)?.songs?.first()?.id != "${christmas.key}-1") delay(20) }
        assertEquals(listOf(other.key, christmas.key), made.toList())
    }

    @Test fun makingAheadIsForWifiOnly() = runBlocking {
        val made = Collections.synchronizedList(ArrayList<String>())
        val mobile = seasonal({ m, _ -> made += m.key; songs(m.key) }, wifi = { false })
        mobile.makeAhead(shown).join()
        assertTrue("nothing on mobile data", made.isEmpty())
        assertNull(mobile.keptNow(shown.first().id))

        // On Wi-Fi until the phone moves to mobile data after the first mix: it stops there.
        var wifi = true
        val moving = SeasonalMixes(File(tmp.root, "b"), scope, { m, _ -> made += m.key; wifi = false; songs(m.key) }, {}, { wifi }, 0)
            .also { it.clock = december }
        withTimeout(5_000) { moving.makeAhead(shown).join() }
        assertEquals(listOf(shown.first().key), made.toList())
    }

    @Test fun makingAheadGoesInTheRowsOrderOneAtATimeAfterTheQuiet() = runBlocking {
        val events = Collections.synchronizedList(ArrayList<String>())
        val running = AtomicInteger()
        var most = 0
        val s = seasonal(
            { m, _ ->
                val now = running.incrementAndGet()
                synchronized(events) { most = maxOf(most, now); events += "make ${m.key}" }
                delay(30)
                running.decrementAndGet()
                songs(m.key)
            },
            quiet = { events += "quiet" },
        )
        // One kept already: it isn't made again.
        val keptOne = shown[2]
        s.keep(keptOne.id, songs("kept"))
        val row = shown.take(5)
        withTimeout(10_000) { s.makeAhead(row).join() }

        val makes = events.filter { it.startsWith("make") }
        assertEquals(row.filter { it != keptOne }.map { "make ${it.key}" }, makes)
        assertEquals("one at a time", 1, most)
        // Each after a quiet network.
        events.forEachIndexed { i, e -> if (e.startsWith("make")) assertEquals("quiet", events[i - 1]) }
        // Only the row's mixes.
        assertNull(s.keptNow(shown[5].id))
        assertEquals("kept-1", s.keptNow(keptOne.id)!!.songs.first().id)
    }

    @Test fun aMixOpenedWhileItsMadeAheadIsMadeOnce() = runBlocking {
        val made = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val s = seasonal({ m, _ -> made.incrementAndGet(); gate.await(); songs(m.key) })
        s.makeAhead(listOf(christmas))
        withTimeout(5_000) { while (made.get() == 0) delay(10) }
        val opening = scope.async { s.mix(christmas.id) }
        delay(100)
        gate.complete(Unit)
        assertEquals(45, withTimeout(5_000) { opening.await() }!!.songs.size)
        assertEquals(1, made.get())
    }

    // ------------------------------------------------------------------------------------------------ asking at once

    /** YouTube as a test: the same answers for the same question, coming back after a while that differs each [run]. */
    private class SlowYouTube(private val run: Int) : HolidayMixMaker.Sources {
        val running = AtomicInteger()
        @Volatile var most = 0

        private suspend fun <T> answer(q: String, block: () -> T): T {
            val now = running.incrementAndGet()
            synchronized(this) { most = maxOf(most, now) }
            delay(Random(q.hashCode() * 31 + run).nextLong(1, 25))
            running.decrementAndGet()
            return block()
        }

        private fun song(id: String, title: String, artist: String) = Song(id, title, listOf(ArtistRef(artist)), durationSec = 200)

        override suspend fun songs(query: String, pages: Int) = answer(query) {
            // Some songs every search finds (they weigh more), and the search's own.
            (1..12).map { song("s$it", "Snow Song $it", "Ann Lee $it") } +
                (1..25 * pages).map { song("${query.hashCode()}-$it", "Snow Waltz $it", "Bo Ray ${query.length}$it") }
        }

        override suspend fun playlists(query: String) = answer("p:$query") {
            // A playlist two seeds both find: only the first seed takes it.
            listOf(PlaylistItem("PLshared", query, "YouTube Music", null), PlaylistItem("PL${query.hashCode()}", query, "YouTube Music", null))
        }

        override suspend fun playlistSongs(id: String) = answer("l:$id") {
            (1..90).map { song("$id-$it", "Holly Night $it", "Cy Dee ${id.length}$it") }
        }
    }

    @Test fun askingSixAtOnceFindsTheSameMixAsOneAtATime() = runBlocking(Dispatchers.Default) {
        for ((n, mix) in (HolidayMixes.on(LocalDateTime.of(2026, 11, 20, 12, 0).toLocalDate()) + HolidayMixes.on(LocalDateTime.of(2026, 12, 31, 12, 0).toLocalDate())).withIndex()) {
            val one = SlowYouTube(n)
            val six = SlowYouTube(n + 100)
            val alone = HolidayMixMaker(one, atOnce = 1).find(mix)
            val together = HolidayMixMaker(six, atOnce = 6).find(mix)
            assertEquals(mix.key, alone, together)
            assertEquals(mix.key, HolidayMixMaker.assemble(mix, alone, "2026-W50"), HolidayMixMaker.assemble(mix, together, "2026-W50"))
            assertEquals(1, one.most)
            assertTrue("${mix.key}: ${six.most} at once", six.most in 2..6)
            // The shared playlist went to one seed only.
            assertTrue(together.count { f -> f.songs.any { it.id.startsWith("PLshared-") } } <= 1)
        }
        // Real mixes come of it.
        val found = HolidayMixMaker(SlowYouTube(7)).find(christmas)
        assertTrue(HolidayMixMaker.assemble(christmas, found, "2026-W50").size >= 20)
    }

    @Test fun aKeptMixFromLastSeasonIsntUsed() = runBlocking {
        val s = seasonal({ m, _ -> songs(m.key) })
        val last = SeasonalMixes.Kept(christmas.id, "2025-CHRISTMAS", System.currentTimeMillis(), songs("last"))
        File(tmp.root, christmas.key + ".json").writeText(Json.encodeToString(SeasonalMixes.Kept.serializer(), last))
        assertNull(s.kept(christmas))
        assertEquals("${christmas.key}-1", withTimeoutOrNull(5_000) { s.mix(christmas.id) }!!.songs.first().id)
    }
}
