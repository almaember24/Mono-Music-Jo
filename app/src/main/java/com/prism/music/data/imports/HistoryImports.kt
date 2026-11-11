package com.prism.music.data.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.prism.music.AppContainer
import com.prism.music.data.FavoriteArtist
import com.prism.music.data.SavedAlbum
import com.prism.music.data.db.toEntity
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.AudioDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Bring your history" (1.8.1): histories and libraries from Spotify's data download, Google Takeout and Last.fm, read
 * on the phone, kept apart from Prism's own plays, matched to YouTube Music songs in the background, and counted where
 * the listener says. Nothing leaves the phone but the song searches matching needs (anonymous, as Prism's search) and
 * Last.fm's own answers.
 *
 * The listener's choices live in the "imports" preferences (in backups with the history): which sources Replay and
 * Rewind count (none by default: Prism's plays only), whether imported plays shape the mixes (turned on by the first
 * import, then the listener's), whether matching may use mobile data (no).
 */
class HistoryImports(private val c: AppContainer) {
    private val prefs = c.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val importer = HistoryImporter(c.db)
    private val jobFile = File(c.app.filesDir, "imports/library.json")

    private val _counted = MutableStateFlow(ImportSource.entries.filter { prefs.getBoolean(countKey(it), false) }.toSet())
    /** The imported sources Replay and Rewind count besides Prism's own plays. */
    val counted: StateFlow<Set<ImportSource>> = _counted

    private val _teach = MutableStateFlow(prefs.getBoolean(TEACH, false))
    /** Imported plays shape the mixes, radio and Quick picks. */
    val teach: StateFlow<Boolean> = _teach

    private val _matchOnMobile = MutableStateFlow(prefs.getBoolean(MOBILE, false))
    val matchOnMobile: StateFlow<Boolean> = _matchOnMobile

    fun setCounted(source: ImportSource, on: Boolean) {
        prefs.edit().putBoolean(countKey(source), on).apply()
        _counted.value = if (on) _counted.value + source else _counted.value - source
    }

    fun setTeach(on: Boolean) {
        prefs.edit().putBoolean(TEACH, on).putBoolean(TEACH_SET, true).apply()
        _teach.value = on
        c.scope.launch(Dispatchers.IO) { runCatching { c.forYou.refresh(force = true) } }
    }

    private val _likeOnAccount = MutableStateFlow(prefs.getBoolean(LIKE_ON_ACCOUNT, false))
    /** Imported Liked Songs are liked on the YouTube Music account too (signed in; off unless asked for, owner 1.8.1). */
    val likeOnAccount: StateFlow<Boolean> = _likeOnAccount

    fun setLikeOnAccount(on: Boolean) {
        prefs.edit().putBoolean(LIKE_ON_ACCOUNT, on).apply()
        _likeOnAccount.value = on
    }

    /**
     * Imported likes kept in Prism only (not on the account, or the account's like failed): library sync leaves them
     * liked, as it does local files' likes, although YouTube's liked list doesn't have them.
     */
    fun keptLikes(): Set<String> = prefs.getStringSet(KEPT_LIKES, emptySet()).orEmpty()

    /**
     * Likes [songs] here as imported likes are (the account too only when "like on the account" is on; else kept in
     * Prism): Spotify's Liked Songs both ways (1.8.1) uses it.
     */
    suspend fun likeImported(songs: List<Song>) = hands.like(songs)

    private fun keepLikes(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(this) { prefs.edit().putStringSet(KEPT_LIKES, keptLikes() + ids).apply() }
    }

    private fun unkeepLike(id: String) {
        synchronized(this) { prefs.edit().putStringSet(KEPT_LIKES, keptLikes() - id).apply() }
    }

    fun setMatchOnMobile(on: Boolean) {
        prefs.edit().putBoolean(MOBILE, on).apply()
        _matchOnMobile.value = on
        ImportMatchWorker.enqueue(c.app, on)
    }

    /**
     * YouTube Music (live) (1.8.1): the account's History page read every few hours while signed in and turned on
     * (off by default). Its switch, state and last read are kept in its own preferences ("youtube_live", in backups
     * with the history).
     */
    val youtubeLive = YouTubeLive(
        c.app.getSharedPreferences(YouTubeLive.PREFS, Context.MODE_PRIVATE), c.db, importer,
        read = { c.ytm.historySections() },
        account = { c.settings.current.takeIf { it.isLoggedIn }?.let { YouTubeLive.accountOf(it.authUser, it.accountEmail, it.dataSyncId) } },
        jobs = YouTubeLiveWorker.Jobs(c.app), scope = c.scope,
        log = { AudioDiagnostics.log("youtube_live", it) },
    )

    /**
     * Turns YouTube Music (live) on (the first read, at once, is only its starting point) or off (its plays stay until
     * removed; with none, Replay stops being asked to count it).
     */
    fun setYouTubeLive(on: Boolean) {
        youtubeLive.setOn(on)
        if (!on && ImportSource.YOUTUBE_LIVE in _counted.value) c.scope.launch(Dispatchers.IO) {
            if (c.db.imports().newest(ImportSource.YOUTUBE_LIVE.key) == null) setCounted(ImportSource.YOUTUBE_LIVE, false)
        }
    }

    /** One read of the History page (the job's), if on, signed in and not paused. */
    suspend fun runYouTubeLive(): Int? = withContext(Dispatchers.IO) { youtubeLive.run() }

    /** What Replay, Rewind and the Replay card count now. */
    fun replay(): PlayStats = stats(_counted.value)

    /** The same plays by name, for a question of [com.prism.music.data.db.CountedPlayDao] Replay doesn't ask. */
    fun countedOrigins(): List<String> = listOf(CountedPlays.PRISM) + _counted.value.sortedBy { it.ordinal }.map { it.key }

    fun stats(sources: Set<ImportSource>): PlayStats =
        if (sources.isEmpty()) PrismPlays(c.db.plays()) else CountedPlays(sources.sortedBy { it.ordinal }, c.db.counted(), c.db.plays())

    /** Plays the recommendations learn from ([TasteHistory]). */
    val taste = TasteHistory(c.db.plays(), c.db.counted(), boost = { c.spotify.taste() }) { if (_teach.value) ImportSource.entries else emptyList() }

    val summaries: Flow<List<com.prism.music.data.db.ImportSummary>> = c.db.imports().summariesFlow()
    val statuses: Flow<List<com.prism.music.data.db.StatusCount>> = c.db.imports().statusFlow()
    val review: Flow<List<com.prism.music.data.db.ImportMatchEntity>> = c.db.imports().reviewFlow()

    /** Reading files or Last.fm now: what's read so far (null when nothing is). */
    val reading = MutableStateFlow<Reading?>(null)
    data class Reading(val source: ImportSource, val plays: Int, val pages: Pair<Int, Int>? = null)

    private val lock = Mutex()

    /**
     * One matching or library run at a time: the one-time and weekly jobs, or a pick in "Needs a look", would otherwise
     * match the same songs and both make the library's playlists and likes.
     */
    private val matching = Mutex()

    /** Files the listener picked, waiting for the page to look at them ([preview]); the page takes them off. */
    val picked = MutableStateFlow<Picked?>(null)
    data class Picked(val source: ImportSource, val uris: List<Uri>)

    private val _lastImports = MutableStateFlow(lastImportsNow())
    /** When each source was last brought in (a repeat import adds only what's new since). */
    val lastImports: StateFlow<Map<ImportSource, Long>> = _lastImports

    private fun lastImportsNow(): Map<ImportSource, Long> =
        ImportSource.entries.mapNotNull { s -> ImportReminders.lastImport(prefs, s)?.let { s to it } }.toMap()

    private fun noteImport(source: ImportSource, at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(ImportReminders.lastKey(source), at).remove(ImportReminders.remindedKey(source)).apply()
        _lastImports.value = lastImportsNow()
        runCatching { ImportReminders.schedule(c.app) }
    }

    private val _remindMonths = MutableStateFlow(ImportReminders.months(prefs))
    /** Months between reminders to bring a fresh Spotify or Takeout download (0: never); only once one was imported. */
    val remindMonths: StateFlow<Int> = _remindMonths

    fun setRemindMonths(months: Int) {
        val m = months.takeIf { it in ImportReminders.CHOICES } ?: ImportReminders.DEFAULT_MONTHS
        prefs.edit().putInt(ImportReminders.MONTHS, m).apply()
        _remindMonths.value = m
        runCatching { ImportReminders.schedule(c.app) }
    }

    private fun nameOf(uri: Uri): String = runCatching {
        c.app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /**
     * What [uris] hold, counted without bringing anything in. After an earlier import of [source], also what of it is
     * new: plays an earlier import brought (the same song at about the same time) and library items already here aren't.
     */
    suspend fun preview(source: ImportSource, uris: List<Uri>): ImportPreview = withContext(Dispatchers.IO) {
        val keys = HashSet<String>()
        var plays = 0
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var library = ParsedLibrary()
        val kinds = mutableSetOf<FileKind>()
        var skipped = 0
        val novelty = importer.novelty(source)
        val count: (ParsedPlay) -> Unit = { p ->
            plays++; keys += ImportKeys.songKey(p.title, p.artist)
            if (p.timestamp < first) first = p.timestamp
            if (p.timestamp > last) last = p.timestamp
            novelty.add(p)
        }
        // Both histories in one download: the extended one alone (the account data's repeats it).
        val extended = source == ImportSource.SPOTIFY && uris.any { u -> SpotifyExport.kindOf(nameOf(u)) == FileKind.SPOTIFY_EXTENDED || zipHasExtended(u) }
        for (u in uris) {
            val stream = c.app.contentResolver.openInputStream(u) ?: continue
            stream.use { s ->
                when (source) {
                    ImportSource.SPOTIFY -> SpotifyExport.read(nameOf(u), s, SpotifyExport.Options(accountHistory = !extended), count).let { r ->
                        library += r.library; kinds += r.kinds; skipped += r.skippedFiles
                    }
                    ImportSource.TAKEOUT -> if (TakeoutHistory.read(nameOf(u), s, count)) kinds += FileKind.TAKEOUT_HISTORY else skipped++
                    ImportSource.LASTFM, ImportSource.SPOTIFY_LIVE, ImportSource.YOUTUBE_LIVE -> Unit
                }
            }
        }
        val fresh = novelty.result()
        ImportPreview(
            source, plays, keys.size, first.takeIf { plays > 0 }, last.takeIf { plays > 0 }, library, kinds, skipped,
            newPlays = fresh.plays, newFirstAt = fresh.firstAt, newLastAt = fresh.lastAt,
            lastImportAt = ImportReminders.lastImport(prefs, source),
            libraryNews = if (library.isEmpty) LibraryNews() else LibraryJob.load(jobFile).news(library),
        )
    }

    private fun zipHasExtended(u: Uri): Boolean = runCatching {
        c.app.contentResolver.openInputStream(u)?.use { s ->
            java.util.zip.ZipInputStream(s.buffered()).use { z ->
                generateSequence { z.nextEntry }.any { SpotifyExport.kindOf(it.name) == FileKind.SPOTIFY_EXTENDED }
            }
        } ?: false
    }.getOrDefault(false)

    /** What the listener chose to bring from what [preview] showed. */
    data class Choice(val history: Boolean = true, val library: LibraryJob.LibraryChoice = LibraryJob.LibraryChoice())

    /** Brings [uris] in: the history stored, the library lined up, matching started. */
    suspend fun bring(source: ImportSource, uris: List<Uri>, choice: Choice, preview: ImportPreview): HistoryImporter.Stats? = lock.withLock {
        withContext(Dispatchers.IO) {
            val session = importer.session(source)
            var library = ParsedLibrary()
            val extended = FileKind.SPOTIFY_EXTENDED in preview.kinds
            reading.value = Reading(source, 0)
            try {
                for (u in uris) {
                    val stream = c.app.contentResolver.openInputStream(u) ?: continue
                    val plays = ArrayList<ParsedPlay>(HistoryImporter.BATCH)
                    suspend fun drain() { plays.forEach { session.add(it) }; plays.clear() }
                    stream.use { s ->
                        // The readers call back plainly; plays are stored a batch at a time between them.
                        val sink: (ParsedPlay) -> Unit = { p -> plays += p }
                        when (source) {
                            ImportSource.SPOTIFY -> {
                                // Read file by file so a long history is stored as it's read, not held whole.
                                SpotifyExport.read(nameOf(u), s, SpotifyExport.Options(history = choice.history, accountHistory = !extended), batched(sink, plays) { runBlocking { drain() } })
                                    .let { library += it.library }
                            }
                            ImportSource.TAKEOUT -> if (choice.history) TakeoutHistory.read(nameOf(u), s, batched(sink, plays) { runBlocking { drain() } })
                            ImportSource.LASTFM, ImportSource.SPOTIFY_LIVE, ImportSource.YOUTUBE_LIVE -> Unit
                        }
                    }
                    drain()
                }
                // What the library adds is told apart from the plays (a repeat may bring only new likes or playlist songs).
                val stats = session.finish().let { s -> if (library.isEmpty) s else s.copy(library = addLibrary(library, choice.library)) }
                // Plays the Spotify connection read live that this download has too count once (the download's).
                if (source == ImportSource.SPOTIFY) runCatching { c.db.spotify().dropLiveTwins(com.prism.music.data.spotify.SpotifyRecent.TWIN_MS) }
                // And YouTube Music (live)'s that a Takeout has (Takeout is the complete history).
                if (source == ImportSource.TAKEOUT) runCatching { c.db.imports().dropYouTubeLiveTwins(YouTubeLive.TWIN_MS) }
                noteImport(source)
                afterImport()
                AudioDiagnostics.log("imports", "${source.key}: $stats")
                stats
            } finally {
                reading.value = null
            }
        }
    }

    /** [sink] that stores [plays] each time a batch is full (the readers aren't suspending). */
    private fun batched(sink: (ParsedPlay) -> Unit, plays: List<ParsedPlay>, flush: () -> Unit): (ParsedPlay) -> Unit = { p ->
        sink(p)
        if (plays.size >= HistoryImporter.BATCH) {
            flush()
            reading.value = reading.value?.let { it.copy(plays = it.plays + HistoryImporter.BATCH) }
        }
    }

    /**
     * What [bringLastFm] would bring, from two small requests ([LastFmPeek]): how many scrobbles (only the new ones
     * after an earlier import) and their dates. Null: not connected, or Last.fm didn't answer.
     */
    suspend fun previewLastFm(): LastFmPeek.Peek? = withContext(Dispatchers.IO) {
        if (c.lastFm.user.value == null) return@withContext null
        val from = LastFmHistory.fromSeconds(c.db.imports().newest(ImportSource.LASTFM.key), lastFmFinished())
        runCatching { LastFmPeek.peek({ page, per -> c.lastFm.recentTracksPage(page, per, from) }, from) }.getOrNull()
    }

    private fun lastFmFinished() = !prefs.getBoolean(LASTFM_UNFINISHED, false)

    /** The connected Last.fm account's scrobbles, page by page. */
    suspend fun bringLastFm(): HistoryImporter.Stats? = lock.withLock {
        withContext(Dispatchers.IO) {
            if (c.lastFm.user.value == null) return@withContext null
            val session = importer.session(ImportSource.LASTFM)
            val page = ArrayList<ParsedPlay>()
            // Again: only the scrobbles since the newest one brought before (that one, at its second, is passed over);
            // everything when the last reading stopped short, so its unread older pages aren't left out for good.
            val from = LastFmHistory.fromSeconds(c.db.imports().newest(ImportSource.LASTFM.key), lastFmFinished())
            reading.value = Reading(ImportSource.LASTFM, 0, 0 to 1)
            try {
                var n = 0
                val read = LastFmHistory.readAll(
                    fetch = { p -> c.lastFm.recentTracksPage(p, LastFmHistory.PER_PAGE, from) },
                    onPlay = { page += it },
                    // Each page stored as it comes: a long scrobble history is never held whole.
                    onProgress = { done, all ->
                        n += page.size
                        runBlocking { page.forEach { session.add(it) } }
                        page.clear()
                        reading.value = Reading(ImportSource.LASTFM, n, done to all)
                    },
                )
                page.forEach { session.add(it) }
                val stats = session.finish()
                prefs.edit().putBoolean(LASTFM_UNFINISHED, !read.finished).apply()
                noteImport(ImportSource.LASTFM)
                afterImport()
                AudioDiagnostics.log("imports", "lastfm: $stats")
                stats
            } finally {
                reading.value = null
            }
        }
    }

    /** Lines [lib] up (as far as [choice] says); returns what of it is new. */
    private suspend fun addLibrary(lib: ParsedLibrary, choice: LibraryJob.LibraryChoice): LibraryNews {
        val before = LibraryJob.load(jobFile)
        val news = before.news(ParsedLibrary(
            if (choice.liked) lib.liked else emptyList(), if (choice.playlists) lib.playlists else emptyList(),
            if (choice.albums) lib.albums else emptyList(), if (choice.artists) lib.artists else emptyList(),
        ))
        val job = before.plus(lib, choice)
        LibraryJob.save(jobFile, job)
        val tracks = (if (choice.liked) lib.liked else emptyList()) + (if (choice.playlists) lib.playlists.flatMap { it.tracks } else emptyList())
        importer.addLibrarySongs(tracks)
        return news
    }

    /** The first import turns "shape your mixes" on (the owner's choice); after that it's the listener's. */
    private fun afterImport() {
        if (!prefs.getBoolean(TEACH_SET, false)) {
            prefs.edit().putBoolean(TEACH, true).putBoolean(TEACH_SET, true).apply()
            _teach.value = true
        }
        ImportMatchWorker.enqueue(c.app, _matchOnMobile.value)
    }

    /**
     * Takes [source]'s plays away (Replay stops counting it); what its library already added to Prism stays, and is
     * remembered ([LibraryJob.remembered]): a download brought again later tops its playlists up, never makes them twice.
     * Its reminders stop.
     */
    suspend fun remove(source: ImportSource) = lock.withLock {
        withContext(Dispatchers.IO) {
            val keep = if (source == ImportSource.SPOTIFY) {
                val job = LibraryJob.load(jobFile).remembered()
                if (job == LibraryJob()) jobFile.delete() else LibraryJob.save(jobFile, job)
                emptySet()
            } else LibraryJob.load(jobFile).keys
            // The Spotify connection's songs (its files only exist while it's connected) keep their matches too.
            val spotify = if (File(c.app.filesDir, com.prism.music.data.spotify.Spotify.DIR).exists()) c.spotify.matchKeys() else emptySet()
            importer.remove(source, keep + spotify)
            setCounted(source, false)
            prefs.edit().remove(ImportReminders.lastKey(source)).remove(ImportReminders.remindedKey(source)).apply()
            _lastImports.value = lastImportsNow()
            runCatching { ImportReminders.schedule(c.app) }
        }
    }

    /** The listener's pick in "Needs a look" ([song] null: none of these). */
    suspend fun fix(key: String, song: Song?) = withContext(Dispatchers.IO) {
        val m = c.db.imports().match(key) ?: return@withContext
        importer.fix(m, song)
        // The library after a running match is done with it (else both would make the same playlists).
        if (song != null) applyLibrary()
    }

    // ---- Matching (from ImportMatchWorker)

    private val matcher = TrackMatcher { q ->
        c.ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }
    }

    /**
     * Matches songs until none are left or [until] (a time), a few searches at a time with a pause between, so YouTube
     * sees about what a person searching would send. [progress]: songs done, and how many were waiting.
     */
    suspend fun matchSome(until: Long, stopped: () -> Boolean, progress: suspend (Int, Int) -> Unit): Boolean = matching.withLock { withContext(Dispatchers.IO) {
        val dao = c.db.imports()
        val week = System.currentTimeMillis() - WEEK_MS
        val total = dao.toMatchCount(week)
        var done = 0
        var failures = 0
        while (System.currentTimeMillis() < until && !stopped()) {
            val batch = dao.toMatch(week, 30)
            if (batch.isEmpty()) break
            for (group in batch.chunked(AT_ONCE)) {
                val answers = coroutineScope {
                    group.map { m -> async { m to runCatching { matcher.match(m.toTrack()) } } }.awaitAll()
                }
                for ((m, r) in answers) {
                    val result = r.getOrNull()
                    if (result == null) { failures++; continue }
                    failures = 0
                    importer.applyMatch(m, result)
                    done++
                }
                // Offline or refused a few times running: stop for now, WorkManager tries again later.
                if (failures >= AT_ONCE * 3) return@withContext false
                progress(done, total)
                delay(PAUSE_MS)
                if (System.currentTimeMillis() >= until || stopped()) break
            }
        }
        runCatching { applyLibraryNow() }.onFailure { AudioDiagnostics.log("imports", "library: $it") }
        dao.toMatchCount(week) == 0
    } }

    /** Songs, albums and artists of the library job, as far as matching has got. */
    suspend fun applyLibrary() = matching.withLock { applyLibraryNow() }

    private suspend fun applyLibraryNow() = withContext(Dispatchers.IO) {
        val job = LibraryJob.load(jobFile)
        if (job == LibraryJob()) return@withContext
        LibraryJob.save(jobFile, job.apply(hands))
    }

    private val hands = object : LibraryJob.Hands {
        override suspend fun matched(keys: Collection<String>): Map<String, Song> {
            val ids = keys.chunked(500).flatMap { c.db.imports().matches(it) }
                .filter { MatchStatus.of(it.status).matched && it.matchedId != null }.associate { it.songKey to it.matchedId!! }
            val songs = ids.values.distinct().chunked(500).flatMap { c.db.songs().getAll(it) }.associateBy { it.id }
            return ids.mapNotNull { (k, id) -> songs[id]?.let { k to it.toSong() } }.toMap()
        }

        override suspend fun pending(keys: Collection<String>): Set<String> =
            keys.chunked(500).flatMap { c.db.imports().matches(it) }.filter { MatchStatus.of(it.status) == MatchStatus.PENDING }.map { it.songKey }.toSet()

        override suspend fun like(songs: List<Song>) {
            val now = System.currentTimeMillis()
            likeAll(
                songs,
                likeHere = { i, s ->
                    val existing = c.db.songs().get(s.id)
                    // The download's order, first newest.
                    (existing?.liked != true).also { if (it) c.db.songs().upsert(s.toEntity(existing).copy(liked = true, likedAt = now - i)) }
                },
                onAccount = c.settings.current.isLoggedIn && _likeOnAccount.value,
                likeThere = { id -> runCatching { c.ytm.like(id, true) }.isSuccess },
                keep = ::keepLikes, unkeep = ::unkeepLike,
            )
        }

        override fun createPlaylist(name: String, songs: List<Song>): String = c.prismLibrary.create(name, songs)
        override fun addToPlaylist(id: String, songs: List<Song>) { c.prismLibrary.add(id, songs) }
        override fun playlistExists(id: String): Boolean = c.prismLibrary.list(id) != null

        override suspend fun saveAlbum(album: LibraryAlbum): Boolean {
            val found = c.ytm.search("${album.title} ${album.artist}", SearchFilter.ALBUMS).items.filterIsInstance<AlbumItem>()
            delay(PAUSE_MS)
            val want = ImportKeys.core(album.title)
            val artist = ImportKeys.norm(ImportKeys.mainArtist(album.artist))
            val hit = found.firstOrNull { ImportKeys.core(it.title) == want && ImportKeys.norm(it.subtitle).contains(artist) } ?: return false
            if (!c.prismLibrary.isSaved(hit.id)) c.prismLibrary.saveAlbum(SavedAlbum(hit.id, hit.title, hit.subtitle, hit.thumbnail, hit.playlistId, System.currentTimeMillis()))
            return true
        }

        override suspend fun starArtist(name: String): Boolean {
            val found = c.ytm.search(name, SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>()
            delay(PAUSE_MS)
            val hit = found.firstOrNull { ImportKeys.norm(it.title) == ImportKeys.norm(name) } ?: return false
            if (!c.artistPrefs.isFavorite(hit.id)) c.artistPrefs.setFavorite(FavoriteArtist(hit.id, hit.title, hit.thumbnail), true)
            return true
        }
    }

    private fun com.prism.music.data.db.ImportMatchEntity.toTrack() =
        TrackToMatch(title, artist, album, durationMs, atLeastMs, isrc, youtubeId)


    companion object {
        const val PREFS = "imports"
        private const val TEACH = "teach"
        private const val TEACH_SET = "teach_set"
        private const val MOBILE = "match_on_mobile"
        private const val LIKE_ON_ACCOUNT = "like_on_account"
        private const val KEPT_LIKES = "kept_likes"
        /** The last Last.fm reading stopped before its oldest page: the next asks for everything again. */
        private const val LASTFM_UNFINISHED = "lastfm_unfinished"
        private fun countKey(s: ImportSource) = "count_${s.key}"

        /** Searches at once, and the pause after each few. */
        const val AT_ONCE = 3
        const val PAUSE_MS = 1_200L
        const val WEEK_MS = 7 * 86_400_000L

        /**
         * Imported likes: each liked here ([likeHere]: false when it already was), then kept in Prism ([keep]) before
         * the account is asked at all, and taken out of that once the account has it ([likeThere], a few at a time). So
         * a run stopped half way (the match job's ten minutes) leaves the rest kept, never un-liked by the next sync.
         */
        internal suspend fun likeAll(
            songs: List<Song>, likeHere: suspend (Int, Song) -> Boolean, onAccount: Boolean, likeThere: suspend (String) -> Boolean,
            keep: (Collection<String>) -> Unit, unkeep: (String) -> Unit, pause: suspend (Long) -> Unit = { delay(it) },
        ) {
            val added = songs.filterIndexed { i, s -> likeHere(i, s) }.map { it.id }
            keep(added)
            if (onAccount) added.forEach { id ->
                if (likeThere(id)) unkeep(id)
                pause(PAUSE_MS / 2)
            }
        }
    }
}
