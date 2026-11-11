package com.prism.music.data.spotify

import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.imports.HistoryImporter
import com.prism.music.data.imports.MatchResult
import com.prism.music.data.imports.TrackToMatch
import com.prism.music.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One of the listener's Spotify playlists as last read, and the Prism playlist that shows it. */
@Serializable
data class MirroredPlaylist(
    val playlist: SpotifyPlaylist,
    val tracks: List<SpotifyTrack> = emptyList(),
    val prismId: String? = null,
    /** The listener deleted its Prism playlist: it isn't made again. */
    val removed: Boolean = false,
    val readAt: Long = 0,
)

@Serializable
data class PlaylistMirror(
    val lists: List<MirroredPlaylist> = emptyList(),
    /** Playlists the listener follows but doesn't own: Development Mode apps can't read their songs (February 2026). */
    val notYours: Int = 0,
    val listedAt: Long = 0,
)

/**
 * "Your Spotify playlists" (1.8.1): the playlists the listener owns or collaborates on, each shown in Library as a
 * Prism playlist with a Spotify mark, whose songs are their YouTube Music matches in Spotify's order. "Keep in step"
 * reads a playlist again when it's opened (only when Spotify says it changed: its snapshot) and all of them daily on
 * Wi-Fi. Matches are remembered with every other import's, so a song is found once; opening a playlist matches its
 * songs at once, a few at a time, and the background matching does the rest.
 *
 * Followed playlists aren't mirrored: since February 2026 a Development Mode app gets only their names, not their songs.
 * Spotify's own mixes (Discover Weekly, Daily Mix…) are Spotify's playlists, so they can't be read either. They're
 * listed in Library all the same ([followed]), with how to bring one over as a copy of the listener's own.
 */
class SpotifyPlaylists(
    private val api: SpotifyApi,
    private val db: PrismDatabase,
    private val importer: HistoryImporter,
    private val dir: File,
    private val hands: Hands,
    /** Finds one song on YouTube Music (the import matcher, or a test's). */
    private val match: suspend (TrackToMatch) -> MatchResult,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /** Where the Prism playlists live (Prism's library; a test's own). */
    interface Hands {
        fun create(name: String, songs: List<Song>, description: String): String
        fun replace(id: String, songs: List<Song>)
        fun rename(id: String, name: String, description: String)
        fun exists(id: String): Boolean
        fun songIds(id: String): List<String>
        fun delete(id: String)
    }

    private val file = File(dir, "playlists.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    @Volatile private var cached: PlaylistMirror? = null

    fun mirror(): PlaylistMirror = cached ?: (runCatching { json.decodeFromString(PlaylistMirror.serializer(), file.readText()) }.getOrNull() ?: PlaylistMirror()).also { cached = it }

    /** The Prism playlists that show Spotify ones (for Library's mark). */
    fun prismIds(): Set<String> = mirror().lists.mapNotNull { it.prismId?.takeIf { _ -> !it.removed } }.toSet()

    fun isMirror(prismId: String) = prismId in prismIds()

    /** The mirrored playlists' songs: their matches stay when an import is removed ([com.prism.music.data.imports.HistoryImporter.remove]). */
    fun matchKeys(): Set<String> = mirror().lists.flatMap { l -> l.tracks.map(SpotifyQueue::key) }.toSet()

    private fun save(m: PlaylistMirror) {
        dir.mkdirs()
        val tmp = File(dir, "playlists.json.tmp")
        tmp.writeText(json.encodeToString(PlaylistMirror.serializer(), m))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        cached = m
    }

    /**
     * Reads the list of playlists and, of each that changed, its songs; lines the songs up for matching and brings the
     * Prism playlists up to date. [userId]: the connected account (its own playlists are the readable ones).
     */
    suspend fun refreshAll(userId: String, now: Long = System.currentTimeMillis()) = lock.withLock {
        val all = ArrayList<SpotifyPlaylist>()
        var page = SpotifyParse.playlists(api.get("/me/playlists", mapOf("limit" to "50")))
        all += page.items
        var pages = 1
        while (pages < MAX_LIST_PAGES) { val next = page.next ?: break; page = SpotifyParse.playlists(api.next(next)); all += page.items; pages++ }
        val (yours, others) = split(all, userId)
        val old = mirror().lists.associateBy { it.playlist.id }
        val refused = ArrayList<SpotifyPlaylist>()
        val lists = yours.mapNotNull { p ->
            val before = old[p.id]
            if (before != null && before.playlist.snapshot == p.snapshot && before.readAt > 0) return@mapNotNull before.copy(playlist = p)
            // One Spotify won't let this app read (a collaborative one it treats as another's): left out, the rest go on.
            val items = try { readItems(p.id) } catch (e: SpotifyError.Forbidden) { refused += p; return@mapNotNull before }
            MirroredPlaylist(p, items, before?.prismId, before?.removed ?: false, now)
        }
        // A playlist gone from Spotify (deleted there, or no longer shared with the listener) goes from Library too.
        old.values.filter { o -> lists.none { it.playlist.id == o.playlist.id } }.forEach { gone -> gone.prismId?.let { if (hands.exists(it)) hands.delete(it) } }
        SpotifyQueue.remember(db, lists.flatMap { it.tracks })
        save(PlaylistMirror(apply(lists), others.size + refused.size, now))
        // The ones whose songs Spotify keeps to itself: only named in Library (never Prism playlists).
        saveFollowed(others + refused.filter { r -> lists.none { it.playlist.id == r.id } })
    }

    // ---- Followed and saved playlists (1.8.1): listed, not mirrored

    private val followedFile = File(dir, "followed.json")
    private val _followed by lazy { MutableStateFlow(readFollowed()) }

    /**
     * The playlists the listener follows or saved (Lofi Girl's, Spotify's own "Made for you"…) as /me/playlists last
     * named them: since February 2026 Spotify answers 403 for their songs, so Library shows them with a note and the way
     * to bring one (a copy of your own), nothing more. Kept in files/spotify/followed.json (a small file, read once);
     * gone with [forget].
     */
    val followed: StateFlow<List<SpotifyPlaylist>> get() = _followed

    private fun readFollowed(): List<SpotifyPlaylist> =
        runCatching { json.decodeFromString(FOLLOWED, followedFile.readText()) }.getOrNull().orEmpty()

    private fun saveFollowed(list: List<SpotifyPlaylist>) {
        dir.mkdirs()
        val tmp = File(dir, "followed.json.tmp")
        tmp.writeText(json.encodeToString(FOLLOWED, list))
        if (!tmp.renameTo(followedFile)) { followedFile.delete(); tmp.renameTo(followedFile) }
        _followed.value = list
    }

    /**
     * "Keep in step" for one playlist being opened ([prismId], its Prism playlist): read again if Spotify says it
     * changed, a few of its songs matched now, the Prism playlist brought up to date.
     */
    suspend fun refreshOne(prismId: String, readAgain: Boolean, now: Long = System.currentTimeMillis()) = lock.withLock {
        val m = mirror()
        val at = m.lists.indexOfFirst { it.prismId == prismId }.takeIf { it >= 0 } ?: return@withLock
        var entry = m.lists[at]
        if (readAgain) {
            val snapshot = runCatching { SpotifyParse.playlistSnapshot(api.get("/playlists/${entry.playlist.id}", mapOf("fields" to "snapshot_id,name,description"))) }.getOrNull()
            if (snapshot != null && snapshot.first != entry.playlist.snapshot) {
                val p = entry.playlist.copy(snapshot = snapshot.first, name = snapshot.second ?: entry.playlist.name)
                entry = entry.copy(playlist = p, tracks = readItems(p.id), readAt = now)
                SpotifyQueue.remember(db, entry.tracks)
            }
        }
        matchNow(entry.tracks)
        val lists = m.lists.toMutableList().also { it[at] = entry }
        save(m.copy(lists = apply(lists)))
    }

    /** Songs matched since are put in their playlists (no request to Spotify). */
    suspend fun reapply() = lock.withLock {
        val m = mirror()
        if (m.lists.isNotEmpty()) save(m.copy(lists = apply(m.lists)))
    }

    /** The songs of one playlist, page by page (owned or collaborative ones only answer). */
    private suspend fun readItems(id: String): List<SpotifyTrack> {
        val out = ArrayList<SpotifyTrack>()
        var page = SpotifyParse.playlistItems(api.get("/playlists/$id/items", mapOf("limit" to "50", "additional_types" to "track", "fields" to ITEM_FIELDS)))
        out += page.items.filterNotNull()
        var pages = 1
        while (pages < MAX_ITEM_PAGES) { val next = page.next ?: break; page = SpotifyParse.playlistItems(api.next(next)); out += page.items.filterNotNull(); pages++ }
        return out
    }

    /** Matches up to [AT_ONCE_ON_OPEN] of [tracks] not looked up yet, now. */
    private suspend fun matchNow(tracks: List<SpotifyTrack>) {
        val pending = SpotifyQueue.pending(db, tracks.map(SpotifyQueue::key))
        val todo = tracks.filter { SpotifyQueue.key(it) in pending }.distinctBy(SpotifyQueue::key).take(AT_ONCE_ON_OPEN)
        for (t in todo) {
            val row = db.imports().match(SpotifyQueue.key(t)) ?: continue
            val r = runCatching { match(TrackToMatch(t.title, t.artist, t.album, t.durationMs, 0, t.isrc)) }.getOrNull() ?: continue
            importer.applyMatch(row, r)
            pause(MATCH_GAP_MS)
        }
    }

    /** The Prism playlists made or brought up to date from [lists]; returns them with their Prism ids. */
    private suspend fun apply(lists: List<MirroredPlaylist>): List<MirroredPlaylist> {
        val matched = SpotifyQueue.matched(db, lists.flatMap { l -> l.tracks.map(SpotifyQueue::key) })
        return lists.map { l ->
            if (l.removed) return@map l
            val songs = songsOf(l.tracks, matched)
            val id = l.prismId
            when {
                id == null -> l.copy(prismId = hands.create(l.playlist.name, songs, l.playlist.description))
                // The listener deleted it from Library: left deleted.
                !hands.exists(id) -> l.copy(removed = true)
                else -> {
                    if (hands.songIds(id) != songs.map { it.id }) hands.replace(id, songs)
                    hands.rename(id, l.playlist.name, l.playlist.description)
                    l
                }
            }
        }
    }

    /** Takes every Spotify playlist out of Library ([keep]: they stay as plain Prism playlists), and forgets them. */
    suspend fun forget(keep: Boolean) = lock.withLock {
        if (!keep) mirror().lists.forEach { l -> l.prismId?.let { if (hands.exists(it)) hands.delete(it) } }
        file.delete()
        cached = null
        followedFile.delete()
        _followed.value = emptyList()
    }

    companion object {
        private const val MAX_LIST_PAGES = 10
        private const val MAX_ITEM_PAGES = 40
        const val AT_ONCE_ON_OPEN = 25
        const val MATCH_GAP_MS = 400L
        /** Only what a song needs, from each item (both the new "item" and the old "track"). */
        private const val TRACK_FIELDS = "type,uri,name,duration_ms,explicit,is_local,external_ids(isrc),artists(name),album(name,images)"
        const val ITEM_FIELDS = "next,total,items(is_local,item($TRACK_FIELDS),track($TRACK_FIELDS))"
        private val FOLLOWED = kotlinx.serialization.builtins.ListSerializer(SpotifyPlaylist.serializer())

        /**
         * /me/playlists split into the ones Spotify lets this app read (the listener's own and collaborative ones) and
         * the rest (followed or saved: their songs answer 403), each in Spotify's order.
         */
        fun split(all: List<SpotifyPlaylist>, userId: String): Pair<List<SpotifyPlaylist>, List<SpotifyPlaylist>> =
            all.partition { it.ownerId == userId || it.collaborative }

        /** Where Spotify's app opens a playlist (it catches the link; a browser shows the web player otherwise). */
        fun openLink(id: String) = "https://open.spotify.com/playlist/$id"

        /** [tracks]' matched songs in their order, each once. */
        fun songsOf(tracks: List<SpotifyTrack>, matched: Map<String, Song>): List<Song> =
            tracks.mapNotNull { matched[SpotifyQueue.key(it)] }.distinctBy { it.id }
    }
}
