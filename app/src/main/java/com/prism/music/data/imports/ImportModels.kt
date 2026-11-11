package com.prism.music.data.imports

import kotlinx.serialization.Serializable

/** Where an imported history came from (1.8.1). [key] is what's stored with each play: never rename one. */
enum class ImportSource(val key: String, private val name_: String) {
    SPOTIFY("spotify", "Spotify"),
    TAKEOUT("takeout", "YouTube Music"),
    LASTFM("lastfm", "Last.fm"),
    /** Recently played, read from the listener's own Spotify connection (1.8.1, "bring your own key"). */
    SPOTIFY_LIVE("spotify_live", ""),
    /** Songs newly at the top of the account's YouTube Music History page, read every few hours ([YouTubeLive]). */
    YOUTUBE_LIVE("youtube_live", "");

    /** Shown to the listener: the services' own names, and the live ones ("Spotify (live)") in the listener's language. */
    val label: String get() = when (this) {
        SPOTIFY_LIVE -> com.prism.music.tr(com.prism.music.R.string.spotify_live_source)
        YOUTUBE_LIVE -> com.prism.music.tr(com.prism.music.R.string.youtube_live_source)
        else -> name_
    }

    /** Read every few hours on its own once turned on, rather than brought in from a file or an account. */
    val isLive: Boolean get() = this == SPOTIFY_LIVE || this == YOUTUBE_LIVE

    companion object {
        fun of(key: String): ImportSource? = entries.firstOrNull { it.key == key }
    }
}

/** How far a song's match got ([com.prism.music.data.db.ImportMatchEntity.status]). */
enum class MatchStatus {
    /** Not looked up yet. */
    PENDING,
    /** Found, and sure of it. */
    SURE,
    /** Something was found, but it may be another version or length: the listener picks ("Needs a look"). */
    REVIEW,
    /** Picked by the listener. */
    FIXED,
    /** Nothing found; tried again in a week. */
    NONE,
    /** The listener said none of the candidates is it. */
    SKIPPED;

    val matched: Boolean get() = this == SURE || this == FIXED

    companion object {
        fun of(s: String?): MatchStatus = entries.firstOrNull { it.name == s } ?: PENDING
    }
}

/**
 * One play read from a file, before it's stored. [endMs]-style or start-style times as the source has them (see
 * [com.prism.music.data.db.ImportedPlayEntity.timestamp]). [fullPlay]: it ran to the song's end, so [playedMs] is the
 * song's length (Spotify's "trackdone").
 */
data class ParsedPlay(
    val timestamp: Long,
    val playedMs: Long,
    val title: String,
    val artist: String,
    val album: String? = null,
    val sourceId: String? = null,
    val skipped: Boolean = false,
    val fullPlay: Boolean = false,
    val estimated: Boolean = false,
    /** A YouTube video id the source names (Google Takeout). */
    val youtubeId: String? = null,
)

/** A song in an imported library (liked, or in a playlist). */
@Serializable
data class LibraryTrack(val title: String, val artist: String, val album: String? = null, val sourceId: String? = null) {
    val key: String get() = ImportKeys.songKey(title, artist)
}

/** [uri]: Spotify's own id for the playlist, when the download names it (a newer download then finds it if renamed). */
@Serializable
data class LibraryPlaylist(val name: String, val tracks: List<LibraryTrack>, val uri: String? = null)

@Serializable
data class LibraryAlbum(val title: String, val artist: String)

/** What a Spotify data download holds besides plays: Liked Songs, playlists, saved albums, followed artists. */
@Serializable
data class ParsedLibrary(
    val liked: List<LibraryTrack> = emptyList(),
    val playlists: List<LibraryPlaylist> = emptyList(),
    val albums: List<LibraryAlbum> = emptyList(),
    val artists: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = liked.isEmpty() && playlists.isEmpty() && albums.isEmpty() && artists.isEmpty()

    operator fun plus(o: ParsedLibrary) = ParsedLibrary(
        (liked + o.liked).distinctBy { it.key }, playlists + o.playlists,
        (albums + o.albums).distinctBy { ImportKeys.norm(it.title) + "|" + ImportKeys.norm(it.artist) }, (artists + o.artists).distinctBy { ImportKeys.norm(it) },
    )
}

/** What a picked file (or zip) holds, counted before anything is brought in. */
data class ImportPreview(
    val source: ImportSource,
    val plays: Int = 0,
    val songs: Int = 0,
    val firstAt: Long? = null,
    val lastAt: Long? = null,
    val library: ParsedLibrary = ParsedLibrary(),
    /** Files read and what they were, for the screen to say ("Extended streaming history"). */
    val kinds: Set<FileKind> = emptySet(),
    /** Files in it Prism doesn't read (a podcast history, an HTML Takeout). */
    val skippedFiles: Int = 0,
    /** Plays not brought in before (a repeat import: an earlier import's plays, to about the minute, are passed over). */
    val newPlays: Int = plays,
    val newFirstAt: Long? = firstAt,
    val newLastAt: Long? = lastAt,
    /** When this source was last brought in; null for a first import. */
    val lastImportAt: Long? = null,
    /** What of the library is new to Prism (a first import: all of it). */
    val libraryNews: LibraryNews = LibraryNews.of(library),
) {
    val isEmpty: Boolean get() = plays == 0 && library.isEmpty

    /** A repeat import: this source was brought in before. */
    val isRepeat: Boolean get() = lastImportAt != null

    /** An older or the same download again: nothing in it that isn't here. */
    val nothingNew: Boolean get() = isRepeat && !isEmpty && newPlays == 0 && libraryNews.isEmpty
}

/**
 * What a download's library adds to what earlier imports brought ([LibraryJob.news]): Liked Songs not liked yet,
 * playlists not brought before, new songs of ones that were, albums and artists not saved or starred yet.
 */
data class LibraryNews(
    val liked: Int = 0,
    val playlists: Int = 0,
    /** New songs of playlists an earlier import made, and how many playlists get them. */
    val playlistSongs: Int = 0,
    val playlistsGrowing: Int = 0,
    val albums: Int = 0,
    val artists: Int = 0,
) {
    val isEmpty: Boolean get() = liked == 0 && playlists == 0 && playlistSongs == 0 && albums == 0 && artists == 0

    companion object {
        fun of(lib: ParsedLibrary) = LibraryNews(lib.liked.size, lib.playlists.size, 0, 0, lib.albums.size, lib.artists.size)
    }
}

/** The kinds of file a data download holds that Prism reads. */
enum class FileKind { SPOTIFY_EXTENDED, SPOTIFY_ACCOUNT, SPOTIFY_LIBRARY, SPOTIFY_PLAYLISTS, TAKEOUT_HISTORY }
