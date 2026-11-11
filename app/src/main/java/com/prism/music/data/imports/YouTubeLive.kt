package com.prism.music.data.imports

import android.content.Context
import android.content.SharedPreferences
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/** One song on YouTube Music's History page, and the shelf it was under ("Today", "Yesterday"… in the account's language). */
@Serializable
data class HistoryEntry(val id: String, val section: String = "")

/** The History page as it was at [at], for the account [account] ([YouTubeLive.accountOf]). */
@Serializable
data class HistorySnapshot(val entries: List<HistoryEntry>, val at: Long, val account: String = "")

/**
 * What changed on YouTube Music's History page between two reads (1.8.1). YouTube says nothing of when a song was played,
 * only its order (newest first) under day shelves, so a song played since the last read is one newly at the top:
 * either added there with the older entries where they were, or moved up from further down (YouTube lists a song once
 * and moves it up when it's played again). Pure, for the tests.
 */
object HistoryDiff {
    /** How many entries below the new ones are compared with the last read (further down, the page may be cut). */
    const val COMPARE = 10
    /** Entries that must line up with the last read for a place to count as where the new ones end. */
    const val MIN_ANCHOR = 3

    /**
     * How many entries at the top of [now] were played since [before] (both newest first). Null when the two have
     * nothing in common (another account, a cleared history, more plays than the page holds): the page is taken as the
     * new starting point and nothing is counted.
     */
    fun newOnTop(before: List<String>, now: List<String>): Int? {
        if (now.isEmpty()) return 0
        // The last read was empty (a history that had nothing yet): all of it was played since.
        if (before.isEmpty()) return now.size
        val known = before.toHashSet()
        if (now.none { it in known }) return null
        val need = minOf(MIN_ANCHOR, before.size)
        for (k in now.indices) {
            val tail = now.subList(k, now.size)
            if (tail.first() !in known) continue
            // Added at the top: the older entries are where they were.
            if (linesUp(tail, before, need)) return k
            // Moved up: the songs played again left their old places, and the others closed up.
            if (k > 0 && linesUp(tail, minusFirst(before, now.subList(0, k)), need)) return k
        }
        // Nothing lines up (YouTube reordered or removed some): only the songs the last read didn't have, at the top.
        return now.indexOfFirst { it in known }
    }

    private fun linesUp(tail: List<String>, ref: List<String>, need: Int): Boolean {
        val n = minOf(COMPARE, tail.size, ref.size)
        return n > 0 && n >= need && (0 until n).all { tail[it] == ref[it] }
    }

    /** [list] without one occurrence of each of [gone]. */
    private fun minusFirst(list: List<String>, gone: List<String>): List<String> {
        val out = list.toMutableList()
        gone.forEach { out.remove(it) }
        return out
    }

    /**
     * When each of the new entries was most likely played, newest first like [sections] (their shelves' labels), all
     * inside the window [from]..[to] between the two reads. Spread evenly, the newest latest. When the shelves change
     * exactly as often as the window passes midnight ("Today" above "Yesterday"), each day's songs stay on their side
     * of it; [below] is the shelf of the first entry under the new ones (a change there is a midnight too).
     */
    fun times(sections: List<String>, below: String?, from: Long, to: Long, zone: ZoneId): List<Long> {
        val n = sections.size
        if (n == 0) return emptyList()
        if (to <= from) return List(n) { to }
        // Runs of the same shelf, top first.
        val groups = ArrayList<IntRange>()
        var start = 0
        for (i in 1..n) if (i == n || sections[i] != sections[start]) { groups += start until i; start = i }
        val changes = (groups.size - 1) + if (below != null && below != sections.last()) 1 else 0
        val midnights = midnightsBetween(from, to, zone)
        val out = LongArray(n)
        if (changes == 0 || changes != midnights.size) {
            spread(0 until n, from, to, out)
        } else {
            groups.forEachIndexed { g, range ->
                val hi = if (g == 0) to else midnights[g - 1]
                val lo = if (g < changes) midnights[g] else from
                spread(range, lo, hi, out)
            }
        }
        return out.toList()
    }

    /** The entries in [range] spread over (lo, hi): the first (newest) latest, none at either end. */
    private fun spread(range: IntRange, lo: Long, hi: Long, out: LongArray) {
        val m = range.count()
        range.forEachIndexed { j, i -> out[i] = hi - (hi - lo) * (j + 1) / (m + 1) }
    }

    /** The local midnights strictly inside (from, to), latest first. */
    fun midnightsBetween(from: Long, to: Long, zone: ZoneId): List<Long> {
        val out = ArrayList<Long>()
        var day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().plusDays(1)
        while (true) {
            val m = day.atStartOfDay(zone).toInstant().toEpochMilli()
            if (m >= to) break
            if (m > from) out += m
            day = day.plusDays(1)
        }
        return out.reversed()
    }
}

/**
 * "YouTube Music (live)" (1.8.1): while signed in and turned on in Bring your history, every few hours Prism reads the
 * account's YouTube Music History page (one request) and counts the songs newly at the top since the last read as plays
 * from elsewhere (the YouTube Music app, the website, a speaker), a source of its own: Replay counts it only when
 * switched on under "Count history from", and Remove takes it away as any import.
 *
 * - The first read after turning it on is only the starting point: nothing from before is brought in (Takeout brings
 *   the past). So is the first read after a week without one, or for another account.
 * - Times are estimates: spread over the time between two reads, each day's songs on their side of midnight.
 * - A song played twice between two reads is on the page once, so it counts once.
 * - Prism's own plays (which Prism reports to that history) are left out: any song Prism played or reported in the
 *   window, by its id or its name (the rule Takeout's plays get, 3 minutes before to 15 after, held to the window).
 *   So are plays a Takeout already brought; one a Takeout brings later is taken out then.
 * - Each play counts as the song's length (estimated, as Takeout's) and is matched at once: the page names the song.
 * - Nothing on the account or in Prism is ever deleted by it. [guard]: five failures running pause it for a day.
 */
class YouTubeLive(
    val prefs: SharedPreferences,
    private val db: PrismDatabase,
    private val importer: HistoryImporter,
    private val read: suspend () -> List<Pair<String, List<Song>>>,
    /** The signed-in account ([accountOf]); null signed out (then nothing is asked). */
    private val account: () -> String?,
    private val jobs: Jobs,
    private val scope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val log: (String) -> Unit = {},
) {
    /** The background job: [YouTubeLiveWorker] in the app, a fake in tests. */
    interface Jobs {
        fun schedule()
        fun cancel()
    }

    /** In-app tests: the History page, the account and the time, without YouTube. */
    class TestHooks(val history: suspend () -> List<Pair<String, List<Song>>>, val account: String = "0|test", val now: (() -> Long)? = null)

    @androidx.annotation.VisibleForTesting
    @Volatile var forTest: TestHooks? = null

    private fun now(): Long = forTest?.now?.invoke() ?: clock()
    private fun who(): String? = forTest?.account ?: account()

    val guard = LiveGuard(prefs, ImportSource.YOUTUBE_LIVE.key, ::now, log)

    private val _on = MutableStateFlow(isOn(prefs))
    /** Turned on in Bring your history (off by default). */
    val on: StateFlow<Boolean> = _on

    private val _lastCheck = MutableStateFlow(snapshot()?.at)
    /** When the History page was last read (null: not yet, or started over). */
    val lastCheck: StateFlow<Long?> = _lastCheck

    private val lock = Mutex()

    fun setOn(on: Boolean) {
        prefs.edit().putBoolean(ON, on).apply()
        _on.value = on
        guard.reset()
        // Off and on again starts from the page as it is then: what was played while off isn't brought in.
        if (!on) forget()
        reschedule()
        // On: the starting point is read at once (so "from when it was turned on" is now, not in a few hours).
        if (on) scope?.launch(Dispatchers.IO) { run() }
    }

    /** The job while on and signed in; none otherwise. */
    fun reschedule() = keepScheduled(_on.value, who() != null, jobs)

    /** Signed out or another account: the next read is a new starting point. */
    fun forget() {
        prefs.edit().remove(SNAPSHOT).apply()
        _lastCheck.value = null
    }

    fun snapshot(): HistorySnapshot? = prefs.getString(SNAPSHOT, null)?.let {
        runCatching { ImportFiles.json.decodeFromString(HistorySnapshot.serializer(), it) }.getOrNull()
    }

    private fun save(s: HistorySnapshot) {
        prefs.edit().putString(SNAPSHOT, ImportFiles.json.encodeToString(HistorySnapshot.serializer(), s)).commit()
        _lastCheck.value = s.at
    }

    /** One run, if on, signed in and not paused; its guard hears how it went. Returns the plays stored (null: not run). */
    suspend fun run(): Int? {
        if (!_on.value || who() == null || !guard.mayRun()) return null
        return try {
            sync().also { guard.succeeded() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            guard.failed(e)
            null
        }
    }

    /** Reads the History page once and stores what's new on it (see the class); returns how many plays were stored. */
    suspend fun sync(): Int = lock.withLock {
        val who = who() ?: return@withLock 0
        val page = forTest?.history?.invoke() ?: read()
        val t = now()
        val entries = page.flatMap { (label, songs) -> songs.map { HistoryEntry(it.id, label) to it } }
        // Nothing on the page (a history cleared or paused, or a page Prism can't read any more): the last read stays the
        // one to compare with, so a page read again later is never taken as all new.
        if (entries.isEmpty()) { log("the History page had no songs"); return@withLock 0 }
        val before = snapshot()
        val next = HistorySnapshot(entries.take(KEEP).map { it.first }, t, who)
        val why = when {
            before == null -> "starting point"
            !sameAccount(before.account, who) -> "another account: a new starting point"
            t <= before.at || t - before.at > MAX_GAP_MS -> "a new starting point after ${(t - before.at) / 3_600_000} h"
            else -> null
        }
        if (why != null || before == null) { save(next); log("${entries.size} on the History page, $why"); return@withLock 0 }
        val k = HistoryDiff.newOnTop(before.entries.map { it.id }, entries.map { it.first.id })
        if (k == null) { save(next); log("the History page changed beyond recognition: a new starting point"); return@withLock 0 }
        if (k == 0) { save(next); return@withLock 0 }
        val fresh = entries.take(k)
        val times = HistoryDiff.times(fresh.map { it.first.section }, entries.getOrNull(k)?.first?.section, before.at, t, zone())
        val plays = fresh.mapIndexed { i, (_, song) -> song to times[i] }.filter { (s, _) -> !s.isEpisode && !s.isLocal && !s.isLive }
        val outside = withContext(Dispatchers.IO) { outsidePrism(plays, before.at, t) }
        val stored = withContext(Dispatchers.IO) { store(outside) }
        // Only once they're stored: a read that failed on the way compares with the last one again, its plays not lost.
        save(next)
        log("$k new at the top, ${plays.size - outside.size} were Prism's own or a Takeout's, $stored stored")
        stored
    }

    /** [plays] without Prism's own (played or reported in the window) and those a Takeout already has. */
    private suspend fun outsidePrism(plays: List<Pair<Song, Long>>, from: Long, to: Long): List<Pair<Song, Long>> {
        if (plays.isEmpty()) return plays
        val ids = HashSet<String>()
        val keys = HashSet<String>()
        db.imports().prismPlays(from - PrismIndex.BEFORE_MS, to).forEach { r ->
            ids += r.songId
            keys += ImportKeys.songKey(r.title, r.primaryArtist)
            keys += ImportKeys.songKey(r.title, r.artistName)
        }
        sent(from - PrismIndex.BEFORE_MS).forEach { (id, key) -> ids += id; keys += key }
        return plays.filter { (s, _) ->
            val key = keyOf(s)
            s.id !in ids && key !in keys && db.imports().takeoutTimes(key, from - PrismIndex.AFTER_MS, to).isEmpty()
        }
    }

    /** Songs Prism reported to the account's history since [since] (the one playing now has no play of its own yet). */
    private fun sent(since: Long): List<Pair<String, String>> = prefs.getStringSet(SENT, emptySet()).orEmpty().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        if (at < since) null else parts[1] to parts[2]
    }

    private suspend fun store(plays: List<Pair<Song, Long>>): Int {
        if (plays.isEmpty()) return 0
        val session = importer.session(ImportSource.YOUTUBE_LIVE)
        plays.forEach { (s, at) -> session.add(parsed(s, at)) }
        val stats = session.finish()
        // The page names the song: matched at once, no search needed (a match made before stays as it is).
        plays.distinctBy { keyOf(it.first) }.forEach { (s, _) ->
            val m = db.imports().match(keyOf(s)) ?: return@forEach
            if (MatchStatus.of(m.status) == MatchStatus.PENDING) importer.applyMatch(m, MatchResult(MatchStatus.SURE, s, listOf(s)), now())
        }
        return stats.stored
    }

    companion object {
        const val PREFS = "youtube_live"
        private const val ON = "on"
        private const val SNAPSHOT = "snapshot"
        private const val SENT = "sent"
        /** How much of the page is kept to compare with (its newest entries). */
        const val KEEP = 300
        /** Longer than this since the last read (the phone was off, or the job couldn't run): a new starting point. */
        const val MAX_GAP_MS = 7 * 86_400_000L
        /**
         * A live play and a Takeout's play of the same song this close are one (a live play's time is only somewhere
         * between two reads, a few hours apart).
         */
        const val TWIN_MS = 6 * 3_600_000L
        /** How long Prism's reports are remembered. */
        private const val SENT_KEPT_MS = 2 * 86_400_000L

        fun isOn(prefs: SharedPreferences): Boolean = prefs.getBoolean(ON, false)

        /** The job runs only while on and signed in; otherwise there's none, and nothing is asked. */
        fun keepScheduled(on: Boolean, signedIn: Boolean, jobs: Jobs) { if (on && signedIn) jobs.schedule() else jobs.cancel() }

        fun keyOf(s: Song): String = ImportKeys.songKey(s.title, s.primaryArtist)

        fun parsed(s: Song, at: Long): ParsedPlay {
            val length = s.durationSec * 1000L
            return ParsedPlay(
                timestamp = at, playedMs = if (length > 0) length else TakeoutHistory.CAP_MS, title = s.title, artist = s.primaryArtist,
                album = s.album?.title, sourceId = s.id, fullPlay = length > 0, estimated = true, youtubeId = s.id,
            )
        }

        /** Which account a read was for: its number in the sign-in and its email (or channel id while that isn't known). */
        fun accountOf(authUser: Int, email: String, dataSyncId: String): String = "$authUser|" + email.ifBlank { dataSyncId }

        /** The same account, or one not known well enough to say (an email still loading). */
        fun sameAccount(a: String, b: String): Boolean {
            if (a.isBlank() || b.isBlank()) return true
            val (ua, da) = a.substringBefore('|') to a.substringAfter('|', "")
            val (ub, dbb) = b.substringBefore('|') to b.substringAfter('|', "")
            return ua == ub && (da.isBlank() || dbb.isBlank() || da == dbb)
        }

        /**
         * Prism reported [id] to the account's history (PlaybackService): remembered for a couple of days, so the next read
         * knows it as Prism's own even before its play is noted (a song still playing, or skipped before it counted).
         * Nothing is kept while the source is off.
         */
        fun noteSent(context: Context, id: String, key: String, now: Long = System.currentTimeMillis()) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!isOn(prefs)) return
            synchronized(this) {
                val kept = prefs.getStringSet(SENT, emptySet()).orEmpty().filter { line ->
                    (line.substringBefore('\t').toLongOrNull() ?: 0) >= now - SENT_KEPT_MS
                }.toMutableSet()
                kept += "$now\t$id\t$key"
                prefs.edit().putStringSet(SENT, kept).apply()
            }
        }

        /**
         * Sign-in changed (PrismApp watches it): signed out, the job stops and the next read after signing in again is a
         * new starting point; signed in, the job is set up again if the source is on. Read from the preferences, so
         * nothing else is built for the many who never turn it on.
         */
        fun signInChanged(context: Context, signedIn: Boolean, wasSignedIn: Boolean?, jobs: Jobs) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (wasSignedIn == true && !signedIn) prefs.edit().remove(SNAPSHOT).remove(LiveGuard.FAILURES).remove(LiveGuard.PAUSED_UNTIL).apply()
            keepScheduled(isOn(prefs), signedIn, jobs)
        }
    }
}
