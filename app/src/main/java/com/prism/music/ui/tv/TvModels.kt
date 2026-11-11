package com.prism.music.ui.tv

import com.prism.music.data.lyrics.LyricLine

/*
 * What Prism TV's screens show (1.8.0): plain values, filled from the shared engine by each screen's holder, so the
 * screens themselves are only drawing and focus (and are drawn with sample values for the design pages and tests).
 */

/** Something in a row: a song, an album, a playlist, a mix, an artist (round) or a music video (wide). */
data class TvItem(val id: String, val title: String, val subtitle: String?, val art: String?, val kind: Kind = Kind.ALBUM) {
    enum class Kind { SONG, ALBUM, PLAYLIST, MIX, ARTIST, VIDEO }
}

/** A row of [items] under its [title] (Quick picks, Your mixes, one of YouTube's shelves…). */
data class TvShelf(val title: String, val items: List<TvItem>)

/** One song in a list. */
data class TvSong(val id: String, val title: String, val artist: String, val length: String?, val art: String? = null)

/** The song playing, as the player and Home's hero show it. */
data class TvNow(
    val title: String,
    val artist: String,
    val album: String?,
    val art: String?,
    /** How far it is, 0–1, and the two times under the line. */
    val fraction: Float,
    val played: String,
    val total: String,
    val playing: Boolean,
    val liked: Boolean = false,
    val shuffle: Boolean = false,
    /** Media3's repeat mode: 0 off, 1 one, 2 all. */
    val repeat: Int = 0,
    val hasLyrics: Boolean = true,
    val hasVideo: Boolean = false,
    /** The song after this one, for the player's "Up next" line. */
    val next: String? = null,
)

/** Lyrics as the TV shows them: the lines, which one is sung now, and the playhead for the sung line's sweep. */
data class TvLyrics(val lines: List<LyricLine>, val current: Int, val positionMs: Long, val source: String?)
