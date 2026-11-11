package com.prism.music

import com.prism.music.data.imports.LastFmPeek
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The look at Last.fm before bringing (1.8.1): "N scrobbles from <date> to <date>" from two small requests, not the
 * whole history; only what's new after an earlier import.
 */
class LastFmPeekTest {
    /** A pretend account: [times] (seconds) newest first, maybe a song playing now. */
    private class Account(val times: List<Long>, val playingNow: Boolean = false) {
        val asked = mutableListOf<Pair<Int, Int>>()

        fun page(page: Int, per: Int, fromSec: Long?): String {
            asked += page to per
            val all = times.filter { fromSec == null || it >= fromSec }
            val pages = maxOf(1, (all.size + per - 1) / per)
            val slice = all.drop((page - 1) * per).take(per)
            val tracks = (if (playingNow && page == 1) listOf("""{"name":"Now","artist":{"#text":"A"},"@attr":{"nowplaying":"true"}}""") else emptyList()) +
                slice.map { t -> """{"name":"Song $t","artist":{"#text":"Artist"},"album":{"#text":"Album"},"date":{"uts":"$t"}}""" }
            return """{"recenttracks":{"track":[${tracks.joinToString(",")}],"@attr":{"page":"$page","perPage":"$per","totalPages":"$pages","total":"${all.size}"}}}"""
        }
    }

    private fun peek(a: Account, fromSec: Long? = null) = runBlocking { LastFmPeek.peek({ p, per -> a.page(p, per, fromSec) }, fromSec) }

    @Test fun twoRequestsForTheWholeHistory() {
        val a = Account((1000 downTo 1).map { 1_700_000_000L + it * 3_600 }, playingNow = true)
        val p = peek(a)!!
        assertEquals(1000, p.total)
        assertEquals((1_700_000_000L + 1000 * 3_600) * 1000, p.newestAt)
        assertEquals((1_700_000_000L + 3_600) * 1000, p.oldestAt)
        assertNull("a first import", p.since)
        assertEquals("the first page of two, then the oldest alone", listOf(1 to 2, 1000 to 1), a.asked)
    }

    @Test fun onlyWhatsNewAfterAnEarlierImport() {
        val newest = 1_700_000_000L
        val a = Account(listOf(newest + 300, newest + 200, newest + 100, newest, newest - 100))
        // The import starts from the newest one brought before, which Last.fm sends again: it isn't counted.
        val p = peek(a, fromSec = newest)!!
        assertEquals(3, p.total)
        assertEquals((newest + 300) * 1000, p.newestAt)
        assertEquals("the oldest new one, not the one already here", (newest + 100) * 1000, p.oldestAt)
        assertEquals(newest * 1000, p.since)
    }

    @Test fun nothingNewOrNothingAtAll() {
        val newest = 1_700_000_000L
        assertTrue("only the one already here", peek(Account(listOf(newest)), fromSec = newest)!!.isEmpty)
        val none = peek(Account(emptyList(), playingNow = true))!!
        assertTrue(none.isEmpty)
        assertNull(none.newestAt)
        // Two scrobbles: both on the first page, nothing more asked.
        val two = Account(listOf(newest + 10, newest))
        assertEquals(2, peek(two)!!.total)
        assertEquals(listOf(1 to 2), two.asked)
    }

    @Test fun lastFmNotAnswering() {
        assertNull(runBlocking { LastFmPeek.peek({ _, _ -> "<html>Bad gateway</html>" }, null) })
    }
}
