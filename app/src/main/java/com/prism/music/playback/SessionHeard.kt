package com.prism.music.playback

import com.prism.music.data.foryou.LocalRadio
import com.prism.music.data.model.Song

/**
 * The songs heard this session (1.8.2, #159.4 "prevent the song to repeat in the current session"), for "No repeats this
 * session" to leave out of autoplay and radios.
 *
 * Every song that started playing counts, however briefly: a song skipped after three seconds never reaches the play
 * history, and the queue's own "already in the queue" check forgets a song once a new queue replaces it. A song is known
 * by its id, its music video's id and by name ([LocalRadio.sameSong], YouTube often has two uploads of one song).
 *
 * A session ends after [IDLE_MS] with nothing started. What's heard is kept through a restart (a phone short of memory
 * can end Prism between songs), read back by [load] and written by [save].
 */
class SessionHeard(
    private val load: () -> Map<String, Long> = { emptyMap() },
    private val save: (Map<String, Long>) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Key (an id or a name) -> when it last started playing. Oldest first. */
    private val heard: LinkedHashMap<String, Long> by lazy { LinkedHashMap(load()) }
    /** The songs themselves, for the rare time a radio has nothing new left. Not kept through a restart. */
    private val songs = LinkedHashMap<String, Song>()

    private fun keysOf(song: Song): List<String> =
        listOfNotNull(song.id, LocalRadio.sameSong(song).takeIf { it.substringBeforeLast('|').isNotBlank() })

    @Synchronized private fun expire() {
        val last = heard.values.maxOrNull() ?: return
        if (now() - last >= IDLE_MS) { heard.clear(); songs.clear() }
    }

    /** [song] started playing; [video] is its music video's id, when known. */
    @Synchronized fun note(song: Song, video: String? = null) {
        expire()
        val at = now()
        (keysOf(song) + listOfNotNull(video)).forEach { k -> heard.remove(k); heard[k] = at }
        songs.remove(song.id)
        songs[song.id] = song
        while (heard.size > MAX_KEYS) heard.remove(heard.keys.first())
        while (songs.size > MAX_SONGS) songs.remove(songs.keys.first())
        save(HashMap(heard))
    }

    /** Whether [song] (or another upload of it) has played this session. */
    @Synchronized fun has(song: Song): Boolean {
        expire()
        return keysOf(song).any { it in heard }
    }

    /** When [song] last started this session, null when it hasn't. */
    @Synchronized fun heardAt(song: Song): Long? {
        expire()
        return keysOf(song).mapNotNull { heard[it] }.maxOrNull()
    }

    /** The session's songs, the one heard longest ago first. */
    @Synchronized fun oldestFirst(): List<Song> {
        expire()
        return songs.values.toList()
    }

    /**
     * A radio's first songs without what's been heard, keeping the song it starts on: [songs] and [start] as
     * they should go into the queue. A list that would be left with hardly anything starts as it is: tapped by hand, it
     * still has to play, and the top-ups after it stay clear of repeats.
     */
    fun radioStart(songs: List<Song>, start: Int): Pair<List<Song>, Int> {
        val first = songs.getOrNull(start) ?: return songs to start
        val kept = songs.filterIndexed { i, s -> i == start || !has(s) }
        if (kept.size < MIN_START && songs.size >= MIN_START) return songs to start
        return kept to kept.indexOfFirst { it === first }.coerceAtLeast(0)
    }

    companion object {
        /** Six hours with nothing started ends the session: a day's listening is one, tomorrow's is another. */
        const val IDLE_MS = 6 * 3_600_000L
        /** A few keys per song; hundreds of songs before the oldest are let go. */
        private const val MAX_KEYS = 1500
        private const val MAX_SONGS = 500
        private const val MIN_START = 5
    }
}
