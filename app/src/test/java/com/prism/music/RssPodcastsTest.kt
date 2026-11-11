package com.prism.music

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.Backup
import com.prism.music.data.BackupPart
import com.prism.music.data.friendlyMessage
import com.prism.music.data.model.Song
import com.prism.music.data.podcast.RssException
import com.prism.music.data.podcast.RssGuard
import com.prism.music.data.podcast.RssPodcasts
import com.prism.music.data.podcast.RssProblem
import com.prism.music.data.podcast.episodeFailureText
import com.prism.music.data.podcast.song
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** The shows kept on the phone, and how Prism talks to a feed's server: over a fake network, with no real one involved. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RssPodcastsTest {
    private val folder = TemporaryFolder().apply { create() }
    private val asked = CopyOnWriteArrayList<Request>()
    private val answers = LinkedHashMap<String, (Request) -> Response>()

    private fun Request.answer(code: Int, body: String = "", vararg headers: Pair<String, String>): Response =
        Response.Builder().request(this).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(body.toResponseBody(null)).build()

    private val network = Interceptor { chain ->
        val r = chain.request()
        asked += r
        val answer = answers[r.url.toString()] ?: error("Nothing answers ${r.url}")
        answer(r)
    }

    private val client: OkHttpClient get() = RssGuard.feedClient(network)
    private val dir: File get() = File(folder.root, "library")

    private fun feed(vararg episodes: Pair<String, String>, title: String = "A Show") =
        """<rss><channel><title>$title</title><description>About it</description>""" + episodes.joinToString("") { (guid, date) ->
            """<item><title>Episode $guid</title><guid>$guid</guid><pubDate>$date</pubDate><enclosure url="https://cdn.example.com/$guid.mp3" type="audio/mpeg"/></item>"""
        } + "</channel></rss>"

    private val url = "https://pod.example.com/feed.xml"

    @Before fun aFeed() {
        answers[url] = { it.answer(200, feed("old" to "Mon, 01 Jan 2024 10:00:00 GMT", "new" to "Tue, 02 Jan 2024 10:00:00 GMT"), "ETag" to "\"v1\"") }
    }

    @Test fun aShowIsAddedNewestFirstAndKept() = runBlocking {
        val store = RssPodcasts(dir, client)
        val show = store.add(url)
        assertEquals("A Show", show.title)
        assertEquals("About it", show.description)
        assertEquals(listOf("Episode new", "Episode old"), show.episodes.map { it.title })
        assertTrue(show.id.startsWith(Song.RSS_PREFIX))
        // Across a restart: the phone's file, read back.
        val again = RssPodcasts(dir, client).shows.value.single()
        assertEquals(show, again)
    }

    @Test fun anEpisodeBecomesAnEpisodeSongOfItsShow() = runBlocking {
        val show = RssPodcasts(dir, client).add(url)
        val song = show.episodes.first().song(show)
        assertTrue(song.isRss)
        assertTrue(song.isEpisode)
        assertFalse(song.isLocal)
        assertEquals(show.id, song.podcastId)
        assertEquals("A Show", song.primaryArtist)
        assertTrue(song.id.startsWith(show.id + ":"))
    }

    @Test fun onlyLinksFromAddedFeedsPlay() = runBlocking {
        val store = RssPodcasts(dir, client)
        val show = store.add(url)
        val first = show.episodes.first()
        assertEquals("https://cdn.example.com/new.mp3", store.audioUrl(first.id))
        assertNull(store.audioUrl(show.id + ":nothing"))
        assertNull(store.audioUrl("rss:nothing:nothing"))
        assertNull(store.audioUrl("dQw4w9WgXcQ"))
    }

    @Test fun sameShowOverHttpOrHttpsIsOneShow() = runBlocking {
        val store = RssPodcasts(dir, client)
        val a = store.add(url)
        val b = store.add("http://pod.example.com/feed.xml")
        assertEquals(a.id, b.id)
        assertEquals(1, store.shows.value.size)
        assertTrue("every request went over https", asked.all { it.url.isHttps })
    }

    @Test fun refreshPicksUpNewEpisodesAndAsksOnlyIfChanged() = runBlocking {
        val store = RssPodcasts(dir, client)
        val show = store.add(url)
        answers[url] = { r ->
            assertEquals("\"v1\"", r.header("If-None-Match"))
            r.answer(304)
        }
        assertEquals(2, store.refresh(show.id)!!.episodes.size)
        answers[url] = { it.answer(200, feed("newest" to "Wed, 03 Jan 2024 10:00:00 GMT", "new" to "Tue, 02 Jan 2024 10:00:00 GMT"), "ETag" to "\"v2\"") }
        val fresh = store.refresh(show.id)!!
        assertEquals(listOf("Episode newest", "Episode new"), fresh.episodes.map { it.title })
        assertEquals("\"v2\"", fresh.etag)
    }

    @Test fun anEpisodeKeepsItsIdWhenItsLinkChanges() = runBlocking {
        val store = RssPodcasts(dir, client)
        val show = store.add(url)
        val before = show.episodes.first { it.title == "Episode new" }
        answers[url] = { it.answer(200, """<rss><channel><title>A Show</title><item><title>Episode new</title><guid>new</guid><enclosure url="https://other.example.com/moved.mp3" type="audio/mpeg"/></item></channel></rss>""") }
        val after = store.refresh(show.id)!!.episodes.single()
        assertEquals(before.id, after.id)
        assertEquals("https://other.example.com/moved.mp3", store.audioUrl(after.id))
    }

    @Test fun anEpisodeUnderWayStaysWhenItLeavesTheFeed() = runBlocking {
        val store = RssPodcasts(dir, client)
        val show = store.add(url)
        val underWay = show.episodes.first { it.title == "Episode old" }.id
        answers[url] = { it.answer(200, feed("new" to "Tue, 02 Jan 2024 10:00:00 GMT")) }
        val fresh = store.refresh(show.id) { it == underWay }!!
        assertEquals(listOf("Episode new", "Episode old"), fresh.episodes.map { it.title })
    }

    @Test fun aBrokenFeedChangesNothing() = runBlocking {
        val store = RssPodcasts(dir, client)
        val show = store.add(url)
        answers[url] = { it.answer(200, "<html>Maintenance</html>") }
        assertEquals(RssProblem.NOT_A_FEED, problemOf { store.refresh(show.id) })
        answers[url] = { it.answer(500) }
        assertEquals(RssProblem.SERVER_ERROR, problemOf { store.refresh(show.id) })
        answers[url] = { throw IOException("gone") }
        assertEquals(RssProblem.CANT_REACH, problemOf { store.refresh(show.id) })
        assertEquals(2, store.show(show.id)!!.episodes.size)
    }

    @Test fun removingAShowForgetsItOnThePhone() = runBlocking {
        val store = RssPodcasts(dir, client)
        val show = store.add(url)
        val first = show.episodes.first()
        assertEquals(show.episodes.map { it.id }, store.remove(show.id))
        assertTrue(store.shows.value.isEmpty())
        // The one playing as it went keeps its link for this run; after a restart it's gone.
        assertNotNull(store.audioUrl(first.id))
        val reopened = RssPodcasts(dir, client)
        assertTrue(reopened.shows.value.isEmpty())
        assertNull(reopened.audioUrl(first.id))
    }

    @Test fun whatsKeptIsBounded() = runBlocking {
        val many = (1..(RssPodcasts.MAX_EPISODES + 100)).map { i -> "e$i" to "Mon, 01 Jan 2024 10:00:00 GMT" }.toTypedArray()
        answers[url] = { it.answer(200, feed(*many)) }
        assertEquals(RssPodcasts.MAX_EPISODES, RssPodcasts(dir, client).add(url).episodes.size)
    }

    @Test fun aRepeatedGuidIsOneEpisode() = runBlocking {
        answers[url] = { it.answer(200, feed("same" to "Mon, 01 Jan 2024 10:00:00 GMT", "same" to "Tue, 02 Jan 2024 10:00:00 GMT")) }
        assertEquals(1, RssPodcasts(dir, client).add(url).episodes.size)
    }

    @Test fun ABadFileOnThePhoneIsNoShowsNotACrash() {
        dir.mkdirs()
        File(dir, RssPodcasts.FILE).writeText("{ not json")
        assertTrue(RssPodcasts(dir, client).shows.value.isEmpty())
    }

    @Test fun theShowsAreInTheLibrarysPartOfABackup() {
        assertEquals("library", RssPodcasts.DIR)
        assertEquals(BackupPart.LIBRARY, Backup.filePart(RssPodcasts.DIR))
    }

    // ---------------------------------------------------------------- The client

    @Test fun theClientCarriesNothingOfYouTubes() = runBlocking {
        RssPodcasts(dir, client).add(url)
        val r = asked.single()
        for (h in listOf("Cookie", "Authorization", "X-Goog-AuthUser", "X-Goog-Visitor-Id", "X-Origin", "Origin", "Referer")) assertNull(h, r.header(h))
        assertTrue(r.header("User-Agent")!!.startsWith("Prism/"))
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(CookieJar.NO_COOKIES, RssGuard.audioClient(network).cookieJar)
        assertEquals(0, RssGuard.audioClient(network).callTimeoutMillis)
    }

    @Test fun redirectsAreFollowedSecurelyAndBounded() = runBlocking {
        answers[url] = { it.answer(301, "", "Location" to "http://moved.example.com/feed.xml") }
        answers["https://moved.example.com/feed.xml"] = { it.answer(302, "", "Location" to "/real.xml") }
        answers["https://moved.example.com/real.xml"] = { it.answer(200, feed("a" to "Tue, 02 Jan 2024 10:00:00 GMT")) }
        val show = RssPodcasts(dir, client).add(url)
        assertEquals("the address stays as pasted", url, show.feedUrl)
        assertEquals(listOf(url, "https://moved.example.com/feed.xml", "https://moved.example.com/real.xml"), asked.map { it.url.toString() })
        answers[url] = { it.answer(302, "", "Location" to url) }
        assertEquals(RssProblem.LINK_REFUSED, problemOf { RssPodcasts(File(folder.root, "other"), client).add(url) })
    }

    @Test fun aRedirectToTheListenersNetworkIsRefused() = runBlocking {
        for (place in listOf("http://192.168.1.1/admin", "https://127.0.0.1/x", "http://localhost:47320/x", "http://[::1]/x", "http://169.254.169.254/latest/meta-data", "http://nas/x")) {
            answers[url] = { it.answer(302, "", "Location" to place) }
            asked.clear()
            assertEquals(place, RssProblem.OWN_NETWORK, problemOf { RssPodcasts(File(folder.root, "other"), client).add(url) })
            assertEquals("never asked", 1, asked.size)
        }
    }

    @Test fun aRedirectToAnotherSchemeGoesNowhere() = runBlocking {
        answers[url] = { it.answer(302, "", "Location" to "file:///sdcard/x") }
        // A redirect with no web address in it is just a bad answer.
        assertEquals(RssProblem.SERVER_ERROR, problemOf { RssPodcasts(File(folder.root, "other"), client).add(url) })
    }

    @Test fun aHeaderTheFeedSentThatCantGoBackIsDropped() {
        val r = RssGuard.feedRequest(okhttp3.HttpUrl.Builder().scheme("https").host("pod.example.com").build(), "badétag\n", "fine")
        assertNull(r.header("If-None-Match"))
        assertEquals("fine", r.header("If-Modified-Since"))
    }

    @Test fun addressesAreCheckedBeforeAnythingIsAsked() = runBlocking {
        assertEquals(RssProblem.BAD_ADDRESS, problemOf { RssPodcasts(dir, client).add("javascript:alert(1)") })
        assertEquals(RssProblem.OWN_NETWORK, problemOf { RssPodcasts(dir, client).add("http://192.168.1.1/feed") })
        assertTrue(asked.isEmpty())
    }

    // ---------------------------------------------------------------- What the listener is told

    @Test fun anEpisodeThatWontPlaySaysSo() {
        val yt = Song("dQw4w9WgXcQ", "x", isEpisode = true)
        val rss = Song("rss:a:b", "x", isEpisode = true)
        val song = Song("dQw4w9WgXcQ", "x")
        // YouTube's own words for a video it won't play: Reddit's report.
        val unavailable = IOException("wrapped", com.prism.music.data.stream.StreamException("Video unavailable"))
        assertEquals(tr(R.string.episode_unavailable_try_rss), episodeFailureText(yt, unavailable))
        // Anything else YouTube says keeps its own words.
        assertNull(episodeFailureText(yt, IOException("Response code: 500")))
        // Songs have their own handling.
        assertNull(episodeFailureText(song, unavailable))
        assertNull(episodeFailureText(null, unavailable))
        // A feed's episode: never told YouTube's story.
        assertEquals(tr(R.string.rss_episode_wont_play), episodeFailureText(rss, unavailable))
        assertEquals(tr(R.string.rss_episode_wont_play), episodeFailureText(rss, IOException("Response code: 404")))
        assertEquals(tr(R.string.rss_own_network), episodeFailureText(rss, IOException("x", RssException(RssProblem.OWN_NETWORK))))
        // Offline: the player's own words say that well.
        assertNull(episodeFailureText(rss, IOException("x", java.net.UnknownHostException("host"))))
        assertEquals(tr(R.string.rss_cant_reach), RssException(RssProblem.CANT_REACH).friendlyMessage())
    }

    private suspend fun problemOf(block: suspend () -> Unit): RssProblem? = try {
        block()
        null
    } catch (e: RssException) {
        e.problem
    }
}
