package com.prism.music.data.foryou

import com.prism.music.data.model.Song

/**
 * What goes on the end of the queue when autoplay or a radio nears its end ([com.prism.music.playback.PlaybackService.maybeAutoplay]),
 * apart from the player and the network, so a whole session of it can be played through in a test.
 *
 * With "No repeats this session" off (the default) and the radio untuned, this is exactly what 1.8.1 did: one answer
 * from YouTube, today's rests and refusals, [Variety.arrange]. With it on, nothing heard this session goes in, and a radio
 * that runs out of new songs isn't the end of the music: see [next].
 */
object RadioTopUp {
    /** A batch: a few by anyone, never twice running ([Variety.arrange]). */
    const val BATCH = 25
    const val PER_ARTIST = 3
    /** Below this many new songs, a top-up with no repeats looks further. */
    const val ENOUGH = 8
    /** How many more answers it may ask YouTube for in one top-up. */
    const val MAX_ASKS = 4

    /** One answer of a radio. */
    class Page(val songs: List<Song>, val continuation: String?)

    /** What leaves a song out of any batch: in the queue, heard in the last hours, skipped from a suggestion, not wanted. */
    class Rules(
        val inQueue: Set<String>,
        val heardLately: Set<String>,
        val turnedDown: (Song) -> Boolean,
        /** Not disliked, not excluded, allowed by the explicit filter. */
        val allowed: (Song) -> Boolean,
    )

    /** The batch as 1.8.1 made it, tuned when a [tune] is set. */
    fun batch(
        mine: List<Song>, youtube: List<Song>, songRadio: Boolean, rules: Rules,
        tune: RadioTune = RadioTune(), signals: RadioSignals = RadioSignals(), seed: Long = 0,
    ): List<Song> {
        // Not what was heard in the last few hours, nor what the listener turned down (skipped at once when a
        // suggestion): YouTube's radio for a favourite brings the same songs back day after day otherwise.
        val theirs = RadioTuner.apply(youtube.filter { it.id !in rules.heardLately && !rules.turnedDown(it) }, tune, signals, seed)
        val own = RadioTuner.apply(mine, tune, signals, seed + 1)
        val base = when {
            songRadio || own.isEmpty() -> 1.0
            theirs.isEmpty() -> 0.0
            // Both: YouTube's radio leads, your own songs that belong with it in between (about two in five).
            else -> 0.6
        }
        // An artist a few times a batch at most, never twice running.
        return Variety.arrange(own, theirs, BATCH, RadioTuner.freshShare(base, tune), PER_ARTIST) { it.primaryArtist.lowercase() }
            .distinctBy { it.id }
            .filter { it.id !in rules.inQueue && rules.allowed(it) }
    }

    /** No repeats: a session's heard songs, and how to ask for more. */
    class NoRepeats(
        val heard: (Song) -> Boolean,
        /** Songs the radio can move on to when its own runs out, most fitting first. */
        val otherSeeds: List<String>,
        /** Asks a radio: its next page with a token, its first without. Null when that failed. Null here: no asking (offline). */
        val ask: (suspend (seed: String, token: String?) -> Page?)?,
        /** Prism's own radio, from the listener's music, for when YouTube's have nothing new. */
        val ownRadio: suspend () -> List<Song>,
        /** The last resort: everything that could come round again, with when it was last heard. */
        val again: () -> List<Song>,
        val heardAt: (Song) -> Long?,
    )

    class Answer(
        val add: List<Song>,
        val continuation: String?,
        /** The radio [continuation] belongs to. */
        val seed: String,
        /** Nothing new was left anywhere: these are songs coming round again, the ones heard longest ago. */
        val cameRound: Boolean = false,
    )

    /**
     * The songs to add. Without [noRepeats], [batch] of [first]. With it:
     * 1. Everything heard this session is left out, and YouTube's next pages are read while there are too few new songs.
     * 2. When a radio's pages run out, the radio of another song from this session takes over (different artists first, so
     *    it goes somewhere new); the queue carries on with that radio from then on.
     * 3. Still nothing: Prism's own radio, from the listener's music.
     * 4. Still nothing, and it isn't only that YouTube couldn't be reached: the songs heard longest ago come round again,
     *    the older half only, rather than the music stopping dead. [Answer.cameRound] says so, for the listener to be told.
     */
    suspend fun next(
        first: Page?, seed: String, continuation: String?, mine: List<Song>, songRadio: Boolean, rules: Rules,
        tune: RadioTune = RadioTune(), signals: RadioSignals = RadioSignals(), shuffle: Long = 0,
        noRepeats: NoRepeats? = null,
    ): Answer {
        if (noRepeats == null) return Answer(batch(mine, first?.songs.orEmpty(), songRadio, rules, tune, signals, shuffle), if (first != null) first.continuation else continuation, seed)
        val fresh: (Song) -> Boolean = { !noRepeats.heard(it) && it.id !in rules.inQueue && it.id !in rules.heardLately && !rules.turnedDown(it) && rules.allowed(it) }
        val found = LinkedHashMap<String, Song>()
        var page = first
        var from = seed
        var token = continuation
        var answered = first != null
        var asks = 0
        val seeds = ArrayDeque(noRepeats.otherSeeds.filter { it != seed })
        val ask = noRepeats.ask
        // Two answers failing is the network rather than one radio: no use going on. The radio keeps its place for next time.
        var failed = if (first == null && ask != null) 1 else 0
        while (true) {
            page?.let { p ->
                p.songs.filter(fresh).forEach { found.putIfAbsent(it.id, it) }
                token = p.continuation
            }
            if (found.size >= ENOUGH || ask == null || asks >= MAX_ASKS || failed >= 2) break
            asks++
            // Its next page; with no more pages, or when that page wouldn't come, another song's radio.
            val next = token.takeIf { page != null }
            val asking = if (next != null) from else seeds.removeFirstOrNull() ?: break
            page = ask(asking, next)
            if (page == null) failed++ else { from = asking; answered = true }
        }
        val own = mine.filter { !noRepeats.heard(it) }
        val add = batch(own, found.values.toList(), songRadio, rules, tune, signals, shuffle)
        if (add.isNotEmpty()) return Answer(add, token, from)
        val more = noRepeats.ownRadio().filter(fresh)
        val ownBatch = batch(more, emptyList(), false, rules, tune, signals, shuffle)
        if (ownBatch.isNotEmpty()) return Answer(ownBatch, token, from)
        // YouTube wasn't reached at all: not a reason to start repeating (the next song tries again).
        if (ask != null && !answered) return Answer(emptyList(), token, from)
        return Answer(comingRound(noRepeats.again(), noRepeats.heardAt, rules), token, from, cameRound = true)
    }

    /** Of [songs], the older half by [heardAt] (songs not heard yet count as oldest), oldest first, at most a batch. */
    fun comingRound(songs: List<Song>, heardAt: (Song) -> Long?, rules: Rules): List<Song> {
        val can = songs.distinctBy { it.id }.filter(rules.allowed).sortedBy { heardAt(it) ?: 0L }
        return can.take((can.size + 1) / 2).take(BATCH)
    }

    /**
     * Songs a dry radio can move on to: the session's, newest first, an artist once each, other artists than the
     * [current] one's first. Only real songs: a file or a live stream has no radio.
     */
    fun otherSeeds(session: List<Song>, current: Song?, count: Int = 3): List<String> {
        val artist = current?.primaryArtist?.lowercase().orEmpty()
        return session.asReversed().filter { !it.isLocal && !it.isLive && !it.isEpisode && it.id != current?.id }
            .distinctBy { it.primaryArtist.lowercase() }
            .sortedBy { it.primaryArtist.lowercase() == artist }
            .take(count).map { it.id }
    }
}

/**
 * How a radio is tuned (1.8.2), each from -1 to 1, 0 being as it comes. Each moves only on what the phone really knows:
 * - [newness]: familiar (-1) to new (1), from the listener's own history: exact. Also how much of a batch is their own
 *   music.
 * - [depth]: hits (-1) to deep cuts (1), from whether a song is on its artist's Top songs on YouTube Music, looked up for
 *   a few artists a batch. An artist not looked up yet says nothing.
 * - [energy]: calm (-1) to energetic (1), from the tempo and loudness of songs already scanned ([RadioTuner.energyOf]). A
 *   song not scanned says nothing, and most songs new to a listener aren't scanned: this one is weak on new music.
 */
data class RadioTune(val newness: Float = 0f, val depth: Float = 0f, val energy: Float = 0f) {
    val neutral: Boolean get() = newness == 0f && depth == 0f && energy == 0f
}

/** What the phone knows of a song. Null: it doesn't know, and the song isn't moved either way. */
class RadioSignals(
    val heard: (Song) -> Boolean? = { null },
    val onTopList: (Song) -> Boolean? = { null },
    val energy: (Song) -> Double? = { null },
)

object RadioTuner {
    /** A tuned list never comes out smaller than this while it has more to give: a radio has to carry on. */
    const val MIN_LEFT = 5

    /** How likely [song] is to stay in the batch: 1 as it comes, 0 never, above 1 leads it. Unknown signals leave it at 1. */
    fun chance(song: Song, tune: RadioTune, signals: RadioSignals): Double {
        var w = 1.0
        if (tune.newness != 0f) signals.heard(song)?.let { known -> w *= if (known) 1.0 - tune.newness else 1.0 + tune.newness }
        if (tune.depth != 0f) signals.onTopList(song)?.let { top -> w *= if (top) 1.0 - tune.depth else 1.0 + tune.depth }
        if (tune.energy != 0f) signals.energy(song)?.let { e -> w *= 1.0 + tune.energy * (2 * e - 1) }
        return w.coerceAtLeast(0.0)
    }

    /**
     * [list] as tuned: each song stays by its [chance], and those that are left are ordered with the most fitting towards
     * the front. Neutral, [list] itself, untouched. [seed] makes one batch's draw repeatable.
     */
    fun apply(list: List<Song>, tune: RadioTune, signals: RadioSignals, seed: Long): List<Song> {
        if (tune.neutral || list.isEmpty()) return list
        val rnd = java.util.Random(seed)
        val scored = list.map { it to chance(it, tune, signals) }
        val (kept, dropped) = scored.partition { (_, w) -> rnd.nextDouble() < w }
        val topUp = dropped.sortedByDescending { it.second }.take((MIN_LEFT - kept.size).coerceAtLeast(0))
        val left = kept + topUp
        // Stable: songs nothing is known of keep YouTube's own order, which is how well they fit the seed.
        return left.sortedByDescending { it.second }.map { it.first }
    }

    /** The share of a batch that's YouTube's rather than the listener's own, moved by the familiar ↔ new slider. */
    fun freshShare(base: Double, tune: RadioTune): Double = when {
        tune.newness > 0 -> base + (1 - base) * tune.newness
        tune.newness < 0 -> base * (1 + 0.8 * tune.newness)
        else -> base
    }

    /**
     * 0 (calm) .. 1 (energetic) from a scan: tempo (70 to 170 BPM) and loudness (-20 to -6 LUFS), whichever are known. Null
     * when neither is. Rough on purpose: a tempo can read half or double, and loud isn't the same as lively.
     */
    fun energyOf(bpm: Double?, lufs: Float?): Double? {
        val parts = listOfNotNull(
            bpm?.let { ((it - 70.0) / 100.0).coerceIn(0.0, 1.0) },
            lufs?.let { ((it + 20.0) / 14.0).coerceIn(0.0, 1.0) },
        )
        return if (parts.isEmpty()) null else parts.average()
    }
}
