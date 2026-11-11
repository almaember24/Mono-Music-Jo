package com.prism.music.data.spotify

import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.SongPlays
import com.prism.music.data.imports.TasteBoost
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Spotify's top songs and artists, by its three time ranges, as last read. */
@Serializable
data class TopItems(
    val fetchedAt: Long = 0,
    val tracks: Map<String, List<SpotifyTrack>> = emptyMap(),
    val artists: Map<String, List<TopArtist>> = emptyMap(),
)

/**
 * "Top songs and artists" (1.8.1): Spotify's own measure of what the listener is into, over about four weeks, six months
 * and a year, read when connected and then weekly, and counted by the mixes, radio and Quick picks as if played
 * ([TasteBoost], through [com.prism.music.data.imports.TasteHistory]): the last four weeks weigh most. Replay never
 * counts it (it isn't plays). The songs go through matching like any import; until one is matched it can't be offered.
 */
class SpotifyTop(private val api: SpotifyApi, private val db: PrismDatabase, private val dir: File) {
    private val file = File(dir, "top.json")
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cached: TopItems? = null

    fun items(): TopItems = cached ?: (runCatching { json.decodeFromString(TopItems.serializer(), file.readText()) }.getOrNull() ?: TopItems()).also { cached = it }

    /** Old enough to read again (a week). */
    fun due(now: Long = System.currentTimeMillis()) = now - items().fetchedAt >= WEEK_MS

    suspend fun refresh(now: Long = System.currentTimeMillis()): TopItems {
        val tracks = LinkedHashMap<String, List<SpotifyTrack>>()
        val artists = LinkedHashMap<String, List<TopArtist>>()
        for (range in RANGES) {
            tracks[range] = SpotifyParse.topTracks(api.get("/me/top/tracks", mapOf("time_range" to range, "limit" to "50")))
            artists[range] = SpotifyParse.topArtists(api.get("/me/top/artists", mapOf("time_range" to range, "limit" to "50")))
        }
        val top = TopItems(now, tracks, artists)
        SpotifyQueue.remember(db, tracks.values.flatten())
        save(top)
        return top
    }

    private fun save(top: TopItems) {
        dir.mkdirs()
        val tmp = File(dir, "top.json.tmp")
        tmp.writeText(json.encodeToString(TopItems.serializer(), top))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        cached = top
    }

    fun forget() { file.delete(); cached = null }

    /** The top songs, whose matches the taste needs (kept when an import is removed). */
    fun matchKeys(): Set<String> = items().tracks.values.flatten().map(SpotifyQueue::key).toSet()

    /** What the mixes learn from, while [on] says so. */
    fun boost(on: () -> Boolean): TasteBoost = object : TasteBoost {
        override suspend fun songs(): List<SongPlays> {
            if (!on()) return emptyList()
            val weights = songWeights(items())
            if (weights.isEmpty()) return emptyList()
            val matched = SpotifyQueue.matched(db, weights.keys)
            return weights.mapNotNull { (key, plays) ->
                val s = matched[key] ?: return@mapNotNull null
                SongPlays(s.id, plays, plays * AVERAGE_PLAY_MS, s.title, s.artistText, s.thumbnail, s.album?.title, s.durationSec, s.artists.firstOrNull()?.id, s.primaryArtist)
            }
        }

        override suspend fun artists(): List<ArtistPlays> {
            if (!on()) return emptyList()
            return artistWeights(items()).map { (a, plays) -> ArtistPlays(a.name, plays, plays * AVERAGE_PLAY_MS, a.image, null) }
        }

        override fun version(): Int = if (on()) (items().fetchedAt / 1000).toInt() else 0
    }

    companion object {
        val RANGES = listOf("short_term", "medium_term", "long_term")
        private val RANGE_WEIGHT = mapOf("short_term" to 3, "medium_term" to 2, "long_term" to 1)
        const val WEEK_MS = 7 * 86_400_000L
        /** What one counted play stands for, so Spotify's top weighs like plays do (by time heard). */
        const val AVERAGE_PLAY_MS = 200_000L

        /** A place in a range's top as plays: the top of the last four weeks as 12, the 50th of the year as 1. */
        fun weight(range: String, rank: Int, of: Int): Int {
            val w = RANGE_WEIGHT[range] ?: 1
            return Math.round(w * 4 * (1.0 - rank.toDouble() / of.coerceAtLeast(1))).toInt().coerceAtLeast(1)
        }

        /** Each song's plays over the three ranges, by song key. */
        fun songWeights(top: TopItems): Map<String, Int> {
            val out = LinkedHashMap<String, Int>()
            top.tracks.forEach { (range, list) -> list.forEachIndexed { i, t -> out.merge(SpotifyQueue.key(t), weight(range, i, list.size), Int::plus) } }
            return out
        }

        fun artistWeights(top: TopItems): List<Pair<TopArtist, Int>> {
            val out = LinkedHashMap<String, Pair<TopArtist, Int>>()
            top.artists.forEach { (range, list) ->
                list.forEachIndexed { i, a ->
                    val k = com.prism.music.data.imports.ImportKeys.norm(a.name)
                    val w = weight(range, i, list.size)
                    out[k] = out[k]?.let { (old, n) -> (old.copy(image = old.image ?: a.image)) to n + w } ?: (a to w)
                }
            }
            return out.values.sortedByDescending { it.second }
        }
    }
}
