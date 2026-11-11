package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchAccount
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #396: from South Korea, YouTube Music's song and video searches come back empty without the account (it needs Premium
 * there), so searches asked without it (#27) found no songs. Signed in, an empty one is asked once more with the account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SearchWithAccountTest {
    private val songRow = """{"musicResponsiveListItemRenderer":{"flexColumns":[
        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Beat It"}]}}},
        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Michael Jackson"}]}}}],
        "playlistItemData":{"videoId":"oRdxUFDoQe0"}}}"""
    private val found = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
        {"musicShelfRenderer":{"contents":[$songRow]}}]}}}}]}}}"""

    /** One request: its endpoint, its body, and whether it went with the account (its cookie). */
    private class Asked(val endpoint: String, val body: JsonObject, val withAccount: Boolean)

    /** A YouTube Music that serves the songs to the account only, as it does in Korea; [anonymous] is the other answer. */
    private fun korea(signedIn: Boolean = true, anonymous: String = "{}"): Pair<YouTubeMusic, MutableList<Asked>> {
        val asked = java.util.Collections.synchronizedList(mutableListOf<Asked>())
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val endpoint = req.url.encodedPath.substringAfter("/youtubei/v1/", "")
            val text = if (endpoint.isEmpty()) "<html></html>" else {
                val buf = okio.Buffer().also { req.body?.writeTo(it) }
                val account = req.header("Cookie") != null
                asked += Asked(endpoint, InnerTube.json.parseToJsonElement(buf.readUtf8()).jsonObject, account)
                if (account) found else anonymous
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fake")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val settings = AppSettings(cookie = if (signedIn) "SAPISID=abc; __Secure-3PAPISID=abc" else "")
        return YouTubeMusic(InnerTube(http, { settings })) to asked
    }

    private fun List<Asked>.searches() = filter { it.endpoint == "search" }

    @Test fun anEmptySongSearchIsAskedAgainWithTheAccount() = runBlocking {
        val (ytm, asked) = korea()
        val songs = ytm.search("beat it", SearchFilter.SONGS)
        assertEquals("the song the account gets", listOf("oRdxUFDoQe0"), songs.items.map { it.id })
        assertEquals("first without the account, then with it", listOf(false, true), asked.searches().map { it.withAccount })
        // Videos too.
        assertEquals(1, korea().first.search("beat it", SearchFilter.VIDEOS).items.size)
    }

    @Test fun theTopSearchToo() = runBlocking {
        // YouTube's unfiltered search, whose card and songs Top results reads.
        val (ytm, asked) = korea()
        assertEquals(listOf("oRdxUFDoQe0"), ytm.youtubeTop("beat it").second.map { it.id })
        assertEquals(listOf(false, true), asked.searches().map { it.withAccount })
    }

    @Test fun onceTheAccountFoundThemLaterSearchesGoWithIt() = runBlocking {
        val (ytm, asked) = korea()
        ytm.search("beat it", SearchFilter.SONGS)
        asked.clear()
        assertEquals(1, ytm.search("thriller", SearchFilter.SONGS).items.size)
        assertEquals(1, ytm.search("thriller", SearchFilter.ALBUMS).items.size)
        assertEquals("straight with the account", listOf(true, true), asked.searches().map { it.withAccount })
        // Typing suggestions stay without it: YouTube would keep every keystroke in the account's history.
        ytm.searchSuggestionsWithItems("thri")
        assertEquals(false, asked.single { it.endpoint == "music/get_search_suggestions" }.withAccount)
    }

    @Test fun signedOutNothingIsAskedAgain() = runBlocking {
        val (ytm, asked) = korea(signedIn = false)
        assertEquals(0, ytm.search("beat it", SearchFilter.SONGS).items.size)
        assertEquals(listOf(false), asked.searches().map { it.withAccount })
    }

    @Test fun whatAnonymousFindsStaysAnonymous() = runBlocking {
        val (ytm, asked) = korea(anonymous = found)
        assertEquals(1, ytm.search("beat it", SearchFilter.SONGS).items.size)
        assertEquals(1, ytm.search("thriller", SearchFilter.SONGS).items.size)
        assertEquals(listOf(false, false), asked.searches().map { it.withAccount })
    }

    @Test fun theDecision() {
        fun again(signedIn: Boolean = true, query: String = "beat it", filter: SearchFilter = SearchFilter.SONGS, nothing: Boolean = true, next: Boolean = false) =
            SearchAccount.askAgain(signedIn, query, filter, nothing, next)
        assertTrue(again())
        assertTrue(again(filter = SearchFilter.VIDEOS))
        assertTrue(again(filter = SearchFilter.TOP))
        assertFalse("signed out", again(signedIn = false))
        assertFalse("nothing typed", again(query = "  "))
        assertFalse("something was found", again(nothing = false))
        assertFalse("a further page", again(next = true))
        assertFalse(again(filter = SearchFilter.ALBUMS))
        assertFalse(again(filter = SearchFilter.ARTISTS))
        // The flag holds only while signed in.
        assertTrue(SearchAccount.withAccount(signedIn = true, accountFoundMore = true))
        assertFalse(SearchAccount.withAccount(signedIn = false, accountFoundMore = true))
        assertFalse(SearchAccount.withAccount(signedIn = true, accountFoundMore = false))
    }

    @Test fun otherKindsAreNotAskedAgain() = runBlocking {
        val (ytm, asked) = korea()
        // An album search that finds nothing finds nothing (YouTube serves albums without Premium there).
        assertEquals(0, ytm.search("beat it", SearchFilter.ALBUMS).items.size)
        assertEquals(listOf(false), asked.searches().map { it.withAccount })
    }
}
