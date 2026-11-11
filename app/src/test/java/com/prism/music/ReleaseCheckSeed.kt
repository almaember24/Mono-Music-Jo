package com.prism.music

import com.prism.music.data.PlaylistFolder
import com.prism.music.data.PrismList
import com.prism.music.data.looks.Backgrounds
import com.prism.music.data.looks.Look
import com.prism.music.data.looks.LookCode
import com.prism.music.data.db.SongEntity
import com.prism.music.data.db.toEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Not a check: the release check's test data (`.claude/release-check-plan.md` §4). With PRISM_SEED_SQL=<file>, looks
 * songs up on YouTube Music and writes SQL that gives a fresh Prism the same library on every test phone: a year of
 * plays in sittings, about 1,250 liked songs (as many as GitHub #103's phone had), a recognised song, and saved
 * synced lyrics for a dozen songs (from LRCLIB), for Search's lyric search on the phone. Fixed seeds, so the history is
 * the same every run; the songs themselves are whatever YouTube Music answers that day.
 */
class ReleaseCheckSeed {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    /** The year's listening: artist, then their songs; the first artists are played most. */
    private val year = listOf(
        "Sabrina Carpenter" to listOf("Espresso", "Please Please Please", "Taste"),
        "The Weeknd" to listOf("Blinding Lights", "Save Your Tears", "Starboy"),
        "Dua Lipa" to listOf("Levitating", "Don't Start Now", "Houdini"),
        "Billie Eilish" to listOf("BIRDS OF A FEATHER", "bad guy", "LUNCH"),
        "Linkin Park" to listOf("Numb", "In the End", "The Emptiness Machine"),
        "Taylor Swift" to listOf("Cruel Summer", "Anti-Hero", "Style"),
        "Michael Jackson" to listOf("Billie Jean", "Beat It"),
        "Korn" to listOf("Freak on a Leash", "Falling Away from Me"),
        "Kendrick Lamar" to listOf("Not Like Us", "HUMBLE."),
        "Bad Bunny" to listOf("DtMF", "Tití Me Preguntó"),
        "Daft Punk" to listOf("Get Lucky", "One More Time"),
        "Fleetwood Mac" to listOf("Dreams", "Everywhere"),
        "Queen" to listOf("Bohemian Rhapsody", "Don't Stop Me Now"),
        "Olivia Rodrigo" to listOf("drivers license", "good 4 u"),
    )

    /** Artists whose songs fill the long Liked songs list. */
    private val likeArtists = listOf(
        "Drake", "Rihanna", "Ed Sheeran", "Ariana Grande", "Post Malone", "Coldplay", "Imagine Dragons", "Beyoncé",
        "Bruno Mars", "Justin Bieber", "Lady Gaga", "Katy Perry", "Maroon 5", "Adele", "Eminem", "Kanye West",
        "Travis Scott", "SZA", "Doja Cat", "Harry Styles", "Shakira", "J Balvin", "Karol G", "BTS", "Arctic Monkeys",
        "Red Hot Chili Peppers", "Nirvana", "Foo Fighters", "Metallica", "Green Day", "Paramore", "Twenty One Pilots",
        "Lana Del Rey", "Frank Ocean", "Tyler, The Creator", "Childish Gambino", "Kid Cudi", "Mac Miller", "J. Cole",
        "Lil Uzi Vert", "Future", "21 Savage", "Nicki Minaj", "Cardi B", "Megan Thee Stallion", "Calvin Harris",
        "David Guetta", "Avicii", "Kygo", "Marshmello", "The Chainsmokers", "Zedd", "Martin Garrix", "Halsey",
        "Selena Gomez", "Miley Cyrus", "Shawn Mendes", "Camila Cabello", "Charlie Puth", "Sia", "Hozier", "Lorde",
        "Tame Impala", "The 1975", "Gorillaz", "Radiohead", "Muse", "The Killers", "Oasis", "Blur", "Pearl Jam",
        "Bring Me The Horizon", "A Day To Remember", "36 Crazyfists", "Papa Roach", "Creed", "Deftones", "System Of A Down",
    )
    private val likesWanted = 1_250

    @Test
    fun writeSeed() = runBlocking {
        val out = System.getenv("PRISM_SEED_SQL")
        assumeTrue("PRISM_SEED_SQL not set", out != null)
        val byArtist = LinkedHashMap<String, List<Song>>()
        for ((artist, titles) in year) {
            byArtist[artist] = titles.mapNotNull { title ->
                val found = runCatching { ytm.search("$title $artist", SearchFilter.SONGS).items }.getOrDefault(emptyList())
                    .filterIsInstance<SongItem>().map { it.song }
                    .firstOrNull { s -> !s.isVideo && s.durationSec > 60 && s.artists.any { it.name.equals(artist, true) } }
                if (found == null) println("MISSING $title - $artist")
                found
            }
        }
        // The long Liked songs list: each artist's songs from Songs search, until there are enough.
        val likes = LinkedHashMap<String, Song>()
        byArtist.values.flatten().forEach { likes[it.id] = it }
        for (artist in likeArtists) {
            if (likes.size >= likesWanted) break
            var token: String? = null
            var pages = 0
            do {
                val r = runCatching { ytm.search(artist, SearchFilter.SONGS, token) }.getOrNull() ?: break
                r.items.filterIsInstance<SongItem>().map { it.song }.filter { !it.isVideo && it.durationSec > 60 }.forEach { likes.putIfAbsent(it.id, it) }
                token = r.continuation
                pages++
            } while (token != null && pages < 2 && likes.size < likesWanted)
        }
        println("LIKES ${likes.size}")

        val rnd = Random(2026)
        val day = 24 * 3600_000L
        val now = System.currentTimeMillis()
        val start = now - 300 * day
        val events = mutableListOf<Triple<String, Long, Long>>()
        val artists = byArtist.keys.filter { byArtist[it]!!.isNotEmpty() }
        fun pickArtist() = artists[weighted(rnd, artists.indices.map { i -> i to 1.0 / (1 + i * 0.35) })]
        fun pickSong(a: String) = byArtist[a]!!.let { it[weighted(rnd, it.indices.map { i -> i to 1.0 / (1 + i * 0.9) })] }
        var t = start
        while (true) {
            t += (day * (0.8 + rnd.nextDouble() * 1.6)).toLong()
            val at0 = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, listOf(8, 8, 13, 17, 18, 20, 21, 22)[rnd.nextInt(8)]); set(Calendar.MINUTE, rnd.nextInt(60)) }.timeInMillis
            if (at0 > now - day) break
            var at = at0
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
        val last = events.groupBy { it.first }.mapValues { e -> e.value.maxOf { it.second } }

        val sql = StringBuilder("BEGIN;\nDELETE FROM play_events;\nDELETE FROM recognitions;\n")
        likes.values.forEachIndexed { i, s ->
            val e: SongEntity = s.toEntity()
            sql.append("INSERT OR REPLACE INTO songs(id,title,artistName,artistId,albumTitle,albumId,durationSec,thumbnail,isVideo,liked,likedAt,lastPlayed,smartDownload,primaryArtist,artistsJson) VALUES(")
                .append(listOf(q(e.id), q(e.title), q(e.artistName), q(e.artistId), q(e.albumTitle), q(e.albumId), e.durationSec, q(e.thumbnail), 0,
                    1, now - i * 3_600_000L, last[e.id] ?: 0, 0, q(e.primaryArtist), q(e.artistsJson)).joinToString(","))
                .append(");\n")
        }
        for ((id, at, ms) in events) sql.append("INSERT INTO play_events(songId,timestamp,playedMs,source,excluded) VALUES(${q(id)},$at,$ms,NULL,0);\n")
        byArtist.values.flatten().firstOrNull { it.title.startsWith("Dreams") }?.let { d ->
            sql.append("INSERT INTO recognitions(at,status,title,artist,album,cover,shazamKey,videoId,signature) VALUES(${now - 3 * day},'found',${q(d.title)},${q(d.artistText)},${q(d.album?.title)},${q(d.thumbnail)},NULL,${q(d.id)},NULL);\n")
        }
        // Saved synced lyrics for a dozen of the year's songs (LRCLIB), as Prism keeps them after showing them.
        var lyrics = 0
        for (s in byArtist.values.flatten().take(14)) {
            val text = runCatching { lrclib(s) }.getOrNull() ?: continue
            sql.append("INSERT OR REPLACE INTO lyrics(songId,source,synced,content,fetchedAt) VALUES(${q(s.id)},'LRCLIB',1,${q(text)},$now);\n")
            lyrics++
        }
        sql.append("COMMIT;\n")
        File(out!!).writeText(sql.toString())
        // The long playlist to scroll (1,000+ songs, a Prism playlist so it works signed out) and a folder holding it.
        val long = PrismList("prism:releasecheck1", "Everything I like", "Release check: a long playlist", likes.values.take(1_100), now - 30 * day, now - day)
        val short = PrismList("prism:releasecheck2", "Road trip", "", byArtist.values.flatten().take(18), now - 20 * day, now - 2 * day)
        val lists = InnerTube.json.encodeToString(ListSerializer(PrismList.serializer()), listOf(long, short))
        val folders = InnerTube.json.encodeToString(ListSerializer(PlaylistFolder.serializer()),
            listOf(PlaylistFolder("folder-releasecheck", "Weekend", listOf(short.id), now - 10 * day)))
        File(File(out).parentFile, "prism.json").writeText("""{"lists":$lists,"folders":$folders}""")
        println("WROTE ${likes.size} liked songs, ${events.size} plays, $lyrics lyrics -> $out (+ prism.json, ${long.songs.size}-song playlist)")
    }

    /**
     * The sweep's "heavy look" as a Look code (data\heavy-look.txt): everything that moves or blurs at once. Animated
     * covers aren't part of a Look (and are off on mobile data), so the sweep turns those on itself.
     */
    @Test
    fun writeHeavyLook() {
        val out = System.getenv("PRISM_SEED_SQL") ?: return
        val everywhere = Backgrounds.tag(listOf("HOME", "SEARCH", "LIBRARY", "REPLAY", "SETTINGS").associateWith { "CONTOURS" } + ("PLAYER" to "SILK"))
        val look = Look(
            "heavy", "Release check heavy",
            values = mapOf(
                "liquid_glass" to "b:1", "backdrops" to everywhere, "backdrop_motion" to "b:1", "player_background" to "s:APPEARANCE",
                "player_backdrop_show" to "f:1.0", "lyrics_style" to "s:GLOW", "visualizer" to "s:BARS", "canvas_fills_background" to "b:1",
                "full_bleed_artwork" to "b:1", "video_full_bleed" to "b:1", "home_header" to "s:NOW_PLAYING",
            ),
        )
        File(File(out).parentFile, "heavy-look.txt").writeText(LookCode.encode(look))
        // The heavy look less one part at a time (a Look leaves out = Prism's default), for finding what costs the most.
        val parts = mapOf(
            "no-liquid-glass" to listOf("liquid_glass"),
            "no-backgrounds" to listOf("backdrops", "backdrop_motion", "player_background", "player_backdrop_show"),
            "no-header" to listOf("home_header"),
            "no-player-extras" to listOf("lyrics_style", "visualizer", "canvas_fills_background", "full_bleed_artwork", "video_full_bleed"),
            "only-backgrounds" to look.values.keys.filterNot { it in listOf("backdrops", "backdrop_motion") },
            "only-liquid-glass" to look.values.keys.filterNot { it == "liquid_glass" },
            "prism" to look.values.keys.toList(),
        )
        File(File(out).parentFile, "heavy-variants.txt").writeText(parts.entries.joinToString("\n") { (name, drop) ->
            name + "|" + LookCode.encode(look.copy(id = name, name = "Check $name", values = look.values - drop.toSet()))
        })
    }

    private fun lrclib(s: Song): String? {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", s.title.substringBefore(" (")).addQueryParameter("artist_name", s.primaryArtist)
            .addQueryParameter("duration", s.durationSec.toString()).build()
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", "Prism release check (github.com/Yigder/Prism)").build()).execute()
            .use { r -> if (r.isSuccessful) r.body.string() else null } ?: return null
        val o = InnerTube.json.parseToJsonElement(body) as? JsonObject ?: return null
        return (o["syncedLyrics"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }

    private fun weighted(rnd: Random, w: List<Pair<Int, Double>>): Int {
        var r = rnd.nextDouble() * w.sumOf { it.second }
        for ((i, x) in w) { r -= x; if (r <= 0) return i }
        return w.last().first
    }

    private fun q(s: String?) = if (s == null) "NULL" else "'" + s.replace("'", "''") + "'"
}
