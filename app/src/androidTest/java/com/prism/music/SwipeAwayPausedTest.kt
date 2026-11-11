package com.prism.music

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.playback.PlaybackServices
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #274 (Redmi Note 9 Pro, Android 12): swiping Prism away from Recents while paused left its player notification, and
 * the service, behind; sometimes it couldn't be dismissed. Something else still connected to the player (here Prism's
 * own screen, on a phone the system's media controls or a watch) kept it alive. Now it goes, and the queue stays.
 */
@RunWith(AndroidJUnit4::class)
class SwipeAwayPausedTest {
    private val tones = mutableListOf<android.net.Uri>()

    @After fun cleanUp() {
        runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { Fixture.container.player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    @Test fun swipedAwayWhilePausedTheNotificationGoes() {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tones += Fixture.tone("Prism swipe tone", 90)
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val song = local.tracks.value.first { it.song.title == "Prism swipe tone" }.song
        val player = Fixture.container.player

        InstrumentationRegistry.getInstrumentation().runOnMainSync { player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Swipe test")) }
        // Playing for real before the pause: togglePlay on a song still loading starts it instead of pausing it.
        waitFor(20_000) { PlaybackServices.taskRemovedForTest != null && player.currentSong.value?.id == song.id && player.isPlaying.value && mediaNotifications() > 0 }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player.togglePlay() }
        // Paused for real first: a pause fades out, and the player only stops wanting to play once the fade is done
        // (on a busy emulator that took longer than a fixed wait, and the swipe then found it still playing).
        waitFor(10_000) {
            var wants = true
            InstrumentationRegistry.getInstrumentation().runOnMainSync { wants = player.playWhenReady }
            !wants && !player.isPlaying.value
        }
        Thread.sleep(500)
        assertEquals("paused, the notification is still there before the swipe", true, mediaNotifications() > 0)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { PlaybackServices.taskRemovedForTest!!.invoke() }
        waitFor(10_000) { mediaNotifications() == 0 }
        // The queue is still there to play on from.
        assertEquals(song.id, player.currentSong.value?.id)
    }

    /** Prism's notifications drawn as a media player. */
    private fun mediaNotifications(): Int =
        Fixture.context.getSystemService(NotificationManager::class.java).activeNotifications.count {
            it.notification.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true
        }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not ready after $timeoutMs ms")
            Thread.sleep(200)
        }
    }
}
