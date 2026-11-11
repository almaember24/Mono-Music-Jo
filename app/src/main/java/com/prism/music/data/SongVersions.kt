package com.prism.music.data

import android.content.SharedPreferences
import com.prism.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** #328 (1.8.1.1): the song as Prism shows it: as the version picked to play in its place, if any. */
val Song.shown: Song get() = SongVersions.shownOf(this)

/**
 * The version of a song the listener picked to hear in its place (1.7.9, Reddit: an album's song played as someone's
 * guitar cover, and nothing in Prism could change it). Song id → the picked recording's id, kept on the phone.
 * Everything that fetches the song's sound (playing, caching, downloading, scans) asks for the pick instead
 * ([com.prism.music.data.stream.StreamResolver.versionOf]); the song keeps its own id, lyrics, likes and history.
 * Where it shows (#328, 1.8.1.1: the player, lists, the notification, the car, widgets), it shows as the pick: its
 * cover, title, artists, album and length ([shown], and [com.prism.music.data.PlaylistCovers.art] for the cover).
 */
class SongVersions(private val prefs: SharedPreferences) {
    /**
     * A picked version: its id, what the menu shows for it ("Artist · Album · 3:41") and, picked since 1.8.1.1, the
     * version itself, whose details the song then shows (#328). Picks kept before that have none and show as the song.
     */
    data class Pick(val id: String, val label: String, val song: Song? = null)

    private val _picks = MutableStateFlow(read())
    val picks: StateFlow<Map<String, Pick>> = _picks

    /** The same picks as Compose state, so a song on screen shows a new pick (or the original again) at once. */
    private val shownPicks = androidx.compose.runtime.mutableStateOf(_picks.value)

    init { shared = this }

    /** Reads the picks again (after "Bring your Prism" merged a phone's in, 1.8.0). */
    fun reload() { _picks.value = read(); shownPicks.value = _picks.value }

    /** The recording that plays for [songId], or null for its own. */
    fun of(songId: String): String? = _picks.value[songId]?.id

    /** A file on this phone picked for [songId] (1.8.3, #243): its local id. It plays from the file. */
    fun fileOf(songId: String): String? = of(songId)?.takeIf { it.startsWith(Song.LOCAL_PREFIX) }

    /**
     * The recording to fetch from YouTube for [songId]: its pick, unless that's a file on the phone, which is never
     * streamed (casting, downloads and scans then use the song's own).
     */
    fun streamedOf(songId: String): String? = of(songId)?.takeUnless { it.startsWith(Song.LOCAL_PREFIX) }

    /** Plays [pick] for [songId] from now on; null goes back to the song's own. */
    fun set(songId: String, pick: Pick?) {
        val ed = prefs.edit()
        if (pick == null || pick.id == songId) ed.remove(songId) else ed.putString(songId, encode(pick))
        ed.apply()
        _picks.value = read(if (pick == null || pick.id == songId) _picks.value - songId else _picks.value + (songId to pick))
        shownPicks.value = _picks.value
    }

    /**
     * #328: [song] as it shows, with the picked version's cover, title, artists, album and length (it plays as that
     * recording). Its id and what it is (a song or a video, an upload) stay its own, so likes, playlists and history
     * still name it. With no pick (or one kept before 1.8.1.1), [song] itself. Read in a composable, it follows a pick.
     */
    fun shown(song: Song): Song {
        val picks = shownPicks.value
        if (picks.isEmpty()) return song
        val v = picks[song.id]?.song ?: return song
        return song.copy(
            title = v.title.ifBlank { song.title },
            artists = v.artists.ifEmpty { song.artists },
            album = v.album,
            durationSec = v.durationSec.takeIf { it > 0 } ?: song.durationSec,
            thumbnail = v.thumbnail ?: song.thumbnail,
            explicit = v.explicit,
        )
    }

    /** The picked version's cover for song [songId], if it has one (#328). */
    fun coverOf(songId: String): String? = shownPicks.value.takeIf { it.isNotEmpty() }?.get(songId)?.song?.thumbnail

    private fun encode(pick: Pick): String = listOfNotNull(
        pick.id, pick.label,
        pick.song?.let { com.prism.music.data.innertube.InnerTube.json.encodeToString(Song.serializer(), it) },
    ).joinToString("\t")

    private fun read(now: Map<String, Pick>? = null): Map<String, Pick> = now ?: prefs.all.mapNotNull { (k, v) ->
        val parts = (v as? String)?.split('\t', limit = 3) ?: return@mapNotNull null
        val version = parts.getOrNull(2)?.let { runCatching { com.prism.music.data.innertube.InnerTube.json.decodeFromString(Song.serializer(), it) }.getOrNull() }
        parts[0].takeIf { it.isNotBlank() }?.let { k to Pick(it, parts.getOrElse(1) { "" }, version?.takeIf { s -> s.id == it }) }
    }.toMap()

    companion object {
        /** The phone's picks, for what draws a song without the app's container at hand (the media session, a row). */
        @Volatile var shared: SongVersions? = null
            internal set

        /** [shown] for [song], or [song] itself before the picks have been read. */
        fun shownOf(song: Song): Song = shared?.shown(song) ?: song

        private val BRACKETS = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
        private val DASH = Regex("\\s+[-–—]\\s+.*$")

        /** A title without its brackets and " - Remastered"-style tails, letters and digits only. */
        fun bare(title: String): String =
            title.replace(BRACKETS, " ").replace(DASH, "").lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

        /**
         * The other recordings of [song] among search [results]: the same title (brackets and tails aside), not the song
         * itself, one of each; the song's own artist first, slowed / sped-up / karaoke edits last, songs before videos.
         * At most [max].
         */
        fun candidates(song: Song, results: List<Song>, max: Int = 12): List<Song> {
            val want = bare(song.title)
            if (want.isEmpty()) return emptyList()
            val mine = song.artists.map { it.name.lowercase() }.filter { it.isNotBlank() }.toSet()
            fun byArtist(s: Song) = s.artists.any { a -> a.name.lowercase().let { n -> mine.any { it in n || n in it } } }
            val asked = com.prism.music.data.innertube.SearchRank.norm(song.title)
            // "sped up//nightcore": slashes would join the words, so they part them first.
            fun knockoff(s: Song) = com.prism.music.data.innertube.SearchRank.knockoffMarks(s.title.replace(Regex("[/|]+"), " "), asked).isNotEmpty()
            return results.asSequence()
                .filter { it.id != song.id && !it.isLive && !it.isEpisode && bare(it.title) == want }
                .distinctBy { it.id }
                .sortedWith(compareBy({ knockoff(it) }, { !byArtist(it) }, { it.isVideo }))
                .take(max)
                .toList()
        }

        /** [v] is [song]'s own recording put up again (same album and length): "Another upload" in the list. */
        fun sameRecording(song: Song, v: Song): Boolean =
            !v.isVideo && song.album?.title != null && v.album?.title.equals(song.album?.title, ignoreCase = true) &&
                (song.durationSec <= 0 || v.durationSec <= 0 || kotlin.math.abs(song.durationSec - v.durationSec) <= 2)
    }
}
