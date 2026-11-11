package com.prism.music.data

import com.prism.music.data.innertube.TopSearch
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.ExplicitContent

/**
 * #216 (1.8.1): the explicit filter's rules in one place. Prism goes by YouTube Music's own "E" mark, which comes with
 * the songs it lists (search, albums, playlists, radios, Home); a song kept on the phone without it (an older like, a
 * file) isn't known to be explicit, so it's let through.
 */
object ExplicitFilter {
    /** Whether [song] may play: always with the filter off, else only when it isn't marked explicit. */
    fun plays(mode: ExplicitContent, song: Song): Boolean = mode == ExplicitContent.SHOW || !song.explicit

    /** Whether [song] is listed: only Hide leaves explicit songs out of lists. */
    fun shows(mode: ExplicitContent, song: Song): Boolean = mode != ExplicitContent.HIDE || !song.explicit

    /** [songs] as a list shows them. */
    fun shown(mode: ExplicitContent, songs: List<Song>): List<Song> =
        if (mode != ExplicitContent.HIDE) songs else songs.filter { !it.explicit }

    /** Search results and shelves: explicit songs left out with Hide (albums, artists and playlists stay). */
    fun <T : BrowseItem> items(mode: ExplicitContent, items: List<T>): List<T> =
        if (mode != ExplicitContent.HIDE) items else items.filter { !(it is SongItem && it.song.explicit) }

    /** Top results with Hide: an explicit song isn't the top card (the best of what's left is), nor in a row. */
    fun top(mode: ExplicitContent, top: TopSearch): TopSearch {
        if (mode != ExplicitContent.HIDE) return top
        val sections = top.sections.map { (f, list) -> f to items(mode, list) }.filter { it.second.isNotEmpty() }
        val card = top.top?.takeUnless { it is SongItem && it.song.explicit }
        return TopSearch(card ?: sections.firstOrNull()?.second?.firstOrNull(), sections)
    }

    /**
     * A queue about to start from [songs] at [start], without what may not play: the songs left and where to start in
     * them (the tapped song, else the first allowed one after it, else the first of all). Null when none may play.
     */
    fun queue(mode: ExplicitContent, songs: List<Song>, start: Int): Pair<List<Song>, Int>? {
        if (mode == ExplicitContent.SHOW || songs.none { it.explicit }) return songs to start
        val kept = songs.withIndex().filter { plays(mode, it.value) }
        if (kept.isEmpty()) return null
        val at = kept.indexOfFirst { it.index >= start }.takeIf { it >= 0 } ?: 0
        return kept.map { it.value } to at
    }
}
