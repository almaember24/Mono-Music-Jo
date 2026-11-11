package com.prism.music

import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.Song
import com.prism.music.playback.PlaybackServices
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * "Hey Google, play … on Prism" (1.8.1, Reddit: Android Auto's voice button did nothing for Prism). The car reaches
 * Prism as a media browser: these connect the same way (Android's own MediaBrowser, then the session's transport
 * controls' play-from-search and the browser's search, as Android Auto does), and the phone's way, the
 * MEDIA_PLAY_FROM_SEARCH activity. (The search goes through Media3's browser: Android's own has no search; the car's
 * compat browser ends in the same session search.) Offline:the songs are files on the phone in a Prism playlist, so the playlist is
 * found by name without YouTube.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSearchAppTest {
    private val tones = mutableListOf<android.net.Uri>()
    private var browser: MediaBrowser? = null

    @After fun cleanUp() {
        val main = InstrumentationRegistry.getInstrumentation()
        runCatching { main.runOnMainSync { browser?.disconnect(); Fixture.container.player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    @Test fun theCarsVoiceRequestPlaysYourPlaylistByName() {
        val songs = setUp()
        val controller = MediaController(Fixture.context, connect().sessionToken)
        controller.transportControls.playFromSearch(
            "voice test road trip",
            Bundle().apply { putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE) },
        )
        waitFor(20_000) { Fixture.container.player.currentSong.value?.id == songs[0].id }
        assertEquals("the queue isn't the playlist", LIST, Fixture.container.queue.source.value.title)
    }

    @Test fun nothingFoundKeepsWhatWasPlayingAndSaysSo() {
        val songs = setUp()
        val main = InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync { Fixture.container.player.playQueue(songs, 1, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.OTHER, "Before")) }
        waitFor(20_000) { Fixture.container.player.currentSong.value?.id == songs[1].id }
        val controller = MediaController(Fixture.context, connect().sessionToken)
        val errors = CopyOnWriteArrayList<CharSequence>()
        main.runOnMainSync {
            controller.registerCallback(object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { state?.errorMessage?.let { errors += it } }
            })
        }
        // A playlist nobody has: the recordings hold no YouTube search for it, so that answers "offline".
        controller.transportControls.playFromSearch(
            "zzqx no such playlist",
            Bundle().apply { putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE) },
        )
        waitFor(15_000) { errors.isNotEmpty() }
        Thread.sleep(1_000)
        assertEquals("what was playing was replaced", songs[1].id, Fixture.container.player.currentSong.value?.id)
        assertEquals("the queue was replaced", "Before", Fixture.container.queue.source.value.title)
    }

    @Test fun theCarsSearchListsYourPlaylist() {
        setUp()
        val context = Fixture.context
        val main = InstrumentationRegistry.getInstrumentation()
        // Media3's own browser here (Android's has no search); the car's compat browser reaches the same search.
        var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaBrowser>? = null
        main.runOnMainSync {
            future = androidx.media3.session.MediaBrowser.Builder(
                context, androidx.media3.session.SessionToken(context, ComponentName(context, PlaybackServices.target(context))),
            ).buildAsync()
        }
        val b = future!!.get(20, TimeUnit.SECONDS)
        try {
            var results: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>? = null
            main.runOnMainSync { b.search("road trip", null) }
            main.runOnMainSync { results = b.getSearchResult("road trip", 0, 50, null) }
            val items = results!!.get(30, TimeUnit.SECONDS).value.orEmpty()
            val list = items.firstOrNull { it.mediaMetadata.title?.toString() == LIST }
            assertTrue("the playlist isn't in the results: ${items.map { it.mediaMetadata.title }}", list != null && list.mediaMetadata.isBrowsable == true)
        } finally {
            main.runOnMainSync { b.release() }
        }
    }

    @Test fun thePhonesVoiceRequestCarriesWhatWasAskedFor() {
        val songs = setUp()
        // As the Assistant sends it: the words heard, what kind of thing it is, and its name.
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setClassName(Fixture.context, "com.prism.music.playback.VoicePlayActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SearchManager.QUERY, "Voice test road trip on Prism")
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
            .putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, "voice test road trip")
        Fixture.context.startActivity(intent)
        waitFor(20_000) { Fixture.container.player.currentSong.value?.id == songs[0].id }
        assertEquals(LIST, Fixture.container.queue.source.value.title)
    }

    /** Two silent songs on the phone, in a Prism playlist; Prism open. */
    private fun setUp(): List<Song> {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tones += Fixture.tone("Prism voice tone one", 60)
        tones += Fixture.tone("Prism voice tone two", 60)
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val songs = listOf("one", "two").map { w -> local.tracks.value.first { it.song.title == "Prism voice tone $w" }.song }
        Fixture.prismPlaylist(LIST, songs)
        return songs
    }

    /** Connected to Prism's media service as a car is (the platform's MediaBrowser). */
    private fun connect(): MediaBrowser {
        val ready = LinkedBlockingQueue<Boolean>()
        val main = InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync {
            browser = MediaBrowser(
                Fixture.context, ComponentName(Fixture.context, PlaybackServices.target(Fixture.context)),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { ready.put(true) }
                    override fun onConnectionFailed() { ready.put(false) }
                },
                null,
            ).apply { connect() }
        }
        assertTrue("couldn't connect to Prism's media service", ready.poll(20, TimeUnit.SECONDS) == true)
        return browser!!
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not there after $timeoutMs ms")
            Thread.sleep(200)
        }
    }

    private companion object {
        const val LIST = "Voice test road trip"
    }
}
