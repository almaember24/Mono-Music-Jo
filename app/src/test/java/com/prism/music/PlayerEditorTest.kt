package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.MenuAction
import com.prism.music.data.prefs.MenuGroup
import com.prism.music.data.prefs.PlayerButton
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.data.prefs.PlayerPart
import com.prism.music.data.prefs.PlayerParts
import com.prism.music.data.prefs.PlayerTimes
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.data.prefs.ViewLook
import com.prism.music.data.prefs.VolumeControl
import com.prism.music.data.prefs.canHide
import com.prism.music.data.prefs.drawsCover
import com.prism.music.data.prefs.episodeMenuFromPlayer
import com.prism.music.data.prefs.menuFromPlayer
import com.prism.music.data.prefs.menuLayout
import com.prism.music.data.prefs.shows
import com.prism.music.data.prefs.showsMenuButton
import com.prism.music.ui.player.MenuEditorContent
import com.prism.music.ui.screens.Group
import com.prism.music.ui.screens.PlayerPartsPreview
import com.prism.music.ui.theme.PrismTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.0 "What's on the player" and the player's ⋯ menu: the defaults are the player and the menu as before, everybody's
 * old switches still read as they were, hiding and showing round-trips through the store, and the menu lays out its
 * tiles, groups and "More" as picked. Renders of the editor (light and dark) go to app/build/design-previews/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h3000dp-xxhdpi", application = android.app.Application::class)
class PlayerEditorTest {
    @get:Rule val compose = createComposeRule()

    private val app = org.robolectric.RuntimeEnvironment.getApplication()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val store by lazy { SettingsRepository(app, scope) }

    // The store is one per process: what a test writes is cleared after it (see BackupPartsTest).
    @After fun clear() = runBlocking { store.editNow { it.clear() } }

    /** The store's settings once [check] holds (its writes land a moment later). */
    private fun settled(check: (AppSettings) -> Boolean): AppSettings = runBlocking {
        kotlinx.coroutines.withTimeout(5_000) { store.flow.first { check(it) } }
    }

    @Test fun theDefaultsAreTheOldPlayerAndMenu() {
        val s = AppSettings()
        // Every part shows but the crossfade's mark and blending covers, shuffle & repeat and the volume bar, which were
        // off by default before too.
        assertEquals(
            listOf(PlayerPart.CROSSFADE_MARK, PlayerPart.BLEND_COVERS, PlayerPart.SHUFFLE_REPEAT, PlayerPart.VOLUME),
            PlayerPart.entries.filter { !s.shows(it) },
        )
        assertTrue(s.showsMenuButton())
        assertTrue("nothing from the player in the menu", s.menuFromPlayer().isEmpty())
        assertEquals(listOf(MenuAction.RADIO, MenuAction.DOWNLOAD, MenuAction.ADD_TO_PLAYLIST, MenuAction.SLEEP), s.view.parts.menuTiles)
        assertTrue(s.view.parts.menuHidden.isEmpty())
    }

    /** Nobody's choices change: each part with a switch of its own before 1.8.0 still reads it. */
    @Test fun oldSwitchesStillReadAsTheyWere() {
        runBlocking {
            store.editNow {
                it[booleanPreferencesKey("player_handle")] = false
                it[booleanPreferencesKey("show_playing_from")] = false
                it[booleanPreferencesKey("lyrics_on_player")] = false
                it[booleanPreferencesKey("show_quality")] = false
                it[booleanPreferencesKey("best_part_button")] = false
                it[booleanPreferencesKey("player_shuffle_repeat")] = true
                it[booleanPreferencesKey("hide_volume_bar")] = false
                it[booleanPreferencesKey("show_output_device")] = false
                it[booleanPreferencesKey("player_nav_band")] = false
                it[stringPreferencesKey("player_times")] = "NONE"
                it[stringPreferencesKey("player_buttons")] = "QUEUE,SLEEP,DJ"
            }
        }
        val s = settled { !it.playerHandle }
        val hidden = PlayerPart.entries.filter { !s.shows(it) }.toSet()
        assertEquals(
            setOf(
                PlayerPart.HANDLE, PlayerPart.FROM_ALBUM, PlayerPart.LYRIC_LINE, PlayerPart.QUALITY, PlayerPart.BEST_PART, PlayerPart.OUTPUT, PlayerPart.NAV_BAND, PlayerPart.TIMES,
                // Off by default, as before (shown only while Crossfade is on).
                PlayerPart.CROSSFADE_MARK, PlayerPart.BLEND_COVERS,
            ),
            hidden,
        )
        assertTrue(s.shows(PlayerPart.SHUFFLE_REPEAT))
        assertTrue(s.shows(PlayerPart.VOLUME))
        assertTrue("no new state for anyone yet", s.view.parts == PlayerParts())
        assertEquals(listOf(PlayerButton.QUEUE, PlayerButton.SLEEP, PlayerButton.DJ), s.playerButtons)
        // Their row had no lyrics button: the menu offers the lyrics (the video button wasn't on it either).
        assertEquals(listOf(MenuAction.OPEN_LYRICS, MenuAction.VIDEO_SWITCH), s.menuFromPlayer())
    }

    @Test fun everyPartHidesAndShowsAgain() {
        for (part in PlayerPart.entries) {
            val before = settled { true }.shows(part)
            store.setPartShown(part, !before)
            assertEquals(part.name, !before, settled { it.shows(part) == !before }.shows(part))
            store.setPartShown(part, before)
            assertEquals(part.name, before, settled { it.shows(part) == before }.shows(part))
        }
        // Back where it started: the new parts' key is gone again.
        assertEquals(PlayerParts(), settled { true }.view.parts)
    }

    @Test fun hidingWritesTheOldSwitchesAndTheNewSet() {
        store.setPartShown(PlayerPart.HANDLE, false)
        store.setPartShown(PlayerPart.TIMES, false)
        store.setPartShown(PlayerPart.COVER, false)
        store.setPartShown(PlayerPart.PROGRESS, false)
        val s = settled { !it.playerHandle && it.playerTimes == PlayerTimes.NONE && it.view.parts.hidden.size == 2 }
        assertEquals(setOf(PlayerPart.COVER, PlayerPart.PROGRESS), s.view.parts.hidden)
        store.setPartShown(PlayerPart.TIMES, true)
        assertEquals(PlayerTimes.BOTH, settled { it.playerTimes != PlayerTimes.NONE }.playerTimes)
    }

    /** A Look or backup from another Prism: unknown names are skipped, and parts with their own switch never come from the set. */
    @Test fun unknownOrOldNamesAreSkipped() {
        runBlocking {
            store.editNow {
                it[stringPreferencesKey("player_hidden_parts")] = "COVER,SPARKLES,HANDLE,,MENU"
                it[stringPreferencesKey("player_menu_hidden")] = "STATS,OPEN_LYRICS,OPEN_QUEUE,NOPE"
                it[stringPreferencesKey("player_menu_tiles")] = "SING,NEXT,STATS,SING,RADIO,OUTPUT,SLEEP"
            }
        }
        val s = settled { it.view.parts.hidden.isNotEmpty() }
        assertEquals(setOf(PlayerPart.COVER, PlayerPart.MENU), s.view.parts.hidden)
        assertTrue("the handle keeps its own switch", s.shows(PlayerPart.HANDLE))
        // "Show lyrics" may be folded away too (#345, while the player opens the lyrics another way: menuFolds).
        assertEquals(setOf(MenuAction.STATS, MenuAction.OPEN_LYRICS), s.view.parts.menuHidden)
        assertEquals(listOf(MenuAction.SING, MenuAction.STATS, MenuAction.RADIO, MenuAction.OUTPUT), s.view.parts.menuTiles)
    }

    @Test fun editThisMenuRoundTrips() {
        store.setMenuShown(MenuAction.STATS, false)
        store.setMenuShown(MenuAction.NOT_FOR_ME, false)
        store.setMenuTiles(listOf(MenuAction.SING, MenuAction.OUTPUT))
        var s = settled { it.view.parts.menuHidden.size == 2 && it.view.parts.menuTiles.size == 2 }
        assertEquals(setOf(MenuAction.STATS, MenuAction.NOT_FOR_ME), s.view.parts.menuHidden)
        assertEquals(listOf(MenuAction.SING, MenuAction.OUTPUT), s.view.parts.menuTiles)
        // No tiles at all is a choice too (not the default four back).
        store.setMenuTiles(emptyList())
        s = settled { it.view.parts.menuTiles.isEmpty() }
        assertTrue(s.view.parts.menuTiles.isEmpty())
        store.setMenuShown(MenuAction.STATS, true)
        assertEquals(setOf(MenuAction.NOT_FOR_ME), settled { it.view.parts.menuHidden.size == 1 }.view.parts.menuHidden)
        store.resetMenu()
        assertEquals(PlayerParts(), settled { it.view.parts == PlayerParts() }.view.parts)
    }

    @Test fun theMenuLaysOutAsPicked() {
        val all = MenuAction.entries.filter { !it.fromPlayer }
        // As it was: the four tiles, and every other action in its group (the tiles not again in a group).
        val today = PlayerParts().menuLayout(all)
        assertEquals(MenuAction.DEFAULT_TILES, today.tiles)
        assertEquals(listOf(MenuGroup.SONG, MenuGroup.LYRICS, MenuGroup.PLAYING, MenuGroup.HELP), today.groups.map { it.first })
        assertEquals(
            // 1.8.3: Add a tag, first after the tiles.
            listOf(MenuAction.TAGS, MenuAction.ALBUM, MenuAction.ARTIST, MenuAction.CREDITS, MenuAction.DETAILS, MenuAction.VERSION, MenuAction.COVER, MenuAction.NOT_FOR_ME),
            today.groups.first { it.first == MenuGroup.SONG }.second,
        )
        assertTrue(today.more.isEmpty())
        val inGroups = today.groups.flatMap { it.second }
        assertTrue(MenuAction.DEFAULT_TILES.none { it in inGroups })
        assertEquals("every action once", all.toSet(), (today.tiles + inGroups).toSet())
        assertEquals(all.size, today.tiles.size + inGroups.size)

        // Hidden ones fold under More, a tile is never folded, the player's own are never folded, missing ones vanish.
        val mine = PlayerParts(menuHidden = setOf(MenuAction.STATS, MenuAction.MARK_STUTTER, MenuAction.SING), menuTiles = listOf(MenuAction.SING, MenuAction.RADIO))
        val local = all - MenuAction.RADIO + MenuAction.OPEN_LYRICS
        val l = mine.menuLayout(local)
        assertEquals(listOf(MenuAction.SING), l.tiles)
        assertEquals(listOf(MenuAction.STATS, MenuAction.MARK_STUTTER), l.more)
        assertFalse("Help is empty, so it's gone", l.groups.any { it.first == MenuGroup.HELP })
        assertEquals(MenuAction.OPEN_LYRICS, l.groups.first { it.first == MenuGroup.PLAYING }.second.first())
        assertEquals(MenuAction.OPEN_LYRICS, PlayerParts(menuHidden = setOf(MenuAction.OPEN_LYRICS)).menuLayout(listOf(MenuAction.OPEN_LYRICS)).groups.single().second.single())
    }

    /** Hiding a part never takes a feature away: what it did shows in the menu. */
    @Test fun hiddenPartsBringTheirActionsToTheMenu() {
        val d = AppSettings()
        val noRow = d.copy(view = ViewLook(parts = PlayerParts(hidden = setOf(PlayerPart.BUTTONS, PlayerPart.PREV_NEXT))), playerButtons = listOf(PlayerButton.LYRICS, PlayerButton.DJ, PlayerButton.EQUALIZER))
        assertEquals(
            listOf(MenuAction.OPEN_LYRICS, MenuAction.OPEN_QUEUE, MenuAction.VIDEO_SWITCH, MenuAction.PREVIOUS, MenuAction.NEXT, MenuAction.DJ, MenuAction.EQUALIZER),
            noRow.menuFromPlayer(),
        )
        // Audio only (the old Song / Video switch off): no music video anywhere.
        assertFalse(MenuAction.VIDEO_SWITCH in noRow.copy(showVideoSwitch = false).menuFromPlayer())
        // The ⋯ button: hidden, the title or artist opens the menu; with both gone too it stays, unless a gesture opens it.
        val noMenu = d.copy(view = ViewLook(parts = PlayerParts(hidden = setOf(PlayerPart.MENU))))
        assertFalse(noMenu.showsMenuButton())
        val bare = d.copy(view = ViewLook(parts = PlayerParts(hidden = setOf(PlayerPart.MENU, PlayerPart.TITLE, PlayerPart.ARTIST))))
        assertTrue(bare.showsMenuButton())
        assertFalse(bare.copy(coverLongPress = PlayerGesture.MENU).showsMenuButton())
        // The wide player's cover has no gestures but seek and skip: there ⋯ stays even with a gesture set to the menu.
        assertTrue(bare.copy(coverLongPress = PlayerGesture.MENU).showsMenuButton(wide = true))
        assertFalse("hidden on its own, the title still opens it", noMenu.showsMenuButton(wide = true))
        // A hidden cover isn't drawn (no animated cover, no turning record) until it shrinks into the header.
        val noCover = d.copy(view = ViewLook(parts = PlayerParts(hidden = setOf(PlayerPart.COVER))))
        assertFalse(noCover.drawsCover(collapsedPastHalf = false))
        assertTrue(noCover.drawsCover(collapsedPastHalf = true))
        assertTrue(d.drawsCover(collapsedPastHalf = false))
        // An episode: with the row hidden, its queue is in the menu (its skips stay on the player).
        assertTrue(d.episodeMenuFromPlayer().isEmpty())
        assertEquals(listOf(MenuAction.OPEN_QUEUE), noRow.episodeMenuFromPlayer())
        // Prism's own volume has no buttons: its slider can't be hidden.
        assertFalse(d.copy(volumeControl = VolumeControl.PRISM).canHide(PlayerPart.VOLUME))
        assertTrue(d.canHide(PlayerPart.VOLUME))
        // …and stays on the player even when a Look applied afterwards (Holiday Mode, a schedule) says hidden: with it
        // gone, Prism's own volume couldn't be changed or turned back to the phone's.
        assertTrue(d.copy(volumeControl = VolumeControl.PRISM, hideVolumeBar = true).shows(PlayerPart.VOLUME))
        assertFalse(d.copy(hideVolumeBar = true).shows(PlayerPart.VOLUME))
    }

    /**
     * Edge-to-edge cover and edge-to-edge video are two switches again (1.8.1, owner): in 1.8.0 they were one, so the
     * button on a playing video turned the cover's edge-to-edge look on and off too.
     */
    @Test fun coverAndVideoEdgeToEdgeAreSeparate() {
        runBlocking { store.editNow { it[booleanPreferencesKey("video_full_bleed")] = true; it[booleanPreferencesKey("full_bleed_artwork")] = false } }
        var s = settled { it.videoFullBleed }
        assertFalse("the video's switch leaves the cover alone", s.fullBleedArtwork)
        // The button on a playing video changes only the video.
        store.setVideoFullBleed(false)
        s = settled { !it.videoFullBleed }
        assertFalse(s.fullBleedArtwork)
        store.setFullBleedArtwork(true)
        s = settled { it.fullBleedArtwork }
        assertFalse("the cover's switch leaves the video alone", s.videoFullBleed)
        assertFalse("off by default", AppSettings().fullBleedArtwork || AppSettings().videoFullBleed)
    }

    /** Background movement: still keeps the mesh as it shipped; lively drifts and grows richer together. */
    @Test fun backgroundMovementIsMotionAndColorsTogether() {
        val d = AppSettings()
        assertEquals(d.meshSpeed to d.meshIntensity, com.prism.music.ui.screens.meshMovement(0f))
        assertEquals(0f to 1f, com.prism.music.ui.screens.meshMovement(0.03f))
        assertEquals(1f to 1.5f, com.prism.music.ui.screens.meshMovement(1f))
        val (speed, colors) = com.prism.music.ui.screens.meshMovement(0.5f)
        assertEquals(0.5f, speed); assertEquals(1.25f, colors)
    }

    @Test fun keysAreLookOrNot() {
        assertEquals('s', store.lookKinds["player_hidden_parts"])
        assertTrue("player_menu_hidden" in store.notLookNames)
        assertTrue("player_menu_tiles" in store.notLookNames)
        assertTrue("Reset look brings the parts back", "player_hidden_parts" in store.resetNames)
    }

    @Test fun everyMenuActionHasANameAndIcon() {
        MenuAction.entries.forEach { a ->
            assertTrue(a.name, com.prism.music.ui.player.menuLabel(a).isNotBlank())
            com.prism.music.ui.player.menuIcon(a)
        }
    }

    // ------------------------------------------------------------------ Renders for the owner

    private fun shoot(name: String, dark: Boolean) {
        val plain = AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        // The second one with Crossfade on, so its mark and blending covers are there to pick.
        val someHidden = plain.copy(
            playerHandle = false, showQuality = false, playerShuffleRepeat = true, mix = true,
            view = plain.view.copy(parts = PlayerParts(hidden = setOf(PlayerPart.LIKE, PlayerPart.BUTTONS))),
        )
        val menu = PlayerParts(menuHidden = setOf(MenuAction.STATS, MenuAction.NOT_FOR_ME), menuTiles = listOf(MenuAction.RADIO, MenuAction.SING, MenuAction.OUTPUT))
        compose.setContent {
            PrismTheme(plain, artworkColor = null) {
                Column(
                    Modifier.testTag("shot").width(412.dp).background(MaterialTheme.colorScheme.background).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Group("What's on the player (as it ships)") { PlayerPartsPreview(plain, Modifier.padding(16.dp)) {} }
                    Group("Handle, quality, like, row hidden; crossfade on") { PlayerPartsPreview(someHidden, Modifier.padding(16.dp)) {} }
                    Group("Edit this menu") { MenuEditorContent(menu, {}, { _, _ -> }, {}, {}) }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        val dir = File("build/design-previews").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun renderLight() = shoot("player-editor-light", dark = false)
    @Test fun renderDark() = shoot("player-editor-dark", dark = true)
}
