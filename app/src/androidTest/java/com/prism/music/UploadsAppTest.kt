package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.prefs.LibraryTile
import com.prism.music.playback.AudioDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library → Uploads (#332, #101) in the app, against answers TYPED BY HAND (assets/uploads-handwritten/, from what
 * ytmusicapi reads; see README.txt there). No account with uploads was at hand, so these show the tile, the page, its
 * tabs, search and sort, and giving up softly; they don't show that YouTube answers this way.
 */
@RunWith(AndroidJUnit4::class)
class UploadsAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val tracks = "FEmusic_library_privately_owned_tracks"
    private val releases = "FEmusic_library_privately_owned_releases"
    private val artists = "FEmusic_library_privately_owned_artists"
    private val coldplay = "FEmusic_library_privately_owned_artist_detaila_po_CICr2crg7OWpchIIY29sZHBsYXk"
    private val months18 = "FEmusic_library_privately_owned_release_detailb_po_18M"
    private val tile = "Music you uploaded"

    private fun answer(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open("uploads-handwritten/$name.json").use { it.readBytes().decodeToString() }

    private fun theFullAccount() {
        RecordedNetwork.pages[tracks] = answer("tracks")
        RecordedNetwork.pages["HAND-TOKEN-2"] = answer("tracks-next")
        RecordedNetwork.pages[releases] = answer("releases")
        RecordedNetwork.pages[artists] = answer("artists")
        RecordedNetwork.pages["HAND-ARTISTS-2"] = answer("no-uploads")
    }

    private fun signIn() = runBlocking {
        c.settings.fromBackup(mapOf("cookie" to "s:SAPISID=test; SID=test", "account_name" to "s:Test Listener"), settings = false, accounts = true)
        withTimeout(10_000) { while (!c.settings.current.isLoggedIn) delay(20) }
    }

    @Before fun start() {
        RecordedNetwork.pages.clear()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
    }

    @After fun end() {
        runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { c.player.stop() } }
        RecordedNetwork.pages.clear()
        RecordedNetwork.answering = null
        runBlocking { c.settings.fromBackup(emptyMap(), settings = false, accounts = true) }
    }

    private fun openLibrary() {
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun tiles() = c.settings.current.libraryTiles

    /** [text] turns up, scrolling to it if it's further down. */
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

    private fun ComposeTestRule.gone(text: String, timeoutMs: Long = 10_000) =
        waitUntil(timeoutMs) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty() }

    private fun openUploads() {
        compose.waitUntil(20_000) { LibraryTile.UPLOADS in tiles() }
        compose.clickWhenFound(hasText(tile) and hasClickAction(), 20_000)
    }

    @Test fun signedOutThereIsNoTile() {
        theFullAccount()
        Fixture.ready()
        Fixture.launch()
        openLibrary()
        runBlocking { c.uploads.check(force = true) }
        assertFalse(c.uploads.has.value)
        assertFalse(LibraryTile.UPLOADS in tiles())
        assertTrue(compose.onAllNodes(hasText(tile)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun anAccountWithNoUploadsSeesNoChange() {
        RecordedNetwork.pages[tracks] = answer("no-uploads")
        Fixture.ready()
        signIn()
        val before = tiles()
        Fixture.launch()
        openLibrary()
        runBlocking { c.uploads.check(force = true) }
        assertFalse(c.uploads.has.value)
        assertEquals("the listener's tiles are as they were", before, tiles())
        assertTrue(compose.onAllNodes(hasText(tile)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theTileGoesInOnceAndStaysOutWhenTakenOut() {
        theFullAccount()
        Fixture.ready()
        signIn()
        val before = tiles()
        assertFalse(LibraryTile.UPLOADS in before)
        Fixture.launch()
        openLibrary()
        compose.waitUntil(20_000) { LibraryTile.UPLOADS in tiles() }
        compose.seeText(tile)
        assertEquals("nothing else moved", before, tiles() - LibraryTile.UPLOADS)
        Fixture.screenshot("uploads-tile")

        c.settings.setLibraryTiles(tiles() - LibraryTile.UPLOADS)
        compose.waitUntil(5_000) { LibraryTile.UPLOADS !in tiles() }
        runBlocking { c.uploads.check(force = true) }
        Thread.sleep(800)
        assertTrue("still has uploads", c.uploads.has.value)
        assertFalse("taken out stays out", LibraryTile.UPLOADS in tiles())
        compose.gone(tile)
    }

    @Test fun thePageListsSearchesAndSorts() {
        theFullAccount()
        Fixture.ready()
        signIn()
        Fixture.launch()
        openLibrary()
        openUploads()

        // Songs, past the first page; the "Shuffle all" row isn't a song.
        compose.seeText("A Sky Full Of Stars")
        compose.seeText("Hold Me")
        compose.seeText("Page Two Song")
        assertTrue(compose.onAllNodes(hasText("Shuffle all")).fetchSemanticsNodes().isEmpty())
        Fixture.screenshot("uploads-songs")

        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("sky coldplay")
        compose.gone("Page Two Song")
        compose.seeText("A Sky Full Of Stars")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("zzz")
        compose.gone("A Sky Full Of Stars")
        compose.seeText("Nothing in Uploads")
    }

    @Test fun albumsSortAndOpen() {
        theFullAccount()
        RecordedNetwork.pages[months18] = answer("release-detail")
        Fixture.ready()
        signIn()
        Fixture.launch()
        openLibrary()
        openUploads()
        compose.seeText("A Sky Full Of Stars")
        compose.clickWhenFound(hasText("Albums") and hasClickAction())
        compose.seeText("Ghost Stories")
        compose.seeText("18 Months")
        fun left(title: String) = compose.onAllNodes(hasText(title) and hasClickAction()).onFirst().fetchSemanticsNode().boundsInRoot.left
        assertTrue("library order first", left("Ghost Stories") < left("18 Months"))
        Fixture.screenshot("uploads-albums")

        // The album list's sort: the pager keeps the page beside it too, so the button that's on the screen.
        val sort = hasText("Library order") and hasClickAction()
        val screen = Fixture.context.resources.displayMetrics.widthPixels.toFloat()
        val shown = compose.onAllNodes(sort).fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.left in 0f..screen }
        assertTrue("the sort button is on the screen", shown >= 0)
        compose.onAllNodes(sort)[shown].performClick()
        compose.clickWhenFound(hasText("Title") and hasClickAction())
        compose.waitUntil(5_000) { left("18 Months") < left("Ghost Stories") }

        compose.clickWhenFound(hasText("18 Months") and hasClickAction())
        compose.seeText("Feel So Close")
    }

    @Test fun artistsListAndOpen() {
        theFullAccount()
        RecordedNetwork.pages[coldplay] = answer("tracks")
        Fixture.ready()
        signIn()
        Fixture.launch()
        openLibrary()
        openUploads()
        compose.seeText("A Sky Full Of Stars")
        compose.clickWhenFound(hasText("Artists") and hasClickAction())
        compose.seeText("Coldplay")
        compose.seeText("12 songs")
        compose.gone("Page Two Song")
        compose.clickWhenFound(hasText("Coldplay") and hasClickAction())
        compose.gone("12 songs")
        compose.seeText("A Sky Full Of Stars")
    }

    @Test fun aSongStartsMarkedAsAnUpload() {
        theFullAccount()
        Fixture.ready()
        signIn()
        Fixture.launch()
        openLibrary()
        openUploads()
        compose.clickWhenFound(hasText("A Sky Full Of Stars", substring = true) and hasClickAction())
        compose.waitUntil(15_000) { c.player.currentSong.value?.id == "Uise6RPKoek" }
        assertTrue(c.player.currentSong.value?.isUpload == true)
    }

    private val songIds get() = c.uploads.lists.value.songs.map { it.id }
    private val pageTwoSong = "pageTwo0001"

    /** Leaving the page while it loads: back on it, the list comes in whole, rather than stopping where it was left. */
    @Test fun leavingDuringALoadDoesntLeaveTheListCutShort() {
        theFullAccount()
        val letGo = java.util.concurrent.CountDownLatch(1)
        RecordedNetwork.answering = { url, body ->
            if ("HAND-TOKEN-2" in url || "HAND-TOKEN-2" in body) { letGo.await(20, java.util.concurrent.TimeUnit.SECONDS); answer("tracks-next") } else null
        }
        Fixture.ready()
        signIn()
        runBlocking {
            val visit = launch(kotlinx.coroutines.Dispatchers.Default) { c.uploads.load() }
            withTimeout(15_000) { while (c.uploads.state.value !is com.prism.music.data.Uploads.State.Ready) delay(20) }
            visit.cancel()
            letGo.countDown()
            visit.join()
        }
        RecordedNetwork.answering = null
        runBlocking { c.uploads.load() }
        assertTrue("the rest of the songs: $songIds", pageTwoSong in songIds)
        assertEquals(listOf("Ghost Stories", "18 Months"), c.uploads.lists.value.albums.map { it.title })
        assertEquals(listOf("Coldplay"), c.uploads.lists.value.artists.map { it.title })
        assertEquals("kept for offline too", songIds, c.offline.uploads()?.songs?.map { it.id })
    }

    /** A page that doesn't come: the fuller list kept offline stays, and stays on show; the next look asks again. */
    @Test fun aFailedPageDoesntReplaceAFullerCopy() {
        theFullAccount()
        Fixture.ready()
        signIn()
        runBlocking { c.uploads.load() }
        assertTrue(pageTwoSong in c.offline.uploads()!!.songs.map { it.id })

        RecordedNetwork.pages.remove("HAND-TOKEN-2")
        runBlocking { c.uploads.load(force = true) }
        assertTrue("the kept copy keeps it", pageTwoSong in c.offline.uploads()!!.songs.map { it.id })
        assertTrue("and it's still listed: $songIds", pageTwoSong in songIds)

        RecordedNetwork.pages["HAND-TOKEN-2"] = answer("tracks-next")
        assertEquals("the page knows it isn't done", com.prism.music.data.Uploads.State.Ready(true), c.uploads.state.value)
        runBlocking { c.uploads.load() }
        assertEquals(com.prism.music.data.Uploads.State.Ready(false), c.uploads.state.value)
    }

    /** The same album or artist twice from YouTube: "Key was already used", and the page closed the app. */
    @Test fun aRepeatedAlbumOrArtistDoesntCrash() {
        theFullAccount()
        RecordedNetwork.pages[releases] = answer("releases")
            .replace("FEmusic_library_privately_owned_release_detailb_po_18M", "FEmusic_library_privately_owned_release_detailb_po_GHOST")
        RecordedNetwork.pages["HAND-ARTISTS-2"] = answer("artists").replace("HAND-ARTISTS-2", "HAND-ARTISTS-3")
        RecordedNetwork.pages["HAND-ARTISTS-3"] = answer("no-uploads")
        Fixture.ready()
        signIn()
        Fixture.launch()
        openLibrary()
        openUploads()
        compose.seeText("A Sky Full Of Stars")
        compose.clickWhenFound(hasText("Albums") and hasClickAction())
        compose.seeText("Ghost Stories")
        compose.clickWhenFound(hasText("Artists") and hasClickAction())
        compose.seeText("12 songs")
        val shown = c.uploads.lists.value
        assertEquals(shown.albums.map { it.id }.distinct(), shown.albums.map { it.id })
        assertEquals(shown.artists.map { it.id }.distinct(), shown.artists.map { it.id })
    }

    /** No radio or shuffle list of YouTube's for these: Shuffle plays their songs, and no Mix is offered that can't start. */
    @Test fun anUploadedArtistShufflesTheirOwnSongs() {
        theFullAccount()
        RecordedNetwork.pages[coldplay] = answer("tracks")
        Fixture.ready()
        signIn()
        Fixture.launch()
        openLibrary()
        openUploads()
        compose.seeText("A Sky Full Of Stars")
        compose.clickWhenFound(hasText("Artists") and hasClickAction())
        compose.seeText("12 songs")
        compose.onAllNodes(hasText("Coldplay") and hasClickAction()).onFirst().performTouchInput { longClick() }
        compose.waitForText(tr(R.string.shuffle))
        assertTrue("no Mix", compose.onAllNodes(hasText(tr(R.string.mix_2))).fetchSemanticsNodes().isEmpty())
        Fixture.screenshot("uploads-artist-menu")
        compose.onAllNodes(hasText(tr(R.string.shuffle)) and hasClickAction()).onFirst().performClick()
        compose.waitUntil(15_000) { c.player.currentSong.value?.isUpload == true }
        // Their songs from both pages: shuffling starts on any of them.
        assertTrue(c.player.currentSong.value?.id in setOf("Uise6RPKoek", "Dtffhy8WJgw", "pageTwo0001"))
    }

    /** YouTube answers some other way: no tile, a line in the diagnostics, nothing broken. */
    @Test fun anotherShapeMeansNoTileAndALineInTheDiagnostics() {
        RecordedNetwork.pages[tracks] = answer("other-shape")
        Fixture.ready()
        c.settings.setAudioDiagnostics(true)
        AudioDiagnostics.setEnabled(true) { "test" }
        signIn()
        val before = tiles()
        Fixture.launch()
        openLibrary()
        runBlocking { c.uploads.check(force = true) }
        assertFalse(c.uploads.has.value)
        assertEquals(before, tiles())
        assertTrue(compose.onAllNodes(hasText(tile)).fetchSemanticsNodes().isEmpty())
        val log = java.io.File(Fixture.context.filesDir, "diagnostics/audio.log")
        val line = "[uploads] check: 2 rows, 0 read (the answer's shape isn't the expected one)"
        compose.waitUntil(10_000) { log.exists() && line in log.readText() }
    }

    /** The other way of failing softly: YouTube doesn't answer at all. */
    @Test fun noAnswerMeansNoTileAndALineInTheDiagnostics() {
        Fixture.ready()
        c.settings.setAudioDiagnostics(true)
        AudioDiagnostics.setEnabled(true) { "test" }
        signIn()
        val before = tiles()
        Fixture.launch()
        openLibrary()
        runBlocking { c.uploads.check(force = true) }
        assertFalse(c.uploads.has.value)
        assertEquals(before, tiles())
        val log = java.io.File(Fixture.context.filesDir, "diagnostics/audio.log")
        compose.waitUntil(10_000) { log.exists() && "[uploads] check failed" in log.readText() }
    }
}
