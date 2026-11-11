package com.prism.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.TvFixture.awaitFocus
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.ui.tv.TAG_COLLECTION
import com.prism.music.ui.tv.TAG_LIBRARY
import com.prism.music.ui.tv.TAG_NAV
import com.prism.music.ui.tv.TAG_PLAYER
import com.prism.music.ui.tv.TAG_SEARCH
import com.prism.music.ui.tv.TvSection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prism TV, slice 5: Search by keyboard (answers from the recordings Search's phone test made), and Library → a
 * playlist's page → Play, all with the remote.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvBrowseTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val tones = ArrayList<android.net.Uri>()

    @Before fun setUp() {
        TvFixture.assumeTv()
        Fixture.ready()
    }

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { Fixture.container.player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    private val inNav = androidx.compose.ui.test.SemanticsMatcher("in the side navigation") { n ->
        n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith("$TAG_NAV/") == true
    }

    /** From wherever focus is, Left into the side navigation, then down to [section] and OK. */
    private fun goTo(section: TvSection) {
        compose.awaitFocus(15_000)
        for (i in 0 until 6) {
            if (compose.onAllNodes(inNav and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
            TvFixture.left(); Thread.sleep(300)
        }
        repeat(section.ordinal) { TvFixture.down(); Thread.sleep(200) }
        compose.awaitFocusOn(hasTestTag("$TAG_NAV/${section.name}"))
        TvFixture.ok()
    }

    @Test fun searchingWithTheKeyboardFindsTheSong() {
        TvFixture.launch()
        goTo(TvSection.SEARCH)
        compose.awaitFocusOn(hasTestTag("$TAG_SEARCH/field"), 10_000)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_SEARCH/input"), 10_000)
        compose.onNode(hasTestTag("$TAG_SEARCH/input")).performTextInput("get lucky daft punk")
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Get Lucky", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // The keyboard's Search: focus goes to the first result (the real TV's feedback: it stayed on the box).
        compose.onNode(hasTestTag("$TAG_SEARCH/input")).performImeAction()
        compose.awaitFocusOn(hasTestTag("$TAG_SEARCH/top") or hasTestTag("$TAG_SEARCH/song/0"), 10_000)
        // Up reaches the search line again.
        TvFixture.up(); Thread.sleep(300)
        compose.awaitFocusOn(hasTestTag("$TAG_SEARCH/field") or hasTestTag("$TAG_SEARCH/voice"), 10_000)
    }

    /** Before typing: how to search with a remote, not a bare box. */
    @Test fun searchSaysHowBeforeAnythingIsTyped() {
        TvFixture.launch()
        goTo(TvSection.SEARCH)
        compose.awaitFocusOn(hasTestTag("$TAG_SEARCH/field"), 10_000)
        compose.onNode(hasTestTag("$TAG_SEARCH/how"), useUnmergedTree = true).assertExists()
        // The microphone is reachable beside the line.
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_SEARCH/voice"))
    }

    /** Signed out, an empty Library tab says why and offers the sign-in with the phone. */
    @Test fun anEmptyLibrarySaysWhyAndOffersTheSignIn() {
        TvFixture.launch()
        goTo(TvSection.LIBRARY)
        compose.awaitFocusOn(hasTestTag("$TAG_LIBRARY/tab/0"), 10_000)
        // Artists: only an account has them.
        TvFixture.right(); Thread.sleep(250); TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_LIBRARY/tab/2"))
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("$TAG_LIBRARY/empty"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_LIBRARY/signin"))
        TvFixture.ok()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(com.prism.music.ui.tv.TAG_PAIR), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aPlaylistOpensFromLibraryAndPlays() {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tones += Fixture.tone("Prism TV list one", 90)
        tones += Fixture.tone("Prism TV list two", 90)
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val songs = listOf("Prism TV list one", "Prism TV list two").map { t -> local.tracks.value.first { it.song.title == t }.song }
        Fixture.prismPlaylist("TV evening", songs)

        TvFixture.launch()
        goTo(TvSection.LIBRARY)
        compose.awaitFocusOn(hasTestTag("$TAG_LIBRARY/tab/0"), 10_000)
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_LIBRARY/grid/0"), 10_000)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("TV evening"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_COLLECTION/play"), 15_000)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Prism TV list two"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // Down from Play goes into the songs, at the first (the real TV's feedback: it did nothing).
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_COLLECTION/song/0"))
        TvFixture.left()
        compose.awaitFocusOn(hasTestTag("$TAG_COLLECTION/play") or hasTestTag("$TAG_COLLECTION/shuffle"))
        if (compose.onAllNodes(hasTestTag("$TAG_COLLECTION/shuffle") and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) TvFixture.left()
        compose.awaitFocusOn(hasTestTag("$TAG_COLLECTION/play"))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 15_000)
        val player = Fixture.container.player
        val end = System.currentTimeMillis() + 15_000
        while (player.currentSong.value?.id != songs[0].id && System.currentTimeMillis() < end) Thread.sleep(100)
        assertEquals(songs[0].id, player.currentSong.value?.id)
        // The playlist's two songs, then only what autoplay adds (it tops a queue this short up from your own music).
        assertEquals(songs.map { it.id }, player.queue.value.take(2).map { it.song.id })
        org.junit.Assert.assertTrue("only autoplay's after the playlist", player.queue.value.size == 2 || Fixture.container.queue.autoplayStart.value == 2)
        // Back: the playlist's page, Play still focused, the playing song marked; Back again: Library, on the playlist.
        TvFixture.back()
        compose.awaitFocusOn(hasTestTag("$TAG_COLLECTION/play"), 10_000)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(com.prism.music.ui.tv.TAG_PLAYING_MARK), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        TvFixture.back()
        compose.awaitFocusOn(hasTestTag("$TAG_LIBRARY/grid/0"), 10_000)
    }
}
