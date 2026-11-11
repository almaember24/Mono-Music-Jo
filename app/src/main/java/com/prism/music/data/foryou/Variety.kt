package com.prism.music.data.foryou

import kotlin.math.ln
import kotlin.math.pow

/**
 * What keeps Prism's recommendations from going round in circles (1.7.9, Reddit: "it always plays the same songs").
 * Pure, so it's unit-tested apart from where the songs come from:
 * - [rest]: how much a song the listener knows is held back today (played a lot lately, served in yesterday's mixes);
 * - [sample]: the familiar part of a mix drawn by weighted chance, so every song that fits gets its turn, not the
 *   same top thirty every day;
 * - [arrange]: a mix's order, familiar and new in turn, its artists spread out (a few songs each at most, never two
 *   running) and taken in turn across the mix's artists rather than mostly the first one.
 */
object Variety {
    /**
     * 0..1, how much of a known song's weight it keeps today. [playsLately] = plays in the last [LATELY_DAYS] days,
     * [hoursSinceLast] since it last played (null: never), [servedDaysAgo] since a mix last offered it (null: never).
     */
    fun rest(playsLately: Int, hoursSinceLast: Double?, servedDaysAgo: Int?): Double {
        var keep = 1.0
        // Heard today or yesterday: it can wait.
        if (hoursSinceLast != null) keep *= (hoursSinceLast / 48.0).coerceIn(0.15, 1.0)
        // Played again and again this week: on repeat already, Prism needn't add to it.
        if (playsLately > 2) keep *= 0.6.pow((playsLately - 2).coerceAtMost(4))
        // In a mix yesterday or the day before: something else today.
        if (servedDaysAgo != null) keep *= when (servedDaysAgo) { 0 -> 0.05; 1 -> 0.2; 2 -> 0.45; 3 -> 0.7; else -> 1.0 }
        return keep.coerceIn(0.0, 1.0)
    }

    /**
     * [n] of [items] drawn without putting back, each in proportion to [weight] (Efraimidis–Spirakis: the n largest
     * u^(1/w)). [seed] makes a day's draw the same all day; [sharpness] above 1 favours the heavy ones more.
     */
    fun <T> sample(items: List<T>, n: Int, seed: Long, sharpness: Double = 1.0, weight: (T) -> Double): List<T> {
        if (n <= 0 || items.isEmpty()) return emptyList()
        val rnd = java.util.Random(seed)
        return items.mapNotNull { item ->
            val w = weight(item).coerceAtLeast(0.0).pow(sharpness)
            if (w <= 0.0) null else item to ln(rnd.nextDouble().coerceAtLeast(1e-12)) / w
        }.sortedByDescending { it.second }.take(n).map { it.first }
    }

    /**
     * A mix of [size] from [known] and [fresh] (each best first): new songs make up [freshShare] of it, spread through
     * it; no artist more than [perArtist] times; never the same artist twice running; and the known songs taken in turn
     * across their artists (the first artist's top song, then the second's…) so a mix of five artists isn't mostly one.
     */
    fun <T> arrange(
        known: List<T>, fresh: List<T>, size: Int, freshShare: Double, perArtist: Int, artistOf: (T) -> String,
    ): List<T> {
        val k = roundRobin(known, artistOf).toMutableList()
        val f = fresh.toMutableList()
        val out = ArrayList<T>(size)
        val count = HashMap<String, Int>()
        var freshTaken = 0
        fun fits(t: T, strict: Boolean): Boolean {
            val a = artistOf(t)
            if (a.isBlank()) return true
            if ((count[a] ?: 0) >= perArtist) return false
            return !strict || out.isEmpty() || artistOf(out.last()) != a
        }
        while (out.size < size && (k.isNotEmpty() || f.isNotEmpty())) {
            // New songs come in step: after this pick, the mix's share of them should be close to [freshShare].
            val wantFresh = f.isNotEmpty() && (k.isEmpty() || freshTaken < (out.size + 1) * freshShare - 0.001)
            val first = if (wantFresh) f else k
            val second = if (wantFresh) k else f
            val pick = sequenceOf(first to true, second to true, first to false, second to false)
                .firstNotNullOfOrNull { (list, strict) -> list.firstOrNull { fits(it, strict) }?.let { list to it } }
            if (pick == null) {
                // Every artist left is at its limit: the mix ends shorter rather than overloaded with one.
                break
            }
            val (list, item) = pick
            list.remove(item)
            if (list === f) freshTaken++
            out += item
            artistOf(item).takeIf { it.isNotBlank() }?.let { count.merge(it, 1, Int::plus) }
        }
        return out
    }

    /** [list] (best first) reordered so its artists take turns, each keeping its own songs' order. */
    fun <T> roundRobin(list: List<T>, artistOf: (T) -> String): List<T> {
        val queues = LinkedHashMap<String, ArrayDeque<T>>()
        list.forEach { queues.getOrPut(artistOf(it).lowercase()) { ArrayDeque() }.addLast(it) }
        val out = ArrayList<T>(list.size)
        while (queues.values.any { it.isNotEmpty() }) queues.values.forEach { q -> q.removeFirstOrNull()?.let(out::add) }
        return out
    }

    /** Of two days' mixes, the share of [today] that was already in [yesterday] (0..1). */
    fun overlap(yesterday: Collection<String>, today: Collection<String>): Double =
        if (today.isEmpty()) 0.0 else today.count { it in yesterday.toSet() }.toDouble() / today.size

    /** A day's seed for [sample], different for each [salt] (a mix's id). */
    fun daySeed(day: String, salt: String): Long = (day + "|" + salt).hashCode().toLong() * 2654435761L

    /** How long a song skipped from a recommendation stays out of them. */
    const val SKIP_DAYS = 60
    /** Plays in this many days count as "lately" for [rest]. */
    const val LATELY_DAYS = 7
}
