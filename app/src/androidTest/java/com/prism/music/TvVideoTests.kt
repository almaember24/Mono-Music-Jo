package com.prism.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.playback.videoModeId
import com.prism.music.ui.tv.TAG_HOME
import com.prism.music.ui.tv.TAG_LYRICS
import com.prism.music.ui.tv.TAG_PLAYER
import com.prism.music.ui.tv.TAG_QUEUE
import com.prism.music.ui.tv.TAG_VIDEO
import com.prism.music.ui.tv.TAG_VIDEO_SCREEN
import com.prism.music.ui.tv.TvVideoControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prism TV's music video with the remote only (the owner's real TV, 1.8.0: "When on a video, you can't move to anything
 * other than the play button" and "The video screen needs to show the queue as well"). Songs are silent tones, which
 * have no video, so the screen shows the cover where the video would be; the controls, their focus and the queue are
 * the same either way.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvVideoTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val tones = ArrayList<android.net.Uri>()
    private val player get() = Fixture.container.player

    @Before fun setUp() {
        TvFixture.assumeTv()
        Fixture.ready()
    }

    /** Re-marks the queue's songs as their videos after each move (see [lineUp]). */
    private var marker: androidx.media3.common.Player.Listener? = null

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { marker?.let { player.player?.removeListener(it) }; player.setVideoMode(false); player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    /**
     * Three songs lined up and playing. Silent tones (no picture), or with [pictures] short clips with a real picture
     * (assets/tv-test-video.m4a: 45 s of a test pattern, H.264 + silent AAC) played as their own videos: each is marked
     * as in video mode, as Prism does once YouTube gave a song's video, and marked again after each move (Prism lines
     * the next song up as its video, or switches it as it starts; for a file on the phone it would find none).
     * [withLyrics]: the first song has lyrics saved for it (as if found earlier), so no network is needed.
     */
    private fun lineUp(pictures: Boolean = false, withLyrics: Boolean = false) {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        val titles = listOf("Prism TV video one", "Prism TV video two", "Prism TV video three")
        titles.forEach { tones += if (pictures) clip(it) else Fixture.tone(it, 90) }
        val local = Fixture.container.localMusic
        val end = System.currentTimeMillis() + 20_000
        // The media store reads a clip's length a moment after it's written.
        while (true) {
            runBlocking { local.scan() }
            if (titles.all { t -> local.tracks.value.any { it.song.title == t } }) break
            if (System.currentTimeMillis() > end) throw AssertionError("the test songs never showed in Local files")
            Thread.sleep(500)
        }
        val songs = titles.map { t -> local.tracks.value.first { it.song.title == t }.song }
        if (withLyrics) {
            val lrc = (0 until 10).joinToString("\n") { i -> "[00:%02d.00]Line number ${i + 1} of the tone".format(i * 8) }
            runBlocking { Fixture.container.db.lyrics().put(com.prism.music.data.db.LyricsEntity(songs[0].id, "LRCLIB", true, lrc, System.currentTimeMillis())) }
        }
        instrumentation.runOnMainSync { player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "TV video test")) }
        waitFor(20_000) { player.currentIndex.value == 0 && player.isPlaying.value }
        if (!pictures) return
        instrumentation.runOnMainSync {
            val p = player.player!!
            p.replaceMediaItems(0, p.mediaItemCount, (0 until p.mediaItemCount).map { asVideo(p.getMediaItemAt(it)) })
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            marker = object : androidx.media3.common.Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    main.post {
                        for (i in 0 until p.mediaItemCount) {
                            val item = p.getMediaItemAt(i)
                            if (item.videoModeId == null && item.mediaId in songs.map { it.id }) p.replaceMediaItem(i, asVideo(item))
                        }
                    }
                }
            }.also { p.addListener(it) }
        }
        waitFor(20_000) { player.isPlaying.value && player.videoMode.value }
    }

    /** [item] marked as playing in video mode (its own file is the video). */
    private fun asVideo(item: androidx.media3.common.MediaItem): androidx.media3.common.MediaItem {
        val extras = android.os.Bundle(item.mediaMetadata.extras ?: android.os.Bundle()).apply { putString(com.prism.music.playback.EXTRA_VIDEO, item.mediaId) }
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    /** One copy of the test clip in the TV's music, as [title]. */
    private fun clip(title: String): android.net.Uri {
        org.junit.Assume.assumeTrue("clips are written through the media store (Android 10+)", android.os.Build.VERSION.SDK_INT >= 29)
        val bytes = instrumentation.context.assets.open("tv-test-video.m4a").use { it.readBytes() }
        val resolver = Fixture.context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "$title.m4a")
            put(android.provider.MediaStore.Audio.Media.TITLE, title)
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** The video screen's frame count (it's open). */
    private fun frames() = com.prism.music.ui.tv.TvVideoProbe.frames ?: throw AssertionError("the video screen counts no frames")
    private fun videoWidth(): Int { var w = 0; instrumentation.runOnMainSync { w = player.player?.videoSize?.width ?: 0 }; return w }

    /** The picture is on screen: frames since the last move, the player's video size, no cover over it. */
    private fun awaitPicture(ms: Long = 20_000) {
        waitFor(ms) { frames().sinceReset.get() >= 3 }
        assertTrue("the player has no video size", videoWidth() > 0)
        compose.waitUntil(5_000) { !exists(com.prism.music.ui.tv.TAG_VIDEO_COVER) }
    }

    /** The tones have no video: the player is told this one has, so its Video button shows (as for a real song). */
    private fun pretendItHasAVideo() {
        val f = com.prism.music.playback.PlayerConnection::class.java.getDeclaredField("_videoAvailable").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        instrumentation.runOnMainSync { (f.get(player) as MutableStateFlow<Boolean>).value = true }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Home → the player → its Video button → the video, focus on play/pause. */
    private fun openTheVideo(pictures: Boolean = false, withLyrics: Boolean = false) {
        lineUp(pictures, withLyrics)
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 15_000)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
        // The tone's own video lookup answers "none" first; then the button is shown.
        val end = System.currentTimeMillis() + 10_000
        while (!exists("$TAG_PLAYER/video")) {
            if (System.currentTimeMillis() > end) throw AssertionError("the player's Video button never showed")
            pretendItHasAVideo(); Thread.sleep(300)
        }
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/lyrics"))
        TvFixture.right(); compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/queue"))
        TvFixture.right(); compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/video"))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag(TvVideoControl.PLAYPAUSE.fullTag), 10_000)
    }

    private fun focusOn(c: TvVideoControl) = compose.awaitFocusOn(hasTestTag(c.fullTag))
    private fun position(): Long { var p = 0L; instrumentation.runOnMainSync { p = player.position }; return p }

    /** Every control is reached with the D-pad; ← → on the progress line seek; Lyrics opens over the video and back. */
    @Test fun everyControlOnTheVideoIsReachedWithTheDpad() {
        openTheVideo()
        // Paused, the controls stay up while the remote moves through them.
        instrumentation.runOnMainSync { player.setPlaying(false) }
        waitFor(5_000) { !player.isPlaying.value }
        TvFixture.left(); focusOn(TvVideoControl.PREVIOUS)
        TvFixture.right(); focusOn(TvVideoControl.PLAYPAUSE)
        for (c in listOf(TvVideoControl.NEXT, TvVideoControl.LYRICS, TvVideoControl.QUEUE, TvVideoControl.SONG, TvVideoControl.MORE)) {
            TvFixture.right(); focusOn(c)
        }
        // Up: the progress line. → seeks 10 s ahead, ← back; focus stays on the line.
        TvFixture.up(); focusOn(TvVideoControl.SEEK)
        val before = position()
        TvFixture.right()
        waitFor(5_000) { position() >= before + 8_000 }
        TvFixture.left()
        waitFor(5_000) { position() < before + 5_000 }
        focusOn(TvVideoControl.SEEK)
        // Down: play/pause again. OK plays.
        TvFixture.down(); focusOn(TvVideoControl.PLAYPAUSE)
        TvFixture.ok()
        waitFor(5_000) { player.isPlaying.value }
        TvFixture.ok()
        waitFor(5_000) { !player.isPlaying.value }
        // ⋯ opens the player's actions panel; Back closes it, focus back on ⋯.
        repeat(6) { TvFixture.right(); Thread.sleep(150) }
        focusOn(TvVideoControl.MORE)
        TvFixture.ok()
        compose.waitUntil(5_000) { exists(com.prism.music.ui.tv.TAG_ACTIONS) }
        TvFixture.back()
        focusOn(TvVideoControl.MORE)
        // Lyrics: over the video, and Back comes back to its controls on Lyrics.
        TvFixture.left(); TvFixture.left(); TvFixture.left()
        focusOn(TvVideoControl.LYRICS)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"), 10_000)
        TvFixture.back()
        focusOn(TvVideoControl.LYRICS)
        // "Back to the song": the player.
        TvFixture.right(); TvFixture.right(); focusOn(TvVideoControl.SONG)
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/video"), 10_000)
    }

    /**
     * Playing, the controls fade after a few seconds with focus on the screen (never on a hidden control); any key
     * brings them back on the control left; Back hides them, and Back again leaves the video.
     */
    @Test fun theControlsFadeAndComeBackAndFocusIsNeverOnAHiddenOne() {
        openTheVideo()
        TvFixture.right(); focusOn(TvVideoControl.NEXT)
        // NEXT pressed would skip; it's only focused. Now the remote is put down.
        compose.awaitFocusOn(hasTestTag(TAG_VIDEO_SCREEN), 10_000)
        compose.waitUntil(5_000) { !exists(TvVideoControl.NEXT.fullTag) }
        val index = player.currentIndex.value
        // A key brings them back (it doesn't press anything): focus on Next, where it was.
        TvFixture.ok()
        focusOn(TvVideoControl.NEXT)
        Thread.sleep(500)
        assertTrue("the key that showed the controls pressed Next", player.currentIndex.value == index)
        // Back with the controls up hides them; Back again: the player.
        TvFixture.back()
        compose.awaitFocusOn(hasTestTag(TAG_VIDEO_SCREEN), 5_000)
        compose.waitUntil(5_000) { !exists(TvVideoControl.NEXT.fullTag) }
        assertTrue(exists(TAG_VIDEO))
        TvFixture.back()
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/video"), 10_000)
    }

    /** The queue on the video: the player's panel, from the Queue button; a song picked plays and the video stays. */
    @Test fun theQueueOpensFromTheVideoAndPlaysTheSongPicked() {
        openTheVideo()
        TvFixture.right(); focusOn(TvVideoControl.NEXT)
        TvFixture.right(); focusOn(TvVideoControl.LYRICS)
        TvFixture.right(); focusOn(TvVideoControl.QUEUE)
        TvFixture.ok()
        // Opens at the playing song, focus on the next one.
        compose.awaitFocusOn(hasTestTag("$TAG_QUEUE/1"), 10_000)
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_QUEUE/2"))
        TvFixture.ok()
        waitFor(5_000) { player.currentIndex.value == 2 && player.isPlaying.value }
        // The panel closes onto the video, Queue focused; the video page stays.
        focusOn(TvVideoControl.QUEUE)
        assertTrue(exists(TAG_VIDEO))
        // Again (paused, so the controls stay), on the last song: it opens on that one. Left closes it.
        instrumentation.runOnMainSync { player.setPlaying(false) }
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_QUEUE/2"), 10_000)
        TvFixture.left()
        focusOn(TvVideoControl.QUEUE)
    }

    /**
     * The owner: "the lyrics should play over the video". Lyrics open over the video, which is still there and plays on
     * under them (on a scrim, not the cover's backdrop); Back closes them onto the video's controls, the page never left.
     */
    @Test fun lyricsPlayOverTheVideo() {
        openTheVideo(withLyrics = true)
        // Paused while the screen is looked at (the sung line moves every frame while playing; the test framework waits
        // for a still screen).
        instrumentation.runOnMainSync { player.setPlaying(false) }
        waitFor(5_000) { !player.isPlaying.value }
        TvFixture.right(); focusOn(TvVideoControl.NEXT)
        TvFixture.right(); focusOn(TvVideoControl.LYRICS)
        TvFixture.ok()
        compose.waitUntil(30_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Line number 1 of the tone", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"), 10_000)
        assertTrue("the lyrics are over the video", exists(com.prism.music.ui.tv.TAG_VIDEO_LYRICS))
        assertTrue("the video is still under them", exists(TAG_VIDEO) && exists(TAG_VIDEO_SCREEN))
        assertTrue("on a scrim", exists("$TAG_LYRICS/scrim"))
        assertTrue("the controls hide under the lyrics", !exists(TvVideoControl.PLAYPAUSE.fullTag))
        // OK on the lines (no line picked) plays: the video plays on under the lyrics, still the video page.
        TvFixture.ok()
        waitFor(5_000) { player.isPlaying.value }
        Thread.sleep(1_500)
        assertTrue("still playing under the lyrics", player.isPlaying.value)
        instrumentation.runOnMainSync { player.setPlaying(false) }
        waitFor(5_000) { !player.isPlaying.value }
        assertTrue(exists(com.prism.music.ui.tv.TAG_VIDEO_LYRICS) && exists(TAG_VIDEO_SCREEN))
        // Paused, the title and Timing are shown (playing, they fade a moment after the last key).
        compose.waitUntil(3_000) { exists("$TAG_LYRICS/timing") && exists("$TAG_LYRICS/title") }
        // Timing works there too: Right reaches it, OK opens its panel, Back closes it onto the lines.
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/timing"))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/nudge"), 5_000)
        TvFixture.back()
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"), 5_000)
        // Back: the lyrics close, the video's controls come back on Lyrics; the video page is still there.
        TvFixture.back()
        focusOn(TvVideoControl.LYRICS)
        assertTrue(!exists(com.prism.music.ui.tv.TAG_VIDEO_LYRICS) && exists(TAG_VIDEO))
    }

    /**
     * The owner's Google TV: the video lagged with lyrics over it (it played on a TextureView, so each video frame drew
     * the whole window again), and the title, progress and Timing stayed over it. The picture keeps coming under the
     * lyrics (now on a SurfaceView), and those fade a moment after the last key while it plays, the lines staying; a key
     * brings them back without doing anything else (OK on the lines would pause); Back closes the lyrics onto the picture.
     */
    @Test fun lyricsOverAPlayingVideoLetThePictureThroughAndTheTitleFades() {
        openTheVideo(pictures = true, withLyrics = true)
        awaitPicture()
        // Opened paused (the screen is looked at; while lyrics play the test framework never sees it still, so then
        // only the player, the frame count and the lyrics' own probe are read).
        instrumentation.runOnMainSync { player.setPlaying(false) }
        waitFor(5_000) { !player.isPlaying.value }
        TvFixture.right(); focusOn(TvVideoControl.NEXT)
        TvFixture.right(); focusOn(TvVideoControl.LYRICS)
        TvFixture.ok()
        compose.waitUntil(30_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Line number", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"), 10_000)
        assertTrue("on a scrim over the video", exists("$TAG_LYRICS/scrim") && exists(TAG_VIDEO_SCREEN))
        assertTrue("paused, the title and Timing show", exists("$TAG_LYRICS/timing") && exists("$TAG_LYRICS/title") && exists("$TAG_LYRICS/progress"))
        // Playing: the picture keeps coming under the lyrics.
        instrumentation.runOnMainSync { player.setPlaying(true) }
        waitFor(5_000) { player.isPlaying.value }
        val before = frames().sinceReset.get()
        waitFor(5_000) { frames().sinceReset.get() >= before + 20 }
        // The remote put down: the title, progress and Timing go (the lines stay). The screen's own clock is moved on
        // (the test framework holds it while it isn't asked to wait for a still screen).
        assertTrue("shown while the remote was just used", com.prism.music.ui.tv.TvLyricsCounts.chrome)
        awayFromTheRemote()
        // A key only brings them back: still playing (OK on the lines would pause).
        TvFixture.ok()
        aFewFrames()
        waitFor(3_000) { com.prism.music.ui.tv.TvLyricsCounts.chrome }
        Thread.sleep(500)
        assertTrue("the key that brought them back paused", player.isPlaying.value)
        // Gone again; paused, they're back, focus on the lines (never on a hidden Timing).
        awayFromTheRemote()
        instrumentation.runOnMainSync { player.setPlaying(false) }
        waitFor(5_000) { !player.isPlaying.value }
        aFewFrames()
        waitFor(3_000) { com.prism.music.ui.tv.TvLyricsCounts.chrome }
        compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"), 5_000)
        assertTrue(exists("$TAG_LYRICS/timing") && exists("$TAG_LYRICS/title") && exists("$TAG_LYRICS/list"))
        // Back: the lyrics close onto the video's controls, the picture still there.
        TvFixture.back()
        focusOn(TvVideoControl.LYRICS)
        assertTrue(!exists(com.prism.music.ui.tv.TAG_VIDEO_LYRICS) && !exists(com.prism.music.ui.tv.TAG_VIDEO_COVER))
        // Back to the song (the player), then the video again: the picture comes back on the screen's new surface.
        TvFixture.right(); focusOn(TvVideoControl.QUEUE)
        TvFixture.right(); focusOn(TvVideoControl.SONG)
        TvFixture.ok()
        // Leaving the video played the song as itself: it's marked as its video again (a file on the TV has none to
        // look up), with the Video button shown.
        // (Waited on through the test framework, which moves the screen's clock: the page closing runs on it.)
        compose.waitUntil(5_000) { !player.videoMode.value }
        instrumentation.runOnMainSync { val p = player.player!!; p.replaceMediaItem(p.currentMediaItemIndex, asVideo(p.currentMediaItem!!)) }
        compose.waitUntil(10_000) { player.videoMode.value }
        val end = System.currentTimeMillis() + 10_000
        while (!exists("$TAG_PLAYER/video")) {
            if (System.currentTimeMillis() > end) throw AssertionError("the player's Video button never showed")
            pretendItHasAVideo(); Thread.sleep(300)
        }
        compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/video"), 10_000)
        TvFixture.ok()
        // The video's controls as they were left: on "Back to the song".
        compose.awaitFocusOn(hasTestTag(TvVideoControl.SONG.fullTag), 10_000)
        instrumentation.runOnMainSync { player.setPlaying(true) }
        awaitPicture()
    }

    /**
     * The owner's TV: "If you skip too fast … it just shows the album artwork". Two skips in a row end on the third
     * song, as its video, with its picture on screen (frames counted since the skips, the player's video size).
     */
    @Test fun skippingTwiceQuicklyEndsOnTheRightVideoWithItsPicture() {
        openTheVideo(pictures = true)
        awaitPicture()
        val before = frames().resets
        TvFixture.press(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        TvFixture.press(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        waitFor(10_000) { player.currentIndex.value == 2 }
        assertTrue("the skips didn't reset the picture", frames().resets > before)
        waitFor(10_000) { player.videoMode.value }
        awaitPicture()
        assertTrue(player.currentSong.value?.title == "Prism TV video three")
        // Still on the video screen, the controls as they were.
        assertTrue(exists(TAG_VIDEO))
    }

    /**
     * The owner's TV: "If you … seek far in a song while on the video, it just shows the album artwork". A long seek
     * from the seek line (30 s on), then one from elsewhere (the phone as a remote), each ending with the picture back.
     */
    @Test fun aLongSeekEndsWithThePictureBack() {
        openTheVideo(pictures = true)
        awaitPicture()
        TvFixture.up(); focusOn(TvVideoControl.SEEK)
        var before = frames().resets
        repeat(3) { TvFixture.right(); Thread.sleep(150) }
        waitFor(5_000) { position() >= 28_000 }
        assertTrue("the seek didn't reset the picture", frames().resets > before)
        awaitPicture()
        before = frames().resets
        instrumentation.runOnMainSync { player.seekTo(2_000) }
        waitFor(5_000) { frames().resets > before }
        awaitPicture()
        assertTrue(player.videoMode.value)
    }

    /** The lyrics' title, progress and Timing fade on their own while a video plays: the screen's clock moved past it. */
    private fun awayFromTheRemote() {
        compose.mainClock.advanceTimeBy(com.prism.music.ui.tv.TV_LYRICS_CHROME_MS + 1_000)
        waitFor(5_000) { !com.prism.music.ui.tv.TvLyricsCounts.chrome }
    }

    /** The screen's clock moved on a few frames (so it shows what a key or a pause changed; well short of the fade). */
    private fun aFewFrames() = compose.mainClock.advanceTimeBy(200)

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not after $timeoutMs ms")
            Thread.sleep(100)
        }
    }
}
