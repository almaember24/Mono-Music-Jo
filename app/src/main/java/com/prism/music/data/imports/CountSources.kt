package com.prism.music.data.imports

import com.prism.music.data.db.ImportSummary

/**
 * "Count history from" (1.8.1, the owner's request): every source listed, so the list is never empty at first. Prism's
 * own plays always count; a source with plays here, or a live one turned on, has its switch; the others are shown
 * greyed with the way to bring them in (or turn them on), and are never counted.
 */
object CountSources {
    enum class State {
        /** Has plays here, or (a live one) is turned on: the listener's switch. */
        COUNTABLE,
        /** Nothing brought in yet (a live one: off): greyed, with "Bring it in" or "Turn on". */
        MISSING,
    }

    data class Row(val source: ImportSource, val state: State, val plays: Int, val counted: Boolean)

    /** Every source in the page's order (Spotify, YouTube Music, Last.fm, then the live ones). */
    fun rows(summaries: List<ImportSummary>, liveOn: Set<ImportSource>, counted: Set<ImportSource>): List<Row> {
        val plays = summaries.associate { it.origin to it.plays }
        return ImportSource.entries.map { s ->
            val n = plays[s.key] ?: 0
            val state = if (n > 0 || (s.isLive && s in liveOn)) State.COUNTABLE else State.MISSING
            Row(s, state, n, counted = state == State.COUNTABLE && s in counted)
        }
    }
}
