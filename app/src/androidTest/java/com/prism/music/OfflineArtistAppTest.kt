package com.prism.music

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.MediaTree
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An artist's page without a network (1.8.2; Reddit). The phone really goes into airplane mode for these (`cmd connectivity
 * airplane-mode`, Android 12+), so Prism's own network watcher says it's offline, and back on afterwards. The one artist
 * page answered online is typed by hand below: no recording of one was at hand.
 */
@RunWith(AndroidJUnit4::class)
class OfflineArtistAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val artistId = "UChandwrittenartist0001"
    private val artistName = "Hand Written Artist"
    private val them = ArtistRef(artistName, artistId)

    private val phoneAlbum = AlbumRef("Phone Album", "MPREb_hand_phone")
    private val kept = Song("tsthand0001", "Kept Song One", listOf(them), phoneAlbum, 200)
    private val notHere = Song("tsthand0002", "Not Here Song", listOf(them), null, 210)
    private val liked = Song("tsthand0003", "Only Liked Song", listOf(them), null, 220)

    private fun shell(command: String) =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .use { java.io.FileInputStream(it.fileDescriptor).readBytes().decodeToString() }

    private fun airplane(on: Boolean) {
        shell("cmd connectivity airplane-mode " + if (on) "enable" else "disable")
        val end = System.currentTimeMillis() + 45_000
        while (c.network.isOnline() == on || c.network.online.value == on) {
            if (System.currentTimeMillis() > end) {
                check(!on) { "Airplane mode didn't take this device offline: this test can't say anything here" }
                return // Coming back is slow on some emulators: the next test's @Before asks again.
            }
            Thread.sleep(200)
        }
    }

    @Before fun start() {
        RecordedNetwork.pages.clear()
        shell("cmd media_session volume --stream 3 --set 0")
        shell("cmd connectivity airplane-mode disable")
    }

    @After fun end() {
        runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { c.player.stop() } }
        RecordedNetwork.pages.clear()
        airplane(false)
    }

    private fun waitForApp() = compose.waitUntil(20_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }

    /** As a shared link to the artist opens: straight to their page. */
    private fun openArtist(id: String) {
        Fixture.context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/channel/$id")).setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    /** [text] turns up on the page, scrolling to it if it's further down. */
    private fun ComposeTestRule.seeText(text: String, timeoutMs: Long = 20_000) {
        val match = hasText(text, substring = true)
        val end = System.currentTimeMillis() + timeoutMs
        while (onAllNodes(match).fetchSemanticsNodes().isEmpty()) {
            if (System.currentTimeMillis() > end) {
                val shown = onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                    .mapNotNull { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() }
                throw AssertionError("Not on the page: \"$text\". On screen: ${shown.joinToString(" | ").take(1200)}")
            }
            onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { onAllNodes(hasScrollToNodeAction())[i].performScrollToNode(match) }
            }
            waitForIdle()
            Thread.sleep(100)
        }
    }

    /** Lets the page's clock run: under test it only moves while the rule is waiting for idle, not during a sleep. */
    private fun settle(ms: Long = 4_000) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(100) }
    }

    /** The page's first list, moved up so the song rows clear the tab bar for the picture. */
    private fun rowsIntoView() {
        compose.onAllNodes(hasScrollToNodeAction())[0].performTouchInput { swipeUp() }
        settle(1_000)
    }

    /** What YouTube's artist page looks like to [com.prism.music.data.innertube.Parser], TYPED BY HAND: songs and an album. */
    private fun artistAnswer(songs: List<Song>, albumTitle: String, albumId: String): String {
        val rows = songs.joinToString(",") { s ->
            """{"musicResponsiveListItemRenderer":{"flexColumns":[
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"${s.title}"}]}}},
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$artistName","navigationEndpoint":{"browseEndpoint":{"browseId":"$artistId",
                "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}]}}}
            ],"playlistItemData":{"videoId":"${s.id}"}}}"""
        }
        return """{"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"$artistName"}]}}},
          "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},"contents":[$rows]}},
            {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]}}},"contents":[
              {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$albumTitle"}]},"subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2024"}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"$albumId","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}}
            ]}}
          ]}}}}]}}}"""
    }

    /** Prism's copy as if the page had opened earlier. */
    private fun savedCopy() = ArtistPage(
        id = artistId, name = artistName, thumbnail = null, description = null, subscribers = null,
        shelves = listOf(
            Shelf("Songs", items = listOf(SongItem(kept), SongItem(notHere))),
            Shelf("Albums", items = listOf(AlbumItem("MPREb_hand_elsewhere", "Elsewhere Album", "Album • 2024", null))),
        ),
        radioPlaylistId = null, shufflePlaylistId = null,
    )

    private fun waitForDownload(song: Song) = compose.waitUntil(20_000) { c.downloads.isDownloaded(song.id) }

    private fun likeInLibrary(song: Song) = runBlocking {
        c.db.songs().upsert(song.toEntity().copy(liked = true, likedAt = System.currentTimeMillis()))
    }

    @Test fun aPageOpenedOnlineOpensOfflineAsItWasKept() {
        RecordedNetwork.pages[artistId] = artistAnswer(listOf(kept, notHere), "Elsewhere Album", "MPREb_hand_elsewhere")
        Fixture.ready()
        Fixture.downloaded(listOf(kept))
        val first = Fixture.launch()
        waitForApp()
        waitForDownload(kept)
        assertTrue("this test starts online", c.network.isOnline())
        openArtist(artistId)
        compose.seeText("Not Here Song")
        assertTrue("online it's YouTube's page, no note", compose.onAllNodes(hasText("Kept on this phone", substring = true)).fetchSemanticsNodes().isEmpty())
        compose.waitUntil(15_000) { c.offline.hasArtist(artistId) }

        airplane(true)
        first.close()
        Fixture.launch()
        waitForApp()
        openArtist(artistId)
        compose.seeText("Kept on this phone")
        compose.seeText("On this phone")
        compose.seeText("Kept Song One")
        settle() // the name is written on once the genre lookup gives up (1.5 s) and takes 1.3 s
        Fixture.screenshot("offline-artist-kept")
        rowsIntoView()
        Fixture.screenshot("offline-artist-kept-rows")

        // What isn't here says so and doesn't play; what is, plays.
        compose.clickWhenFound(hasText("Not Here Song") and hasClickAction())
        Thread.sleep(1_500)
        assertNull("a song that isn't on the phone doesn't start", c.player.currentSong.value)
        compose.clickWhenFound(hasText("Elsewhere Album") and hasClickAction())
        Thread.sleep(1_000)
        compose.seeText("Kept on this phone") // still here: an album not kept doesn't open on an error
        compose.clickWhenFound(hasText("Kept Song One") and hasClickAction())
        compose.waitUntil(15_000) { c.player.currentSong.value?.id == kept.id }
    }

    @Test fun withNoCopyAPageIsMadeFromThePhone() {
        Fixture.ready()
        Fixture.downloaded(listOf(kept))
        Fixture.launch()
        waitForApp()
        waitForDownload(kept)
        likeInLibrary(liked)
        airplane(true)
        assertFalse(c.offline.hasArtist(artistId))
        openArtist(artistId)
        compose.seeText("Made from what's on this phone")
        compose.seeText("Your songs")
        compose.seeText("Kept Song One")
        compose.seeText("Only Liked Song")
        settle() // as above: the name is written on
        Fixture.screenshot("offline-artist-from-phone")
        rowsIntoView()
        Fixture.screenshot("offline-artist-from-phone-rows")

        compose.clickWhenFound(hasText("Only Liked Song") and hasClickAction())
        Thread.sleep(1_500)
        assertNull("liked but not on the phone: dimmed, doesn't start", c.player.currentSong.value)
        compose.clickWhenFound(hasText("Kept Song One") and hasClickAction())
        compose.waitUntil(15_000) { c.player.currentSong.value?.id == kept.id }
    }

    @Test fun theirAlbumFromThePhoneOpens() {
        Fixture.ready()
        Fixture.downloaded(listOf(kept))
        Fixture.launch()
        waitForApp()
        waitForDownload(kept)
        airplane(true)
        openArtist(artistId)
        compose.seeText("Made from what's on this phone")
        compose.clickWhenFound(hasText("Phone Album") and hasClickAction())
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Made from what's on this phone")).fetchSemanticsNodes().isEmpty() }
        compose.seeText("Kept Song One")
        compose.seeText("Phone Album")
        // The page's own note says "You're offline · 1 of 1 song…"; the error page is the one that asks to check the connection.
        compose.seeText("1 of 1 song")
        assertTrue("no error page", compose.onAllNodes(hasText("Check your connection", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun anArtistPrismKnowsNothingAboutSaysItsOffline() {
        Fixture.ready()
        Fixture.launch()
        waitForApp()
        airplane(true)
        openArtist("UCnobodyknowsthisartist")
        compose.seeText("You're offline")
    }

    @Test fun whenYouTubeDoesntAnswerTheKeptCopyShows() {
        Fixture.ready()
        Fixture.downloaded(listOf(kept))
        assertTrue(c.offline.saveArtist(savedCopy()))
        Fixture.launch()
        waitForApp()
        waitForDownload(kept)
        assertTrue("this test is about being online with a failing answer", c.network.isOnline())
        openArtist(artistId) // no answer set: RecordedNetwork says 503
        compose.seeText("Kept on this phone")
        compose.seeText("Not Here Song")
    }

    @Test fun theCarListsOnlyWhatPlays() {
        Fixture.ready()
        Fixture.downloaded(listOf(kept))
        Fixture.launch()
        waitForApp()
        waitForDownload(kept)
        likeInLibrary(liked)
        airplane(true)

        val fromPhone = runBlocking { MediaTree(c).children("ar:$artistId") }.map { it.mediaId }
        assertTrue("the downloaded song: $fromPhone", fromPhone.any { kept.id in it })
        assertFalse("not the liked one that isn't here: $fromPhone", fromPhone.any { liked.id in it })

        assertTrue(c.offline.saveArtist(savedCopy()))
        val fromCopy = runBlocking { MediaTree(c).children("ar:$artistId") }.map { it.mediaId }
        assertTrue("the kept page's song on the phone: $fromCopy", fromCopy.any { kept.id in it })
        assertFalse("not the kept page's other song: $fromCopy", fromCopy.any { notHere.id in it })
    }
}
