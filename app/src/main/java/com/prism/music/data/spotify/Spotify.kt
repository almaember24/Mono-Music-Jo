package com.prism.music.data.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.PrismLibrary
import com.prism.music.data.imports.ImportMatchWorker
import com.prism.music.data.imports.MatchResult
import com.prism.music.data.imports.MatchStatus
import com.prism.music.data.imports.TasteBoost
import com.prism.music.data.imports.TrackMatcher
import com.prism.music.data.imports.TrackToMatch
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.AudioDiagnostics
import com.prism.music.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Prism's optional Spotify connection (1.8.1): Spotify's official Web API, through a developer app the listener makes
 * for themselves in Spotify's dashboard ("bring your own key": Development Mode allows each app 5 users and needs its
 * owner to have Premium, so each listener has their own). Signed in with Authorization Code + PKCE in a browser tab; no
 * password or secret ever reaches Prism. Off until connected, and then each feature has its own switch, off too:
 * - Recent plays → Replay's "Spotify (live)" history ([SpotifyRecent]);
 * - Top songs and artists → the mixes' taste ([SpotifyTop]);
 * - Your playlists → Library ([SpotifyPlaylists]);
 * - Liked Songs both ways ([SpotifyLikes]).
 * Never connected, nothing here asks anything of anyone and no job is scheduled. Only Spotify's own addresses are asked
 * (and YouTube Music's search, as every import's matching does); nothing is sent anywhere else.
 */
class Spotify(private val c: AppContainer) {
    val store = SpotifyStore(
        c.app.getSharedPreferences(SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE),
        c.app.getSharedPreferences(SpotifyStore.PREFS, Context.MODE_PRIVATE),
        KeystoreBox(),
    )
    val api = SpotifyApi(c.http, store)
    private val dir = File(c.app.filesDir, DIR)
    private fun log(s: String) = AudioDiagnostics.log("spotify", s)
    val guards = SpotifyFeature.entries.associateWith { SpotifyGuard(store.prefs, it, log = ::log) }

    /** Matching for the playlists and likes opened now (the import matcher's way; a test's own). */
    @androidx.annotation.VisibleForTesting
    @Volatile var matchForTest: (suspend (TrackToMatch) -> Song?)? = null

    private val matcher = TrackMatcher { q -> c.ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song } }
    private val match: suspend (TrackToMatch) -> MatchResult = { t ->
        matchForTest?.let { f -> f(t)?.let { MatchResult(MatchStatus.SURE, it, listOf(it)) } ?: MatchResult(MatchStatus.NONE, null, emptyList()) }
            ?: matcher.match(t)
    }

    val recent = SpotifyRecent(api, c.db, c.imports.importer, store)
    val top = SpotifyTop(api, c.db, dir)
    val playlists = SpotifyPlaylists(api, c.db, c.imports.importer, dir, LibraryHands(c.prismLibrary), match)
    val likes = SpotifyLikes(api, c.db, c.imports.importer, dir, { c.imports.likeImported(it) }, { c.imports.keptLikes() }, match)

    // ---- The connection

    sealed interface Connection {
        data object None : Connection
        /** The browser tab is open; Prism waits for Spotify's answer. */
        data object Waiting : Connection
        data class Connected(val name: String) : Connection
        /** Spotify took the access back: Reconnect. */
        data object Revoked : Connection
        /** The last try didn't work ([message] says why, in the listener's words). */
        data class Failed(val message: String) : Connection
    }

    private val _connection = MutableStateFlow(connectionNow())
    val connection: StateFlow<Connection> = _connection

    private fun connectionNow(): Connection = when {
        store.revoked -> Connection.Revoked
        store.connected -> Connection.Connected(store.userName ?: store.userId.orEmpty())
        else -> Connection.None
    }

    val connected: Boolean get() = store.connected

    private var waiting: Job? = null
    private var receiver: LoopbackReceiver? = null

    /**
     * Opens Spotify's authorize page in a browser tab for [clientId], and waits (up to [WAIT_MS]) on the phone's own
     * loopback address for its answer. False when the Client ID can't be one.
     */
    fun connect(context: Context, clientId: String): Boolean {
        val id = clientId.trim()
        if (!SpotifyOAuth.validClientId(id)) return false
        store.clientId = id
        cancelConnect()
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        val r = runCatching { LoopbackReceiver(BACK_TO, tr(R.string.spot_browser_done), tr(R.string.spot_browser_back), SpotifyOAuth.PORT) }.getOrElse {
            _connection.value = Connection.Failed(tr(R.string.spot_failed_receiver)); return true
        }
        receiver = r
        val redirect = SpotifyOAuth.redirectUri(r.port)
        _connection.value = Connection.Waiting
        waiting = c.scope.launch(Dispatchers.IO) {
            try {
                val end = System.currentTimeMillis() + WAIT_MS
                while (true) {
                    val left = end - System.currentTimeMillis()
                    val query = if (left > 0) r.await(left) else null
                    // Timed out (the tab was left): back to as it was.
                    if (query == null) { _connection.value = connectionNow(); return@launch }
                    when (val cb = SpotifyOAuth.parseCallback(query, state)) {
                        is SpotifyOAuth.Callback.WrongState, SpotifyOAuth.Callback.Invalid -> continue
                        is SpotifyOAuth.Callback.Refused -> {
                            log("sign-in refused: ${cb.error}")
                            _connection.value = Connection.Failed(if (cb.error == "access_denied") tr(R.string.spot_failed_denied) else tr(R.string.spot_failed_refused, cb.error))
                            return@launch
                        }
                        is SpotifyOAuth.Callback.Code -> { finish(id, cb.code, redirect, verifier); return@launch }
                    }
                }
            } finally {
                r.close()
                if (receiver === r) receiver = null
            }
        }
        val url = SpotifyOAuth.authorizeUrl(id, redirect, state, Pkce.challenge(verifier))
        openTab(context, url)
        return true
    }

    private suspend fun finish(clientId: String, code: String, redirect: String, verifier: String) {
        try {
            val p = api.exchange(clientId, code, redirect, verifier)
            log("connected")
            _connection.value = Connection.Connected(p.name)
            schedule()
            enabledFeatures().forEach { f -> c.scope.launch { run(f) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("sign-in didn't finish: ${e.javaClass.simpleName}")
            store.clearTokens()
            _connection.value = Connection.Failed(
                when (e) {
                    is SpotifyError.Forbidden -> tr(R.string.spot_failed_forbidden)
                    is SpotifyError.Revoked -> tr(R.string.spot_failed_client)
                    else -> tr(R.string.spot_failed_network)
                },
            )
        }
    }

    fun cancelConnect() {
        waiting?.cancel(); waiting = null
        receiver?.close(); receiver = null
        if (_connection.value == Connection.Waiting) _connection.value = connectionNow()
    }

    /** A browser tab over Prism (the Custom Tabs way, by its intent extra: no library needed). */
    private fun openTab(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /**
     * Forgets the connection: tokens (and their key), jobs, what was read and cached; the Client ID too when
     * [clientIdToo]; the Spotify playlists leave Library unless [keepPlaylists] (then they stay as Prism's own). The
     * plays already brought in as "Spotify (live)" stay, as every import does, until removed in Bring your history.
     * Not cancellable once begun: the tokens go first, so the page switches to the set-up form at once and leaves, and
     * a half-done disconnect would keep the playlists, likes, files and switches.
     */
    suspend fun disconnect(clientIdToo: Boolean, keepPlaylists: Boolean) = withContext(Dispatchers.IO + NonCancellable) {
        cancelConnect()
        SpotifyWorker.cancel(c.app)
        // The tokens first, so a feature running now stops at its next request; then each waits for its run to end.
        store.wipe(clientIdToo)
        running.getValue(SpotifyFeature.PLAYLISTS).withLock { runCatching { playlists.forget(keepPlaylists) } }
        running.getValue(SpotifyFeature.TOP).withLock { top.forget() }
        running.getValue(SpotifyFeature.LIKES).withLock { likes.forget() }
        dir.deleteRecursively()
        guards.values.forEach { it.reset() }
        _options.value = optionsNow()
        _hidden.value = hiddenNow()
        _connection.value = Connection.None
        log("disconnected")
    }

    @androidx.annotation.VisibleForTesting
    fun connectForTest(tokens: SpotifyTokens, profile: SpotifyProfile) {
        store.saveTokens(tokens)
        store.userId = profile.id
        store.userName = profile.name
        _connection.value = Connection.Connected(profile.name)
    }

    // ---- The features' switches

    data class Options(
        val recent: Boolean = false,
        val top: Boolean = false,
        val playlists: Boolean = false,
        val keepInStep: Boolean = true,
        val likes: Boolean = false,
    ) {
        fun on(f: SpotifyFeature) = when (f) {
            SpotifyFeature.RECENT -> recent; SpotifyFeature.TOP -> top; SpotifyFeature.PLAYLISTS -> playlists; SpotifyFeature.LIKES -> likes
        }
    }

    private fun optionsNow() = Options(
        store.prefs.getBoolean("on_recent", false), store.prefs.getBoolean("on_top", false), store.prefs.getBoolean("on_playlists", false),
        store.prefs.getBoolean("keep_in_step", true), store.prefs.getBoolean("on_likes", false),
    )

    private val _options = MutableStateFlow(optionsNow())
    val options: StateFlow<Options> = _options

    private fun enabledFeatures() = SpotifyFeature.entries.filter { _options.value.on(it) }

    /** Turns [f] on or off; on, it runs at once. Off, its job's work stops (playlists leave Library). */
    fun setEnabled(f: SpotifyFeature, on: Boolean) {
        store.prefs.edit().putBoolean("on_${f.key}", on).apply()
        _options.value = optionsNow()
        guards.getValue(f).reset()
        schedule()
        c.scope.launch {
            if (on) run(f)
            else running.getValue(f).withLock {
                when (f) {
                    SpotifyFeature.PLAYLISTS -> runCatching { playlists.forget(keep = false) }
                    SpotifyFeature.TOP -> top.forget()
                    SpotifyFeature.LIKES -> likes.forget()
                    SpotifyFeature.RECENT -> Unit
                }
            }
        }
    }

    // ---- Followed and saved playlists (1.8.1): named in Library, songs not shared by Spotify

    private fun hiddenNow(): Set<String> = store.prefs.getStringSet(FOLLOWED_HIDDEN, null).orEmpty().toSet()
    private val _hidden = MutableStateFlow(hiddenNow())

    /**
     * What Library lists after the mirrored playlists while "Your playlists" is on and connected: the playlists the
     * listener follows or saved, less the ones hidden from Library. Never Prism playlists.
     */
    val followedShown: StateFlow<List<SpotifyPlaylist>> by lazy {
        combine(playlists.followed, _options, _hidden, _connection) { list, o, hidden, _ ->
            if (o.playlists && store.connected) list.filter { it.id !in hidden } else emptyList()
        }.stateIn(c.scope, SharingStarted.Eagerly, emptyList())
    }

    /** "Hide from Library" on one: kept in the Spotify prefs (they go with Disconnect). */
    fun hideFollowed(id: String) {
        val now = hiddenNow() + id
        store.prefs.edit().putStringSet(FOLLOWED_HIDDEN, now).apply()
        _hidden.value = now
    }

    /** "Refresh now": the playlists read again at once (a copy just made in Spotify appears), whatever the day's run did. */
    fun refreshPlaylistsNow(): Job = c.scope.launch { run(SpotifyFeature.PLAYLISTS) }

    fun setKeepInStep(on: Boolean) {
        store.prefs.edit().putBoolean("keep_in_step", on).apply()
        _options.value = optionsNow()
    }

    /** The background jobs while connected and any feature is on; none otherwise. */
    private fun schedule() {
        if (!store.connected || enabledFeatures().isEmpty()) SpotifyWorker.cancel(c.app) else SpotifyWorker.schedule(c.app)
    }

    // ---- Running a feature

    private val running = SpotifyFeature.entries.associateWith { Mutex() }

    /**
     * Runs [f] once, if connected, on and not paused; its guard hears how it went. From the background job
     * ([scheduled]): the playlists and likes are read again daily and on Wi-Fi only (songs matched since are put in
     * their playlists at every run), the top weekly, recent plays every time.
     */
    suspend fun run(f: SpotifyFeature, scheduled: Boolean = false, unmetered: Boolean = true, now: Long = System.currentTimeMillis()) = running.getValue(f).withLock {
        if (!store.connected || !_options.value.on(f)) return@withLock
        val guard = guards.getValue(f)
        if (!guard.mayRun()) return@withLock
        val daily = !scheduled || unmetered
        try {
            when (f) {
                SpotifyFeature.RECENT -> recent.sync()
                SpotifyFeature.TOP -> if (!scheduled || top.due(now)) top.refresh(now)
                SpotifyFeature.PLAYLISTS ->
                    if (daily && (!scheduled || now - playlists.mirror().listedAt >= DAILY_MS)) playlists.refreshAll(store.userId.orEmpty(), now)
                    else playlists.reapply()
                SpotifyFeature.LIKES -> if (daily && (!scheduled || now - likes.state().lastRun >= DAILY_MS)) likes.sync(now)
            }
            guard.succeeded()
            matchSoon()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotifyError.Revoked) {
            log("${f.key}: access taken back")
            _connection.value = Connection.Revoked
            SpotifyWorker.cancel(c.app)
        } catch (e: SpotifyError.NotConnected) {
            _connection.value = connectionNow()
        } catch (e: Exception) {
            guard.failed(e)
        }
    }

    /**
     * Bring your history's matching, for the songs a run lined up: started when some wait and it isn't already lined
     * up or running (its enqueue replaces a running one, which would only start it over).
     */
    private suspend fun matchSoon() = withContext(Dispatchers.IO) {
        runCatching {
            if (c.db.imports().toMatchCount(System.currentTimeMillis() - com.prism.music.data.imports.HistoryImports.WEEK_MS) == 0) return@runCatching
            val busy = androidx.work.WorkManager.getInstance(c.app).getWorkInfosForUniqueWork(ImportMatchWorker.workName()).get()
                .any { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
            if (!busy) ImportMatchWorker.enqueue(c.app, c.imports.matchOnMobile.value)
        }
    }

    /** A Spotify playlist's Prism playlist was opened: kept in step (read again when Spotify says it changed). */
    fun opened(prismId: String) {
        if (!store.connected || !_options.value.playlists || !playlists.isMirror(prismId)) return
        val guard = guards.getValue(SpotifyFeature.PLAYLISTS)
        if (!guard.mayRun()) return
        c.scope.launch {
            running.getValue(SpotifyFeature.PLAYLISTS).withLock {
                try {
                    playlists.refreshOne(prismId, readAgain = _options.value.keepInStep)
                    guard.succeeded()
                } catch (e: CancellationException) { throw e } catch (e: SpotifyError.Revoked) {
                    _connection.value = Connection.Revoked
                } catch (e: Exception) { guard.failed(e) }
            }
        }
    }

    /** The counts "Liked Songs both ways" shows before it's turned on. */
    suspend fun likesDryRun(): Result<LikesPlan.Counts> = runCatching { likes.dryRun() }.onFailure { if (it is SpotifyError.Revoked) _connection.value = Connection.Revoked }

    /** The mixes' extra taste while "Top songs and artists" is on (null otherwise: nothing is read). */
    fun taste(): TasteBoost? = if (store.connected && _options.value.top) topBoost else null
    private val topBoost by lazy { top.boost { store.connected && _options.value.top } }

    /**
     * The songs the connection still needs matched, though no imported play may name them: mirrored playlists', likes
     * waiting, the top. Removing an import in Bring your history keeps their match lines.
     */
    fun matchKeys(): Set<String> = playlists.matchKeys() + likes.matchKeys() + top.matchKeys()

    /** Library's mark: this Prism playlist shows a Spotify one. */
    fun isSpotifyPlaylist(prismId: String): Boolean = _options.value.playlists && playlists.isMirror(prismId)

    /** Prism's library, as the playlists' mirror needs it. */
    private class LibraryHands(private val lib: PrismLibrary) : SpotifyPlaylists.Hands {
        override fun create(name: String, songs: List<Song>, description: String) = lib.create(name, songs, description)
        override fun replace(id: String, songs: List<Song>) { lib.replace(id, songs) }
        override fun rename(id: String, name: String, description: String) {
            val l = lib.list(id) ?: return
            if (l.title != name || l.description != description) lib.rename(id, name, description)
        }
        override fun exists(id: String) = lib.list(id) != null
        override fun songIds(id: String) = lib.list(id)?.songs?.map { it.id }.orEmpty()
        override fun delete(id: String) = lib.delete(id)
    }

    companion object {
        const val DIR = "spotify"
        private const val FOLLOWED_HIDDEN = "followed_hidden"
        /** Where the browser comes back to Prism (the manifest's prism://spotify filter). */
        const val BACK_TO = "prism://spotify"
        const val DASHBOARD = "https://developer.spotify.com/dashboard"
        private const val WAIT_MS = 10 * 60_000L
        /** A little under a day, so a job that runs every few hours reads them once a day. */
        const val DAILY_MS = 20 * 3_600_000L

        /**
         * A Prism playlist's page opened: [spotify] (AppContainer's lazy one) hears of it only when Spotify is connected,
         * read from its sign-in preferences without building it. Most listeners never connect, and building it (its
         * keystore box, client and four helpers) on the main thread for every playlist page cost them for nothing.
         */
        fun pageOpened(context: Context, prismId: String, spotify: () -> Spotify) {
            if (SpotifyStore.connectedIn(context.getSharedPreferences(SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE))) spotify().opened(prismId)
        }
    }
}
