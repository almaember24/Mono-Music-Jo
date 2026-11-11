package com.prism.music.data

import com.prism.music.AppContainer
import com.prism.music.data.db.SongEntity
import com.prism.music.data.db.SongPlays
import com.prism.music.data.db.mergeUploads
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln

/** "You and this artist" on their page: from the plays on this phone, and shown nowhere else. */
data class ArtistYou(
    val firstPlayedAt: Long,
    val plays: Int,
    val ms: Long,
    val topSongId: String,
    val topSongTitle: String,
    val topSongPlays: Int,
) {
    companion object {
        /** From [songs] (each song's plays, as Replay counts them) and the first play; null with no plays. Uploads of one song count as one. */
        fun from(songs: List<SongPlays>, firstPlayedAt: Long?): ArtistYou? {
            if (firstPlayedAt == null) return null
            val top = songs.mergeUploads().firstOrNull() ?: return null
            return ArtistYou(firstPlayedAt, songs.sumOf { it.plays }, songs.sumOf { it.ms }, top.songId, top.title, top.plays)
        }
    }
}

/**
 * An artist's essentials for this listener: YouTube's top songs in their order, moved by how much the listener plays each,
 * with the songs they play most that aren't among those. No plays, or an order no different from YouTube's: none.
 */
object ArtistEssentials {
    /** How much a listener's plays count against YouTube's order: 10 plays outweigh being the artist's top song. */
    private const val PLAYS_WEIGHT = 0.6
    private const val MIN_PLAYS = 3
    const val SHOWN = 8

    private val BRACKETS = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
    private fun key(title: String) = com.prism.music.data.innertube.SearchRank.norm(title.replace(BRACKETS, ""))

    /** [played]: the songs the listener played and how often. A video they played counts for its song, not as a song of its own. */
    fun pick(top: List<Song>, played: List<Pair<Song, Int>>, limit: Int = SHOWN): List<Song> {
        if (played.sumOf { it.second } < MIN_PLAYS) return emptyList()
        val plays = HashMap<String, Int>()
        played.forEach { (s, n) -> plays[key(s.title)] = (plays[key(s.title)] ?: 0) + n }
        val tops = top.filter { !it.isVideo }.distinctBy { key(it.title) }
        val known = tops.mapTo(HashSet()) { key(it.title) }
        val own = played.filter { (s, _) -> !s.isVideo && key(s.title) !in known }.sortedByDescending { it.second }.map { it.first }.distinctBy { key(it.title) }
        val all = tops + own
        fun score(s: Song): Double {
            val rank = tops.indexOf(s)
            val popular = if (rank < 0) 0.0 else 1.0 - rank.toDouble() / tops.size
            return popular + PLAYS_WEIGHT * ln(1.0 + (plays[key(s.title)] ?: 0))
        }
        val picked = all.sortedByDescending(::score).take(limit)
        return picked.takeUnless { it == tops.take(picked.size) }.orEmpty()
    }
}

/** The plays [ArtistYou] and [ArtistEssentials] read, by the artist's id or their name (YouTube has several ids for many). */
suspend fun AppContainer.artistYou(id: String, name: String): ArtistYou? = withContext(Dispatchers.IO) {
    val o = imports.countedOrigins()
    ArtistYou.from(db.counted().artistSongs(o, id, name), db.counted().artistFirstPlay(o, id, name))
}

suspend fun AppContainer.artistEssentials(id: String, name: String, top: List<Song>): List<Song> = withContext(Dispatchers.IO) {
    val plays = db.counted().artistTasteSongs(imports.countedOrigins(), id, name)
    if (plays.isEmpty()) return@withContext emptyList()
    val rows: Map<String, SongEntity> = plays.map { it.songId }.chunked(500).flatMap { db.songs().getAll(it) }.associateBy { it.id }
    ArtistEssentials.pick(top, plays.mapNotNull { p -> rows[p.songId]?.let { it.toSong() to p.plays } })
}
