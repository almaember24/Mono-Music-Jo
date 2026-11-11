package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** #130: Search's playlists come as YouTube Music's own (Featured) and people's (Community). Live against YouTube Music. */
@Category(LiveNetwork::class)
class SearchPlaylistsLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun featuredAndCommunityPlaylistsDiffer() = runBlocking {
        val featured = ytm.search("chill", SearchFilter.FEATURED_PLAYLISTS).items.filterIsInstance<PlaylistItem>()
        val community = ytm.search("chill", SearchFilter.PLAYLISTS).items.filterIsInstance<PlaylistItem>()
        println("[playlists] featured: " + featured.take(5).joinToString(" | ") { "${it.title} (${it.subtitle})" })
        println("[playlists] community: " + community.take(5).joinToString(" | ") { "${it.title} (${it.subtitle})" })
        assertTrue("featured found", featured.isNotEmpty())
        assertTrue("community found", community.isNotEmpty())
        // YouTube Music's own playlists name it as their maker.
        assertTrue("featured are YouTube Music's", featured.take(5).count { it.subtitle.contains("YouTube Music") } >= 3)
        assertTrue("community are people's", community.take(5).count { it.subtitle.contains("YouTube Music") } <= 1)
    }
}
