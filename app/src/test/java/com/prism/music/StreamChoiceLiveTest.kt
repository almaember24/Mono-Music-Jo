package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.obj
import com.prism.music.data.innertube.str
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.AudioQuality
import com.prism.music.data.stream.AudioPick
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * Live against YouTube: what audio the direct (visionOS) answer offers for well-known songs, and that Prism picks Opus
 * from it at High quality whenever there is one, as YouTube Music does.
 */
@Category(LiveNetwork::class)
class StreamChoiceLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val innerTube = InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) })
    private val ytm = YouTubeMusic(innerTube)

    @Test
    fun opusIsPickedWheneverOffered() = runBlocking {
        val queries = listOf(
            "linger royel otis", "blinding lights the weeknd", "espresso sabrina carpenter", "bad guy billie eilish",
            "levitating dua lipa", "as it was harry styles", "pink pony club chappell roan", "mr brightside the killers",
        )
        var checked = 0
        for (q in queries) {
            val song = ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().firstOrNull()?.song ?: continue
            val res = runCatching { innerTube.visionOsPlayer(song.id) }.getOrNull() ?: continue
            if (res.str("playabilityStatus", "status") != "OK") { println("[stream] ${song.title}: ${res.str("playabilityStatus", "status")}"); continue }
            val audio = res.obj("streamingData")?.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.str("mimeType")?.startsWith("audio/") == true }
            val choices = audio.map { o ->
                val full = o.str("mimeType")!!
                AudioPick.Choice(
                    full.substringBefore(";"), Regex("codecs=\"([^\"]+)\"").find(full)?.groupValues?.get(1),
                    ((o.str("averageBitrate") ?: o.str("bitrate"))?.toIntOrNull() ?: 0) / 1000,
                    o.obj("audioTrack")?.str("audioIsDefault") != "false",
                )
            }
            val pick = AudioPick.pick(choices, AudioQuality.HIGH)!!
            val oldPick = choices.indices.maxBy { choices[it].kbps }
            println(
                "[stream] ${song.title} – ${song.artistText} (${song.id}): " +
                    audio.mapIndexed { i, o -> "${o.str("itag")} ${choices[i].codec} ${choices[i].kbps}k" }.joinToString(", ") +
                    " → now itag ${audio[pick].str("itag")}, before itag ${audio[oldPick].str("itag")}"
            )
            if (choices.any { it.opus }) assertTrue("${song.title}: Opus offered but not picked", choices[pick].opus)
            checked++
        }
        assertTrue("checked some songs", checked >= 4)
    }

    /** The video-only streams of Linger's video: what googlevideo answers at the start of each (a real MP4 starts with ftyp). */
    @Test
    fun videoStreamsStartAsMp4() {
        val http2 = http.newBuilder().addInterceptor(com.prism.music.data.stream.YouTubeStreamInterceptor(http)).build()
        for (id in listOf("JGUVB19e13s", "SkTt9k4Y-a8")) {
            val res = innerTube.visionOsPlayer(id)
            println("[vstream] $id status ${res.str("playabilityStatus", "status")}")
            val formats = res.obj("streamingData")?.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.str("mimeType")?.startsWith("video/mp4") == true }
            for (f in formats.take(6)) {
                val url = f.str("url") ?: continue
                http2.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=0-63").build()).execute().use { r ->
                    val b = r.body.bytes()
                    println("[vstream] $id itag ${f.str("itag")} ${f.str("mimeType")} ${f.str("height")}p: HTTP ${r.code}, ${b.size} bytes, " +
                        "type '${String(b.copyOfRange(4, 8.coerceAtMost(b.size)), Charsets.ISO_8859_1)}', first ${b.take(16).joinToString(" ") { "%02x".format(it) }}")
                }
            }
        }
    }

    /** A range from the middle of a video stream matches the same bytes read from the start (what a seek or a later chunk gets). */
    @Test
    fun videoRangesAreConsistent() {
        val http2 = http.newBuilder().addInterceptor(com.prism.music.data.stream.YouTubeStreamInterceptor(http)).build()
        val id = "JGUVB19e13s"
        val res = innerTube.visionOsPlayer(id)
        val f = res.obj("streamingData")?.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
            .first { it.str("itag") == "135" }
        val url = f.str("url")!!
        fun get(from: Long, to: Long) = http2.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=$from-$to").build()).execute()
            .use { r -> println("[vrange] bytes=$from-$to: HTTP ${r.code}, ${r.header("Content-Type")}, ${r.header("Content-Range")}"); r.body.bytes() }
        val whole = get(0, 3_000_000)
        val mid = get(2_000_000, 2_000_063)
        val same = whole.copyOfRange(2_000_000, 2_000_064).contentEquals(mid)
        println("[vrange] whole ${whole.size} bytes; middle range matches: $same")
        // The atoms from the start: each box header's size and type, as the extractor walks them.
        var p = 0L
        val sb = StringBuilder()
        while (p + 8 <= whole.size && sb.length < 600) {
            val size = java.nio.ByteBuffer.wrap(whole, p.toInt(), 4).int.toLong() and 0xffffffffL
            val type = String(whole, p.toInt() + 4, 4, Charsets.ISO_8859_1)
            sb.append("$type($size) ")
            if (size < 8) break
            p += size
        }
        println("[vrange] boxes: $sb")
        assertTrue("middle range matches", same)
    }

    /** Whether a direct video link still answers 40 s after it was given (when the listener switches to video later). */
    @Test
    fun directVideoLinkLater() {
        val http2 = http.newBuilder().addInterceptor(com.prism.music.data.stream.YouTubeStreamInterceptor(http)).build()
        val res = innerTube.visionOsPlayer("JGUVB19e13s")
        val formats = res.obj("streamingData")?.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
        val video = formats.first { it.str("itag") == "135" }.str("url")!!
        val audio = formats.first { it.str("itag") == "251" }.str("url")!!
        fun code(u: String, from: Long) = http2.newCall(okhttp3.Request.Builder().url(u).header("Range", "bytes=$from-${from + 63}").build()).execute().use { it.code }
        println("[vlater] at once: video ${code(video, 0)} audio ${code(audio, 0)}")
        Thread.sleep(40_000)
        println("[vlater] after 40 s: video ${code(video, 1_000_000)} audio ${code(audio, 1_000_000)}")
        Thread.sleep(30_000)
        println("[vlater] after 70 s: video ${code(video, 2_000_000)} audio ${code(audio, 2_000_000)}")
    }

    /** What an album's "Other versions" cards carry (three "After Hours (Deluxe)" looked the same). */
    @Test
    fun otherVersionsDetails() = runBlocking {
        val album = ytm.search("after hours the weeknd", SearchFilter.ALBUMS).items.filterIsInstance<com.prism.music.data.model.AlbumItem>().first()
        val res = innerTube.post("browse", kotlinx.serialization.json.buildJsonObject { put("browseId", kotlinx.serialization.json.JsonPrimitive(album.id)) })
        val text = res.toString()
        val at = text.indexOf("Other versions")
        println("[versions] album ${album.title} ${album.id}, 'Other versions' at $at")
        Regex("\"musicTwoRowItemRenderer\":(\\{.{0,2500})").findAll(text.substring(maxOf(0, at))).take(4).forEach { m ->
            val s = m.groupValues[1]
            val title = Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)").find(s)?.groupValues?.get(1)
            val subtitle = Regex("\"subtitle\":\\{\"runs\":(\\[.*?\\])").find(s)?.groupValues?.get(1)?.let { r -> Regex("\"text\":\"([^\"]+)").findAll(r).joinToString("") { it.groupValues[1] } }
            val badges = Regex("\"(subtitleBadges|badges)\":(\\[.{0,300})").find(s)?.groupValues?.get(2)?.let { b -> Regex("\"label\":\"([^\"]+)|\"iconType\":\"([^\"]+)").findAll(b).joinToString { it.value } }
            val id = Regex("\"browseId\":\"(MPRE[^\"]+)").find(s)?.groupValues?.get(1)
            println("[versions] $title | $subtitle | badges $badges | $id")
        }
    }

    /** What YouTube links as "Linger"'s video, and what a video search finds; then what Prism picks. */
    @Test
    fun lingerHasItsVideo() = runBlocking {
        val songs = ytm.search("linger royel otis", SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }
            .filter { it.title.contains("linger", true) && it.artistText.contains("royel", true) }
        println("[video] songs: " + songs.joinToString { "${it.title} – ${it.artistText} (${it.id}, ${it.durationSec}s, artist ${it.artists.firstOrNull()?.id})" })
        for (s in songs) {
            println("[video] next(${s.id}) counterpart = ${ytm.next(s.id).counterparts[s.id]}")
            println("[video] next(${s.id}, RDAMVM) counterpart = ${ytm.next(s.id, playlistId = "RDAMVM${s.id}").counterparts[s.id]}")
        }
        // Every Linger by Royel Otis that YouTube Music lists as a song.
        ytm.search("Royel Otis Linger", SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }.take(6)
            .forEach { println("[video] song result: ${it.title} – ${it.artistText} (${it.id}, ${it.durationSec}s, album ${it.album?.title})") }
        val videos = ytm.search("Linger Royel Otis", SearchFilter.VIDEOS).items.filterIsInstance<SongItem>().map { it.song }
        videos.take(8).forEach { v -> println("[video] search: ${v.title} | ${v.artists.joinToString { "${it.name} (${it.id})" }} | ${v.durationSec}s | ${v.id}") }
        val found = songs.map { s -> s.id to ytm.findMusicVideo(s) }
        println("[video] findMusicVideo: $found")
        assertTrue("a video for Linger", found.any { it.second != null })
    }

    /** Every "Linger" by Royel Otis that YouTube Music has (search pages, the artist's page, albums), and its video. */
    @Test
    fun everyLingerHasItsVideo() = runBlocking {
        val seen = LinkedHashMap<String, com.prism.music.data.model.Song>()
        for (q in listOf("linger royel otis", "royel otis linger", "linger siriusxm royel otis", "linger cranberries cover royel otis")) {
            for (f in listOf(SearchFilter.SONGS, SearchFilter.VIDEOS)) runCatching {
                ytm.search(q, f).items.filterIsInstance<SongItem>().map { it.song }
                    .filter { it.title.contains("linger", true) && it.artistText.contains("royel", true) }
                    .forEach { seen.putIfAbsent(it.id, it) }
            }
        }
        runCatching {
            val artistId = seen.values.flatMap { it.artists }.firstOrNull { it.name.contains("Royel", true) && it.id != null }?.id
            if (artistId != null) ytm.artist(artistId).shelves.flatMap { it.items }.filterIsInstance<SongItem>().map { it.song }
                .filter { it.title.contains("linger", true) }.forEach { seen.putIfAbsent(it.id, it) }
        }
        // As it comes from its album's page (the album's tracks), from a radio of it, and with the next queue's item.
        for (s in seen.values.toList()) {
            s.album?.id?.let { a ->
                runCatching { ytm.album(a).songs.filter { it.title.contains("linger", true) } }.getOrNull()?.forEach { seen.putIfAbsent("album:" + it.id, it) }
            }
            runCatching { ytm.next(s.id).songs.firstOrNull { it.id == s.id } }.getOrNull()?.let { seen.putIfAbsent("next:" + it.id, it) }
        }
        for ((key, s) in seen) {
            print("[linger] $key ")
            val v = runCatching { ytm.findMusicVideo(s) }
            println("[linger] ${s.title} | ${s.artists.joinToString { "${it.name}(${it.id})" }} | ${s.id} | ${s.durationSec}s | video=${s.isVideo} | album ${s.album?.title} -> ${v.getOrNull() ?: v.exceptionOrNull()}")
        }
    }
}
