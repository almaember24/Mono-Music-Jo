package com.prism.music

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.player.CrossfadeShapeKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.1, the owner's: the progress bar shaped by the crossfade. Two silent tones on the phone stand in for two songs (no
 * network), Crossfade on at a fixed 8 s: the first song's bar tapers once its blend is coming, the option off leaves the
 * bar as it was, and after the blend the next song's bar grows in.
 */
@RunWith(AndroidJUnit4::class)
class CrossfadeBarAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val tones = ArrayList<android.net.Uri>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val player get() = Fixture.container.player
    private val settings get() = Fixture.container.settings

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        runBlocking { settings.setMix(false); settings.setCrossfadeShape(false) }
    }

    /** Two 60 s tones queued (not an album, so they blend), the first playing; Crossfade on for 8 s, the shaped bar on. */
    private fun twoSongs(): Pair<Song, Song> {
        Fixture.ready()
        runBlocking {
            settings.setMix(true)
            settings.setMixAuto(false)
            settings.setMixSeconds(8)
            settings.setCrossfadeShape(true)
        }
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tones += Fixture.tone("Prism blend tone A", 60)
        tones += Fixture.tone("Prism blend tone B", 60)
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val a = local.tracks.value.first { it.song.title == "Prism blend tone A" }.song
        val b = local.tracks.value.first { it.song.title == "Prism blend tone B" }.song
        instrumentation.runOnMainSync { player.playQueue(listOf(a, b), 0, QueueSource(QueueKind.OTHER, "Crossfade bar test")) }
        compose.waitUntil(20_000) { player.currentSong.value?.id == a.id && player.isPlaying.value }
        return a to b
    }

    /** The full player (by the link a widget's cover uses), then paused so Compose goes idle. */
    private fun openPlayer() {
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        Thread.sleep(2_000)
        instrumentation.runOnMainSync { player.setPlaying(false) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("progressBar"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun bar(shape: String) = hasTestTag("progressBar") and SemanticsMatcher.expectValue(CrossfadeShapeKey, shape)
    private val anyShape = hasTestTag("progressBar") and SemanticsMatcher.keyIsDefined(CrossfadeShapeKey)
    private fun shown(m: SemanticsMatcher) = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun theBarTakesTheCrossfadesShape() {
        val (a, b) = twoSongs()
        // Half way in: the blend 8 s before the end is known (a fixed length is known at once).
        instrumentation.runOnMainSync { player.seekTo(31_000) }
        compose.waitUntil(15_000) { Fixture.container.mixState.mark.value?.let { it.songId == a.id && it.lengthMs == 8_000L } == true }
        openPlayer()
        compose.waitUntil(10_000) { shown(bar("out")) }
        // Off: the bar as before, no shape at all.
        runBlocking { settings.setCrossfadeShape(false) }
        compose.waitUntil(10_000) { !shown(anyShape) && shown(hasTestTag("progressBar")) }
        runBlocking { settings.setCrossfadeShape(true) }
        compose.waitUntil(10_000) { shown(bar("out")) }

        // Through the blend (at 0:52): the next song comes in, its bar growing in over the blend.
        instrumentation.runOnMainSync { player.seekTo(39_000); player.setPlaying(true) }
        compose.waitUntil(40_000) { player.currentSong.value?.id == b.id && Fixture.container.mixState.into.value?.songId == b.id }
        // The blend's length: 8 s, or a little less if it was put off to get in step (it still ends where it would have).
        val fadeIn = Fixture.container.mixState.into.value?.lengthMs ?: 0L
        assertTrue("the fade-in is the blend's length ($fadeIn ms)", fadeIn in 4_000L..8_000L)
        instrumentation.runOnMainSync { player.setPlaying(false) }
        compose.waitUntil(10_000) { shown(bar("in")) }
    }
}
