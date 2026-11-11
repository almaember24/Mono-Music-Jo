package com.prism.music.data.local

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Library → Local music: every music file on the phone (MP3, M4A, FLAC, OGG, WAV…), as Prism songs that play from
 * the file. What a song is comes from the file's own tags (title, artist, album, track number) and its album's
 * artwork; nothing is looked up online for it, and Prism never moves or deletes the files. Local songs' ids are
 * `local:<MediaStore id>` ([Song.isLocal]).
 */
class LocalMusic(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Folders left out (#207, #249), by [folderOf]: their files and those below them aren't read or listed. */
    private val skipFolders: () -> Set<String> = { emptySet() },
) {
    data class Track(
        val song: Song,
        val uri: Uri,
        val albumId: Long,
        val trackNumber: Int,
        val year: Int,
        val addedAt: Long,
        val mime: String,
        val size: Long,
    )

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    /** Every music file found in the last scan, newest first. */
    val tracks: StateFlow<List<Track>> = _tracks
    private val _scanned = MutableStateFlow(false)
    /** True once a scan has run with the permission (so an empty list means there's no music, not no access). */
    val scanned: StateFlow<Boolean> = _scanned
    private val _folders = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Every folder with music in it ([folderOf]) and how many files it holds itself, left out or not. */
    val folders: StateFlow<Map<String, Int>> = _folders
    @Volatile private var byId: Map<String, Track> = emptyMap()
    private var pending: Job? = null
    private var watching = false
    // One at a time, so the last one asked for is the last one shown (a folder ticked while another scan is running).
    private val scanning = Mutex()

    fun hasPermission() = context.checkSelfPermission(LocalLossless.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The file a local song plays from. */
    fun uriFor(id: String): Uri? = byId[id]?.uri
        ?: id.removePrefix(Song.LOCAL_PREFIX).toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }

    fun track(id: String): Track? = byId[id]

    /** Scans now, then again whenever Android's music files change. */
    fun start() {
        scope.launch { scan() }
        if (watching) return
        watching = true
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // Copying an album fires a burst of changes: wait for it to settle, then scan once.
                pending?.cancel()
                pending = scope.launch { delay(5_000); scan() }
            }
        }
        runCatching { context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer) }
    }

    /** The folders to leave out changed: the list follows. Nothing happens to the files. */
    fun rescan() {
        pending?.cancel()
        pending = scope.launch { scan() }
    }

    suspend fun scan() = scanning.withLock { scanNow() }

    private suspend fun scanNow() = withContext(Dispatchers.IO) {
        if (!hasPermission()) { _scanned.value = false; return@withContext }
        val out = ArrayList<Track>()
        val skips = skipFolders()
        val inFolders = HashMap<String, Int>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE,
            // The folder: Android names an untagged song's album after it (#143).
            if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA,
            // Which storage it's on (a memory card's folder isn't the phone's of the same name).
            if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.VOLUME_NAME else MediaStore.Audio.Media._ID,
        )
        val folders = HashMap<Long, String>()
        // Music only (not ringtones, recordings or podcasts' downloads), and long enough to be a song. Some FLACs come in
        // with no length or not marked as music (Reddit, 1.7.6): FLAC is never a ringtone or a recording, so it's taken
        // either way, and a file with no length has it read from the file below.
        val flac = "${MediaStore.Audio.Media.MIME_TYPE} IN ('audio/flac', 'audio/x-flac')"
        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR $flac) AND " +
            "(${MediaStore.Audio.Media.DURATION} >= ? OR ${MediaStore.Audio.Media.DURATION} IS NULL OR ${MediaStore.Audio.Media.DURATION} = 0)"
        // Whether each album has artwork, looked at once per album (asking for art that isn't there fails every time the
        // notification or a list asks, so songs without any get none).
        val art = HashMap<Long, String?>()
        fun artFor(albumId: Long): String? = if (albumId <= 0) null else art.getOrPut(albumId) {
            val uri = "content://media/external/audio/albumart/$albumId"
            uri.takeIf { runCatching { context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.close() != null }.getOrDefault(false) }
        }
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, arrayOf(MIN_DURATION_MS.toString()),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val folder = folderOf(c.getString(11), if (android.os.Build.VERSION.SDK_INT >= 29) c.getString(12) else null)
                    if (folder.isNotEmpty()) inFolders[folder] = (inFolders[folder] ?: 0) + 1
                    if (isSkipped(folder, skips)) continue
                    val albumId = c.getLong(4)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val lengthMs = c.getLong(5).takeIf { it > 0 } ?: lengthOf(uri, "$id:${c.getLong(10)}") ?: continue
                    if (lengthMs < MIN_DURATION_MS) continue
                    val song = Song(
                        id = Song.LOCAL_PREFIX + id,
                        title = title,
                        artists = artistsOf(c.getString(2)),
                        album = c.getString(3)?.takeIf { it.isNotBlank() && it != UNKNOWN }?.let { AlbumRef(it) },
                        durationSec = (lengthMs / 1000).toInt(),
                    )
                    out += Track(
                        song, uri, albumId,
                        // MediaStore's TRACK is disc × 1000 + track.
                        c.getInt(6) % 1000, c.getInt(7), c.getLong(8) * 1000, c.getString(9) ?: "", c.getLong(10),
                    )
                    folderName(c.getString(11))?.let { folders[id] = it }
                }
            }
        }.onFailure { Log.w(TAG, "scan failed", it) }
        _folders.value = inFolders
        // Songs whose "album" is only their folder's name (no album tag): shown without an album, and never given
        // another file's cover as "the album's" (#143: M4As in a "Quick Share" folder all showing one song's cover).
        untagged(out, folders).takeIf { it.isNotEmpty() }?.let { ids ->
            out.replaceAll { t -> if (t.song.id in ids) t.copy(song = t.song.copy(album = null)) else t }
        }
        // The list shows as soon as the files are known; covers follow (opening every album's artwork took long
        // enough on a big library that the page sat on its spinner).
        publish(out)
        _scanned.value = true
        Log.i(TAG, "${out.size} music files")
        // The album's artwork as Android serves it (from the files' embedded covers): it opens as an image, so Coil,
        // the media notification and the car can all show it. Android files untagged songs from one folder (and other
        // mixes) under one album, whose artwork is then one file's cover for all of them (#129): those songs read their
        // own covers instead.
        val mixed = mixedAlbums(out)
        val withArt = out.map { t ->
            if (t.albumId in mixed) t else artFor(t.albumId)?.let { t.copy(song = t.song.copy(thumbnail = it)) } ?: t
        }
        if (art.values.any { it != null }) publish(withArt)
        val filled = fillEmbeddedArt(withArt, mixed)
        // A song in a real album (a compilation) with no cover of its own still shows the album's.
        val rest = filled.map { t ->
            if (t.song.thumbnail != null || t.albumId !in mixed || t.song.album == null) t
            else artFor(t.albumId)?.let { t.copy(song = t.song.copy(thumbnail = it)) } ?: t
        }
        if (rest != filled) publish(rest)
    }

    private fun publish(tracks: List<Track>) {
        byId = tracks.associateBy { it.song.id }
        _tracks.value = tracks
    }

    private val albumTags by lazy { context.getSharedPreferences("local_album_tags", Context.MODE_PRIVATE) }
    private val lengths by lazy { context.getSharedPreferences("local_lengths", Context.MODE_PRIVATE) }

    /** A file's length when Android doesn't know it (some FLACs), read from the file once and remembered; 0: unreadable. */
    private fun lengthOf(uri: Uri, key: String): Long? {
        if (lengths.contains(key)) return lengths.getLong(key, 0).takeIf { it > 0 }
        val ms = runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally { r.release() }
        }.getOrNull() ?: 0L
        lengths.edit().putLong(key, ms).apply()
        return ms.takeIf { it > 0 }
    }

    /**
     * Ids of songs with no album tag of their own: of the [untaggedCandidates], the ones whose file has no album in it,
     * read once per file (by id and size) and remembered.
     */
    private fun untagged(tracks: List<Track>, folders: Map<Long, String>): Set<String> {
        val out = HashSet<String>()
        val learned = HashMap<String, Boolean>()
        var reads = 0
        for (t in untaggedCandidates(tracks, folders)) {
            val key = "${t.song.id.removePrefix(Song.LOCAL_PREFIX)}:${t.size}"
            val tagged = if (albumTags.contains(key)) albumTags.getBoolean(key, true) else {
                if (reads >= MAX_COVER_READS) continue
                reads++
                runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(context, t.uri)
                        !r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).isNullOrBlank()
                    } finally { r.release() }
                }.getOrDefault(true).also { learned[key] = it }
            }
            if (!tagged) out += t.song.id
        }
        if (learned.isNotEmpty()) albumTags.edit().apply { learned.forEach { (k, v) -> putBoolean(k, v) } }.apply()
        return out
    }

    private val artDir by lazy { java.io.File(context.filesDir, "artwork/local").apply { mkdirs() } }
    private val noArt by lazy { context.getSharedPreferences("local_no_art", Context.MODE_PRIVATE) }

    /**
     * Songs whose album Android has no artwork for: the cover embedded in the file itself, read once and kept (#54), so
     * they show their own cover (offline, of course) instead of none. Files with no cover in them are remembered.
     */
    private fun fillEmbeddedArt(tracks: List<Track>, mixed: Set<Long>): List<Track> {
        val missing = tracks.filter { it.song.thumbnail == null }
        if (missing.isEmpty()) return tracks
        val found = HashMap<String, String>()
        val none = ArrayList<String>()
        var reads = 0
        for (t in missing) {
            // One cover per album, except in mixed albums (see [mixedAlbums]), where each song keeps its own.
            val key = if (t.albumId > 0 && t.albumId !in mixed) "a${t.albumId}" else "s${t.song.id.removePrefix(Song.LOCAL_PREFIX)}"
            val file = java.io.File(artDir, "$key.jpg")
            if (!file.exists()) {
                // Files are opened a few hundred a scan at most; the rest follow on the next scans.
                if (key in none || noArt.contains(key) || reads >= MAX_COVER_READS) continue
                reads++
                val bytes = runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try { r.setDataSource(context, t.uri); r.embeddedPicture } finally { r.release() }
                }.getOrNull()
                if (bytes == null || bytes.isEmpty()) { none += key; continue }
                runCatching { file.writeBytes(bytes) }.onFailure { continue }
            }
            found[t.song.id] = Uri.fromFile(file).toString()
        }
        if (none.isNotEmpty()) noArt.edit().apply { none.forEach { putBoolean(it, true) } }.apply()
        if (found.isEmpty()) return tracks
        return tracks.map { t -> found[t.song.id]?.let { t.copy(song = t.song.copy(thumbnail = it)) } ?: t }.also(::publish)
    }

    companion object {
        private const val TAG = "PrismLocal"
        private const val MIN_DURATION_MS = 20_000L
        private const val MAX_COVER_READS = 400
        private const val UNKNOWN = "<unknown>"

        /**
         * Album ids whose one artwork may be wrong for some of its songs (#129): a song with no album tag (Android groups
         * those by folder), songs naming different albums, or different artists under one album name (a downloader's
         * catch-all "album"). Their songs read their own embedded covers; a real compilation's files all carry its cover,
         * so it looks the same, read once per song instead of once per album.
         */
        internal fun mixedAlbums(tracks: List<Track>): Set<Long> =
            tracks.filter { it.albumId > 0 }.groupBy { it.albumId }.filterValues { songs ->
                songs.size > 1 && (songs.any { it.song.album == null } ||
                    songs.mapTo(HashSet()) { it.song.album?.title?.trim()?.lowercase() }.size > 1 ||
                    songs.mapTo(HashSet()) { it.song.artists.firstOrNull()?.name?.trim()?.lowercase() }.size > 1)
            }.keys

        /**
         * Songs that may have no album tag (#143): Android then names the album after the song's folder ("Quick Share",
         * "Download"), so an album named exactly like the folder, on a song with no track number and no year (an
         * untagged file has neither), is checked against the file itself. A tagged album in a folder of its own name
         * nearly always has track numbers, so it's rarely read at all.
         */
        internal fun untaggedCandidates(tracks: List<Track>, folders: Map<Long, String>): List<Track> = tracks.filter { t ->
            val album = t.song.album?.title ?: return@filter false
            val folder = folders[t.song.id.removePrefix(Song.LOCAL_PREFIX).toLongOrNull()] ?: return@filter false
            t.trackNumber <= 0 && t.year <= 0 && album.trim().equals(folder.trim(), ignoreCase = true)
        }

        /** The last folder of a MediaStore RELATIVE_PATH ("Download/Quick Share/") or file path (".../Quick Share/a.m4a"). */
        internal fun folderName(path: String?): String? {
            if (path.isNullOrBlank()) return null
            val parts = path.trimEnd('/').split('/')
            // A file path ends with the file's name; a relative path ends with the folder.
            val folder = if (path.endsWith("/")) parts.lastOrNull() else parts.getOrNull(parts.size - 2)
            return folder?.takeIf { it.isNotBlank() }
        }

        /**
         * A file's folder, the same way on every Android: "Music/Audiobooks/" on the phone's own storage, with the
         * storage in front on any other ("1234-ABCD:Music/"). [path] is RELATIVE_PATH (a folder, with [volume]) on Android
         * 10 and up, and below that the file's whole path. "": at the top of a storage, which can't be left out.
         */
        internal fun folderOf(path: String?, volume: String?): String {
            if (path.isNullOrBlank()) return ""
            if (volume != null) {
                val rel = path.trim('/').let { if (it.isEmpty()) return "" else "$it/" }
                return if (volume == "external_primary") rel else "$volume:$rel"
            }
            val parent = path.substringBeforeLast('/', "")
            val own = parent.removePrefix("/storage/emulated/0")
            if (own != parent) return own.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            val card = Regex("^/storage/([^/]+)/?(.*)$").find(parent) ?: return parent.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            return if (card.groupValues[2].isEmpty()) "" else "${card.groupValues[1]}:${card.groupValues[2].trim('/')}/"
        }

        /** Whether [folder] is one of [skips] or inside one. Folder names' capitals don't count on phone storage. */
        internal fun isSkipped(folder: String, skips: Set<String>): Boolean =
            folder.isNotEmpty() && skips.any { folder.startsWith(it, ignoreCase = true) }

        /** A row of the folder list: [songs] counts those below it too. */
        data class FolderRow(val path: String, val name: String, val depth: Int, val songs: Int, val skipped: Boolean, val parentSkipped: Boolean)

        /**
         * The folders with music in them as a tree in order, each under the folders above it, so a whole "Music/Old/" can
         * be left out when its files are all further down. A folder left out earlier that's gone from the phone is listed
         * as well (with no songs), so it can be ticked back.
         */
        internal fun folderRows(counts: Map<String, Int>, skips: Set<String>): List<FolderRow> {
            val totals = java.util.TreeMap<String, Int>(String.CASE_INSENSITIVE_ORDER)
            fun storage(p: String) = p.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }
            for ((path, n) in counts) {
                var at = path
                while (at.isNotEmpty()) {
                    totals[at] = (totals[at] ?: 0) + n
                    val body = at.removePrefix(storage(at)).trimEnd('/')
                    at = if ('/' in body) storage(at) + body.substringBeforeLast('/') + "/" else ""
                }
            }
            skips.forEach { if (it !in totals) totals[it] = 0 }
            return totals.map { (path, n) ->
                val body = path.removePrefix(storage(path)).trimEnd('/')
                val depth = body.count { it == '/' }
                FolderRow(
                    // A memory card's top folders say so ("1234-ABCD: Music").
                    path, if (depth == 0 && storage(path).isNotEmpty()) "${storage(path)} $body" else body.substringAfterLast('/'), depth, n,
                    skipped = isSkipped(path, skips),
                    parentSkipped = skips.any { !it.equals(path, ignoreCase = true) && path.startsWith(it, ignoreCase = true) },
                )
            }
        }

        /** "A; B" or "A / B" in a tag are several artists; "<unknown>" is none. */
        fun artistsOf(tag: String?): List<ArtistRef> {
            if (tag.isNullOrBlank() || tag == UNKNOWN) return emptyList()
            return tag.split(";", " / ", "\u0000").map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { ArtistRef(it) }
        }
    }
}
