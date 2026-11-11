package com.prism.music.data.imports

import com.prism.music.data.imports.ImportFiles.str
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Last.fm's scrobbles (1.8.1), through the Last.fm account connected in Settings: `user.getRecentTracks`, 200 a page,
 * newest first, a short pause between pages (Last.fm asks apps to keep it under a few requests a second). A scrobble
 * is a song heard for half its length or four minutes, so it counts as the song's length, never more than the time to
 * the next one ([TakeoutHistory]'s rule).
 */
object LastFmHistory {
    data class Page(val plays: List<ParsedPlay>, val page: Int, val totalPages: Int, val total: Int)

    const val PER_PAGE = 200
    /** Between pages: well under Last.fm's limit. */
    const val PAUSE_MS = 400L
    private const val TRIES = 3

    /**
     * `user.getRecentTracks`' `from` (seconds) for a repeat import: the newest scrobble brought before, so Last.fm sends
     * only what came since (that scrobble again too, which the import passes over as already here). Null: everything.
     */
    fun fromSeconds(newestStoredMs: Long?): Long? = newestStoredMs?.takeIf { it > 0 }?.div(1000)

    /**
     * [fromSeconds], unless the last reading stopped before its oldest page ([lastFinished] false): then everything is
     * asked again, the scrobbles already here passed over, so the pages it never read are read at last.
     */
    fun fromSeconds(newestStoredMs: Long?, lastFinished: Boolean): Long? = if (lastFinished) fromSeconds(newestStoredMs) else null

    /** [readAll]'s answer: the scrobbles read, and whether every page was ([finished] false: one kept failing). */
    data class Read(val plays: Int, val finished: Boolean)

    /** One page of `user.getRecentTracks` (JSON); [laterAt]: the time of the play read before this page's first. */
    fun page(text: String, laterAt: Long? = null): Page? {
        val root = runCatching { ImportFiles.json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val recent = root["recenttracks"] as? JsonObject ?: return null
        val attr = recent["@attr"] as? JsonObject
        val tracks = when (val t = recent["track"]) {
            is JsonArray -> t.mapNotNull { it as? JsonObject }
            // A page with one scrobble has it on its own, not in a list.
            is JsonObject -> listOf(t)
            else -> emptyList()
        }
        var later = laterAt
        val plays = tracks.mapNotNull { o ->
            // What's playing now has no date yet: it isn't a scrobble.
            if ((o["@attr"] as? JsonObject)?.str("nowplaying") == "true") return@mapNotNull null
            val at = (o["date"] as? JsonObject)?.str("uts")?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            val title = o.str("name") ?: return@mapNotNull null
            val artist = text(o["artist"]) ?: return@mapNotNull null
            val gap = later?.let { it - at }?.takeIf { it > 0 }
            if (later == null || at <= later!!) later = at
            ParsedPlay(
                timestamp = at, playedMs = gap?.coerceAtMost(TakeoutHistory.CAP_MS) ?: TakeoutHistory.CAP_MS,
                title = title, artist = artist, album = text(o["album"]), sourceId = o.str("mbid"), estimated = true,
            )
        }
        return Page(plays, attr?.str("page")?.toIntOrNull() ?: 1, attr?.str("totalPages")?.toIntOrNull() ?: 1, attr?.str("total")?.toIntOrNull() ?: plays.size)
    }

    /** `{"#text": "X"}` or `{"name": "X"}` (extended answers) or plain text. */
    private fun text(e: JsonElement?): String? = when (e) {
        is JsonObject -> (e.str("#text") ?: e.str("name"))
        is JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() }
        else -> null
    }

    /**
     * Every page, newest first: [fetch] asks for one page (1-based), [onPlay] gets each scrobble, [onProgress] the
     * pages read of all. A page that fails is asked again twice, then reading stops with what was read (not [Read.finished]).
     */
    suspend fun readAll(
        fetch: suspend (Int) -> String,
        onPlay: (ParsedPlay) -> Unit,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        pause: Long = PAUSE_MS,
    ): Read {
        var page = 1
        var pages = 1
        var later: Long? = null
        var read = 0
        while (page <= pages) {
            var p: Page? = null
            for (attempt in 1..TRIES) {
                p = runCatching { page(fetch(page), later) }.getOrNull()
                if (p != null) break
                delay(pause * attempt * 4)
            }
            p ?: return Read(read, finished = false)
            pages = p.totalPages.coerceAtLeast(1)
            p.plays.forEach { onPlay(it); read++ }
            p.plays.lastOrNull()?.let { later = it.timestamp }
            onProgress(page, pages)
            page++
            if (page <= pages) delay(pause)
        }
        return Read(read, finished = true)
    }
}
