package com.prism.music.data.foryou

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * What the listener made of Prism's recommendations (1.7.9), kept on the phone: which songs Prism served on which day
 * (a Daily Mix, the time-of-day mix, radio and autoplay), and which of those they skipped straight away or heard
 * through. Plays under ten seconds never reach the play history, so before this a recommendation skipped at once
 * left no trace and came back the next day.
 * - Served songs rest for a few days ([servedDaysAgo], [Variety.rest]): tomorrow's mixes don't repeat today's.
 * - A song skipped from a recommendation ([turnedDown]): a new one once, one you know twice, is left out of them
 *   for [Variety.SKIP_DAYS] days. Hearing it through clears that.
 * - An artist whose recommended songs are skipped again and again, and never heard through, counts for less
 *   ([artistDoubt]).
 */
class RecFeedback(context: Context) {
    private val prefs = context.getSharedPreferences("for_you_feedback", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var state: State = runCatching { json.decodeFromString(State.serializer(), prefs.getString(KEY, null) ?: "{}") }.getOrDefault(State())

    @Serializable
    data class State(
        /** Song id → the epoch day Prism last served it. */
        val served: Map<String, Int> = emptyMap(),
        /** Song id → the epoch days it was skipped from a recommendation. */
        val skipped: Map<String, List<Int>> = emptyMap(),
        /** Song ids heard through from a recommendation (kept as known-good). */
        val heard: Map<String, Int> = emptyMap(),
        /** Main artist (lower case) → recent skips of their recommended songs, and recent songs heard through. */
        val artistSkips: Map<String, List<Int>> = emptyMap(),
        val artistHeard: Map<String, List<Int>> = emptyMap(),
    )

    private fun today() = LocalDate.now().toEpochDay().toInt()

    @Synchronized
    private fun change(f: (State) -> State) {
        val now = today()
        val s = f(state).let { s ->
            // Only what still matters is kept: two weeks of serving, two months of skips.
            s.copy(
                served = s.served.filterValues { now - it <= 14 }.let { m -> if (m.size > MAX_SERVED) m.entries.sortedByDescending { it.value }.take(MAX_SERVED).associate { it.key to it.value } else m },
                skipped = s.skipped.mapValues { (_, d) -> d.filter { now - it <= Variety.SKIP_DAYS } }.filterValues { it.isNotEmpty() },
                heard = s.heard.filterValues { now - it <= 180 },
                artistSkips = s.artistSkips.mapValues { (_, d) -> d.filter { now - it <= 30 }.takeLast(20) }.filterValues { it.isNotEmpty() },
                artistHeard = s.artistHeard.mapValues { (_, d) -> d.filter { now - it <= 30 }.takeLast(20) }.filterValues { it.isNotEmpty() },
            )
        }
        state = s
        prefs.edit().putString(KEY, json.encodeToString(State.serializer(), s)).apply()
    }

    /** Prism offered [ids] today (a mix made, songs added by radio or autoplay). */
    fun served(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val d = today()
        change { s -> s.copy(served = s.served + ids.associateWith { d }) }
    }

    /** Days since Prism last offered [id] (0 = today), or null. */
    fun servedDaysAgo(id: String): Int? = state.served[id]?.let { today() - it }

    /** The ids Prism offered [daysAgo] days ago (yesterday's mixes, for one). */
    fun servedOn(daysAgo: Int): Set<String> { val d = today() - daysAgo; return state.served.filterValues { it == d }.keys }

    /** [songId] (by [artist]) from a recommendation was skipped within its first moments. */
    fun skipped(songId: String, artist: String) {
        val d = today()
        change { s ->
            s.copy(
                skipped = s.skipped + (songId to (s.skipped[songId].orEmpty() + d)),
                artistSkips = if (artist.isBlank()) s.artistSkips else s.artistSkips + (artist.lowercase() to (s.artistSkips[artist.lowercase()].orEmpty() + d)),
            )
        }
    }

    /** [songId] from a recommendation was heard through: it's welcome again. */
    fun heard(songId: String, artist: String) {
        val d = today()
        change { s ->
            s.copy(
                skipped = s.skipped - songId,
                heard = s.heard + (songId to d),
                artistHeard = if (artist.isBlank()) s.artistHeard else s.artistHeard + (artist.lowercase() to (s.artistHeard[artist.lowercase()].orEmpty() + d)),
            )
        }
    }

    /** Whether [songId] is left out of recommendations for now: skipped once if it was new to them, twice if known. */
    fun turnedDown(songId: String, known: Boolean): Boolean {
        val n = state.skipped[songId]?.size ?: return false
        return n >= if (known) 2 else 1
    }

    /**
     * 0..1, how much an artist's songs are held back as recommendations: their recommended songs skipped often lately
     * and rarely heard through. 0 for most artists.
     */
    fun artistDoubt(artist: String): Double {
        val a = artist.lowercase()
        val skips = state.artistSkips[a]?.size ?: 0
        val heard = state.artistHeard[a]?.size ?: 0
        if (skips < 3) return 0.0
        return ((skips - 2 * heard) / 6.0).coerceIn(0.0, 0.8)
    }

    /** Everything forgotten (Settings → Recommendations → start over). */
    fun clear() = change { State() }

    private companion object {
        const val KEY = "state"
        const val MAX_SERVED = 3000
    }
}
