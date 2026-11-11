package com.prism.music

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.podcast.RssGuard
import com.prism.music.data.podcast.RssPodcasts
import com.prism.music.data.podcast.song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Podcasts by RSS in the app (1.8.2): a show added by its address, on the Podcasts page and in its own page; an episode
 * playing straight from its audio link, with its place kept; the show removed. The feed and the audio are the test's own
 * answers ([RecordedNetwork.byteAnswers]): no YouTube and no real server. [aRealFeedPlays] is the same with a real one.
 */
@RunWith(AndroidJUnit4::class)
class RssPodcastTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val player get() = c.player
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { player.stop() } }
        RecordedNetwork.byteAnswers.clear()
        RecordedNetwork.asked.clear()
    }

    /** A silent WAV: long enough to seek around in, with a place worth keeping. */
    private fun wav(seconds: Int): ByteArray {
        val rate = 8_000
        val samples = rate * seconds
        val data = ByteArrayOutputStream()
        fun le32(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        data.write("RIFF".toByteArray()); le32(36 + samples * 2); data.write("WAVEfmt ".toByteArray())
        le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        data.write("data".toByteArray()); le32(samples * 2); data.write(ByteArray(samples * 2))
        return data.toByteArray()
    }

    private fun serve(url: String, type: String, bytes: ByteArray, code: Int = 200) {
        RecordedNetwork.byteAnswers[url] = ByteAnswer(type, bytes, code)
    }

    /** The show of the tests: the older episode listed first, as some feeds do, and HTML in its description. */
    private fun serveFeed() {
        serve(
            FEED, "application/rss+xml",
            """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>
              <title>Test Radio</title>
              <itunes:author>Test Host</itunes:author>
              <description><![CDATA[<p>A show <b>about</b> tests.</p>]]></description>
              <item>
                <title>$OLDER</title><guid>older</guid><pubDate>Mon, 05 Jan 2026 08:00:00 GMT</pubDate>
                <enclosure url="$AUDIO/older.wav" type="audio/wav" length="1"/><itunes:duration>4:00</itunes:duration>
              </item>
              <item>
                <title>$NEWEST</title><guid>newest</guid><pubDate>Thu, 05 Feb 2026 08:00:00 GMT</pubDate>
                <description>What this one is about.</description>
                <enclosure url="$AUDIO/newest.wav" type="audio/wav" length="1"/><itunes:duration>240</itunes:duration>
              </item>
            </channel></rss>""".toByteArray(),
        )
        serve("$AUDIO/", "audio/wav", wav(240))
    }

    private fun openPodcasts() {
        Fixture.ready()
        Fixture.launch()
        compose.clickWhenFound(hasContentDescription(tr(R.string.podcasts)), 20_000)
        compose.waitForText(tr(R.string.rss_add_a_podcast), 15_000)
    }

    private fun typeAddress(address: String) {
        compose.onNode(hasTestTag("rssAddress")).performTextClearance()
        compose.onNode(hasTestTag("rssAddress")).performTextInput(address)
        compose.onNode(hasText(tr(R.string.add)) and hasClickAction()).performClick()
    }

    private fun position(): Long {
        var p = 0L
        instrumentation.runOnMainSync { p = player.position }
        return p
    }

    private fun pause() = instrumentation.runOnMainSync { player.setPlaying(false) }

    /** Without Compose: while something plays the screen never goes idle, and Compose's own waits would hang on that. */
    private fun waitFor(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("$what: not after $timeoutMs ms (playing ${player.currentSong.value?.title} at ${position()} ms)")
            Thread.sleep(200)
        }
    }

    private fun tapEpisode(title: String) {
        // On the page, not the mini player's copy: nothing here taps the episode already in the mini player.
        compose.onAllNodes(hasText(title) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun present(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    @Test fun addListPlayResumeRemove() {
        serveFeed()
        openPodcasts()

        // Add: from the address to the show's own page.
        compose.clickWhenFound(hasText(tr(R.string.rss_add_a_podcast)) and hasClickAction())
        compose.waitUntil(5_000) { present(hasTestTag("rssAddress")) }
        typeAddress(FEED)
        compose.waitForText(NEWEST, 15_000)
        compose.waitForText("Test Host")
        compose.waitForText("A show about tests.")
        Fixture.screenshot("rss-show-page")

        val show = c.rssPodcasts.shows.value.single()
        assertEquals("Test Radio", show.title)
        assertEquals("newest first, whatever order the feed lists them in", listOf(NEWEST, OLDER), show.episodes.map { it.title })
        val newest = show.episodes[0]
        val older = show.episodes[1]
        assertTrue(newest.song(show).isRss && newest.song(show).isEpisode)

        // Kept on the phone: what a restart (or a backup, which takes the library's folder) reads.
        assertEquals("Test Radio", RssPodcasts(File(c.app.filesDir, RssPodcasts.DIR), RssGuard.feedClient()).shows.value.single().title)

        // Play: from its own link, with nothing of YouTube's or the listener's sent along.
        tapEpisode(NEWEST)
        waitFor("the newest episode plays", 30_000) { player.currentSong.value?.id == newest.id && position() > 2_000 }
        assertTrue(player.currentSong.value!!.isEpisode)
        assertEquals("Podcast feed", c.streamDetails[newest.id]?.first)
        val toAudio = RecordedNetwork.asked.filter { it.url.encodedPath.endsWith("/newest.wav") }
        assertTrue("it asked the feed's audio link", toAudio.isNotEmpty())
        toAudio.forEach { r ->
            assertEquals("https", r.url.scheme)
            listOf("Cookie", "Authorization", "X-Goog-AuthUser", "X-Goog-Visitor-Id", "X-Origin").forEach { h -> assertNull("$h went to a feed's host", r.header(h)) }
        }

        // Its place: saved as it plays, and where it starts next time.
        instrumentation.runOnMainSync { player.seekTo(60_000) }
        waitFor("the place is saved", 25_000) { c.episodes.positionOf(newest.id) >= 55_000 }
        pause()
        tapEpisode(OLDER)
        waitFor("the older episode plays", 30_000) { player.currentSong.value?.id == older.id && position() > 500 }
        pause()
        tapEpisode(NEWEST)
        waitFor("the newest episode comes back", 30_000) { player.currentSong.value?.id == newest.id && position() > 0 }
        waitFor("it resumes at its place", 15_000) { position() >= 55_000 }
        pause()

        // The player as for any podcast: its skips and its speed, nothing a feed's episode can't do.
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        compose.waitUntil(15_000) { present(hasTestTag("playerDeck")) }
        assertTrue("back 15", present(hasContentDescription(trp(R.plurals.back_n_seconds, 15))))
        assertTrue("ahead 30", present(hasContentDescription(trp(R.plurals.ahead_n_seconds, 30))))
        Fixture.screenshot("rss-player")
        compose.onAllNodes(hasContentDescription(tr(R.string.song_options))).onFirst().performClick()
        compose.waitForText(tr(R.string.playback_speed_2))
        assertFalse("nothing to share", present(hasContentDescription(tr(R.string.share))))
        Fixture.screenshot("rss-player-menu")
        compose.onAllNodes(hasText(tr(R.string.playback_speed_2))).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { player.episodeSpeed.value == 1.25f }
        instrumentation.runOnMainSync { player.setEpisodeSpeed(1f) }
        // Back to the show's page: the menu, then the player.
        val device = UiDevice.getInstance(instrumentation)
        device.pressBack()
        compose.waitUntil(5_000) { !present(hasText(tr(R.string.playback_speed_2))) }
        device.pressBack()

        // Not in the listener's songs (Replay's lists are built on them).
        runBlocking {
            assertNull(c.db.songs().get(newest.id))
            assertEquals(0, c.db.plays().count(0, Long.MAX_VALUE))
        }

        // Remove: the show and your place in it go. The episode under way can still finish.
        compose.clickWhenFound(hasContentDescription(tr(R.string.rss_remove_show)), 15_000)
        compose.waitForText(tr(R.string.rss_remove_show_detail))
        compose.onNode(hasText(tr(R.string.remove)) and hasClickAction()).performClick()
        compose.waitForText(tr(R.string.rss_add_a_podcast), 15_000)
        assertTrue(c.rssPodcasts.shows.value.isEmpty())
        assertFalse("its row on the Podcasts page is gone", present(hasText(tr(R.string.rss_your_shows))))
        assertEquals(0L, c.episodes.positionOf(newest.id))
        assertNotNull(c.rssPodcasts.audioUrl(newest.id))
    }

    /** Reddit's report turned around: an episode that won't play says so, and the player goes on to what's next. */
    @Test fun anEpisodeThatWontPlaySaysSoAndMovesOn() {
        serve(
            FEED, "application/rss+xml",
            """<rss version="2.0"><channel><title>Half broken</title>
              <item><title>Broken</title><guid>b</guid><pubDate>Thu, 05 Feb 2026 08:00:00 GMT</pubDate><enclosure url="$AUDIO/broken.mp3" type="audio/mpeg"/></item>
              <item><title>Fine</title><guid>f</guid><pubDate>Mon, 05 Jan 2026 08:00:00 GMT</pubDate><enclosure url="$AUDIO/fine.wav" type="audio/wav"/></item>
            </channel></rss>""".toByteArray(),
        )
        serve("$AUDIO/broken.mp3", "text/plain", "gone".toByteArray(), code = 404)
        serve("$AUDIO/fine.wav", "audio/wav", wav(60))
        Fixture.ready()
        Fixture.launch()
        val show = runBlocking { c.rssPodcasts.add(FEED) }
        val (broken, fine) = show.episodes.map { it.song(show) }
        val said = CopyOnWriteArrayList<String>()
        val listening = CoroutineScope(Dispatchers.Default).launch { player.errors.collect { said += it } }
        try {
            Thread.sleep(500)
            instrumentation.runOnMainSync { player.playQueue(listOf(broken, fine), 0, QueueSource(QueueKind.OTHER, "RSS test")) }
            // Within "“Broken”: …. On to the next song.", so it's found without its last full stop.
            waitFor("it says so", 45_000) { said.any { tr(R.string.rss_episode_wont_play).trimEnd('.') in it && "Broken" in it } }
            waitFor("it goes on to the next one", 15_000) { player.currentSong.value?.id == fine.id && position() > 500 }

            // Alone in the queue (and a show that's gone, as a queue saved before removing it brings back): the words,
            // and no crash.
            said.clear()
            val gone = Song(id = "rss:000000000000:0000000000000000", title = "Gone", artists = listOf(ArtistRef("Gone show")), isEpisode = true, podcastId = "rss:000000000000")
            instrumentation.runOnMainSync { player.playQueue(listOf(gone), 0, QueueSource(QueueKind.OTHER, "RSS test")) }
            waitFor("it says it's gone", 45_000) { said.any { tr(R.string.rss_episode_gone).trimEnd('.') in it } }
        } finally {
            listening.cancel()
        }
        assertTrue("YouTube was never asked", RecordedNetwork.asked.none { it.url.host.contains("youtube") })
    }

    @Test fun anAddressThatWontDoSaysWhyInTheDialog() {
        serve(FEED, "text/html", "<html><body><p>Just a web page</p></body></html>".toByteArray())
        openPodcasts()
        compose.clickWhenFound(hasText(tr(R.string.rss_add_a_podcast)) and hasClickAction())
        compose.waitUntil(5_000) { present(hasTestTag("rssAddress")) }

        typeAddress(FEED)
        compose.waitForText(tr(R.string.rss_not_a_feed), 15_000)

        // The listener's own network: refused before anything is asked.
        val before = RecordedNetwork.asked.size
        typeAddress("http://192.168.1.1/feed.xml")
        compose.waitForText(tr(R.string.rss_own_network), 15_000)
        assertEquals(before, RecordedNetwork.asked.size)

        typeAddress("javascript:alert(1)")
        compose.waitForText(tr(R.string.rss_bad_address), 15_000)
        Fixture.screenshot("rss-dialog-problem")
        assertTrue(c.rssPodcasts.shows.value.isEmpty())

        compose.onNode(hasText(tr(R.string.cancel)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !present(hasTestTag("rssAddress")) }
    }

    /** With a real server, once in a while (tools/record.ps1 -live): NPR News Now, a public feed of short bulletins. */
    @Live @Test fun aRealFeedPlays() {
        Fixture.ready()
        Fixture.launch()
        val show = runBlocking { c.rssPodcasts.add(REAL_FEED) }
        assertTrue(show.episodes.isNotEmpty())
        val first = show.episodes[0]
        android.util.Log.i("PrismRssTest", "${show.title}: ${show.episodes.size} episodes, first ${first.title} at ${first.audioUrl}")
        instrumentation.runOnMainSync { player.playQueue(listOf(first.song(show)), 0, QueueSource(QueueKind.OTHER, "RSS test")) }
        waitFor("a real episode plays", 60_000) { player.currentSong.value?.id == first.id && position() > 3_000 }
        Fixture.screenshot("rss-real-feed")
        pause()
    }

    private companion object {
        const val FEED = "https://pod.example.com/feed.xml"
        const val AUDIO = "https://cdn.example.com/audio"
        const val REAL_FEED = "https://feeds.npr.org/500005/podcast.xml"
        const val NEWEST = "The newest episode"
        const val OLDER = "An older episode"
    }
}
