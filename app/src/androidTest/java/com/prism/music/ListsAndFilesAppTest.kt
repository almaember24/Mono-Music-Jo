package com.prism.music

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.media3.exoplayer.offline.Download
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.prefs.LyricsSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.2, on a device: lyrics read out of a music file (#344), folders left out of Local files with the files left alone
 * (#207, #249), skipping songs in a list's download (#221) and keeping a playlist downloaded (#298).
 */
@RunWith(AndroidJUnit4::class)
class ListsAndFilesAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = Fixture.context
    private val c get() = Fixture.container
    private val added = ArrayList<Uri>()

    @After fun cleanUp() {
        added.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
    }

    private fun mayReadMusic() {
        val p = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, p)
    }

    private fun addFile(name: String, folder: String, bytes: ByteArray): Uri {
        org.junit.Assume.assumeTrue("the media store takes files from an app from Android 10", android.os.Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name-${System.currentTimeMillis()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$folder/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        added += uri
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun asset(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }

    private fun openSetting(title: String, query: String) {
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.clickWhenFound(hasText(title) and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
    }

    // ------------------------------------------------------------ #207, #249

    private fun onPhone() = c.localMusic.tracks.value.filter { it.song.title.startsWith("Prism cover") }

    @Test fun aFolderLeftOutLeavesTheListAndTheFileStays() {
        Fixture.ready()
        mayReadMusic()
        addFile("kept", "PrismKept", asset("cover-tone-png.mp3"))
        val leftOut = addFile("out", "PrismLeftOut", asset("cover-tone-jpg.mp3"))
        val size = asset("cover-tone-jpg.mp3").size
        Fixture.launch()
        runBlocking { c.localMusic.scan() }
        compose.waitUntil(30_000) { onPhone().size == 2 }

        openSetting("Folders in Local files", "folders in local")
        compose.clickWhenFound(hasText("Folders in Local files") and hasClickAction())
        compose.waitForText("PrismLeftOut")
        val tick = isToggleable() and hasContentDescription("PrismLeftOut")
        compose.onNode(tick and isOn()).performClick()
        compose.waitUntil(15_000) { onPhone().size == 1 }

        // Only the list changed: the file's there, whole, and still in the phone's media store.
        val stillThere = context.contentResolver.openInputStream(leftOut)!!.use { it.readBytes().size }
        assertEquals(size, stillThere)
        context.contentResolver.query(leftOut, arrayOf(MediaStore.Audio.Media._ID), null, null, null)!!.use { assertEquals(1, it.count) }

        compose.onNode(tick and isOff()).performClick()
        compose.waitUntil(15_000) { onPhone().size == 2 }
    }

    // ------------------------------------------------------------ #344

    /** An ID3v2.4 tag with a title and synced lyrics, in front of the sound of a test file (its own tag taken off). */
    private fun withLyrics(sound: ByteArray, title: String, lrc: String): ByteArray {
        fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte())
        fun frame(id: String, data: ByteArray) = id.toByteArray() + syncsafe(data.size) + byteArrayOf(0, 0) + data
        val frames = frame("TIT2", byteArrayOf(3) + title.toByteArray()) + frame("TPE1", byteArrayOf(3) + "Prism tests".toByteArray()) +
            frame("USLT", byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + lrc.toByteArray())
        val oldTag = if (String(sound, 0, 3) == "ID3") 10 + ((sound[6].toInt() shl 21) or (sound[7].toInt() shl 14) or (sound[8].toInt() shl 7) or sound[9].toInt()) else 0
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncsafe(frames.size) + frames + sound.copyOfRange(oldTag, sound.size)
    }

    @Test fun lyricsInAFileAreASourceOnceSwitchedOn() {
        Fixture.ready()
        mayReadMusic()
        val lrc = "[00:01.00]A made up first line\n[00:03.50]A made up second line\n"
        addFile("lyrics", "PrismLyrics", withLyrics(asset("cover-tone-png.mp3"), "Prism lyrics file", lrc))
        Fixture.launch()
        runBlocking { c.localMusic.scan() }
        compose.waitUntil(30_000) { c.localMusic.tracks.value.any { it.song.title == "Prism lyrics file" } }
        val song = c.localMusic.tracks.value.first { it.song.title == "Prism lyrics file" }.song

        // As it comes: the file isn't read.
        assertNull(runBlocking { c.lyrics.fetch(song, LyricsSource.EMBEDDED) })

        openSetting("Read lyrics from your music files", "read lyrics from")
        compose.clickWhenFound(hasText("Read lyrics from your music files") and hasClickAction())
        compose.waitUntil(5_000) { c.settings.current.behavior.embeddedLyrics }

        val found = runBlocking { withTimeout(20_000) { c.lyrics.fetch(song, LyricsSource.EMBEDDED) } }
        assertNotNull("read from the file through the phone's media store", found)
        assertTrue(found!!.synced)
        assertEquals(listOf("A made up first line", "A made up second line"), found.lines.filter { !it.isGap }.map { it.text })
        assertEquals(1_000L, found.lines.first { !it.isGap }.timeMs)
    }

    // ------------------------------------------------------------ #221

    private fun openPlaylist(title: String, firstSong: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        compose.clickWhenFound(hasText(title) and hasClickAction(), 15_000)
        compose.waitForText(firstSong)
    }

    @Test fun aSkippedSongLeavesTheListCountingAsDownloaded() {
        Fixture.ready()
        val songs = Fixture.songs(3)
        Fixture.prismPlaylist("Road trip", songs)
        Fixture.downloaded(songs.take(2))
        Fixture.launch()
        openPlaylist("Road trip", "Test song 1")
        compose.waitUntil(10_000) { c.downloadState.value.keys.containsAll(songs.take(2).map { it.id }) }
        val remove = hasClickAction() and hasContentDescription("Remove downloads")
        assertTrue("not all of it's down yet", compose.onAllNodes(remove).fetchSemanticsNodes().isEmpty())

        // The song's menu (a long press picks songs since Select many, 1.8.3).
        compose.clickWhenFound(hasContentDescription("More") and hasClickAction() and androidx.compose.ui.test.hasParent(hasText("Test song 3")))
        compose.clickWhenFound(hasText("Skip in downloads") and hasClickAction())
        compose.waitUntil(5_000) { songs[2].id in c.downloadSkips.ids.value }
        compose.waitForText("Skipped in downloads")

        // Downloaded now, as the list goes: its button removes the two that are here.
        compose.clickWhenFound(remove)
        compose.waitForText("Delete the 2 songs of Road trip")
        compose.onAllNodes(hasText("Cancel") and hasClickAction()).onFirst().performClick()

        // Downloading the whole list leaves it out.
        c.downloads.downloadAll(songs)
        compose.waitForIdle()
        Thread.sleep(1_000)
        assertNull(c.downloads.downloads.value[songs[2].id])
        assertEquals(Download.STATE_COMPLETED, c.downloads.downloads.value[songs[0].id]?.state)
    }

    // ------------------------------------------------------------ #298

    @Test fun aKeptPlaylistFetchesWhatsNewAndLeavesWhatsSkipped() {
        Fixture.ready()
        val songs = Fixture.songs(4)
        val id = Fixture.prismPlaylist("Road trip", songs.take(2))
        Fixture.downloaded(songs.take(2))
        c.settings.setDownloadWifiOnly(false)
        Fixture.launch()
        openPlaylist("Road trip", "Test song 1")
        compose.waitUntil(10_000) { c.downloadState.value.keys.containsAll(songs.take(2).map { it.id }) }

        // The page's ⋯, at the top: each song row has its own further down.
        val more = hasClickAction() and hasContentDescription("More")
        compose.waitUntil(5_000) { compose.onAllNodes(more).fetchSemanticsNodes().isNotEmpty() }
        val nodes = compose.onAllNodes(more).fetchSemanticsNodes()
        compose.onAllNodes(more)[nodes.indices.minBy { nodes[it].boundsInRoot.top }].performClick()
        compose.clickWhenFound(hasText("Keep downloaded") and hasClickAction())
        compose.waitUntil(5_000) { id in c.settings.current.behavior.keptPlaylists }

        c.downloadSkips.set(songs[3].id, true)
        c.prismLibrary.add(id, songs.drop(2))
        // Past the watch's wait from the start, plus its own pause after a change.
        compose.waitUntil(45_000) { c.downloads.downloads.value[songs[2].id] != null }
        assertNull("skipped", c.downloads.downloads.value[songs[3].id])
        // Nothing that was here came down again.
        assertEquals(Download.STATE_COMPLETED, c.downloads.downloads.value[songs[0].id]?.state)

        // With the page closed too: the watch in the background does it.
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
        compose.waitForText("Library")
        val later = Fixture.songs(5).last()
        c.prismLibrary.add(id, listOf(later))
        compose.waitUntil(45_000) { c.downloads.downloads.value[later.id] != null }
    }
}
