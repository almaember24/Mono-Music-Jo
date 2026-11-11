package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** 1.7.6 Search: typing suggestions come with artists, songs and albums (and their pictures). Live against YouTube Music. */
@Category(LiveNetwork::class)
class SearchSuggestionsLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun suggestionsCarryItems() = runBlocking {
        val (texts, items) = ytm.searchSuggestionsWithItems("daft pu")
        println("[suggest] texts: $texts")
        println("[suggest] items: " + items.joinToString(" | ") { "${it::class.simpleName} ${it.title} (${it.subtitle}) art=${it.thumbnail != null}" })
        assertTrue("completions", texts.isNotEmpty())
        assertTrue("items with pictures", items.any { it.thumbnail != null })
    }
}
