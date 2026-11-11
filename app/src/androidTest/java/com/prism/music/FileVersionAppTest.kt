package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A file on the phone as a song's version (1.8.3, #243), in the app: a song's "Play a different version" lists the
 * phone's file of the same title as "Your file"; picked, the song plays from the file; "Original" goes back.
 */
@RunWith(AndroidJUnit4::class)
class FileVersionAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val added = ArrayList<android.net.Uri>()
    private var listId: String? = null
    private val song = Song("fileversion-song", "File Anthem", listOf(ArtistRef("Test Artist")), durationSec = 25)

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        runCatching { c.pickVersion(song, null) }
        listId?.let { c.prismLibrary.delete(it) }
        added.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
    }

    private fun openVersions() {
        compose.clickWhenFound(hasContentDescription("More") and hasClickAction() and hasParent(hasText(song.title)), 15_000)
        compose.clickWhenFound(hasText("Play a different version") and hasClickAction())
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("version:own")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aPhoneFileIsPickedPlayedAndUndone() {
        val p = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, p)
        added += Fixture.tone(song.title, 25)
        Fixture.ready()
        listId = Fixture.prismPlaylist("Files test", listOf(song))
        Fixture.launch()
        runBlocking { c.localMusic.scan() }
        compose.waitUntil(30_000) { c.localMusic.tracks.value.any { it.song.title == song.title } }
        val file = c.localMusic.tracks.value.first { it.song.title == song.title }.song

        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        for (i in 0 until 8) {
            if (compose.onAllNodes(hasText("Playlists") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeRight(startX = width * 0.15f, endX = width * 0.95f) }
            compose.waitForIdle()
        }
        compose.onAllNodes(hasText("Playlists") and hasClickAction()).onFirst().performClick()
        for (i in 0 until 6) {
            if (compose.onAllNodes(hasText("Files test") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.35f) }
            compose.waitForIdle()
        }
        compose.clickWhenFound(hasText("Files test") and hasClickAction(), 10_000)
        compose.waitForText(song.title)

        openVersions()
        compose.clickWhenFound(hasTestTag("version:${file.id}"), 10_000)
        compose.waitUntil(5_000) { c.versions.fileOf(song.id) == file.id }
        assertNull("never asked of YouTube", c.versions.streamedOf(song.id))

        // Played: from the file.
        instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.OTHER, "test")) }
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && c.streamDetails[song.id]?.first?.startsWith("Your file") != true) Thread.sleep(200)
        assertTrue("plays from the file: ${c.streamDetails[song.id]?.first}", c.streamDetails[song.id]?.first?.startsWith("Your file on this phone") == true)
        fun position(): Long { var at = 0L; instrumentation.runOnMainSync { at = c.player.position }; return at }
        val moving = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < moving && position() < 2_000) Thread.sleep(200)
        assertTrue("it plays (at ${position()} ms)", position() >= 2_000)
        instrumentation.runOnMainSync { c.player.stop() }

        // Back to the song's own.
        openVersions()
        Fixture.screenshot("file-version")
        compose.clickWhenFound(hasTestTag("version:own"))
        compose.waitUntil(5_000) { c.versions.of(song.id) == null }
        assertEquals(null, c.versions.fileOf(song.id))
    }
}
