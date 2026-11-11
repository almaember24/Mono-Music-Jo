package com.prism.music.data.seasonal

import android.util.Log
import com.prism.music.AppContainer
import com.prism.music.data.foryou.Mix
import com.prism.music.data.model.Song
import com.prism.music.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.concurrent.ConcurrentHashMap

/**
 * The holiday mixes on this phone (1.8.0): made when one is opened in its season, or ahead of that while Home's row of
 * them is on screen on Wi-Fi ([makeAhead]; never at start, never on mobile data), kept in files/seasonal/ for the season
 * and made again about once a week, from YouTube Music signed out ([YouTubeHolidaySources]). Nothing about the listener
 * goes into them, and nothing of theirs goes out.
 */
class SeasonalMixes internal constructor(
    private val dir: File,
    private val scope: CoroutineScope,
    /** Makes a mix's songs for a week (YouTube Music's searches, [HolidayMixMaker.build]); a test makes its own. */
    private val build: suspend (HolidayMix, String) -> List<Song>,
    /** Waits until no song is starting or buffering ([AppContainer.quietNetwork]): only work in the background waits. */
    private val quietNetwork: suspend () -> Unit,
    /** On a network that doesn't charge by use (Wi-Fi), as making ahead needs. */
    private val unmetered: () -> Boolean,
    /** How long Home's row is shown before making ahead starts: Home's own loading goes first. */
    private val aheadAfterMs: Long = AHEAD_AFTER_MS,
) {
    constructor(c: AppContainer) : this(
        File(c.app.filesDir, "seasonal"),
        c.scope,
        { mix, week -> HolidayMixMaker(YouTubeHolidaySources(c.ytm)).build(mix, week) },
        { c.quietNetwork() },
        { c.network.isOnline() && !c.network.metered.value },
    )

    /** The clock the season is read from: the phone's own (its time zone too); a test sets a date of its own. */
    @Volatile var clock: Clock? = null

    fun today(): LocalDate = LocalDate.now(clock ?: Clock.systemDefaultZone())

    /** The season now, or null outside both. */
    fun holiday(): Holiday? = Holiday.on(today())

    /** The mixes Home shows today. */
    fun shown(): List<HolidayMix> = HolidayMixes.on(today())

    @Serializable
    data class Kept(val id: String, val season: String, val made: Long, val songs: List<Song>)

    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, Kept>()
    /** Each mix being made now, so a page and the row's making ahead (or two pages) share one making. */
    private val building = ConcurrentHashMap<String, Deferred<Kept?>>()
    /**
     * Work in the background (the weekly remake, making ahead) one mix at a time: each is a dozen searches, and the phone
     * has better things to do. A mix that's opened never waits on it: it's made at once beside it.
     */
    private val background = Mutex()
    @Volatile private var ahead: Job? = null

    private fun file(mix: HolidayMix) = File(dir, mix.key + ".json")

    /** This season's key: a mix kept last Christmas isn't this Christmas's. */
    private fun season(mix: HolidayMix, date: LocalDate = today()) = "${date.year}-${mix.holiday.name}"

    private fun week(date: LocalDate = today()) = "${date.get(IsoFields.WEEK_BASED_YEAR)}-W${date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"

    /** [mix] as kept for this season, if it is (no network). */
    suspend fun kept(mix: HolidayMix): Kept? {
        val want = season(mix)
        memory[mix.id]?.takeIf { it.season == want }?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(mix)
            val k = runCatching { json.decodeFromString(Kept.serializer(), f.readText()) }.getOrNull()
            if (k != null && k.season != want) { f.delete(); null }
            else k?.also { memory[mix.id] = it }
        }
    }

    /** Kept already in memory, for a menu that opens at once. */
    fun keptNow(id: String): Kept? = HolidayMixes.byId(id)?.let { m -> memory[id]?.takeIf { it.season == season(m) } }

    private fun fresh(k: Kept?) = k != null && System.currentTimeMillis() - k.made <= WEEK_MS

    /**
     * Mix [id] for its page, the car or a pinned section: the kept one (made again behind it once it's a week old), else
     * made now, without waiting for the network to be quiet (the listener is waiting for it). Null out of its season, or
     * when it couldn't be made.
     */
    suspend fun mix(id: String): Mix? {
        val def = HolidayMixes.byId(id) ?: return null
        if (!def.shownOn(today())) return null
        val k = kept(def)
        if (k != null) {
            if (!fresh(k)) scope.launch { runCatching { remake(def) } }
            return toMix(def, k)
        }
        return making(def).await()?.let { toMix(def, it) }
    }

    /** Makes [mix] again in the background, after any song that's starting ([quietNetwork]); the old one stays if this falls short. */
    private suspend fun remake(mix: HolidayMix) {
        background.withLock {
            quietNetwork()
            // Made by another call while this one waited.
            if (fresh(kept(mix))) return
            making(mix).await()
        }
    }

    /**
     * Makes Home's holiday [mixes] ahead, so opening or playing one is instant: while their row is shown (call it when it
     * is; the phone's Home does, and the TV's can), [aheadAfterMs] in, one at a time in their order on Home, each after any
     * song that's starting, on Wi-Fi only (stopping when the phone moves to mobile data). Only the mixes not kept yet this
     * season: a kept one opens at once already. Calling it again while it runs changes nothing. The searches are
     * anonymous, as for a mix that's opened.
     */
    @Synchronized fun makeAhead(mixes: List<HolidayMix>): Job {
        ahead?.takeIf { it.isActive }?.let { return it }
        val todo = mixes.filter { it.shownOn(today()) }.distinctBy { it.id }
        return scope.launch {
            if (todo.isEmpty() || !unmetered()) return@launch
            delay(aheadAfterMs)
            for (mix in todo) {
                if (kept(mix) != null) continue
                background.withLock {
                    quietNetwork()
                    if (!unmetered()) return@launch
                    if (kept(mix) == null) runCatching { making(mix).await() }.onFailure { if (it is CancellationException) throw it }
                }
            }
        }.also { ahead = it }
    }

    /** [mix] being made: the making that's already under way, else a new one (on Prism's scope, so leaving its page doesn't waste it). */
    private fun making(mix: HolidayMix): Deferred<Kept?> {
        building[mix.id]?.let { return it }
        val job = scope.async(start = CoroutineStart.LAZY) { make(mix) }
        building.putIfAbsent(mix.id, job)?.let { job.cancel(); return it }
        job.invokeOnCompletion { building.remove(mix.id, job) }
        job.start()
        return job
    }

    private suspend fun make(mix: HolidayMix): Kept? {
        val songs = withContext(Dispatchers.IO) {
            try { build(mix, week()) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "${mix.key} not made", e)
                emptyList()
            }
        }
        Log.i(TAG, "${mix.key}: ${songs.size} songs")
        if (songs.size < MIN_SONGS) return kept(mix)
        return put(mix, songs)
    }

    /** [songs] kept as [id] for this season, made now (also how a test puts one in place). */
    suspend fun keep(id: String, songs: List<Song>) { HolidayMixes.byId(id)?.let { put(it, songs) } }

    private suspend fun put(mix: HolidayMix, songs: List<Song>): Kept {
        val k = Kept(mix.id, season(mix), System.currentTimeMillis(), songs)
        memory[mix.id] = k
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            runCatching { file(mix).writeText(json.encodeToString(Kept.serializer(), k)) }
        }
        return k
    }

    fun toMix(mix: HolidayMix, k: Kept): Mix {
        val artists = k.songs.map { it.primaryArtist }.filter { it.isNotBlank() }.distinct()
        return Mix(mix.id, tr(mix.name), tr(mix.blurb), artists.take(4), mix.top, k.made, k.songs)
    }

    companion object {
        private const val TAG = "PrismHoliday"
        internal const val WEEK_MS = 7 * 86_400_000L
        /** Fewer than this and the mix isn't kept (YouTube answered badly): the last one stays. */
        private const val MIN_SONGS = 20
        private const val AHEAD_AFTER_MS = 8_000L
    }
}
