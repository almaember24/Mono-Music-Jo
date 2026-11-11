package com.prism.music.data

import com.prism.music.data.model.Song

/**
 * #299 (1.8.2): keeps long songs out of Autoplay ("Longest song", Playback & sound). Mixes and compilations that run for an
 * hour are what it's for. A song's length not being known lets it through, so nothing is lost to a missing figure.
 */
object AutoplayLength {
    /** The slider's far end, and its steps. */
    const val MAX_MINUTES = 60
    const val STEP_MINUTES = 5

    /** Whether [song] may be added with a limit of [maxMinutes] (0: no limit). */
    fun fits(song: Song, maxMinutes: Int): Boolean =
        maxMinutes <= 0 || song.durationSec <= 0 || song.durationSec <= maxMinutes * 60

    /** [songs] as Autoplay may add them. */
    fun keep(songs: List<Song>, maxMinutes: Int): List<Song> =
        if (maxMinutes <= 0) songs else songs.filter { fits(it, maxMinutes) }
}
