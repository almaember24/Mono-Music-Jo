package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.AppContainer
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SyncState { IDLE, SYNCING, DONE, ERROR }

/**
 * A list read from the database that can't take Prism down (#103): on a phone short of memory, reading ~1,250 liked
 * songs failed partway ("Couldn't read row 1248… from CursorWindow"), and the error closed the app. Now it's logged and
 * read again, a little later each time ([stepMs] more per try, up to ten steps); the list keeps what it had meanwhile.
 */
internal fun <T> kotlinx.coroutines.flow.Flow<T>.resilient(what: String, stepMs: Long = 1_000L): kotlinx.coroutines.flow.Flow<T> =
    retryWhen { cause, attempt ->
        if (cause is kotlinx.coroutines.CancellationException || attempt >= MAX_READ_TRIES) return@retryWhen false
        runCatching { android.util.Log.w("PrismLibrary", "Reading $what failed (try ${attempt + 1}), reading it again: $cause") }
        com.prism.music.playback.AudioDiagnostics.log("library", "reading $what failed, read again: ${cause.javaClass.simpleName}")
        kotlinx.coroutines.delay(stepMs * (attempt + 1).coerceAtMost(10))
        true
    }.catch { cause ->
        // Still failing after every try: the list keeps what it had (Prism keeps running) until it's next opened.
        if (cause is kotlinx.coroutines.CancellationException) throw cause
        runCatching { android.util.Log.e("PrismLibrary", "Gave up reading $what: $cause") }
        com.prism.music.playback.AudioDiagnostics.log("library", "gave up reading $what: ${cause.javaClass.simpleName}")
    }

/** How many times a failed library read is tried again ([resilient]) before giving up. */
private const val MAX_READ_TRIES = 20L

/** Local mirror of the signed-in user's YouTube Music library, with two-way like sync. */
class LibraryRepository(private val c: AppContainer) {
    private val songs = c.db.songs()

    val likedIds: StateFlow<Set<String>> = songs.likedIdsFlow().resilient("liked ids").map { it.toSet() }
        .stateIn(c.scope, SharingStarted.Eagerly, emptySet())

    val liked = songs.likedFlow().resilient("liked songs").map { list -> list.map { it.toSong() } }
        .stateIn(c.scope, SharingStarted.Eagerly, emptyList())

    /**
     * The liked songs as the lists show them (#111): a liked music video whose song is liked too shows once, as the song,
     * unless Settings → Library & Replay → "A song and its video as one" is off. [liked] stays everything that's liked
     * (sync, the Liked playlist mirror, downloads use it).
     */
    val likedShown: StateFlow<List<com.prism.music.data.model.Song>> =
        kotlinx.coroutines.flow.combine(liked, c.settings.flow.map { it.mergeLikedVideos }.distinctUntilChanged()) { list, merge ->
            if (merge) LikedMerge.merge(list, c.queue.counterparts) else list
        }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(c.scope, SharingStarted.Eagerly, emptyList())

    val recent = songs.recentFlow(40).resilient("recent songs").map { list -> list.map { it.toSong() } }
        .stateIn(c.scope, SharingStarted.Eagerly, emptyList())

    /** Ids of the phone's own files, most recently played first. */
    val recentLocalIds: StateFlow<List<String>> = songs.recentLocalIdsFlow().resilient("recent files")
        .stateIn(c.scope, SharingStarted.Lazily, emptyList())

    val playlists = MutableStateFlow<List<BrowseItem>>(emptyList())
    val albums = MutableStateFlow<List<BrowseItem>>(emptyList())
    val artists = MutableStateFlow<List<BrowseItem>>(emptyList())
    val syncState = MutableStateFlow(SyncState.IDLE)
    val lastError = MutableStateFlow<String?>(null)

    private val keptLoad: kotlinx.coroutines.Job

    init {
        // The library as last synced, so it's all there offline (#55) and before the first sync of a launch finishes.
        // A restored copy signs out right after start ([forgetAccount] stops this, so the old account's lists don't
        // land after they were cleared).
        keptLoad = c.scope.launch(Dispatchers.IO) {
            if (!c.settings.current.isLoggedIn) return@launch
            val saved = c.offline.library() ?: return@launch
            synchronized(this@LibraryRepository) {
                if (!isActive) return@launch
                if (playlists.value.isEmpty()) playlists.value = saved.playlists
                if (albums.value.isEmpty()) albums.value = saved.albums
                if (artists.value.isEmpty()) artists.value = saved.artists
            }
        }
    }

    /** Signed out: the account's lists, and its playlists and albums kept for offline, are gone from the phone. */
    fun forgetAccount() {
        synchronized(this) { keptLoad.cancel() }
        synchronized(pending) { pending.clear(); followUps?.cancel() }
        playlists.value = emptyList(); albums.value = emptyList(); artists.value = emptyList()
        c.scope.launch(Dispatchers.IO) { c.offline.clear(); c.homeCache.clear() }
    }

    /**
     * Switches Prism to another channel on the session: another Google account, or a brand account of the same one.
     * The last channel's likes (from YouTube; files' likes are Prism's own), library lists, offline copies and Liked
     * songs playlist go, and the new one's are synced. Replay and the phone's own history stay: they're this phone's.
     */
    suspend fun switchAccount(a: com.prism.music.data.model.YtAccount, cookie: String? = null) = withContext(Dispatchers.IO) {
        c.settings.saveIdentity(a.authUser, a.dataSyncId, cookie)
        c.settings.flow.first { it.authUser == a.authUser && it.dataSyncId == a.dataSyncId && (cookie == null || it.cookie == cookie) }
        c.settings.saveAccountInfo(a.name, listOfNotNull(a.byline, a.email).distinct().joinToString(" · "), a.avatar)
        // Before the likes go: the playlist mirror would take them out of the old channel's playlist.
        c.likedPlaylist.forget()
        val kept = c.imports.keptLikes() // imported likes kept in Prism are this phone's, not the channel's
        likedIds.value.filterNot { it.startsWith(Song.LOCAL_PREFIX) || it in kept }.forEach { songs.setLiked(it, false, 0) }
        forgetAccount()
        syncInBackground()
    }

    /** Keeps the library's lists for offline, with their covers. */
    private fun persist() {
        if (playlists.value.isEmpty() && albums.value.isEmpty() && artists.value.isEmpty()) return
        c.offline.saveLibrary(playlists.value, albums.value, artists.value)
        c.scope.launch(Dispatchers.IO) {
            // List-sized (they're shown as tiles and rows), one at a time when the music isn't loading.
            (playlists.value + albums.value).forEach { c.artwork.save(c.covers.art(it), size = 544) }
        }
    }

    /**
     * On Wi-Fi, after a sync: the library's playlists and albums are saved with their songs (each once a day at most,
     * twelve per sync), so they open offline even if they haven't been opened lately.
     */
    private suspend fun keepForOffline() {
        if (!c.network.isOnline() || c.network.metered.value) return
        val day = 24 * 3_600_000L
        val now = System.currentTimeMillis()
        val todo = (playlists.value.filterIsInstance<com.prism.music.data.model.PlaylistItem>().filter { !it.isMix && it.id != "LM" } + albums.value)
            .filter { now - c.offline.savedAt(it.id) > day }
            .take(12)
        for (item in todo) {
            // Never while the music is loading: a whole playlist is many requests, and since 1.6.8 they ran alongside
            // the first songs of every launch (users heard buffering).
            c.quietNetwork()
            if (c.network.metered.value) break
            val page = runCatching {
                if (item is AlbumItem) c.ytm.album(item.id) else c.ytm.playlistAll(item.id, 1000)
            }.getOrNull() ?: continue
            c.offline.savePage(item.id, page)
            kotlinx.coroutines.delay(4_000)
        }
    }

    private val dislikePrefs = c.app.getSharedPreferences("dislikes", android.content.Context.MODE_PRIVATE)
    /** Songs to keep out of autoplay, radio and smart downloads. */
    val dislikedIds = MutableStateFlow(dislikePrefs.getStringSet("ids", emptySet())!!.toSet())

    fun isDisliked(id: String) = id in dislikedIds.value

    fun setDisliked(song: Song, disliked: Boolean) {
        val next = if (disliked) dislikedIds.value + song.id else dislikedIds.value - song.id
        dislikedIds.value = next
        dislikePrefs.edit().putStringSet("ids", next).apply()
        if (disliked && isLiked(song.id)) setLiked(song, false)
        c.scope.launch(Dispatchers.IO) {
            if (disliked && c.downloads.downloads.value.containsKey(song.id) && songs.get(song.id)?.smartDownload == true) {
                c.downloads.remove(song.id, byListener = false)
            }
            // Tells YouTube Music too, so its recommendations learn from it.
            if (c.settings.current.isLoggedIn) runCatching { c.ytm.dislike(song.id, disliked) }
        }
    }

    fun isLiked(id: String) = id in likedIds.value

    fun toggleLike(song: Song) = setLiked(song, !isLiked(song.id))

    fun setLiked(song: Song, liked: Boolean) {
        // An episode from a feed: nothing Liked songs, the account or a Replay list could do anything with.
        if (song.isRss) return
        c.scope.launch(Dispatchers.IO) { likeNow(song, liked) }
    }

    /**
     * Select many (1.8.3): several songs liked or unliked, one after another rather than all at once (a whole playlist
     * picked was hundreds of calls to YouTube Music side by side).
     */
    fun setLiked(many: List<Song>, liked: Boolean) {
        val likable = many.filter { !it.isRss }
        if (likable.isEmpty()) return
        c.scope.launch(Dispatchers.IO) { likable.forEach { runCatching { likeNow(it, liked) } } }
    }

    private suspend fun likeNow(song: Song, liked: Boolean) {
        val existing = songs.get(song.id)
        songs.upsert(song.toEntity(existing).copy(liked = liked, likedAt = if (liked) System.currentTimeMillis() else 0))
        // "Love on Last.fm when I like a song" (#133), local files included.
        c.lastFm.liked(song, liked)
        // A file on the phone is liked in Prism only: there's nothing to download or tell YouTube.
        if (song.isLocal) return
        if (liked && c.settings.current.downloadLikes) c.downloads.download(song)
        if (c.settings.current.isLoggedIn) {
            runCatching { c.ytm.like(song.id, liked) }.onFailure { lastError.value = tr(R.string.couldn_t_sync_like, it.friendlyMessage()) }
        }
    }

    suspend fun recordPlayed(song: Song) {
        // The song list doesn't keep what makes one an episode: a feed's would come back as a song, with no way to play.
        if (song.isRss) return
        val existing = songs.get(song.id)
        songs.upsert(song.toEntity(existing).copy(lastPlayed = System.currentTimeMillis()))
    }

    /** Pulls liked songs, playlists, albums and artists from the account. */
    suspend fun sync() = withContext(Dispatchers.IO) {
        if (!c.settings.current.isLoggedIn || syncState.value == SyncState.SYNCING) return@withContext
        syncState.value = SyncState.SYNCING
        try {
            val remote = c.ytm.likedSongs()
            val before = likedIds.value
            val now = System.currentTimeMillis()
            // Chunked: SQLite caps bound parameters at 999 on older Android versions.
            val existing = remote.map { it.id }.chunked(500).flatMap { songs.getAll(it) }.associateBy { it.id }
            // YouTube's order, newest like first, every time (#99): songs Prism already had kept their first like time,
            // so one liked again (or re-liked in another app) stayed where it was instead of moving to the top.
            songs.upsertAll(remote.mapIndexed { i, s ->
                s.toEntity(existing[s.id]).copy(liked = true, likedAt = now - i)
            })
            if (remote.isNotEmpty()) {
                val remoteIds = remote.map { it.id }.toSet()
                // Local songs' likes are Prism's own, and so are imported likes kept off the account (1.8.1): YouTube
                // doesn't know them, so it can't take them away.
                likesToDrop(before, remoteIds, c.imports.keptLikes()).forEach { songs.setLiked(it, false, 0) }
            }
            if (c.settings.current.downloadLikes && before.isNotEmpty()) {
                remote.filter { it.id !in before }.forEach { c.downloads.download(it) }
            }
            reloadPlaylists()
            runCatching { albums.value = c.ytm.libraryAlbums() }
            runCatching { artists.value = c.ytm.libraryArtists() }
            runCatching {
                c.ytm.accountInfo()?.let { c.settings.saveAccountInfo(it.name, it.email, it.avatar) }
            }
            persist()
            c.scope.launch(Dispatchers.IO) { runCatching { keepForOffline() } }
            syncState.value = SyncState.DONE
            lastError.value = null
            // Likes from YouTube Music are in: bring the Liked songs playlist (if there is one) in line.
            c.likedPlaylist.syncInBackground(full = true)
            // New songs in playlists kept downloaded (#298).
            if (c.settings.current.behavior.keptPlaylists.isNotEmpty()) c.scope.launch(Dispatchers.IO) { runCatching { c.keptPlaylists.syncAll() } }
        } catch (e: Exception) {
            lastError.value = e.friendlyMessage()
            syncState.value = SyncState.ERROR
        }
    }

    private val thumbPrefs = c.app.getSharedPreferences("artist_thumbs", android.content.Context.MODE_PRIVATE)
    /** Artist photos for artists that only appear as names on songs, fetched once and kept. */
    val artistThumbs = MutableStateFlow<Map<String, String>>(thumbPrefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())
    private val thumbsLoading = java.util.Collections.synchronizedSet(HashSet<String>())

    fun loadArtistThumbs(ids: List<String>) {
        // Each photo costs an artist page; data saver leaves them for Wi-Fi (#40).
        if (c.dataSaving()) return
        val todo = ids.filter { it !in artistThumbs.value && thumbsLoading.add(it) }
        if (todo.isEmpty()) return
        c.scope.launch(Dispatchers.IO) {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                todo.forEach { id ->
                    launch {
                        gate.acquire()
                        try {
                            val thumb = runCatching { c.ytm.artist(id).thumbnail }.getOrNull()
                            if (thumb != null) {
                                thumbPrefs.edit().putString(id, thumb).apply()
                                artistThumbs.value = artistThumbs.value + (id to thumb)
                            }
                        } finally { gate.release() }
                    }
                }
            }
        }
    }

    private fun nameKey(name: String) = "name:" + name.lowercase().trim()

    /** An artist's photo, by id, or by name for songs saved without the artist's id. */
    fun artistPhoto(thumbs: Map<String, String>, id: String?, name: String): String? = id?.let { thumbs[it] } ?: thumbs[nameKey(name)]

    /**
     * Fetches photos for artists from play history (Replay): by id when the song carried one,
     * otherwise by searching for the name. Waits until they're in.
     */
    suspend fun fetchArtistPhotos(artists: List<Pair<String?, String>>) = withContext(Dispatchers.IO) {
        if (c.dataSaving()) return@withContext
        val todo = artists.distinct().filter { (id, name) ->
            artistPhoto(artistThumbs.value, id, name) == null && thumbsLoading.add(id ?: nameKey(name))
        }
        if (todo.isEmpty()) return@withContext
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            todo.forEach { (id, name) ->
                launch {
                    val key = id ?: nameKey(name)
                    gate.acquire()
                    try {
                        val thumb = if (id != null) runCatching { c.ytm.artist(id).thumbnail }.getOrNull() else photoByName(name)
                        if (thumb != null) {
                            thumbPrefs.edit().putString(key, thumb).apply()
                            artistThumbs.value = artistThumbs.value + (key to thumb)
                        }
                    } finally {
                        gate.release()
                        // A miss can be tried again next time.
                        thumbsLoading.remove(key)
                    }
                }
            }
        }
    }

    /** "Drake, 21 Savage" is looked up as written, then as its first artist; only an exact name match counts. */
    private suspend fun photoByName(name: String): String? {
        val first = name.split(", ", " & ").first().trim()
        for (n in listOf(name, first).distinct()) {
            val items = runCatching { c.ytm.search(n, com.prism.music.data.innertube.SearchFilter.ARTISTS).items }.getOrNull() ?: continue
            val want = com.prism.music.data.innertube.SearchRank.norm(n)
            items.filterIsInstance<ArtistItem>().firstOrNull { com.prism.music.data.innertube.SearchRank.norm(it.title) == want }
                ?.thumbnail?.let { return it }
        }
        return null
    }

    /**
     * Deletes one of your playlists from YouTube Music; one you saved from someone else is just
     * taken out of the library. Gone from the list straight away, put back if it fails.
     */
    suspend fun deletePlaylist(item: BrowseItem): Result<Unit> = withContext(Dispatchers.IO) {
        val before = playlists.value
        playlists.value = before.filterNot { it.id == item.id }
        // One just made here (#414) isn't put back by the next refresh.
        val wasPending = synchronized(pending) { pending.remove(item.id.removePrefix("VL")) }
        runCatching { c.ytm.deletePlaylist(item.id) }
            .recoverCatching { c.ytm.unsavePlaylist(item.id) }
            .onSuccess {
                if (c.likedPlaylist.playlistId.value == item.id) c.likedPlaylist.forget()
                val pins = c.settings.current.homePlaylists
                if (pins.any { it.id == item.id }) c.settings.setHomePlaylists(pins.filterNot { it.id == item.id })
            }
            .onFailure {
                playlists.value = before
                if (wasPending != null) synchronized(pending) { pending[item.id.removePrefix("VL")] = wasPending }
            }
    }

    private val orderPrefs = c.app.getSharedPreferences("library_order", android.content.Context.MODE_PRIVATE)

    /** The listener's own order for the Library's playlists (ids, top first); empty until they arrange them. */
    val playlistOrder = MutableStateFlow(orderPrefs.getString("playlists", "")!!.split("\n").filter { it.isNotBlank() })

    /** How the Library's playlists are sorted (a name the Library screen knows), or null for its default. */
    val playlistSort = MutableStateFlow(orderPrefs.getString("playlist_sort", null))

    fun setPlaylistOrder(ids: List<String>) {
        playlistOrder.value = ids
        orderPrefs.edit().putString("playlists", ids.joinToString("\n")).apply()
    }

    fun setPlaylistSort(name: String) {
        playlistSort.value = name
        orderPrefs.edit().putString("playlist_sort", name).apply()
    }

    /**
     * How the listener last sorted a list ("liked", "downloads", "playlist:<id>", "library:songs"…), as a name the
     * screen knows; null for its default. Kept so a sort isn't lost on leaving the page.
     */
    /** Playlists, albums and artists pinned to the top of their Library tab (1.7.6), by id, newest pin first. */
    val pinned = MutableStateFlow(orderPrefs.getString("pinned", "")!!.split("\n").filter { it.isNotBlank() })

    fun setPinned(id: String, on: Boolean) {
        val now = pinned.value.filterNot { it == id }.let { if (on) listOf(id) + it else it }
        pinned.value = now
        orderPrefs.edit().putString("pinned", now.joinToString("\n")).apply()
    }

    fun sortOf(list: String): String? = orderPrefs.getString("sort:$list", null)

    fun setSort(list: String, name: String?) {
        orderPrefs.edit().apply { if (name == null) remove("sort:$list") else putString("sort:$list", name) }.apply()
    }

    fun syncInBackground() {
        c.scope.launch { sync() }
    }

    /**
     * Stars an artist (or takes the star off). Kept on the phone either way; signed in, it also
     * subscribes to them on YouTube Music ([channelId]), which is what YouTube's own app does.
     */
    fun setFavoriteArtist(artist: FavoriteArtist, channelId: String?, on: Boolean) {
        c.artistPrefs.setFavorite(artist, on)
        if (on && artists.value.none { it.id == artist.id }) {
            artists.value = listOf(ArtistItem(artist.id, artist.name, "Artist", artist.thumbnail)) + artists.value
        }
        if (c.settings.current.isLoggedIn && channelId != null) c.scope.launch(Dispatchers.IO) {
            runCatching { c.ytm.subscribe(channelId, on) }.onFailure { lastError.value = tr(R.string.couldn_t_update_on_youtube_music, artist.name, it.friendlyMessage()) }
        }
    }

    /**
     * Saves an album or playlist to the account's library, or takes it out. False if YouTube Music refused; an album's
     * other playlist id ([orElse]) is tried before giving up.
     */
    suspend fun setSaved(playlistId: String, saved: Boolean, orElse: String? = null): Boolean = withContext(Dispatchers.IO) {
        listOfNotNull(playlistId, orElse).distinct().any { id ->
            runCatching { c.ytm.setLibrarySaved(id, saved) }.onFailure { android.util.Log.w("PrismLibrary", "library ${if (saved) "save" else "removal"} of $id refused: $it") }.isSuccess
        }.also { ok -> if (ok) refreshCollections() }
    }

    /**
     * Takes an album out of the library. YouTube can open a library album as another edition of it (a remaster, a
     * deluxe), whose playlist id isn't the one the library holds, so un-saving the page left the library's copy there.
     * The library's own entry for [albumId] (with its own playlist id) goes too. False if it's still in the library.
     */
    suspend fun removeAlbum(albumId: String, pagePlaylistId: String?): Boolean = withContext(Dispatchers.IO) {
        val entry = albums.value.filterIsInstance<AlbumItem>().firstOrNull { it.id == albumId }
        val ids = listOfNotNull(pagePlaylistId, entry?.playlistId).distinct()
        if (ids.isEmpty()) return@withContext false
        val ok = ids.map { runCatching { c.ytm.setLibrarySaved(it, false) }.isSuccess }.any { it }
        if (!ok) return@withContext false
        val fresh = runCatching { c.ytm.libraryAlbums() }.getOrNull()
        if (fresh != null) { albums.value = fresh; persist() }
        fresh?.none { it.id == albumId } ?: true
    }

    /**
     * When each YouTube playlist was last changed from Prism (songs added), by its id without "VL". An open playlist
     * page, or one kept in a tab's back stack, loads again when it's behind (Reddit, 1.7.9: a song added from the queue
     * "wasn't there", because the playlist page still showed what it had loaded before).
     */
    val playlistEdits = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Songs went into [playlistId]: its page and the Library's playlists (count, cover) are brought up to date. */
    fun playlistChanged(playlistId: String) {
        val id = playlistId.removePrefix("VL")
        playlistEdits.value = playlistEdits.value + (id to System.currentTimeMillis())
        refreshCollections()
    }

    /** Fetches the library's playlists and albums again (after one is saved, created or changed). */
    fun refreshCollections() {
        if (!c.settings.current.isLoggedIn) return
        c.scope.launch(Dispatchers.IO) {
            reloadPlaylists()
            runCatching { albums.value = c.ytm.libraryAlbums() }
            persist()
        }
    }

    /** Where the library's playlists are read from (a test hands in its own). */
    internal var fetchPlaylists: suspend () -> List<BrowseItem> = { c.ytm.libraryPlaylists() }

    /** The library's playlists from YouTube, with the ones just made here that it doesn't list yet ([created]). */
    internal suspend fun reloadPlaylists() {
        runCatching { playlists.value = withPending(fetchPlaylists()) }
    }

    /**
     * Playlists made from Prism that YouTube's library list may not show yet (#414), by id without "VL". YouTube took
     * about two minutes to list a new one, and every refresh until then put back a list without it.
     */
    private val pending = HashMap<String, PendingPlaylist>()
    private var followUps: kotlinx.coroutines.Job? = null

    private fun withPending(fetched: List<BrowseItem>): List<BrowseItem> = synchronized(pending) {
        val merged = mergePending(fetched, pending, System.currentTimeMillis())
        pending.keys.retainAll(merged.still.keys)
        merged.list
    }

    /**
     * A playlist was just made on YouTube Music ([id], named [name], with [songs] songs): it's in Library at once, kept
     * there through refreshes until YouTube lists it (or [PENDING_MS] pass), and the list is asked for again a few times
     * in the next minute so YouTube's own entry (cover, count) takes over as soon as it's there.
     */
    fun created(id: String, name: String, songs: Int = 0) {
        val key = id.removePrefix("VL")
        val item = PlaylistItem(key, name, com.prism.music.data.model.songCount(songs), null)
        synchronized(pending) { pending[key] = PendingPlaylist(item, System.currentTimeMillis()) }
        playlists.value = listOf(item) + playlists.value.filterNot { it.id.removePrefix("VL") == key }
        refreshCollections()
        synchronized(pending) {
            followUps?.cancel()
            followUps = c.scope.launch(Dispatchers.IO) {
                var waited = 0L
                for (at in FOLLOW_UPS_MS) {
                    kotlinx.coroutines.delay(at - waited)
                    waited = at
                    if (synchronized(pending) { pending.isEmpty() } || !c.settings.current.isLoggedIn) break
                    reloadPlaylists()
                    persist()
                }
            }
        }
    }

    companion object {
        /** How long a playlist made here is kept in Library without YouTube listing it. */
        internal const val PENDING_MS = 10 * 60_000L

        /** When Library's playlists are asked for again after one is made (YouTube is slow to list it). */
        private val FOLLOW_UPS_MS = longArrayOf(5_000, 20_000, 60_000)

        internal data class PendingPlaylist(val item: PlaylistItem, val at: Long)
        internal data class PendingMerge(val list: List<BrowseItem>, val still: Map<String, PendingPlaylist>)

        /**
         * [fetched] (the library's playlists as YouTube lists them) with the [pending] ones it doesn't list yet on top,
         * newest first; [PendingMerge.still] is what stays pending: not listed, and made less than [PENDING_MS] ago.
         */
        internal fun mergePending(fetched: List<BrowseItem>, pending: Map<String, PendingPlaylist>, now: Long): PendingMerge {
            val listed = fetched.mapTo(HashSet()) { it.id.removePrefix("VL") }
            val still = pending.filter { (id, p) -> id !in listed && now - p.at <= PENDING_MS }
            val missing = still.values.sortedByDescending { it.at }.map { it.item }
            return PendingMerge(missing + fetched, still)
        }

        /**
         * The likes a sync takes away: liked here before, no longer in the account's liked list, and not Prism's own
         * (local files' likes, and imported likes kept off the account: [kept]).
         */
        internal fun likesToDrop(before: Set<String>, remote: Set<String>, kept: Set<String>): List<String> =
            (before - remote).filterNot { it.startsWith(Song.LOCAL_PREFIX) || it in kept }

        /**
         * [items] in the listener's [order]; ones not placed yet (new playlists) lead, in the
         * order YouTube Music lists them.
         */
        fun <T : BrowseItem> inOrder(items: List<T>, order: List<String>): List<T> {
            if (order.isEmpty()) return items
            val at = order.withIndex().associate { it.value to it.index }
            val (placed, fresh) = items.partition { it.id in at }
            return fresh + placed.sortedBy { at.getValue(it.id) }
        }

        /** Albums your songs come from, most songs first. Fills the Albums tab beyond the ones saved on YouTube Music. */
        fun albumsFrom(songs: List<Song>): List<AlbumItem> = songs
            .filter { it.album?.id != null && !it.isVideo }
            .groupBy { it.album!!.id!! }
            .map { (id, list) ->
                val first = list.first()
                AlbumItem(id, first.album!!.title, first.primaryArtist + " • " + (com.prism.music.data.model.songCount(list.size)), first.thumbnail) to list.size
            }
            .sortedByDescending { it.second }
            .map { it.first }

        /** Artists of your songs, most songs first. */
        fun artistsFrom(songs: List<Song>): List<ArtistItem> = songs
            .flatMap { s -> s.artists.filter { it.id != null }.map { it to s } }
            .groupBy { it.first.id!! }
            .map { (id, pairs) ->
                ArtistItem(id, pairs.first().first.name, com.prism.music.data.model.songCount(pairs.size), null) to pairs.size
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
