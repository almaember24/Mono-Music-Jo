package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.prefs.MenuAction
import com.prism.music.data.prefs.PlayerPart
import com.prism.music.data.prefs.shows
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.0 "What's on the player" and the ⋯ menu, in the app: a hidden part leaves the player and what it did is in the
 * menu; the editor's player preview hides a part with a tap; Edit this menu hides an action (folded under More) and
 * pins a tile, and both show in the menu.
 */
@RunWith(AndroidJUnit4::class)
class PlayerEditorAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private var tone: android.net.Uri? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val settings get() = Fixture.container.settings

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { Fixture.container.player.stop() } }
        tone?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        // Upright again, and the phone's own rotation back (wideMenuButtonStaysWithoutTitleAndArtist turns it).
        runCatching {
            val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            device.setOrientationNatural(); device.unfreezeRotation()
        }
    }

    /** A test tone playing ([episode]: as a podcast episode), the full player open (the widget's link), paused so Compose goes idle. */
    private fun openPlayer(episode: Boolean = false) {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone("Prism editor tone", 120)
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val song = local.tracks.value.first { it.song.title == "Prism editor tone" }.song.let { if (episode) it.copy(isEpisode = true) else it }
        instrumentation.runOnMainSync { Fixture.container.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Editor test")) }
        compose.waitForText("Prism editor tone", 20_000)
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        Thread.sleep(2_000)
        instrumentation.runOnMainSync { Fixture.container.player.setPlaying(false) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("playerDeck")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun present(m: androidx.compose.ui.test.SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun menuButton() = hasContentDescription(tr(R.string.song_options))

    @Test fun hiddenPartsLeaveThePlayerAndTheirActionsStayInTheMenu() {
        openPlayer()
        assertTrue("the progress bar shows", present(hasTestTag("progressBar")))
        assertTrue("previous shows", present(hasContentDescription(tr(R.string.widget_previous))))
        assertTrue("the queue's button shows", present(hasContentDescription(tr(R.string.up_next))))

        settings.setPartShown(PlayerPart.PROGRESS, false)
        settings.setPartShown(PlayerPart.PREV_NEXT, false)
        settings.setPartShown(PlayerPart.BUTTONS, false)
        compose.waitUntil(10_000) { !present(hasTestTag("progressBar")) }
        compose.waitUntil(5_000) { !present(hasContentDescription(tr(R.string.widget_previous))) && !present(hasContentDescription(tr(R.string.up_next))) }
        assertTrue("play stays", present(hasTestTag("playDisc")))
        Fixture.screenshot("player-editor-hidden-parts")

        // The menu has what they did: the lyrics, the queue, previous.
        compose.onAllNodes(menuButton()).onFirst().performSemanticsClick()
        compose.waitForText(tr(R.string.edit_this_menu), 10_000)
        assertTrue(present(hasText(tr(R.string.show_lyrics))))
        assertTrue(present(hasText(tr(R.string.up_next))))
        assertTrue(present(hasText(tr(R.string.previous_song))))
        Fixture.screenshot("player-editor-menu-from-player")
        compose.clickWhenFound(hasText(tr(R.string.up_next)) and hasClickAction())
        compose.waitUntil(10_000) { !present(hasText(tr(R.string.edit_this_menu))) }
        assertTrue("Up next opened the queue", present(hasTestTag("playerDeck")))

        // Shown again: back on the player.
        settings.setPartShown(PlayerPart.PROGRESS, true)
        settings.setPartShown(PlayerPart.PREV_NEXT, true)
        settings.setPartShown(PlayerPart.BUTTONS, true)
        compose.waitUntil(10_000) { present(hasTestTag("progressBar")) }
    }

    /**
     * A podcast episode with previous & next and the row hidden: it keeps its back 15 s / ahead 30 s, and its menu has
     * the queue the hidden row held.
     */
    @Test fun anEpisodeKeepsItsSkipsAndItsQueue() {
        settings.setPartShown(PlayerPart.PREV_NEXT, false)
        settings.setPartShown(PlayerPart.BUTTONS, false)
        openPlayer(episode = true)
        compose.waitUntil(10_000) { present(hasContentDescription(trp(R.plurals.back_n_seconds, 15))) }
        assertTrue("ahead 30 s stays", present(hasContentDescription(trp(R.plurals.ahead_n_seconds, 30))))
        compose.onAllNodes(menuButton()).onFirst().performSemanticsClick()
        // The episode's menu (its own group, with Stats for nerds), and the queue in it.
        compose.waitForText(tr(R.string.stats_for_nerds), 10_000)
        Fixture.screenshot("player-editor-episode-menu")
        compose.clickWhenFound(hasText(tr(R.string.up_next)) and hasClickAction())
        compose.waitUntil(10_000) { !present(hasText(tr(R.string.stats_for_nerds))) }
    }

    /**
     * The wide player (a phone on its side): with the ⋯ button, the title and the artist hidden and the cover's long press
     * set to the menu, ⋯ still shows there (its cover has no long press), and opens the menu.
     */
    @Test fun wideMenuButtonStaysWithoutTitleAndArtist() {
        settings.setPartShown(PlayerPart.MENU, false)
        settings.setPartShown(PlayerPart.TITLE, false)
        settings.setPartShown(PlayerPart.ARTIST, false)
        settings.setGesture("cover_long_press", com.prism.music.data.prefs.PlayerGesture.MENU)
        openPlayer()
        assertFalse("upright: the long press opens it, so ⋯ goes", present(menuButton()))
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).setOrientationLeft()
        compose.waitUntil(15_000) { present(menuButton()) }
        compose.onAllNodes(menuButton()).onFirst().performSemanticsClick()
        compose.waitForText(tr(R.string.edit_this_menu), 10_000)
        Fixture.screenshot("player-editor-wide-menu")
    }

    /** With the ⋯ button hidden, a long press on the title opens the menu. */
    @Test fun withoutTheMenuButtonTheTitleOpensTheMenu() {
        openPlayer()
        settings.setPartShown(PlayerPart.MENU, false)
        compose.waitUntil(10_000) { !present(menuButton()) }
        compose.onAllNodes(hasTestTag("playerTitle")).onFirst().performTouchInput { longClick() }
        compose.waitForText(tr(R.string.edit_this_menu), 10_000)
    }

    /** Settings → Player → What's on the player: a tap on the preview's progress bar hides it, another brings it back. */
    @Test fun theEditorsPreviewHidesAPart() {
        Fixture.ready()
        Fixture.launch()
        compose.openSettings()
        compose.clickWhenFound(hasText(tr(R.string.customize_prism)) and hasClickAction(), 15_000)
        compose.clickWhenFound(hasText(tr(R.string.player)) and hasClickAction(), 15_000)
        val bar = hasContentDescription(tr(R.string.progress_bar)) and hasClickAction()
        compose.clickWhenFound(bar, 15_000)
        compose.waitUntil(5_000) { !settings.current.shows(PlayerPart.PROGRESS) }
        Fixture.screenshot("player-editor-settings")
        compose.clickWhenFound(bar)
        compose.waitUntil(5_000) { settings.current.shows(PlayerPart.PROGRESS) }
    }

    /** Edit this menu: an action hidden folds under More, a pinned one becomes a tile; Back to Prism's menu undoes both. */
    @Test fun editThisMenuRoundTrips() {
        openPlayer()
        compose.onAllNodes(menuButton()).onFirst().performSemanticsClick()
        compose.clickWhenFound(hasText(tr(R.string.edit_this_menu)) and hasClickAction(), 10_000)
        compose.waitUntil(10_000) { present(hasTestTag("menuEditor")) }
        // Stats off; Playlist's tile off and Sing's on.
        compose.clickWhenFound(hasText(tr(R.string.stats_for_nerds)) and hasClickAction())
        compose.clickWhenFound(hasContentDescription(tr(R.string.quick_tile_s, tr(R.string.add_to_playlist))))
        compose.clickWhenFound(hasContentDescription(tr(R.string.quick_tile_s, tr(R.string.sing))))
        compose.waitUntil(5_000) {
            val p = settings.current.view.parts
            MenuAction.STATS in p.menuHidden && MenuAction.SING in p.menuTiles && MenuAction.ADD_TO_PLAYLIST !in p.menuTiles
        }
        Fixture.screenshot("player-editor-edit-menu")
        compose.clickWhenFound(hasText(tr(R.string.done)) and hasClickAction())

        // The menu again: Stats only under More, Sing a tile (its row gone from Lyrics).
        compose.waitUntil(10_000) { present(hasText(tr(R.string.edit_this_menu))) && !present(hasTestTag("menuEditor")) }
        assertFalse("Stats folded away", present(hasText(tr(R.string.stats_for_nerds))))
        assertEquals("Sing once, as a tile", 1, compose.onAllNodes(hasText(tr(R.string.sing))).fetchSemanticsNodes().size)
        compose.clickWhenFound(hasText(tr(R.string.more)) and hasClickAction())
        compose.waitForText(tr(R.string.stats_for_nerds), 5_000)
        Fixture.screenshot("player-editor-menu-more")

        settings.resetMenu()
        compose.waitUntil(5_000) { settings.current.view.parts.menuHidden.isEmpty() }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performSemanticsClick() =
        performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
}
