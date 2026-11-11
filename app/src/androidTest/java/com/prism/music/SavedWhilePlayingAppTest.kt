package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.LibraryTile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Saved while playing (#159): the songs whole in the song cache, listed, and really playing with the phone in airplane
 * mode. The song bytes are a silent WAV written through a CacheWriter as a played-through song's are.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedWhilePlayingAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val tile = "Saved while playing"

    private fun shell(command: String) =
        instrumentation.uiAutomation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).readBytes().decodeToString() }

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
        shell("cmd media_session volume --stream 3 --set 0")
        shell("cmd connectivity airplane-mode disable")
        Fixture.ready()
    }

    @After fun end() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        airplane(false)
    }

    private fun wav(seconds: Int): ByteArray {
        val rate = 8_000
        val samples = rate * seconds
        val data = java.io.ByteArrayOutputStream()
        fun le32(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        data.write("RIFF".toByteArray()); le32(36 + samples * 2); data.write("WAVEfmt ".toByteArray())
        le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        data.write("data".toByteArray()); le32(samples * 2); data.write(ByteArray(samples * 2))
        return data.toByteArray()
    }

    /** [part]: only the song's first bytes, as a song that was skipped leaves. With none, it ran to its end. */
    private fun cached(song: Song, part: Long? = null) {
        val spec = DataSpec.Builder().setUri("prism://audio/${song.id}").setKey(song.id).apply { if (part != null) setLength(part) }.build()
        CacheWriter(CacheDataSource(c.playerCache, ByteArrayDataSource(wav(60))), spec, null, null).cache()
    }

    private fun known(vararg songs: Song) = runBlocking { songs.forEach { c.db.songs().upsert(it.toEntity()) } }

    private fun waitForApp() = compose.waitUntil(20_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }

    private fun openLibrary() {
        waitForApp()
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun addTile() {
        val before = c.settings.current.libraryTiles
        assertFalse("nobody's Library changes by itself", LibraryTile.SAVED in before)
        c.settings.setLibraryTiles(before + LibraryTile.SAVED)
        compose.waitUntil(5_000) { LibraryTile.SAVED in c.settings.current.libraryTiles }
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun whatsWholeIsListedAndPlaysWithNoConnection() {
        val (whole, part, downloaded, nameless) = Fixture.songs(4)
        known(whole, part, downloaded)
        cached(whole)
        cached(part, 64_000)
        cached(downloaded)
        cached(nameless)
        Fixture.downloaded(listOf(downloaded))
        Fixture.launch()
        waitForApp()
        compose.waitUntil(20_000) { c.downloads.isDownloaded(downloaded.id) }
        addTile()
        openLibrary()
        compose.clickWhenFound(hasText(tile) and hasClickAction(), 15_000)

        compose.waitForText(whole.title, 15_000)
        assertFalse("only its start is here", shows(part.title))
        assertFalse("Downloads lists it already", shows(downloaded.title))
        assertFalse("Prism has no name for it", shows(nameless.title))
        Fixture.screenshot("saved-while-playing")

        airplane(true)
        compose.clickWhenFound(hasText(whole.title) and hasClickAction())
        fun position(): Long { var p = 0L; instrumentation.runOnMainSync { p = c.player.position }; return p }
        val end = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < end && position() < 3_000) Thread.sleep(200)
        assertEquals(whole.id, c.player.currentSong.value?.id)
        assertTrue("plays from the phone (at ${position()} ms)", position() >= 3_000)
    }

    @Test fun nothingSavedSaysSoAndDownloadsMenuLeadsThere() {
        Fixture.launch()
        openLibrary()
        compose.clickWhenFound(hasText("Downloads") and hasClickAction(), 15_000)
        compose.clickWhenFound(hasContentDescription("More") and hasClickAction(), 15_000)
        compose.clickWhenFound(hasText(tile) and hasClickAction())
        compose.waitForText("Nothing saved yet", 15_000)
    }
}
