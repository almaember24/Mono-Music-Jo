package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * Live against YouTube: a song's music video is the artist's own (their channel, VEVO), never a fan's lyric video or a
 * cover. "What You Need" (Bring Me The Horizon) and "Memories" (Panic! At The Disco) used to open fan uploads.
 */
@Category(LiveNetwork::class)
class MusicVideoLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val innerTube = InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) })
    private val ytm = YouTubeMusic(innerTube)

    /** Query, the artist, and words that mark the official video's channel (any of them). */
    private val cases = listOf(
        Triple("What You Need Bring Me The Horizon", "Bring Me The Horizon", listOf("bring me the horizon", "bmth")),
        Triple("Memories Panic! At The Disco", "Panic! At The Disco", listOf("panic", "fueled by ramen")),
        Triple("Mr. Brightside The Killers", "The Killers", listOf("killers")),
        Triple("Bohemian Rhapsody Queen", "Queen", listOf("queen")),
        Triple("Numb Linkin Park", "Linkin Park", listOf("linkin park")),
        Triple("Blinding Lights The Weeknd", "The Weeknd", listOf("weeknd")),
        Triple("Chop Suey System Of A Down", "System Of A Down", listOf("system of a down")),
        Triple("Sugar We're Goin Down Fall Out Boy", "Fall Out Boy", listOf("fall out boy")),
    )

    @Test
    fun musicVideosAreTheArtistsOwn() = runBlocking {
        var bad = 0
        for ((query, artist, owners) in cases) {
            val song = ytm.search(query, SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }
                .firstOrNull { s -> !s.isVideo && s.artists.any { it.name.equals(artist, true) } } ?: continue
            val pairing = runCatching { ytm.next(song.id).counterparts[song.id] }.getOrNull()
            val picked = ytm.findMusicVideo(song)
            // Who posted it, from YouTube's own answer for that video.
            val video = picked?.let { id -> runCatching { ytm.next(id).songs.firstOrNull { it.id == id } }.getOrNull() }
            val by = video?.artistText.orEmpty().lowercase()
            val ok = picked == null || owners.any { by.contains(it) }
            if (!ok) bad++
            println("VIDEO ${if (ok) "ok " else "BAD"} '${song.title}' (${song.id}, ${song.durationSec}s) -> $picked \"${video?.title}\" by ${video?.artistText} (pairing=$pairing)")
            if (!ok || picked == null) {
                ytm.search("${song.title} ${song.primaryArtist} official video", SearchFilter.VIDEOS).items.filterIsInstance<SongItem>().take(6).forEach { v ->
                    println("    official? \"${v.song.title}\" by ${v.song.artists.joinToString { "${it.name}[${it.id}]" }} ${v.song.durationSec}s ${v.song.id} same=${com.prism.music.data.innertube.MusicVideoMatch.sameTitle(song, v.song)}")
                }
                ytm.search("${song.title} ${song.primaryArtist}", SearchFilter.VIDEOS).items.filterIsInstance<SongItem>().take(8).forEach { v ->
                    println("    candidate \"${v.song.title}\" by ${v.song.artists.joinToString { "${it.name}[${it.id}]" }} ${v.song.durationSec}s ${v.song.id}")
                }
                println("    song artists: ${song.artists.joinToString { "${it.name}[${it.id}]" }}")
            }
        }
        assertTrue("$bad songs got someone else's video", bad == 0)
    }
}
