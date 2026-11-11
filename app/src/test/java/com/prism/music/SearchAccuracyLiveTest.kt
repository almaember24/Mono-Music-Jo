package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * Search finds the song a title is known for (owner, 1.8.0 on the TV: "upside down" didn't show Jack Johnson's song,
 * a trending Hazbin Hotel song of the same name took the top card). Live against YouTube Music, signed out, so it reads
 * what a new listener gets. A title shared by several artists should lead with the one most people mean: the most
 * played. 2026-11-01: 22 of 30 led with the song meant before search read play counts (the owner's 4 of 7: upside
 * down, hello and dreams missed), 30 of 30 after. Later that day YouTube's card for "yesterday" became the 2019 film's
 * soundtrack album and for "africa" a new 2026 album: both give way to the song now.
 */
@Category(LiveNetwork::class)
class SearchAccuracyLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    /** The owner's titles: each must lead with this artist. */
    private val owners = listOf(
        "upside down" to "Jack Johnson", "creep" to "Radiohead", "hello" to "Adele", "halo" to "Beyoncé",
        "yesterday" to "The Beatles", "thriller" to "Michael Jackson", "dreams" to "Fleetwood Mac",
    )

    /** More titles shared by several artists, with the one most people mean. */
    private val more = listOf(
        "toxic" to "Britney Spears", "believer" to "Imagine Dragons", "shallow" to "Lady Gaga", "wonderwall" to "Oasis",
        "africa" to "TOTO", "imagine" to "John Lennon", "faded" to "Alan Walker", "umbrella" to "Rihanna",
        "royals" to "Lorde", "happy" to "Pharrell Williams", "firework" to "Katy Perry", "radioactive" to "Imagine Dragons",
        "numb" to "Linkin Park", "roar" to "Katy Perry", "clocks" to "Coldplay", "despacito" to "Luis Fonsi",
        "hurt" to "Johnny Cash", "smooth" to "Santana", "chandelier" to "Sia", "stressed out" to "Twenty One Pilots",
        "zombie" to "The Cranberries", "titanium" to "David Guetta", "bad romance" to "Lady Gaga",
    )

    /** The top result is [artist]'s song, or the album its title track names (Thriller → the album). */
    private fun leadsWith(top: BrowseItem?, artist: String): Boolean = when (top) {
        is SongItem -> top.song.artists.any { it.name.contains(artist, ignoreCase = true) }
        is AlbumItem -> top.subtitle.contains(artist, ignoreCase = true)
        else -> false
    }

    private fun describe(top: BrowseItem?): String = when (top) {
        is SongItem -> "${top.song.title} · ${top.song.artistText}" + (top.plays?.let { " · $it plays" } ?: "") + if (top.song.isVideo) " (video)" else ""
        null -> "nothing"
        else -> "${top.title} · ${top.subtitle}"
    }

    private fun hits(titles: List<Pair<String, String>>): List<String> = runBlocking {
        titles.mapNotNull { (q, artist) ->
            val r = runCatching { ytm.searchTop(q) }.getOrNull()
            val ok = leadsWith(r?.top, artist)
            println("[${if (ok) "hit " else "MISS"}] $q → ${describe(r?.top)} (want $artist)")
            if (ok) null else q
        }
    }

    @Test
    fun theOwnersTitlesLeadWithTheSongMeant() {
        val missed = hits(owners)
        assertTrue("Missed: $missed", missed.isEmpty())
    }

    @Test
    fun sharedTitlesMostlyLeadWithTheSongMeant() {
        val missed = hits(more)
        println("[score] ${more.size - missed.size} of ${more.size}")
        // Live, and some of these are close calls (Hurt: Nine Inch Nails wrote it): most, not all.
        assertTrue("Missed ${missed.size} of ${more.size}: $missed", missed.size <= more.size / 5)
    }

    /** Albums people mean by their title stand against bigger songs of it (AM: Nio Garcia's, 491M plays). */
    @Test
    fun realAlbumsStand() = runBlocking {
        val albums = listOf("am" to "Arctic Monkeys", "lemonade" to "Beyoncé", "after hours" to "The Weeknd", "rumours" to "Fleetwood Mac", "blonde" to "Frank Ocean")
        val missed = albums.mapNotNull { (q, artist) ->
            val top = runCatching { ytm.searchTop(q) }.getOrNull()?.top
            val ok = top is AlbumItem && top.subtitle.contains(artist, ignoreCase = true)
            println("[${if (ok) "hit " else "MISS"}] $q → ${describe(top)} (want the album by $artist)")
            if (ok) null else q
        }
        assertTrue("Missed: $missed", missed.isEmpty())
    }

    /** Jack Johnson's song is also among the first songs, and his video leads the videos (it was 7th). */
    @Test
    fun upsideDownRowsToo() = runBlocking {
        val r = ytm.searchTop("upside down")
        fun row(f: SearchFilter) = r.sections.firstOrNull { it.first == f }?.second.orEmpty().filterIsInstance<SongItem>()
        val songs = listOfNotNull(r.top as? SongItem) + row(SearchFilter.SONGS)
        assertTrue("Jack Johnson's song among the first", songs.take(3).any { s -> s.song.artists.any { it.name == "Jack Johnson" } })
        val videos = row(SearchFilter.VIDEOS)
        println("[videos] " + videos.take(4).joinToString(" | ") { describe(it) })
        assertTrue("Jack Johnson's video among the first videos", videos.take(3).any { v -> v.song.artists.any { it.name == "Jack Johnson" } })
    }
}
