package com.prism.music

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An artist page's three extras (1.8.2), each behind its switch. YouTube's page and Wikipedia's answers are TYPED BY HAND,
 * in the shape seen live ([ArtistBioLiveTest] asks the real thing). The offline test really goes into airplane mode.
 */
@RunWith(AndroidJUnit4::class)
class ArtistExtrasAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val artistId = "UChandwrittenextras0001"
    private val artistName = "Hand Written Extras"
    private val them = ArtistRef(artistName, artistId)
    private val one = Song("tstxtra0001", "Popular Song Alpha", listOf(them), null, 200)
    private val two = Song("tstxtra0002", "Popular Song Bravo", listOf(them), null, 210)
    private val three = Song("tstxtra0003", "Quiet Song Charlie", listOf(them), null, 220)
    private val albumTitle = "Elsewhere Records Album"
    private val bioText = "Hand Written Extras are a band made up for this test."

    /** What was asked of Wikipedia and Wikidata. */
    private val asked = CopyOnWriteArrayList<String>()

    private fun shell(command: String) =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .use { java.io.FileInputStream(it.fileDescriptor).readBytes().decodeToString() }

    private fun airplane(on: Boolean) {
        shell("cmd connectivity airplane-mode " + if (on) "enable" else "disable")
        val end = System.currentTimeMillis() + 45_000
        while (c.network.isOnline() == on || c.network.online.value == on) {
            if (System.currentTimeMillis() > end) {
                check(!on) { "Airplane mode didn't take this device offline: this test can't say anything here" }
                return
            }
            Thread.sleep(200)
        }
    }

    @Before fun start() {
        RecordedNetwork.pages.clear()
        shell("cmd media_session volume --stream 3 --set 0")
        shell("cmd connectivity airplane-mode disable")
        RecordedNetwork.pages[artistId] = artistAnswer()
        wikipedia()
        Fixture.ready()
    }

    @After fun end() {
        RecordedNetwork.answering = null
        RecordedNetwork.pages.clear()
        airplane(false)
    }

    /** [musician]: whether Wikidata knows the article's subject as one. [named]: how the article names the artist's work. */
    private fun wikipedia(musician: Boolean = true, named: String = "''$albumTitle'' and \"Popular Song Alpha\" and [[Popular Song Bravo]]") {
        RecordedNetwork.answering = { url, _ ->
            if ("wikipedia.org" in url || "wikidata.org" in url) asked += url
            when {
                "wikipedia.org/w/rest.php/v1/search/page" in url -> """{"pages":[{"key":"Hand_Written_Extras","title":"Hand Written Extras"}]}"""
                "wikipedia.org/api/rest_v1/page/summary/" in url ->
                    """{"type":"standard","title":"Hand Written Extras","extract":"$bioText","wikibase_item":"Q1","content_urls":{"mobile":{"page":"https://en.m.wikipedia.org/wiki/Hand_Written_Extras"}}}"""
                "wikidata.org" in url -> if (musician) """{"claims":{"P434":[{"id":"x"}]}}""" else """{"claims":{}}"""
                "wikipedia.org/w/rest.php/v1/page/" in url ->
                    """{"source":${kotlinx.serialization.json.JsonPrimitive(named)},"license":{"title":"CC BY-SA 4.0","url":"https://creativecommons.org/licenses/by-sa/4.0/deed.en"}}"""
                else -> null
            }
        }
    }

    private fun artistAnswer(description: String? = null): String {
        val rows = listOf(one, two, three).joinToString(",") { s ->
            """{"musicResponsiveListItemRenderer":{"flexColumns":[
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"${s.title}"}]}}},
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$artistName","navigationEndpoint":{"browseEndpoint":{"browseId":"$artistId",
                "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}]}}}
            ],"playlistItemData":{"videoId":"${s.id}"}}}"""
        }
        val about = description?.let { ""","description":{"runs":[{"text":"$it"}]}""" }.orEmpty()
        return """{"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"$artistName"}]}$about}},
          "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},"contents":[$rows]}},
            {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]}}},"contents":[
              {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$albumTitle"}]},"subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2024"}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_hand_extras","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}}
            ]}}
          ]}}}}]}}}"""
    }

    /** Four plays of the artist's third song and one of their first, so the listener's order isn't YouTube's. */
    private fun plays() = runBlocking {
        listOf(one, two, three).forEach { c.db.songs().upsert(it.toEntity()) }
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        c.db.plays().insert(PlayEventEntity(songId = one.id, timestamp = now - 40 * day, playedMs = 200_000))
        (1..4).forEach { c.db.plays().insert(PlayEventEntity(songId = three.id, timestamp = now - it * day, playedMs = 900_000)) }
    }

    private fun switches(bio: Boolean = false, you: Boolean = false, essentials: Boolean = false) {
        c.settings.setArtistBio(bio); c.settings.setArtistYou(you); c.settings.setArtistEssentials(essentials)
        compose.waitUntil(5_000) { c.settings.current.behavior.let { it.artistBio == bio && it.artistYou == you && it.artistEssentials == essentials } }
    }

    private fun waitForApp() = compose.waitUntil(20_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }

    private fun openArtist() {
        Fixture.context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/channel/$artistId")).setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

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

    /** Whether [text] is anywhere on the page, looking all the way down it. */
    private fun onPage(text: String): Boolean {
        val match = hasText(text, substring = true)
        compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { compose.onAllNodes(hasScrollToNodeAction())[i].performScrollToNode(match) }
        }
        compose.waitForIdle()
        return compose.onAllNodes(match).fetchSemanticsNodes().isNotEmpty()
    }

    private fun settle(ms: Long = 2_500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(100) }
    }

    private fun theSongsAreIn() = compose.seeText("Popular Song Alpha")

    @Test fun switchedOffThePageIsAsItWasAndWikipediaIsNeverAsked() {
        plays()
        Fixture.launch()
        waitForApp()
        openArtist()
        theSongsAreIn()
        settle()
        assertFalse(onPage("You and this artist"))
        assertFalse(onPage("Essentials for you"))
        assertFalse(onPage(bioText))
        assertEquals("nothing's sent to Wikipedia", emptyList<String>(), asked.toList())
    }

    @Test fun switchedOnEachExtraShows() {
        plays()
        Fixture.launch()
        waitForApp()
        switches(bio = true, you = true, essentials = true)
        openArtist()
        theSongsAreIn()
        compose.seeText("You and this artist")
        compose.seeText("Quiet Song Charlie · 4") // the listener's top song, and how many plays
        Fixture.screenshot("artist-you")
        compose.seeText("Essentials for you")
        compose.seeText(bioText)
        compose.seeText("on Wikipedia, by its contributors") // the credit, beside it as the licence asks
        compose.seeText("CC BY-SA 4.0")
        Fixture.screenshot("artist-bio")
    }

    @Test fun withNoPlaysThereIsNoYouAndNoEssentials() {
        Fixture.launch()
        waitForApp()
        switches(you = true, essentials = true)
        openArtist()
        theSongsAreIn()
        settle()
        assertFalse(onPage("You and this artist"))
        assertFalse(onPage("Essentials for you"))
    }

    @Test fun anArticleThatIsntSurelyThemShowsNothing() {
        wikipedia(musician = false)
        Fixture.launch()
        waitForApp()
        switches(bio = true)
        openArtist()
        theSongsAreIn()
        compose.waitUntil(15_000) { asked.any { "wikidata.org" in it } }
        settle()
        assertFalse(onPage(bioText))
        assertFalse(onPage("on Wikipedia, by its contributors"))
    }

    @Test fun anArticleNamingTooLittleOfTheirWorkShowsNothing() {
        wikipedia(named = "A band. ''$albumTitle'' only.")
        Fixture.launch()
        waitForApp()
        switches(bio = true)
        openArtist()
        theSongsAreIn()
        compose.waitUntil(15_000) { asked.any { "rest.php/v1/page/" in it } }
        settle()
        assertFalse(onPage(bioText))
    }

    /** YouTube's description being Wikipedia's text (it often is), with its credit: the page has it, so nothing is asked. */
    @Test fun whatYouTubeAlreadyQuotesIsntAskedFor() {
        RecordedNetwork.pages[artistId] = artistAnswer("A band. From Wikipedia (https://en.wikipedia.org/wiki/Hand_Written_Extras) under CC BY-SA.")
        Fixture.launch()
        waitForApp()
        switches(bio = true)
        openArtist()
        theSongsAreIn()
        settle()
        assertEquals(emptyList<String>(), asked.toList())
        assertFalse(onPage(bioText))
    }

    @Test fun aKeptPageOpensOfflineWithItsBioAndYourPlays() {
        plays()
        val first = Fixture.launch()
        waitForApp()
        switches(bio = true, you = true, essentials = true)
        openArtist()
        theSongsAreIn()
        compose.seeText(bioText)
        compose.waitUntil(15_000) { c.offline.hasArtist(artistId) }

        airplane(true)
        asked.clear()
        first.close()
        Fixture.launch()
        waitForApp()
        openArtist()
        compose.seeText("Kept on this phone")
        compose.seeText("You and this artist")
        compose.seeText("Essentials for you")
        compose.seeText(bioText)
        Fixture.screenshot("artist-bio-offline")
        assertEquals("offline, nothing's asked", emptyList<String>(), asked.toList())
    }

    @Test fun theSwitchesAreInSettings() {
        Fixture.launch()
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput("you and this artist")
        compose.clickWhenFound(hasText("You and this artist") and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        assertFalse("off until turned on", c.settings.current.behavior.artistYou)
        compose.clickWhenFound(hasText("You and this artist") and hasClickAction())
        compose.waitUntil(5_000) { c.settings.current.behavior.artistYou }
        assertTrue(c.settings.current.behavior.artistYou)
        assertFalse("the others are their own", c.settings.current.behavior.artistBio)
    }
}
