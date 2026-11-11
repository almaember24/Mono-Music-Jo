package com.prism.music.data.spotify

import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.imports.HistoryImporter
import com.prism.music.data.imports.ImportKeys
import com.prism.music.data.imports.MatchResult
import com.prism.music.data.imports.MatchStatus
import com.prism.music.data.imports.TrackMatcher
import com.prism.music.data.imports.TrackToMatch
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A song liked in Prism, as the likes' sync sees it. */
data class PrismLike(val id: String, val title: String, val artist: String, val durationSec: Int, val likedAt: Long) {
    val key: String get() = ImportKeys.songKey(title, artist)
}

/**
 * What the likes' sync has seen of both sides, so each like is carried over once and never back: a like is new on its
 * side when it wasn't seen before (or was liked again since). Likes Prism made itself are marked [LikesPlan.OURS] until
 * they're seen, so they never count as the listener's new ones.
 */
@Serializable
data class LikesState(
    /** Spotify's Liked Songs by URI → when they were liked, as last seen. */
    val seenSpotify: Map<String, Long> = emptyMap(),
    /** Prism's liked songs by id → when they were liked, as last seen. */
    val seenPrism: Map<String, Long> = emptyMap(),
    /** Spotify likes waiting for their YouTube Music match (URI → song key) before they're liked here. */
    val waitingHere: Map<String, String> = emptyMap(),
    /** Prism song id → the Spotify track it is. */
    val links: Map<String, String> = emptyMap(),
    /** Prism likes Spotify's search found nothing sure for. */
    val notOnSpotify: Set<String> = emptySet(),
    val lastRun: Long = 0,
    val likedHere: Int = 0,
    val likedOnSpotify: Int = 0,
)

/**
 * The likes' plan, worked out without Spotify or the database (so it can be tried on its own). It never takes a like
 * away on either side: unliking isn't carried over, and a like the listener took away isn't put back, because it was
 * already seen.
 */
object LikesPlan {
    const val OURS = -1L

    data class Counts(val toLikeHere: Int, val toLikeOnSpotify: Int)

    fun isNew(seen: Long?, at: Long): Boolean = seen == null || (seen != OURS && at > seen)

    /**
     * Before the switch is on: how many songs each side would get, by song (the same song liked on both counts
     * nowhere). Likes kept in Prism from an import ([kept]) aren't sent to Spotify: they came from there.
     */
    fun dryRun(spotify: List<SavedTrack>, prism: List<PrismLike>, kept: Set<String>): Counts {
        val prismKeys = prism.map { it.key }.toSet()
        val spotifyKeys = spotify.map { SpotifyQueue.key(it.track) }.toSet()
        return Counts(
            spotifyKeys.count { it !in prismKeys },
            prism.filter { it.id !in kept }.map { it.key }.distinct().count { it !in spotifyKeys },
        )
    }

    data class Plan(val toLikeHere: List<SpotifyTrack>, val toLikeOnSpotify: List<PrismLike>)

    fun plan(spotify: List<SavedTrack>, prism: List<PrismLike>, state: LikesState, kept: Set<String>): Plan {
        val prismKeys = prism.map { it.key }.toSet()
        val spotifyKeys = spotify.map { SpotifyQueue.key(it.track) }.toSet()
        val spotifyUris = spotify.map { it.track.uri }.toSet()
        val here = spotify.filter { s ->
            isNew(state.seenSpotify[s.track.uri], s.addedAt) && SpotifyQueue.key(s.track) !in prismKeys && s.track.uri !in state.waitingHere
        }.map { it.track }.distinctBy { SpotifyQueue.key(it) }
        val there = prism.filter { p ->
            p.id !in kept && isNew(state.seenPrism[p.id], p.likedAt) && p.key !in spotifyKeys && state.links[p.id] !in spotifyUris
        }.distinctBy { it.key }
        return Plan(here, there)
    }

    /**
     * The state after a run: everything seen on both sides as it is now, Prism's own new likes marked, and the Prism
     * likes not looked up yet ([unprocessed]) left unseen so the next run takes them.
     */
    fun after(
        state: LikesState, spotify: List<SavedTrack>, prism: List<PrismLike>, likedHere: Collection<String>, likedOnSpotify: Collection<String>,
        unprocessed: Set<String>, now: Long,
    ): LikesState {
        val seenS = spotify.associate { it.track.uri to it.addedAt }.toMutableMap()
        likedOnSpotify.forEach { if (it !in seenS) seenS[it] = OURS }
        val seenP = prism.filter { it.id !in unprocessed }.associate { it.id to it.likedAt }.toMutableMap()
        likedHere.forEach { seenP[it] = OURS }
        return state.copy(seenSpotify = seenS, seenPrism = seenP, lastRun = now)
    }

    /** Spotify's search results as songs, for [TrackMatcher.judge] (the URI as the id). */
    fun asSongs(tracks: List<SpotifyTrack>): List<Song> = tracks.map {
        Song(it.uri, it.title, it.artists.map { a -> ArtistRef(a) }, durationSec = (it.durationMs / 1000).toInt(), explicit = it.explicit)
    }
}

/**
 * "Liked Songs both ways" (1.8.1, off unless the listener turns it on after seeing its counts): a song liked in Spotify
 * is liked here once it's matched (on the YouTube Music account too only when Bring your history's "like on the account"
 * is on, as imported likes are), and a song liked here is found on Spotify (sure matches only) and saved to its Liked
 * Songs. [LikesPlan] keeps it from going round in circles.
 */
class SpotifyLikes(
    private val api: SpotifyApi,
    private val db: PrismDatabase,
    private val importer: HistoryImporter,
    private val dir: File,
    /** Likes songs here (HistoryImports.likeImported). */
    private val likeHere: suspend (List<Song>) -> Unit,
    /** Imported likes kept in Prism only (HistoryImports.keptLikes). */
    private val kept: () -> Set<String>,
    private val match: suspend (TrackToMatch) -> MatchResult,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val file = File(dir, "likes.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    fun state(): LikesState = runCatching { json.decodeFromString(LikesState.serializer(), file.readText()) }.getOrNull() ?: LikesState()

    private fun save(s: LikesState) {
        dir.mkdirs()
        val tmp = File(dir, "likes.json.tmp")
        tmp.writeText(json.encodeToString(LikesState.serializer(), s))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun forget() { file.delete() }

    /** Spotify likes waiting for their match (kept when an import is removed, or they'd never be liked here). */
    fun matchKeys(): Set<String> = state().waitingHere.values.toSet()

    /** Spotify's Liked Songs, page by page (up to [MAX_LIKES]). */
    suspend fun spotifyLikes(): List<SavedTrack> {
        val out = ArrayList<SavedTrack>()
        var page = SpotifyParse.savedTracks(api.get("/me/tracks", mapOf("limit" to "50")))
        out += page.items
        while (out.size < MAX_LIKES) { val next = page.next ?: break; page = SpotifyParse.savedTracks(api.next(next)); out += page.items }
        return out
    }

    suspend fun prismLikes(): List<PrismLike> = db.songs().liked().filter { !it.id.startsWith(Song.LOCAL_PREFIX) && !it.isVideo }
        .map { e -> e.toSong().let { s -> PrismLike(s.id, s.title, s.primaryArtist.ifBlank { e.artistName }, s.durationSec, e.likedAt) } }

    /** The counts shown before turning it on. */
    suspend fun dryRun(): LikesPlan.Counts = LikesPlan.dryRun(spotifyLikes(), prismLikes(), kept())

    /** One run both ways. */
    suspend fun sync(now: Long = System.currentTimeMillis()) = lock.withLock {
        val spotify = spotifyLikes()
        val prism = prismLikes()
        var state = state()
        val plan = LikesPlan.plan(spotify, prism, state, kept())

        // Spotify → here: lined up for matching, liked once matched (some matched now).
        SpotifyQueue.remember(db, plan.toLikeHere)
        val waiting = state.waitingHere + plan.toLikeHere.associate { it.uri to SpotifyQueue.key(it) }
        val pending = SpotifyQueue.pending(db, waiting.values)
        for ((uri, key) in waiting.entries.filter { it.value in pending }.take(AT_ONCE)) {
            val row = db.imports().match(key) ?: continue
            val t = spotify.firstOrNull { it.track.uri == uri }?.track ?: plan.toLikeHere.firstOrNull { it.uri == uri } ?: continue
            runCatching { match(TrackToMatch(t.title, t.artist, t.album, t.durationMs, 0, t.isrc)) }.getOrNull()?.let { importer.applyMatch(row, it) }
            pause(GAP_MS)
        }
        val matched = SpotifyQueue.matched(db, waiting.values)
        val prismIds = prism.map { it.id }.toSet()
        val songs = waiting.mapNotNull { (_, key) -> matched[key] }.distinctBy { it.id }.filter { it.id !in prismIds }
        if (songs.isNotEmpty()) likeHere(songs)
        // Waiting no more: liked, or the listener passed over every candidate.
        val statuses = waiting.values.chunked(500).flatMap { db.imports().matches(it) }.associate { it.songKey to MatchStatus.of(it.status) }
        val stillWaiting = waiting.filter { (_, key) -> key !in matched && statuses[key] != MatchStatus.SKIPPED && statuses[key] != null }
        val links = state.links.toMutableMap()
        waiting.forEach { (uri, key) -> matched[key]?.let { links[it.id] = uri } }

        // Here → Spotify: a few searches a run; sure ones saved.
        val found = ArrayList<String>()
        val searched = HashSet<String>()
        val notFound = state.notOnSpotify.toMutableSet()
        for (p in plan.toLikeOnSpotify.take(SEARCHES_PER_RUN)) {
            searched += p.id
            val q = "${ImportKeys.core(p.title)} artist:${ImportKeys.mainArtist(p.artist)}"
            val hits = runCatching { SpotifyParse.searchTracks(api.get("/search", mapOf("q" to q, "type" to "track", "limit" to "5"))) }
                .getOrElse { if (it is SpotifyError.RateLimited || it is SpotifyError.Revoked) throw it else emptyList() }
            val r = TrackMatcher.judge(TrackToMatch(p.title, p.artist, durationMs = p.durationSec * 1000L), LikesPlan.asSongs(hits))
            val uri = r.song?.id?.takeIf { r.status == MatchStatus.SURE }
            if (uri == null) notFound += p.id else { found += uri; links[p.id] = uri }
        }
        for (batch in found.distinct().chunked(SAVE_BATCH)) api.put("/me/library", mapOf("uris" to batch.joinToString(",")))
        val unprocessed = plan.toLikeOnSpotify.map { it.id }.toSet() - searched
        state = LikesPlan.after(state, spotify, prism, songs.map { it.id }, found, unprocessed, now).copy(
            waitingHere = stillWaiting, links = links, notOnSpotify = notFound,
            likedHere = state.likedHere + songs.size, likedOnSpotify = state.likedOnSpotify + found.distinct().size,
        )
        save(state)
        state
    }

    companion object {
        const val MAX_LIKES = 5_000
        const val SEARCHES_PER_RUN = 60
        const val AT_ONCE = 25
        /** Spotify's most per save (PUT /me/library). */
        const val SAVE_BATCH = 40
        const val GAP_MS = 400L
    }
}
