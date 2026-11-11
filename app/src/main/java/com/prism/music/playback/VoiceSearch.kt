package com.prism.music.playback

import android.os.Bundle
import android.provider.MediaStore

/**
 * What a spoken "play … on Prism" asks for (1.8.1, Reddit: Android Auto's voice button did nothing for Prism). The
 * Assistant and Gemini send the words they heard ([MediaStore.EXTRA_MEDIA_FOCUS] says what kind of thing they took it for,
 * with the artist, album, title, genre or playlist they made out beside it), to the phone's activity or, in the car, to
 * the media session. Before, every request was looked up as a song: "play Taylor Swift" played one song, "play my liked
 * songs" a song called that, and a request with nothing found cleared what was playing.
 */
sealed interface VoiceRequest {
    /** Shuffle what's found ("shuffle my liked songs"). */
    val shuffle: Boolean

    /** "Play music on Prism": nothing named, so carry on with the last queue. */
    data object Resume : VoiceRequest { override val shuffle = false }

    /** "Play my liked songs". */
    data class Liked(override val shuffle: Boolean = false) : VoiceRequest

    /** "Play my downloads". */
    data class Downloads(override val shuffle: Boolean = false) : VoiceRequest

    /** "Play Taylor Swift" with the artist focus: the artist's top songs. */
    data class Artist(val name: String, override val shuffle: Boolean = false) : VoiceRequest

    /** "Play the album Midnights": the album, from its first song. */
    data class Album(val title: String, val artist: String?, override val shuffle: Boolean = false) : VoiceRequest

    /** "Play my playlist Road trip": one of the listener's own playlists by name, else a playlist found by it. */
    data class Playlist(val name: String, override val shuffle: Boolean = false) : VoiceRequest

    /** A song ("play Anti-Hero by Taylor Swift"), or a genre: the best song, then a radio from it. */
    data class Song(val text: String, override val shuffle: Boolean = false) : VoiceRequest

    /** Anything else: whatever Search's Top result is (an artist, album, playlist or song). */
    data class Top(val text: String, override val shuffle: Boolean = false) : VoiceRequest
}

/** Nothing to play for a voice request: the session's answer fails, so the queue playing stays as it was. */
class VoiceNotFound(val request: VoiceRequest) : Exception("Nothing to play for $request")

object VoiceSearch {
    const val FOCUS_ARTIST = MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
    const val FOCUS_ALBUM = MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
    const val FOCUS_SONG = MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
    const val FOCUS_PLAYLIST = MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE
    const val FOCUS_GENRE = MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE
    /** "Any kind": what the Assistant sends for a request it couldn't place ("play music on Prism" too). */
    const val FOCUS_ANY = "vnd.android.cursor.item/*"

    /** The request in [query] and the voice extras beside it (an intent's, or the media session's play-from-search). */
    fun parse(query: String?, extras: Bundle?): VoiceRequest = parse(
        query,
        focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS),
        title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
        artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
        album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
        genre = extras?.getString(MediaStore.EXTRA_MEDIA_GENRE),
        playlist = extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST),
    )

    /** The voice extras that [parse] reads, copied out of an intent's [from] (null when there are none). */
    fun extrasOf(from: Bundle?): Bundle? {
        if (from == null) return null
        val out = Bundle()
        for (key in KEYS) from.getString(key)?.let { out.putString(key, it) }
        return out.takeIf { !it.isEmpty }
    }

    private val KEYS = listOf(
        MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.EXTRA_MEDIA_TITLE, MediaStore.EXTRA_MEDIA_ARTIST, MediaStore.EXTRA_MEDIA_ALBUM,
        MediaStore.EXTRA_MEDIA_GENRE, MediaStore.EXTRA_MEDIA_PLAYLIST,
    )

    fun parse(
        query: String?,
        focus: String? = null,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
        playlist: String? = null,
    ): VoiceRequest {
        var text = clean(query)
        // "Shuffle …": the Assistant usually keeps the word in what it heard.
        val shuffle = SHUFFLE.containsMatchIn(text)
        text = text.replace(SHUFFLE, "").trim()
        val t = clean(title)
        val ar = clean(artist)
        val al = clean(album)
        val g = clean(genre)
        val pl = clean(playlist)
        // Words of its own first: "my liked songs" is the listener's, whatever kind the Assistant took it for.
        val own = pl.ifEmpty { text }
        if (LIKED.matches(own)) return VoiceRequest.Liked(shuffle)
        if (DOWNLOADS.matches(own)) return VoiceRequest.Downloads(shuffle)
        return when (focus) {
            FOCUS_ARTIST -> ar.ifEmpty { text }.takeIf { it.isNotEmpty() }?.let { VoiceRequest.Artist(it, shuffle) }
            FOCUS_ALBUM -> al.ifEmpty { text }.takeIf { it.isNotEmpty() }?.let { VoiceRequest.Album(it, ar.ifEmpty { null }, shuffle) }
            FOCUS_PLAYLIST -> pl.ifEmpty { text }.takeIf { it.isNotEmpty() }?.let { VoiceRequest.Playlist(it, shuffle) }
            FOCUS_SONG -> (if (t.isNotEmpty()) listOf(t, ar).filter { it.isNotEmpty() }.joinToString(" ") else text)
                .takeIf { it.isNotEmpty() }?.let { VoiceRequest.Song(it, shuffle) }
            FOCUS_GENRE -> g.ifEmpty { text }.takeIf { it.isNotEmpty() }?.let { VoiceRequest.Song(it, shuffle) }
            else -> null
        } ?: when {
            text.isNotEmpty() -> VoiceRequest.Top(text, shuffle)
            // No words, but the parts were made out ("play Midnights by Taylor Swift" sent in pieces).
            al.isNotEmpty() -> VoiceRequest.Album(al, ar.ifEmpty { null }, shuffle)
            t.isNotEmpty() -> VoiceRequest.Song(listOf(t, ar).filter { it.isNotEmpty() }.joinToString(" "), shuffle)
            ar.isNotEmpty() -> VoiceRequest.Artist(ar, shuffle)
            pl.isNotEmpty() -> VoiceRequest.Playlist(pl, shuffle)
            g.isNotEmpty() -> VoiceRequest.Song(g, shuffle)
            shuffle -> VoiceRequest.Liked(true)
            else -> VoiceRequest.Resume
        }
    }

    /** What was heard, without "on Prism" (when the Assistant leaves it in) or a leading "play". */
    private fun clean(s: String?): String {
        var t = s?.trim().orEmpty()
        if (t.isEmpty()) return t
        t = t.replace(ON_PRISM, "").replace(PLAY, "").trim()
        return t.replace(SPACES, " ")
    }

    /** Whether [name] is the playlist the listener named ([spoken]): the same words, or all of them in it. */
    fun names(name: String, spoken: String): Int {
        val a = words(name)
        val b = words(spoken)
        if (a.isEmpty() || b.isEmpty()) return 0
        return when {
            a == b -> 3
            a.joinToString("") == b.joinToString("") -> 3
            a.containsAll(b) -> 2
            b.containsAll(a) && a.size >= 2 -> 1
            else -> 0
        }
    }

    private fun words(s: String): List<String> =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(DIACRITICS, "").split(NOT_WORD).filter { it.isNotEmpty() && it !in FILLER }

    private val FILLER = setOf("my", "the", "playlist", "a")
    private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")
    private val ON_PRISM = Regex("(^|\\s+)(on|in|from|with|using)\\s+prism(\\s+music)?\\s*$", RegexOption.IGNORE_CASE)
    private val PLAY = Regex("^play\\s+", RegexOption.IGNORE_CASE)
    private val SHUFFLE = Regex("^shuffle\\b\\s*|\\s*\\bon shuffle$|\\s*\\bshuffled$", RegexOption.IGNORE_CASE)
    private val LIKED = Regex(
        "^(my\\s+)?((liked|favou?rite|loved)\\s+(songs|music|tracks)|likes|favou?rites|(songs|music)\\s+i\\s+(like|liked|love))$",
        RegexOption.IGNORE_CASE,
    )
    private val DOWNLOADS = Regex("^(my\\s+)?(downloads|downloaded\\s+(songs|music)|offline\\s+(songs|music))$", RegexOption.IGNORE_CASE)
}
