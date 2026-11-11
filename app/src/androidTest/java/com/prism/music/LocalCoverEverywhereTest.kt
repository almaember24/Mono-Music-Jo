package com.prism.music

import android.app.NotificationManager
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.ArtworkProvider
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.playback.toMediaItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #350: a local file's cover read from the file showed in the player but not in the notification (nor the car): those
 * go through [ArtworkProvider], which only served Prism's own picked covers. The two files here share an album and
 * differ in artist, so Prism reads each one's own cover (what Android files many a phone's songs as).
 */
@RunWith(AndroidJUnit4::class)
class LocalCoverEverywhereTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = Fixture.context
    private val added = ArrayList<Uri>()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { Fixture.container.player.stop() } }
        added.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
    }

    private fun grant(permission: String) = instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)

    private fun addFile(asset: String) {
        org.junit.Assume.assumeTrue("the media store takes files from an app from Android 10", android.os.Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${asset.removeSuffix(".mp3")}-${System.currentTimeMillis()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/PrismCovers/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        added += uri
        context.contentResolver.openOutputStream(uri)!!.use { out -> instrumentation.context.assets.open(asset).use { it.copyTo(out) } }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
    }

    private fun read(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd -> java.io.FileInputStream(fd.fileDescriptor).readBytes() }
    }.getOrNull()

    private fun ByteArray.isJpeg() = size > 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()

    /** Both files on the phone and scanned, their covers read out of them. */
    private fun scanned() {
        Fixture.ready()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            grant(android.Manifest.permission.READ_MEDIA_AUDIO)
            grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else grant(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        addFile("cover-tone-png.mp3")
        addFile("cover-tone-jpg.mp3")
        val c = Fixture.container
        Fixture.launch()
        runBlocking { c.localMusic.scan() }
        compose.waitUntil(30_000) {
            c.localMusic.tracks.value.filter { it.song.title.startsWith("Prism cover") }.size == 2 &&
                c.localMusic.tracks.value.filter { it.song.title.startsWith("Prism cover") }.all { it.song.thumbnail?.startsWith("file:") == true }
        }
    }

    /** What Android Auto, a watch or Android's own media controls read: the session's picture, as a JPEG. */
    @Test fun aFilesOwnCoverReachesTheSession() {
        scanned()
        val c = Fixture.container
        for (title in listOf("Prism cover one", "Prism cover two")) {
            val song = c.localMusic.tracks.value.first { it.song.title == title }.song
            val uri = song.toMediaItem().mediaMetadata.artworkUri
            assertNotNull("$title has no picture for the session", uri)
            val bytes = read(uri!!)
            assertNotNull("the artwork provider refused $title's cover (${song.thumbnail})", bytes)
            // PNG in the file or not, the car gets a JPEG: it leaves a PNG blank.
            assertTrue("$title's cover isn't a JPEG", bytes!!.isJpeg())
            assertEquals(320, BitmapFactory.decodeByteArray(bytes, 0, bytes.size).width)
        }
    }

    /** The whole way: the file's cover on the playing song's notification. */
    @Test fun aFilesOwnCoverReachesTheNotification() {
        scanned()
        val c = Fixture.container
        val song = c.localMusic.tracks.value.first { it.song.title == "Prism cover one" }.song
        instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Cover test")) }
        val manager = context.getSystemService(NotificationManager::class.java)
        compose.waitUntil(30_000) {
            manager.activeNotifications.any { n -> n.notification.extras.getCharSequence("android.title")?.toString() == "Prism cover one" && n.notification.getLargeIcon() != null }
        }
    }

    /** A file Android keeps album art for: that art, through the provider too, and on the notification. */
    @Test fun androidsAlbumArtReachesTheNotification() {
        Fixture.ready()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            grant(android.Manifest.permission.READ_MEDIA_AUDIO)
            grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else grant(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        addFile("cover-tone-jpg.mp3")
        val c = Fixture.container
        Fixture.launch()
        runBlocking { c.localMusic.scan() }
        compose.waitUntil(30_000) { c.localMusic.tracks.value.any { it.song.title == "Prism cover two" && it.song.thumbnail != null } }
        val song = c.localMusic.tracks.value.first { it.song.title == "Prism cover two" }.song
        val uri = song.toMediaItem().mediaMetadata.artworkUri!!
        assertEquals(song.thumbnail, "${context.packageName}.artwork", uri.authority)
        assertNotNull("no picture for ${song.thumbnail}", read(uri))
        instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Cover test")) }
        val manager = context.getSystemService(NotificationManager::class.java)
        compose.waitUntil(30_000) {
            manager.activeNotifications.any { n -> n.notification.extras.getCharSequence("android.title")?.toString() == "Prism cover two" && n.notification.getLargeIcon() != null }
        }
    }

    /** The cover's own file changed: not the old picture. Files elsewhere in Prism's data stay out of reach. */
    @Test fun theProviderTakesOnlyLocalCovers() {
        Fixture.ready()
        val dir = File(context.filesDir, "artwork/local").apply { mkdirs() }
        val cover = File(dir, "s424242.jpg")
        val small = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        cover.outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val song = Song(Song.LOCAL_PREFIX + "424242", "A file", listOf(ArtistRef("A")), thumbnail = Uri.fromFile(cover).toString())
        val uri = song.toMediaItem().mediaMetadata.artworkUri!!
        assertEquals(48, read(uri)!!.let { BitmapFactory.decodeByteArray(it, 0, it.size) }.width)
        val big = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        cover.outputStream().use { big.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(96, read(uri)!!.let { BitmapFactory.decodeByteArray(it, 0, it.size) }.width)
        // Not out of its folder, by name or by climbing.
        val secret = File(context.filesDir, "secret.jpg").apply { writeBytes(cover.readBytes()) }
        assertNull(read(ArtworkProvider.contentUri(Uri.fromFile(secret).toString())))
        assertNull(read(ArtworkProvider.contentUri("file://${dir.path}/../../secret.jpg")))
        secret.delete()
    }
}
