package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.Lrc
import com.prism.music.data.model.PodcastItem
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.parseDuration
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Podcasts (live against YouTube Music, signed out) and the parsing they and the lyric timing fixes rely on. */
class PodcastTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun episodeLengthsInWords() {
        assertEquals(2 * 3600 + 54 * 60, parseDuration("2 hr 54 min"))
        assertEquals(47 * 60, parseDuration("47 min"))
        assertEquals(3600, parseDuration("1 hr"))
        assertEquals(3 * 60 + 5, parseDuration("3:05"))
    }

    @Test
    fun lrcOffsetMovesEveryLine() {
        // [offset:+500]: the lyrics come half a second sooner than their stamps.
        val lines = Lrc.parse("[offset:+500]\n[00:10.00]first\n[00:12.50]second").filter { !it.isGap }
        assertEquals(9_500L, lines[0].timeMs)
        assertEquals(12_000L, lines[1].timeMs)
        val later = Lrc.parse("[offset:-250]\n[00:10.00]first").filter { !it.isGap }
        assertEquals(10_250L, later[0].timeMs)
    }

    @org.junit.experimental.categories.Category(LiveNetwork::class)
    @Test
    fun podcastsPageHasShowsAndEpisodes() = runBlocking {
        val shelves = ytm.podcastsHome()
        shelves.take(4).forEach { s -> println("${s.title}: ${s.items.take(3).joinToString { it.title }}") }
        val items = shelves.flatMap { it.items }
        assertTrue("no shows", items.any { it is PodcastItem })
        assertTrue("no episodes", items.any { it is SongItem && it.song.isEpisode && !it.song.isVideo })
    }

    @org.junit.experimental.categories.Category(LiveNetwork::class)
    @Test
    fun showPageListsEpisodesAndMore() = runBlocking {
        val show = ytm.search("joe rogan experience", SearchFilter.PODCASTS).items.filterIsInstance<PodcastItem>().first()
        println("show ${show.id} ${show.title} — ${show.subtitle}")
        assertTrue(show.id.startsWith("MPSP"))
        val page = ytm.podcast(show.id)
        page.episodes.take(3).forEach { println("${it.song.id}  ${it.song.title}  ${it.date}  ${it.song.durationSec}s") }
        assertTrue("no episodes", page.episodes.isNotEmpty())
        assertTrue(page.episodes.all { it.song.isEpisode && it.song.podcastId == page.id })
        assertTrue("no lengths", page.episodes.any { it.song.durationSec > 600 })
        val token = page.continuation
        if (token != null) {
            val (more, _) = ytm.podcastEpisodes(token, show)
            println("next page: ${more.size} episodes")
            assertTrue(more.isNotEmpty())
            assertTrue(more.none { m -> page.episodes.any { it.song.id == m.song.id } })
        }
    }

    @org.junit.experimental.categories.Category(LiveNetwork::class)
    @Test
    fun episodeSearchFindsEpisodesWithTheirShow() = runBlocking {
        val eps = ytm.search("joe rogan", SearchFilter.EPISODES).items.filterIsInstance<SongItem>().map { it.song }
        eps.take(3).forEach { println("${it.id}  ${it.title} — ${it.artistText} (${it.podcastId})") }
        assertTrue(eps.isNotEmpty())
        assertTrue(eps.all { it.isEpisode })
        assertTrue(eps.any { it.podcastId?.startsWith("MPSP") == true })
    }
}
