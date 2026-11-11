package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.TimeUnit

/**
 * #383 (1.8.3): with "Songs, not videos: Everywhere", two playlists played from Home gave the videos' sound. What YouTube
 * gives for their video rows, signed out: a song found for each (Prism's search match), and what the playlist's own
 * watch queue pairs.
 */
@Category(LiveNetwork::class)
class SongsEverywhereLiveTest {
    private var settings = AppSettings()
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))
    private fun log(msg: String) = println("[live] $msg")

    /** The songs YouTube files as episodes there (no show, the singer linked) are read as videos with their artist. */
    @Test fun noSongReadAsAnEpisode() = runBlocking {
        val songs = ytm.playlistAll("PLEwlFF5xcXHzubwrqTqBzexAGjhl_1LoA", 60).songs
        val episodes = songs.filter { it.isEpisode }
        log("${songs.size} rows, ${episodes.size} read as episodes: ${episodes.take(5).map { it.title }}")
        if (episodes.isNotEmpty()) {
            val api = InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) })
            val text = api.post("browse", kotlinx.serialization.json.buildJsonObject { put("browseId", kotlinx.serialization.json.JsonPrimitive("VLPLEwlFF5xcXHzubwrqTqBzexAGjhl_1LoA")) }).toString()
            val at = text.indexOf("\"text\":\"${episodes.first().title}\"")
            if (at >= 0) log("raw: " + text.substring(at, (at + 1500).coerceAtMost(text.length)).replace(Regex("\"(clickT|t)rackingParams\":\"[^\"]*\",?"), ""))
        }
        org.junit.Assert.assertTrue(episodes.isEmpty())
    }

    @Test fun reportersPlaylists() = runBlocking {
        for (id in listOf("PLEwlFF5xcXHzubwrqTqBzexAGjhl_1LoA", "PLtLEpc9CWsdfxbsiuKYw02kpbCF1mnNcS")) {
            val songs = ytm.playlistAll(id, 60).songs
            val videos = songs.filter { it.isVideo }
            log("$id: ${songs.size} rows, ${videos.size} videos, ${songs.count { it.isEpisode }} read as episodes")
            songs.filter { it.isEpisode || it.title.contains("Iraga", true) }.forEach { log("  row ${it.id} \"${it.title}\" episode=${it.isEpisode} video=${it.isVideo} artists=${it.artistText}") }
            var found = 0
            for (v in videos.take(25)) {
                val s = runCatching { ytm.songForVideo(v) }.getOrNull()
                if (s != null) found++
                log("  ${v.id} \"${v.title}\" / ${v.artistText} (${v.durationSec}s) -> ${s?.let { "${it.id} \"${it.title}\" ${it.durationSec}s" } ?: "none"}")
            }
            log("  matched $found of ${videos.take(25).size}")
            val first = songs.firstOrNull() ?: continue
            val panel = runCatching { ytm.next(first.id, id) }.getOrNull()
            log("  next(first, playlist): ${panel?.songs?.size} rows, ${panel?.counterparts?.size} paired, ${panel?.songs?.count { it.isVideo }} videos")
        }
    }
}
