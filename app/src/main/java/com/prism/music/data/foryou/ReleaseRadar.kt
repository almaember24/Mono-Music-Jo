package com.prism.music.data.foryou

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.AppContainer
import com.prism.music.container
import com.prism.music.data.Notifications
import com.prism.music.data.db.ReleaseEntity
import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.str
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Release Radar: new albums, singles and EPs from the listener's artists (starred, saved in the library, and the 30
 * most played lately), found by looking at each artist's page now and then (every three days, about one page a
 * second) and comparing it with what was there before. The first look at an artist is a quiet baseline: their back
 * catalogue is never announced. Exact release dates come from Deezer (no key; matched by artist name and fans, then
 * by title); without one, the day Prism first saw it stands in.
 *
 * It makes the "New releases" page, Home's section, a Release Radar playlist (rebuilt each Friday: every song of new
 * singles and EPs, the three most played of new albums, up to 40) and, for starred artists, a notification.
 */
class ReleaseRadar(private val c: AppContainer) {
    private val prefs = c.app.getSharedPreferences("release_radar", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _running = MutableStateFlow(false)
    /** A look round the artists is under way (the New releases page shows it). */
    val running: StateFlow<Boolean> = _running

    /** It's been three days or more since the last look round (or there's never been one). */
    fun due() = System.currentTimeMillis() - prefs.getLong(LAST_RUN, 0) > 3 * 86_400_000L

    /** The releases worth showing as new: from the last [days] days, newest first. */
    suspend fun fresh(days: Int = 90): List<ReleaseEntity> {
        val today = LocalDate.now()
        return c.db.releases().fresh(today.year - 1).filter { isRecent(it, today, days) }
    }

    /** Looks round the listener's artists (if it hasn't for [minHours]), finds new releases, and updates the playlist. */
    suspend fun refresh(minHours: Int = 0): Int = lock.withLock {
        val now = System.currentTimeMillis()
        if (minHours > 0 && now - prefs.getLong(LAST_RUN, 0) < minHours * 3_600_000L) return@withLock 0
        _running.value = true
        try {
            val artists = artists()
            if (artists.isEmpty()) return@withLock 0
            val checked = prefs.getStringSet(CHECKED, emptySet())!!.toMutableSet()
            val today = LocalDate.now()
            var found = 0
            for ((i, a) in artists.withIndex()) {
                if (i > 0) delay(PAGE_GAP_MS)
                val page = runCatching { withContext(Dispatchers.IO) { c.ytm.artist(a.id) } }.getOrNull() ?: continue
                val known = c.db.releases().idsFor(a.id).toSet()
                val first = a.id !in checked
                val seen = discography(page).filter { it.id !in known }
                if (seen.isEmpty()) { checked += a.id; continue }
                // Dates for this year's (and late last year's) releases; older ones are back catalogue whatever happens.
                val dates = if (seen.any { it.year >= today.year - 1 }) runCatching { deezerDates(page.name) }.getOrDefault(emptyMap()) else emptyMap()
                val rows = seen.map { r ->
                    val date = Deezer.dateFor(dates, r.title)?.takeIf { d -> d.startsWith(r.year.toString()) || r.year == 0 }
                    // Back catalogue on a first look, unless it's out within the last month (that's still news).
                    val recentlyOut = date?.let { runCatching { ChronoUnit.DAYS.between(LocalDate.parse(it), today) in 0..30 }.getOrDefault(false) } == true
                    ReleaseEntity(
                        id = r.id, artistId = a.id, artistName = page.name, title = r.title, kind = r.kind, year = r.year,
                        releaseDate = date, thumbnail = r.thumbnail, playlistId = r.playlistId, firstSeenAt = now,
                        baseline = first && !recentlyOut,
                        // Found on a first look: shown, never announced.
                        notifiedAt = if (first) now else 0,
                    )
                }
                c.db.releases().insertAll(rows)
                found += rows.count { !it.baseline }
                checked += a.id
                // Kept as it goes: a look cut short (Prism closed, the phone busy) doesn't take a first look again.
                if (i % 10 == 9) prefs.edit().putStringSet(CHECKED, HashSet(checked)).apply()
            }
            prefs.edit().putStringSet(CHECKED, checked).putLong(LAST_RUN, now).apply()
            notify()
            rebuildPlaylist(force = found > 0 && !prefs.contains(PLAYLIST_WEEK))
            Log.i(TAG, "looked at ${artists.size} artists: $found new releases")
            found
        } finally {
            _running.value = false
        }
    }

    /**
     * The artists to keep an eye on: starred, followed on YouTube Music (signed in; Reddit 1.7: "not only the ones I've
     * played in Prism"), saved, then the most played lately (up to [MAX_ARTISTS]), less excluded ones. Only who's
     * watched for new releases: Prism's own mixes from your music don't use this.
     */
    private suspend fun artists(): List<FavoriteRef> {
        val now = System.currentTimeMillis()
        val starred = c.artistPrefs.favorites.value.map { FavoriteRef(it.id, it.name) }
        val played = c.imports.taste.tasteTopArtists(now - 90L * 86_400_000, now, 60).filter { it.artistId != null }
            .map { FavoriteRef(it.artistId!!, it.artistName) }
        val followed = if (!c.settings.current.isLoggedIn) emptyList() else
            runCatching { withContext(Dispatchers.IO) { c.ytm.librarySubscriptions() } }.getOrNull().orEmpty().map { FavoriteRef(it.id, it.title) }
        val library = c.library.artists.value.map { FavoriteRef(it.id, it.title) }
        val turn = prefs.getInt(TURN, 0)
        val list = watchList(starred, played, followed, library, turn) { c.exclusions.isArtistExcluded(it.id, it.name) }
        prefs.edit().putInt(TURN, turn + 1).apply()
        return list
    }

    data class FavoriteRef(val id: String, val name: String)

    /** One notification for new releases by starred artists ("New from X, Y and 3 more"); every new release is marked as announced. */
    private suspend fun notify() {
        val pending = c.db.releases().fresh(LocalDate.now().year - 1).filter { it.notifiedAt == 0L }
        if (pending.isEmpty()) return
        val starred = c.artistPrefs.favorites.value.map { it.id }.toSet()
        val worth = pending.filter { it.artistId in starred }
        if (worth.isNotEmpty() && c.settings.current.releaseNotifications) {
            val names = worth.map { it.artistName }.distinct()
            val title = when (names.size) {
                1 -> tr(R.string.new_from, names[0])
                2 -> tr(R.string.new_from_and, names[0], names[1])
                else -> tr(R.string.new_from_and_more, names[0], names[1], names.size - 2)
            }
            val text = worth.take(4).joinToString(" · ") { "${it.title} (${it.kind.lowercase()})" }
            Notifications.post(c.app, Notifications.NEW_RELEASES, NOTIFICATION_ID, title, text, "prism://releases")
        }
        c.db.releases().setNotified(pending.map { it.id }, System.currentTimeMillis())
    }

    /**
     * The Release Radar playlist, made once a week (the week starts on Friday, when most music comes out) or when
     * [force]d: new singles and EPs whole, new albums' three most played songs, from the last 14 days, up to 40.
     */
    suspend fun rebuildPlaylist(force: Boolean = false) {
        val today = LocalDate.now()
        val week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY)).toString()
        if (!force && prefs.getString(PLAYLIST_WEEK, null) == week && c.db.mixes().get(MadeForYou.RADAR) != null) return
        val releases = c.db.releases().fresh(today.year - 1).filter { isRecent(it, today, 14) }
        val songs = ArrayList<Song>()
        for (r in releases) {
            if (songs.size >= MAX_SONGS) break
            val page = runCatching { withContext(Dispatchers.IO) { c.ytm.album(r.id) } }.getOrNull() ?: continue
            val tracks = page.songs.filter { !c.library.isDisliked(it.id) }
            val picked = if (r.kind == "Album") tracks.sortedByDescending { page.trackPlays[it.id] ?: 0L }.take(3) else tracks
            picked.forEach { if (songs.size < MAX_SONGS && songs.none { s -> s.id == it.id }) songs += it }
        }
        val artists = releases.map { it.artistName }.distinct()
        c.forYou.saveMix(
            MadeForYou.RADAR, tr(R.string.release_radar),
            if (songs.isEmpty()) tr(R.string.new_music_from_your_artists_will) else tr(R.string.new_from, MadeForYou.namesLine(artists)),
            artists.take(4), RADAR_COLOR, songs,
        )
        prefs.edit().putString(PLAYLIST_WEEK, week).apply()
    }

    // ------------------------------------------------------------------ Deezer dates

    /** The artist's releases on Deezer, normalized title -> release date ("2026-10-16"); the artist's Deezer id is kept. */
    private suspend fun deezerDates(artist: String): Map<String, String> {
        val key = "dz:${norm(artist)}"
        val id = prefs.getString(key, null) ?: (Deezer.artistId(c.http, artist) ?: "").also { prefs.edit().putString(key, it).apply() }
        return if (id.isEmpty()) emptyMap() else Deezer.releaseDates(c.http, id)
    }

    /** Deezer's public API (no key), paced gently. */
    object Deezer {
        private val json = Json { ignoreUnknownKeys = true }

        private suspend fun get(http: okhttp3.OkHttpClient, url: okhttp3.HttpUrl): JsonObject = withContext(Dispatchers.IO) {
            delay(DEEZER_GAP_MS)
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException(tr(R.string.deezer_answered_http, r.code))
                json.parseToJsonElement(r.body.string()).jsonObject
            }
        }

        /** The artist of exactly that name with the most fans (null when there's none). */
        suspend fun artistId(http: okhttp3.OkHttpClient, name: String): String? =
            get(http, "https://api.deezer.com/search/artist".toHttpUrl().newBuilder().addQueryParameter("q", name).addQueryParameter("limit", "10").build())
                .arr("data").orEmpty().mapNotNull { it as? JsonObject }
                .filter { norm(it.str("name") ?: "") == norm(name) }
                .maxByOrNull { it.str("nb_fan")?.toLongOrNull() ?: 0L }
                ?.str("id")

        /**
         * The artist's releases' dates, by title: under its exact title ([exact], "=…"), and under its loose one
         * ([norm]) with the earliest date among editions sharing it (the original, not a later "Track by Track" or
         * acoustic edition). Look up with [dateFor].
         */
        suspend fun releaseDates(http: okhttp3.OkHttpClient, artistId: String): Map<String, String> {
            val albums = get(http, "https://api.deezer.com/artist/$artistId/albums".toHttpUrl().newBuilder().addQueryParameter("limit", "60").build())
            val out = HashMap<String, String>()
            albums.arr("data").orEmpty().mapNotNull { it as? JsonObject }.forEach { a ->
                val title = a.str("title") ?: return@forEach
                val date = a.str("release_date") ?: return@forEach
                out.merge("=" + exact(title), date) { old, new -> minOf(old, new) }
                out.merge(norm(title), date) { old, new -> minOf(old, new) }
            }
            return out
        }

        /** [title]'s date in [dates] ([releaseDates]): the exact title's, else the loose one's. */
        fun dateFor(dates: Map<String, String>, title: String): String? = dates["=" + exact(title)] ?: dates[norm(title)]

        private fun exact(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }

    companion object {
        private const val TAG = "ReleaseRadar"
        private const val LAST_RUN = "last_run"
        private const val CHECKED = "checked"
        private const val PLAYLIST_WEEK = "playlist_week"
        private const val TURN = "turn"
        /** Artists looked at in one go (about a second each): 200 since 1.8.1 (was 150). */
        const val MAX_ARTISTS = 200
        /** Followed and library artists looked at each time however many are starred and played. */
        private const val MIN_ROTATING = 50
        private const val MAX_SONGS = 40
        private const val PAGE_GAP_MS = 1_000L
        private const val DEEZER_GAP_MS = 250L
        private const val NOTIFICATION_ID = 4101
        private const val RADAR_COLOR = 0xFF636363.toInt()

        /**
         * Who to look at this time ([turn] counts the looks). Starred artists and the 30 most played lately always (Reddit,
         * 1.7.9: "New from your artists" missed some of the listener's artists: followed and library artists came first,
         * so with many of them the cap was reached before the ones actually played, which were never looked at). The
         * followed and library artists past the cap take turns, a different stretch each look, so every one of them
         * comes up within a few looks instead of the same ones always being left out.
         */
        fun watchList(
            starred: List<FavoriteRef>, played: List<FavoriteRef>, followed: List<FavoriteRef>, library: List<FavoriteRef>,
            turn: Int, max: Int = MAX_ARTISTS, excluded: (FavoriteRef) -> Boolean = { false },
        ): List<FavoriteRef> {
            val must = (starred + played.take(30)).distinctBy { it.id }.filterNot(excluded)
            val mustIds = must.map { it.id }.toSet()
            val rest = (followed + library).distinctBy { it.id }.filter { it.id !in mustIds && !excluded(it) }
            val room = (max - must.size).coerceAtLeast(MIN_ROTATING)
            if (rest.size <= room) return must + rest
            val start = ((turn.toLong() * room) % rest.size).toInt()
            return must + (rest.drop(start) + rest.take(start)).take(room)
        }

        /** The releases on an artist's page: its shelves of albums (YouTube's Albums, Singles & EPs, in any language). */
        fun discography(page: ArtistPage): List<Seen> = page.shelves
            .filter { s -> s.items.isNotEmpty() && s.items.all { it is AlbumItem } }
            .flatMap { s -> s.items.filterIsInstance<AlbumItem>() }
            .distinctBy { it.id }
            .map { a -> val (kind, year) = kindAndYear(a.subtitle); Seen(a.id, a.title, kind, year, a.thumbnail, a.playlistId) }

        data class Seen(val id: String, val title: String, val kind: String, val year: Int, val thumbnail: String?, val playlistId: String?)

        /** "Single • 2026" -> (Single, 2026); "2024" (an album) -> (Album, 2024); "EP • 2025" -> (EP, 2025). */
        fun kindAndYear(subtitle: String): Pair<String, Int> {
            val parts = subtitle.split('•', '·').map { it.trim() }.filter { it.isNotEmpty() }
            val year = parts.lastOrNull { it.length == 4 && it.all(Char::isDigit) }?.toInt() ?: 0
            val kind = when (parts.firstOrNull()?.lowercase()) { "single" -> "Single"; "ep" -> "EP"; else -> "Album" }
            return kind to year
        }

        /** A title (or name) to match by: lower case, without bracketed parts, an "EP"/"Single" tag, or punctuation. */
        fun norm(s: String): String = s.lowercase()
            .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
            .replace(Regex("""\s+-\s+(single|ep)$"""), "")
            .replace(Regex("""\s+(ep|single)$"""), "")
            .filter { it.isLetterOrDigit() }

        /**
         * #311 (1.8.1): the New releases page's "Favorite artists only" (kept with its other filters, in the library's
         * sort choices; Home's section follows it). Off, every watched artist's releases show, as before.
         */
        const val STARRED_ONLY = "releases:starred_only"

        /** The releases by the starred artists ([starred], their ids). */
        fun fromFavorites(releases: List<ReleaseEntity>, starred: Set<String>): List<ReleaseEntity> = releases.filter { it.artistId in starred }

        /**
         * The artists to pick one of on the New releases page (#311): those with releases in [releases], the ones played
         * most lately ([mostPlayed], artist ids, most first) leading, then by name; as (id, name).
         */
        fun artistsToPick(releases: List<ReleaseEntity>, mostPlayed: List<String>, max: Int = 12): List<Pair<String, String>> {
            val rank = mostPlayed.withIndex().associate { (i, id) -> id to i }
            return releases.distinctBy { it.artistId }
                .sortedWith(compareBy<ReleaseEntity> { rank[it.artistId] ?: Int.MAX_VALUE }.thenBy { it.artistName.lowercase() })
                .take(max)
                .map { it.artistId to it.artistName }
        }

        /** Out within the last [days] days, by its date where known, else by when Prism first saw it. */
        fun isRecent(r: ReleaseEntity, today: LocalDate, days: Int): Boolean {
            val date = r.releaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: java.time.Instant.ofEpochMilli(r.firstSeenAt).atZone(ZoneId.systemDefault()).toLocalDate()
            val age = ChronoUnit.DAYS.between(date, today)
            return age in -7..days
        }

        /** Every three days, on any network (an artist page is small), and once soon after start-up when it's due. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            wm.enqueueUniquePeriodicWork(
                "release_radar", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReleaseRadarWorker>(3, TimeUnit.DAYS).setConstraints(net).build(),
            )
        }

        /** Looks now (Home's first load when it's been a while, or after picking artists). */
        fun runSoon(context: Context) {
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "release_radar_now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReleaseRadarWorker>().setConstraints(net).build(),
            )
        }
    }
}

class ReleaseRadarWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        return runCatching { c.releaseRadar.refresh(minHours = 12) }.fold({ Result.success() }, { Result.retry() })
    }
}
