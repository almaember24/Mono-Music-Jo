package com.prism.music.data.lastfm

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import com.prism.music.BuildConfig
import com.prism.music.data.model.Song
import com.prism.music.playback.AudioDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/**
 * Last.fm scrobbling (1.7.8, #133): off until the listener connects their own Last.fm account, through Last.fm's own
 * page in the browser (Prism never sees the password; it gets a session for this app back). A song counts once
 * it's been heard for half its length or 4 minutes (Last.fm's rule; songs shorter than 30 s never), "Now playing"
 * is sent as each starts, and scrobbles wait on the phone while offline. Podcast episodes and live streams aren't
 * scrobbled. It's the listener's own account, connected by them, so this isn't telemetry. The API key and secret
 * are Prism's (local.properties: lastfm.apiKey, lastfm.secret); a build without them doesn't offer it.
 */
class LastFm(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    /** The listener's choices (Settings → Recommendations & Replay → Last.fm). */
    data class Options(
        val scrobble: Boolean = true,
        /** Only a song's first artist (#160: "A, B & C" is three artists to Last.fm, and none of them matched). */
        val firstArtistOnly: Boolean = false,
        val loveOnLike: Boolean = false,
    )

    @Serializable
    private data class Scrobble(val artist: String, val track: String, val album: String? = null, val timestamp: Long, val duration: Int = 0)

    val available: Boolean get() = KEY.isNotBlank() && SECRET.isNotBlank()

    private val prefs = context.getSharedPreferences("lastfm", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _user = MutableStateFlow(prefs.getString("user", null)?.takeIf { prefs.getString("session", null) != null })
    /** The connected Last.fm account's name, or null. */
    val user: StateFlow<String?> = _user

    private val _options = MutableStateFlow(
        Options(prefs.getBoolean("scrobble", true), prefs.getBoolean("first_artist", false), prefs.getBoolean("love", false)),
    )
    val options: StateFlow<Options> = _options

    private val _waiting = MutableStateFlow(queue().size)
    /** Scrobbles not sent yet (offline, or Last.fm busy). */
    val waiting: StateFlow<Int> = _waiting

    fun setOptions(o: Options) {
        _options.value = o
        prefs.edit().putBoolean("scrobble", o.scrobble).putBoolean("first_artist", o.firstArtistOnly).putBoolean("love", o.loveOnLike).apply()
    }

    private val active: Boolean get() = available && _user.value != null && _options.value.scrobble

    // ---- Signing in

    /** Last.fm's page that asks the listener to let Prism scrobble; it comes back to prism://lastfm?token=… */
    fun authUrl(): String = "https://www.last.fm/api/auth/?api_key=$KEY&cb=" + java.net.URLEncoder.encode("prism://lastfm", "UTF-8")

    /** Swaps the token Last.fm sent back for a session. False when Last.fm refused it. */
    suspend fun finishSignIn(token: String): Boolean = runCatching {
        val res = call(mapOf("method" to "auth.getSession", "token" to token))
        val session = res["session"]?.jsonObject ?: return false
        val key = session["key"]?.jsonPrimitive?.contentOrNull ?: return false
        val name = session["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        prefs.edit().putString("session", key).putString("user", name).apply()
        _user.value = name
        scope.launch { flush() }
        true
    }.getOrElse { AudioDiagnostics.log("lastfm", "sign-in failed: $it"); false }

    fun signOut() {
        prefs.edit().remove("session").remove("user").remove("queue").apply()
        _user.value = null
        _waiting.value = 0
    }

    // ---- Scrobbling

    /** The play being heard now, and how much of it has been. */
    private class Play(val song: Song, val startedAt: Long) { var heardMs = 0L; var done = false }
    @Volatile private var playing: Play? = null

    /** A song started (the player's queue moved on to it). */
    fun started(song: Song) {
        if (!active || song.isEpisode || song.isLive) { playing = null; return }
        playing = Play(song, System.currentTimeMillis() / 1000)
        scope.launch { runCatching { nowPlaying(song) } }
    }

    /** [ms] more of [song] was heard; once that's enough, it's scrobbled (once per play). */
    fun heard(song: Song, ms: Long) {
        val p = playing ?: return
        if (p.done || p.song.id != song.id || !active) return
        p.heardMs += ms
        val sec = song.durationSec
        if (sec in 1 until MIN_SONG_SEC) return
        val needMs = if (sec > 0) minOf(sec * 500L, MAX_NEEDED_MS) else MAX_NEEDED_MS
        if (p.heardMs < needMs) return
        p.done = true
        enqueue(Scrobble(artistOf(song), song.title, song.album?.title, p.startedAt, sec.coerceAtLeast(0)))
        scope.launch { flush() }
    }

    /** The song was liked or unliked here: loved or unloved on Last.fm too, when that's on. */
    fun liked(song: Song, liked: Boolean) {
        if (!available || _user.value == null || !_options.value.loveOnLike || song.isEpisode) return
        scope.launch {
            runCatching { call(mapOf("method" to if (liked) "track.love" else "track.unlove", "artist" to artistOf(song), "track" to song.title), signed = true) }
                .onFailure { AudioDiagnostics.log("lastfm", "love failed: $it") }
        }
    }

    private fun artistOf(song: Song): String =
        if (_options.value.firstArtistOnly) song.artists.firstOrNull()?.name ?: song.artistText else song.artistText

    private suspend fun nowPlaying(song: Song) {
        call(
            buildMap {
                put("method", "track.updateNowPlaying"); put("artist", artistOf(song)); put("track", song.title)
                song.album?.title?.let { put("album", it) }
                if (song.durationSec > 0) put("duration", song.durationSec.toString())
            },
            signed = true,
        )
    }

    private val queueLock = Any()

    private fun queue(): List<Scrobble> = runCatching {
        json.decodeFromString(ListSerializer(Scrobble.serializer()), prefs.getString("queue", null) ?: "[]")
    }.getOrDefault(emptyList())

    private fun saveQueue(q: List<Scrobble>) {
        prefs.edit().putString("queue", json.encodeToString(ListSerializer(Scrobble.serializer()), q)).apply()
        _waiting.value = q.size
    }

    private fun enqueue(s: Scrobble) = synchronized(queueLock) { saveQueue((queue() + s).takeLast(MAX_QUEUE)) }

    private val sending = Mutex()

    /** Sends what's waiting, 50 at a time (Last.fm's most per request); what fails stays for next time. */
    suspend fun flush() = sending.withLock {
        if (!available || _user.value == null) return@withLock
        while (true) {
            val batch = synchronized(queueLock) { queue() }.take(BATCH)
            if (batch.isEmpty()) return@withLock
            val params = HashMap<String, String>().apply {
                put("method", "track.scrobble")
                batch.forEachIndexed { i, s ->
                    put("artist[$i]", s.artist); put("track[$i]", s.track); put("timestamp[$i]", s.timestamp.toString())
                    s.album?.let { put("album[$i]", it) }
                    if (s.duration > 0) put("duration[$i]", s.duration.toString())
                }
            }
            val failure = runCatching { call(params, signed = true) }.exceptionOrNull()
            if (failure != null) {
                // Offline, or Last.fm busy for a while: kept for later. Turned down for good (its data, not the moment):
                // dropped, or it would hold up every scrobble after it.
                val forGood = failure is LastFmError && failure.code !in TRY_AGAIN && failure.code != ERROR_INVALID_SESSION
                AudioDiagnostics.log("lastfm", if (forGood) "${batch.size} scrobbles refused, dropped: $failure" else "scrobbles kept for later: $failure")
                if (!forGood) return@withLock
            }
            synchronized(queueLock) { saveQueue(queue().drop(batch.size)) }
        }
    }

    // ---- Bring your history (1.8.1)

    /**
     * One page of the connected account's scrobbles, newest first ([com.prism.music.data.imports.LastFmHistory]), as
     * Last.fm's JSON. Public data of the account, asked with Prism's key: nothing is signed or sent but the user's name.
     */
    suspend fun recentTracksPage(page: Int, perPage: Int = 200, from: Long? = null): String = withContext(Dispatchers.IO) {
        val name = _user.value ?: throw IOException("Last.fm isn't connected")
        val url = recentTracksUrl(name, page, perPage, from, KEY)
        val req = Request.Builder().url(url).header("User-Agent", "Prism/${BuildConfig.VERSION_NAME}").build()
        http.newCall(req).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw IOException(tr(R.string.last_fm_answered_http, r.code))
            body
        }
    }

    // ---- The API

    class LastFmError(val code: Int, message: String) : IOException(tr(R.string.last_fm, message, code))

    /** One request to Last.fm's API (POST, JSON answer). Signed calls carry the session and the signature. */
    private suspend fun call(params: Map<String, String>, signed: Boolean = true): JsonObject = withContext(Dispatchers.IO) {
        val all = HashMap(params).apply {
            put("api_key", KEY)
            if (signed && params["method"] != "auth.getSession") prefs.getString("session", null)?.let { put("sk", it) }
        }
        val form = FormBody.Builder().apply {
            all.forEach { (k, v) -> add(k, v) }
            add("api_sig", signature(all, SECRET))
            add("format", "json")
        }.build()
        val req = Request.Builder().url(API).post(form).header("User-Agent", "Prism/${BuildConfig.VERSION_NAME}").build()
        http.newCall(req).execute().use { r ->
            val obj = runCatching { json.parseToJsonElement(r.body.string()).jsonObject }.getOrNull() ?: throw IOException(tr(R.string.last_fm_answered_http, r.code))
            obj["error"]?.jsonPrimitive?.intOrNull?.let { code ->
                // The listener took Prism's access back on Last.fm: signed out here too.
                if (code == ERROR_INVALID_SESSION) signOut()
                throw LastFmError(code, obj["message"]?.jsonPrimitive?.contentOrNull ?: "error")
            }
            obj
        }
    }

    companion object {
        private val KEY get() = BuildConfig.LASTFM_API_KEY
        private val SECRET get() = BuildConfig.LASTFM_SECRET
        private const val API = "https://ws.audioscrobbler.com/2.0/"
        private const val BATCH = 50
        private const val MAX_QUEUE = 2_000
        private const val MIN_SONG_SEC = 30
        private const val MAX_NEEDED_MS = 4 * 60_000L
        private const val ERROR_INVALID_SESSION = 9

        /** `user.getRecentTracks` for [user]: one page, and with [from] (seconds) only the scrobbles since then. */
        fun recentTracksUrl(user: String, page: Int, perPage: Int, from: Long?, key: String): okhttp3.HttpUrl =
            okhttp3.HttpUrl.Builder().scheme("https").host("ws.audioscrobbler.com").addPathSegments("2.0/")
                .addQueryParameter("method", "user.getrecenttracks").addQueryParameter("user", user)
                .addQueryParameter("limit", perPage.toString()).addQueryParameter("page", page.toString())
                .apply { if (from != null) addQueryParameter("from", from.toString()) }
                .addQueryParameter("api_key", key).addQueryParameter("format", "json").build()

        /**
         * Last.fm's errors that pass or aren't the scrobbles' fault, so they're kept: operation failed (8), service offline
         * (11), temporarily unavailable (16), rate limit (29), and Prism's own key refused (10, 13, 26).
         */
        private val TRY_AGAIN = setOf(8, 10, 11, 13, 16, 26, 29)

        /** Last.fm's api_sig: every parameter (not format or callback) sorted by name, name and value joined, the secret after, MD5. */
        internal fun signature(params: Map<String, String>, secret: String): String {
            val text = params.filterKeys { it != "format" && it != "callback" }.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
            return MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
