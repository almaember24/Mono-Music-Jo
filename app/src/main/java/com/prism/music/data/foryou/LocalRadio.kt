package com.prism.music.data.foryou

import com.prism.music.data.model.Song
import kotlin.math.min

/**
 * Prism's own radio: what to play after some songs, picked from the listener's own music (what they've played, liked,
 * downloaded and keep on the phone), with nothing asked of anyone. A song scores for:
 * - being played around the seeds before ([coListen]: the same sitting, a few songs apart) — the strongest sign two
 *   songs belong together for this listener;
 * - the seed's artist, and its genre;
 * - keys that blend (when DJ mode has read both);
 * - how much the listener likes it ([affinity], 0..1).
 * The most recent seed counts most. Picks never run the same artist twice in a row, and take at most [PER_ARTIST]
 * songs by anyone.
 */
object LocalRadio {
    /** Songs played within this of each other are one sitting. */
    const val SESSION_GAP_MS = 30L * 60_000
    /** How many songs apart still count as "played around" a song. */
    const val WINDOW = 3
    const val PER_ARTIST = 3

    data class Play(val songId: String, val timestamp: Long)

    /**
     * The same song under another upload (YouTube often has two "Beat It"s): its title without bracketed parts
     * ("(feat. …)", "(Remastered)") and its main artist.
     */
    fun sameSong(s: Song): String =
        s.title.lowercase().replace(BRACKETS, "").replace(SPACES, " ").trim() + "|" + s.primaryArtist.lowercase()

    private val BRACKETS = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val SPACES = Regex("""\s+""")

    /** For each song, the songs played around it in the same sitting, weighted 1/distance and summed over all plays. */
    fun coListen(plays: List<Play>): Map<String, Map<String, Double>> {
        val out = HashMap<String, HashMap<String, Double>>()
        val sorted = plays.sortedBy { it.timestamp }
        var start = 0
        for (i in sorted.indices) {
            if (i > 0 && sorted[i].timestamp - sorted[i - 1].timestamp > SESSION_GAP_MS) start = i
            for (j in maxOf(start, i - WINDOW) until i) {
                val a = sorted[i].songId
                val b = sorted[j].songId
                if (a == b) continue
                val w = 1.0 / (i - j)
                out.getOrPut(a) { HashMap() }.merge(b, w, Double::plus)
                out.getOrPut(b) { HashMap() }.merge(a, w, Double::plus)
            }
        }
        return out
    }

    class Pool(
        val songs: List<Song>,
        val coListen: Map<String, Map<String, Double>>,
        val affinity: (Song) -> Double,
        val genreOf: (String) -> String?,
        /** 0..1: how well two songs' keys blend (0 when either isn't known). */
        val keyFit: (String, String) -> Double = { _, _ -> 0.0 },
    )

    data class Pick(val song: Song, val score: Double, val related: Boolean)

    /**
     * Your picks to play beside YouTube's radio: only the ones that belong with the seeds (played around them, the same
     * artist or genre). Favourites that have nothing to do with them fill in only when your music is all there is
     * ([onlyYours]: offline, after a file on the phone, or "Your music"). Reddit, after 1.7.8: "if I select rock it
     * plays NSYNC, if I select opera, NSYNC too".
     */
    fun besideYouTube(picks: List<Pick>, onlyYours: Boolean): List<Pick> = if (onlyYours) picks else picks.filter { it.related }

    /**
     * Up to [count] songs to follow [seeds] (newest last), from [pool], leaving out [exclude]. [jitter]: a small
     * per-song shake (0.9–1.1) so the same seed doesn't always give the same order.
     */
    fun pick(seeds: List<Song>, pool: Pool, exclude: Set<String>, count: Int, jitter: (String) -> Double = { 1.0 }): List<Pick> {
        if (seeds.isEmpty()) return emptyList()
        val weights = seeds.asReversed().take(3).mapIndexed { i, s -> s to listOf(1.0, 0.6, 0.4)[i] }
        val seedIds = seeds.map { it.id }.toSet()
        val seedSongs = seeds.map { sameSong(it) }.toSet()
        val scored = pool.songs.asSequence()
            .filter { it.id !in exclude && it.id !in seedIds && sameSong(it) !in seedSongs }
            .distinctBy { it.id }
            .map { x ->
                var rel = 0.0
                var related = false
                for ((seed, w) in weights) {
                    val co = pool.coListen[seed.id]?.get(x.id) ?: 0.0
                    val sameArtist = seed.primaryArtist.isNotBlank() && seed.primaryArtist.equals(x.primaryArtist, ignoreCase = true)
                    val g = pool.genreOf(seed.id)
                    val sameGenre = g != null && g == pool.genreOf(x.id)
                    val fit = pool.keyFit(seed.id, x.id)
                    if (co > 0 || sameArtist || sameGenre) related = true
                    rel += w * (3.0 * min(1.0, co / 2.0) + 1.2 * (if (sameArtist) 1.0 else 0.0) + 0.8 * (if (sameGenre) 1.0 else 0.0) + 0.5 * fit)
                }
                val like = pool.affinity(x).coerceIn(0.0, 1.0)
                // Something the seeds have nothing to do with only fills in, a long way behind.
                val score = (rel + 1.0 * like) * (if (related) 1.0 else 0.3) * jitter(x.id)
                Pick(x, score, related)
            }
            .sortedByDescending { it.score }
            .distinctBy { sameSong(it.song) }
            .toList()
        // Greedy, for variety: not the artist just picked (nor the last seed's, first), and a few songs each at most.
        val out = ArrayList<Pick>(count)
        val perArtist = HashMap<String, Int>()
        var last = seeds.last().primaryArtist.lowercase()
        val rest = scored.toMutableList()
        while (out.size < count && rest.isNotEmpty()) {
            val i = rest.indexOfFirst { p ->
                val a = p.song.primaryArtist.lowercase()
                (a.isBlank() || a != last) && (perArtist[a] ?: 0) < PER_ARTIST
            }.takeIf { it >= 0 } ?: rest.indexOfFirst { (perArtist[it.song.primaryArtist.lowercase()] ?: 0) < PER_ARTIST }.takeIf { it >= 0 } ?: break
            val p = rest.removeAt(i)
            out += p
            val a = p.song.primaryArtist.lowercase()
            perArtist.merge(a, 1, Int::plus)
            last = a
        }
        return out
    }
}
