package com.prism.music

import com.prism.music.data.db.SongEntity
import com.prism.music.data.db.toEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Not a check: the showcase film's demo year. With PRISM_FILM_SQL=<file>, looks up a curated set of well-known songs
 * (colorful covers, karaoke lyrics) on YouTube Music and writes SQL that fills a fresh Prism's database with a
 * believable 2026: about 80 hours in sittings of one or two artists, likes, one recognised song. Run it against the
 * film copy's prism.db on the emulator (adb root + sqlite3), then carry it to the phone with Prism's own backup.
 */
class FilmDemoYear {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    /** Artist, then their songs; the first artists are played most. */
    private val year = listOf(
        "Sabrina Carpenter" to listOf("Espresso", "Please Please Please", "Taste"),
        "The Weeknd" to listOf("Blinding Lights", "Save Your Tears", "Starboy"),
        "Dua Lipa" to listOf("Levitating", "Don't Start Now", "Houdini"),
        "Billie Eilish" to listOf("BIRDS OF A FEATHER", "bad guy", "LUNCH"),
        "Chappell Roan" to listOf("Good Luck, Babe!", "HOT TO GO!", "Pink Pony Club"),
        "Taylor Swift" to listOf("Cruel Summer", "Anti-Hero", "Style"),
        "Michael Jackson" to listOf("Billie Jean", "Beat It", "Wanna Be Startin' Somethin'"),
        "Olivia Rodrigo" to listOf("good 4 u", "vampire"),
        "Harry Styles" to listOf("As It Was", "Watermelon Sugar"),
        "Tame Impala" to listOf("The Less I Know The Better", "Let It Happen"),
        "Kendrick Lamar" to listOf("Not Like Us", "HUMBLE."),
        "SZA" to listOf("Kill Bill", "Snooze"),
        "Daft Punk" to listOf("Get Lucky", "One More Time"),
        "Fleetwood Mac" to listOf("Dreams", "Everywhere"),
        "Queen" to listOf("Bohemian Rhapsody", "Don't Stop Me Now"),
        "Lorde" to listOf("Green Light", "Royals"),
        "Daniel Caesar" to listOf("Best Part"),
    )

    @Test
    fun writeDemoYear() = runBlocking {
        val out = System.getenv("PRISM_FILM_SQL")
        assumeTrue("PRISM_FILM_SQL not set", out != null)
        val byArtist = LinkedHashMap<String, List<Song>>()
        for ((artist, titles) in year) {
            byArtist[artist] = titles.mapNotNull { title ->
                val found = ytm.search("$title $artist", SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }
                    .firstOrNull { s -> !s.isVideo && s.durationSec > 60 && s.artists.firstOrNull()?.name.equals(artist, true) && norm(s.title).startsWith(norm(title)) }
                    ?: ytm.search("$title $artist", SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }
                        .firstOrNull { s -> !s.isVideo && s.durationSec > 60 && s.artists.any { it.name.equals(artist, true) } }
                if (found == null) println("MISSING $title — $artist") else println("FOUND ${found.id} ${found.title} — ${found.artistText} (${found.durationSec}s)")
                found
            }
        }
        val rnd = Random(2026)
        val events = mutableListOf<Triple<String, Long, Long>>()
        val day = 24 * 3600_000L
        val start = Calendar.getInstance().apply { set(2026, Calendar.JANUARY, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 20, 17, 0, 0) }.timeInMillis
        val artists = byArtist.keys.toList().filter { byArtist[it]!!.isNotEmpty() }
        // Zipf-ish: the first artist about four times the tenth; within an artist, the first song most.
        fun pickArtist() = artists[(artists.indices).map { i -> i to 1.0 / (1 + i * 0.35) }.let { w -> weighted(rnd, w) }]
        fun pickSong(a: String) = byArtist[a]!!.let { it[(it.indices).map { i -> i to 1.0 / (1 + i * 0.9) }.let { w -> weighted(rnd, w) }] }
        var t = start
        while (true) {
            // A sitting every day or two, more often in recent months; mornings, commutes and evenings.
            t += (day * (0.8 + rnd.nextDouble() * 1.6) * (if (t > now - 60 * day) 0.7 else 1.0)).toLong()
            val hourCal = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, listOf(8, 8, 13, 17, 18, 20, 21, 22)[rnd.nextInt(8)]); set(Calendar.MINUTE, rnd.nextInt(60)) }
            var at = hourCal.timeInMillis
            if (at > now) break
            val main = pickArtist()
            val other = pickArtist()
            repeat(4 + rnd.nextInt(8)) {
                val s = pickSong(if (rnd.nextDouble() < 0.65) main else other)
                val full = s.durationSec * 1000L
                val played = if (rnd.nextDouble() < 0.88) full else (full * (0.3 + rnd.nextDouble() * 0.5)).toLong()
                events += Triple(s.id, at, played)
                at += played + 2_000
            }
        }
        val songs = byArtist.values.flatten()
        val last = events.groupBy { it.first }.mapValues { e -> e.value.maxOf { it.second } }
        // Liked: each artist's first song and a few more.
        val liked = byArtist.values.mapNotNull { it.firstOrNull() } + byArtist.values.mapNotNull { it.getOrNull(1) }.take(6)
        val sql = StringBuilder("BEGIN;\nDELETE FROM play_events;\nDELETE FROM recognitions;\n")
        for (s in songs) {
            val e: SongEntity = s.toEntity()
            val l = s in liked
            sql.append("INSERT OR REPLACE INTO songs(id,title,artistName,artistId,albumTitle,albumId,durationSec,thumbnail,isVideo,liked,likedAt,lastPlayed,smartDownload,primaryArtist,artistsJson) VALUES(")
                .append(listOf(q(e.id), q(e.title), q(e.artistName), q(e.artistId), q(e.albumTitle), q(e.albumId), e.durationSec, q(e.thumbnail), 0,
                    if (l) 1 else 0, if (l) start + rnd.nextLong(200 * day) else 0, last[e.id] ?: 0, 0, q(e.primaryArtist), q(e.artistsJson)).joinToString(","))
                .append(");\n")
        }
        for ((id, at, ms) in events) sql.append("INSERT INTO play_events(songId,timestamp,playedMs,source,excluded) VALUES(${q(id)},$at,$ms,NULL,0);\n")
        songs.firstOrNull { it.title.startsWith("Dreams") }?.let { d ->
            sql.append("INSERT INTO recognitions(at,status,title,artist,album,cover,shazamKey,videoId,signature) VALUES(${now - 3 * day},'found',${q(d.title)},${q(d.artistText)},${q(d.album?.title)},${q(d.thumbnail)},NULL,${q(d.id)},NULL);\n")
        }
        sql.append("COMMIT;\n")
        File(out!!).writeText(sql.toString())
        val hours = events.sumOf { it.third } / 3_600_000.0
        println("WROTE ${songs.size} songs, ${events.size} plays, ${"%.1f".format(hours)} h, ${liked.size} liked → $out")
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun weighted(rnd: Random, w: List<Pair<Int, Double>>): Int {
        var r = rnd.nextDouble() * w.sumOf { it.second }
        for ((i, x) in w) { r -= x; if (r <= 0) return i }
        return w.last().first
    }

    private fun q(s: String?) = if (s == null) "NULL" else "'" + s.replace("'", "''") + "'"
}
