package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.screens.OwnSections
import com.prism.music.ui.screens.PinnedTile
import com.prism.music.ui.theme.PrismTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A pinned album or playlist as one tile on Home (1.8.3, D48, Reddit: "when I pin an album to Home it puts all the
 * songs on"): the song list stays the default; the tile shows the cover, name and kind, opens the page and plays.
 * Pictures go to app/build/design-previews/pinned-tile-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h800dp-xxhdpi")
class PinnedTileTest {
    @get:Rule val compose = createComposeRule()

    private val album = HomeSections.pinKey("album", "MPREb_test")
    private val playlist = HomeSections.pinKey("playlist", "PLtest")
    private val mix = HomeSections.pinKey("mix", "RDtest")

    @Test fun theSongsStayTheDefaultAndOldLayoutsRead() {
        assertFalse(HomeSectionConfig(album, "An Album").tile)
        // A layout stored before 1.8.3 has no "tile": it reads as songs.
        val old = """[{"key":"$album","title":"An Album","style":"LIST"}]"""
        val read = Json { ignoreUnknownKeys = true }.decodeFromString<List<HomeSectionConfig>>(old)
        assertFalse(read.single().tile)
        val kept = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HomeSectionConfig.serializer()), listOf(read.single().copy(tile = true)))
        assertTrue("kept", Json.decodeFromString<List<HomeSectionConfig>>(kept).single().tile)
    }

    @Test fun onlyPinsWithAPageCanBeATile() {
        assertTrue(OwnSections.canTile(album))
        assertTrue(OwnSections.canTile(playlist))
        assertFalse("a radio mix has no page", OwnSections.canTile(mix))
        assertFalse("a rule section isn't a pin", OwnSections.canTile(HomeSections.LIKED_MONTH))
        assertEquals("Album", OwnSections.kindOf(album))
        assertEquals("Playlist", OwnSections.kindOf(playlist))
        assertNull(OwnSections.kindOf(mix))
    }

    @Test fun theTileOpensAndPlays() {
        var opened = 0
        var played = 0
        compose.setContent {
            PrismTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                Column(Modifier.width(400.dp)) { PinnedTile("An Album", "Album", null, onOpen = { opened++ }, onPlay = { played++ }) }
            }
        }
        compose.onNodeWithTag("pinTilePlay").performClick()
        assertEquals(1, played)
        assertEquals("Play doesn't open the page", 0, opened)
        compose.onNodeWithText("An Album").performClick()
        assertEquals(1, opened)
    }

    private fun shoot(name: String, settings: AppSettings) {
        compose.setContent {
            PrismTheme(settings, artworkColor = null) {
                Column(Modifier.width(400.dp).background(MaterialTheme.colorScheme.background)) {
                    PinnedTile("Random Access Memories", "Album", null, onOpen = {}, onPlay = {})
                    PinnedTile("A very long playlist name that has to wrap onto a second line somewhere", "Playlist", null, onOpen = {}, onPlay = {})
                }
            }
        }
        compose.waitForIdle()
        val dir = File("build/design-previews").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun dark() = shoot("pinned-tile-dark", AppSettings(themeMode = ThemeMode.DARK))
    @Test fun light() = shoot("pinned-tile-light", AppSettings(themeMode = ThemeMode.LIGHT))
    @Test fun performanceMode() = shoot("pinned-tile-performance", AppSettings(themeMode = ThemeMode.LIGHT, performanceMode = true).effective())
}
