package com.prism.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.tv.TAG_AMBIENT
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #265 (1.8.0): desk mode on a phone. Switched on, charging and playing: after a quiet minute the ambient screen; a tap
 * brings Prism back. (Off by default: TvAmbientLogicTest.)
 */
@RunWith(AndroidJUnit4::class)
class DeskModeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var tone: android.net.Uri? = null

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).close()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { Fixture.container.player.stop() } }
        tone?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        Fixture.container.settings.setDeskMode(false)
        Fixture.container.settings.setTvAmbientMinutes(3)
        shell("dumpsys battery reset")
    }

    private fun shown() = compose.onAllNodes(hasTestTag(TAG_AMBIENT), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun deskModeShowsTheAmbientScreenWhileChargingAndPlaying() {
        Fixture.ready()
        Fixture.container.settings.setDeskMode(true)
        Fixture.container.settings.setTvAmbientMinutes(1)
        // The emulator says it's charging.
        shell("dumpsys battery set ac 1")
        shell("dumpsys battery set status 2")
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone("Prism desk tone", 150)
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val song = local.tracks.value.first { it.song.title == "Prism desk tone" }.song
        instrumentation.runOnMainSync { Fixture.container.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Desk test")) }
        compose.waitUntil(90_000) { shown() }
        compose.onAllNodes(hasTestTag(TAG_AMBIENT), useUnmergedTree = true).onFirst().performClick()
        compose.waitUntil(5_000) { !shown() }
        assertTrue(Fixture.container.player.isPlaying.value)
    }
}
