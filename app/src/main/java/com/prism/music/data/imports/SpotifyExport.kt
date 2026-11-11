package com.prism.music.data.imports

import com.prism.music.data.imports.ImportFiles.bool
import com.prism.music.data.imports.ImportFiles.long
import com.prism.music.data.imports.ImportFiles.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Spotify's data download (1.8.1), from its Privacy page: "Account data" (a year of plays, the library, playlists) or
 * "Extended streaming history" (every play since the account began). Read as a zip, or the files taken out of it:
 *
 * - Extended history, `Streaming_History_Audio_*.json` (older: `endsong_*.json`): `ts` (when the play ended, UTC),
 *   `ms_played`, `master_metadata_track_name`, `master_metadata_album_artist_name`, `master_metadata_album_album_name`,
 *   `spotify_track_uri`, `reason_start`, `reason_end`, `skipped`… Podcast and audiobook plays have no track: left out.
 * - Account data's history, `StreamingHistory_music_*.json` (older: `StreamingHistory*.json`): `endTime`
 *   ("2023-01-04 21:15", UTC), `artistName`, `trackName`, `msPlayed`. Podcasts' own files are left out.
 * - `YourLibrary.json`: `tracks` (Liked Songs), `albums`, `artists` (followed).
 * - `Playlist*.json`: `playlists` [{name, items: [{track: {trackName, artistName, albumName, trackUri}}]}] (some
 *   downloads also give a playlist's `uri`).
 *
 * Where a download holds both histories, the extended one is the whole story and the account data's (the same plays
 * again, to the minute) is passed over: [Options.accountHistory].
 */
object SpotifyExport {
    data class Options(val history: Boolean = true, val accountHistory: Boolean = true, val library: Boolean = true)

    data class Result(val library: ParsedLibrary, val kinds: Set<FileKind>, val skippedFiles: Int)

    fun kindOf(file: String): FileKind? {
        val n = file.substringAfterLast('/').lowercase()
        if (!n.endsWith(".json")) return null
        return when {
            n.startsWith("streaming_history_audio") || n.startsWith("endsong") -> FileKind.SPOTIFY_EXTENDED
            n.startsWith("streaminghistory_music") -> FileKind.SPOTIFY_ACCOUNT
            // Podcasts and video histories aren't music.
            n.startsWith("streaminghistory_podcast") || n.startsWith("streaming_history_video") -> null
            n.startsWith("streaminghistory") -> FileKind.SPOTIFY_ACCOUNT
            n == "yourlibrary.json" -> FileKind.SPOTIFY_LIBRARY
            n.startsWith("playlist") -> FileKind.SPOTIFY_PLAYLISTS
            else -> null
        }
    }

    /** Reads one picked file or zip ([name] is its name), each play to [onPlay]; what's in the library comes back. */
    fun read(name: String, input: InputStream, options: Options = Options(), onPlay: (ParsedPlay) -> Unit): Result {
        var library = ParsedLibrary()
        val kinds = mutableSetOf<FileKind>()
        var skipped = 0
        ImportFiles.forEachFile(name, input) { file, stream ->
            when (val kind = kindOf(file)) {
                FileKind.SPOTIFY_EXTENDED -> { kinds += kind; if (options.history) ImportFiles.arrayItems(stream).forEach { o -> extended(o)?.let(onPlay) } }
                FileKind.SPOTIFY_ACCOUNT -> { kinds += kind; if (options.history && options.accountHistory) ImportFiles.arrayItems(stream).forEach { o -> account(o)?.let(onPlay) } }
                FileKind.SPOTIFY_LIBRARY -> { kinds += kind; if (options.library) library += yourLibrary(ImportFiles.whole(stream) as? JsonObject) }
                FileKind.SPOTIFY_PLAYLISTS -> { kinds += kind; if (options.library) library += playlists(ImportFiles.whole(stream) as? JsonObject) }
                else -> if (file.endsWith(".json", ignoreCase = true)) skipped++
            }
        }
        return Result(library, kinds, skipped)
    }

    /** An extended-history play; null for podcasts, audiobooks and entries without a song. */
    internal fun extended(o: JsonObject): ParsedPlay? {
        val title = o.str("master_metadata_track_name") ?: return null
        val artist = o.str("master_metadata_album_artist_name") ?: return null
        val end = o.str("ts")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
        val ms = (o.long("ms_played") ?: return null).coerceAtLeast(0)
        val reasonEnd = o.str("reason_end")
        return ParsedPlay(
            timestamp = end, playedMs = ms, title = title, artist = artist, album = o.str("master_metadata_album_album_name"),
            sourceId = o.str("spotify_track_uri"),
            skipped = o.bool("skipped") == true || reasonEnd == "fwdbtn",
            // Ran to its end: what it played is the song's whole length.
            fullPlay = reasonEnd == "trackdone",
        )
    }

    private val ACCOUNT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** An account-data play ("endTime" to the minute, UTC). */
    internal fun account(o: JsonObject): ParsedPlay? {
        val title = o.str("trackName") ?: return null
        val artist = o.str("artistName") ?: return null
        val end = o.str("endTime")?.let { t ->
            runCatching { LocalDateTime.parse(t.take(16), ACCOUNT_TIME).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
                ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
        } ?: return null
        val ms = (o.long("msPlayed") ?: return null).coerceAtLeast(0)
        // "Unknown Track" by "Unknown Artist": a file on the phone Spotify knew nothing about.
        if (title == "Unknown Track" && artist == "Unknown Artist") return null
        return ParsedPlay(timestamp = end, playedMs = ms, title = title, artist = artist)
    }

    internal fun yourLibrary(o: JsonObject?): ParsedLibrary {
        o ?: return ParsedLibrary()
        val tracks = (o["tracks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { t ->
            val title = t.str("track") ?: return@mapNotNull null
            val artist = t.str("artist") ?: return@mapNotNull null
            LibraryTrack(title, artist, t.str("album"), t.str("uri"))
        }
        val albums = (o["albums"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { a ->
            LibraryAlbum(a.str("album") ?: return@mapNotNull null, a.str("artist") ?: return@mapNotNull null)
        }
        val artists = (o["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") }
        return ParsedLibrary(tracks.distinctBy { it.key }, emptyList(), albums, artists.distinct())
    }

    internal fun playlists(o: JsonObject?): ParsedLibrary {
        val lists = (o?.get("playlists") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { p ->
            val name = p.str("name") ?: return@mapNotNull null
            val tracks = (p["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { item ->
                // Episodes and the phone's own files ("localTrack") aren't songs to find.
                val t = item["track"] as? JsonObject ?: return@mapNotNull null
                LibraryTrack(t.str("trackName") ?: return@mapNotNull null, t.str("artistName") ?: return@mapNotNull null, t.str("albumName"), t.str("trackUri"))
            }
            // Its "uri", when a download has one: a renamed playlist is still the same one.
            LibraryPlaylist(name, tracks, p.str("uri"))
        }
        return ParsedLibrary(playlists = lists)
    }
}
