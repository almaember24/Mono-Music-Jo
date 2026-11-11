package com.prism.music.data.imports

/**
 * A look at Last.fm before bringing anything (1.8.1): how many scrobbles there are and from when to when, in two small
 * requests (the newest scrobble with Last.fm's own count, then the oldest, the last page of one), not the whole history.
 */
object LastFmPeek {
    /**
     * [total] scrobbles to bring, [newestAt]..[oldestAt] (ms; null when there are none). [since]: a repeat import's
     * starting point (the newest scrobble already here, ms), null for the first.
     */
    data class Peek(val total: Int, val newestAt: Long?, val oldestAt: Long?, val since: Long?) {
        val isEmpty: Boolean get() = total <= 0
    }

    /**
     * Asks [fetch] (page, per page; Last.fm's JSON) for the first page of two and then the oldest scrobble. [fromSec]:
     * `user.getRecentTracks`' `from` for a repeat import ([LastFmHistory.fromSeconds]); the scrobble at exactly that
     * second is already here, and isn't counted.
     */
    suspend fun peek(fetch: suspend (Int, Int) -> String, fromSec: Long?): Peek? {
        // Two a page: a song playing now comes first, undated, and isn't a scrobble.
        val first = LastFmHistory.page(fetch(1, 2)) ?: return null
        val since = fromSec?.times(1000)
        if (first.total <= 0 || first.plays.isEmpty()) return Peek(0, null, null, since)
        // The [n]th scrobble from the newest (1-based), a page of one; this page's own when it's on it.
        suspend fun nth(n: Int): Long? = first.plays.getOrNull(n - 1)?.timestamp
            ?: LastFmHistory.page(fetch(n, 1))?.plays?.lastOrNull()?.timestamp
        var total = first.total
        var oldest = nth(total) ?: first.plays.last().timestamp
        // The one brought last time comes again (Last.fm's `from` includes it): the one before it is the oldest new one.
        if (since != null && oldest <= since) {
            total--
            if (total <= 0) return Peek(0, null, null, since)
            oldest = nth(total) ?: first.plays.first().timestamp
        }
        return Peek(total, first.plays.first().timestamp, oldest, since)
    }
}
