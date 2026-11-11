package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reddit, after 1.7.8 moved the lyrics' source to their end: the source back beside Sing, as an option. Off it isn't
 * there (1.7.8's look stays the default); on, it's under the open lyrics and opens the Lyrics source sheet.
 */
@RunWith(AndroidJUnit4::class)
class LyricsSourceButtonTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private var tone: android.net.Uri? = null

    @After fun cleanUp() {
        runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { Fixture.container.player.stop() } }
        tone?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    @Test fun theSourceButtonOpensTheSourceSheet() {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone("Prism source tone", 120)
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val song = local.tracks.value.first { it.song.title == "Prism source tone" }.song
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Fixture.container.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Source test")) }

        // The full player, by the link a widget's cover uses (a click by the song's title found a Home row listing it,
        // and UiAutomator can't read the screen while music plays), then its lyrics.
        compose.waitForText("Prism source tone", 20_000)
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        // Paused: a playing player never lets Compose go idle, and the test reads the screen through it.
        Thread.sleep(2_000)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Fixture.container.player.setPlaying(false) }
        compose.clickWhenFound(hasContentDescription("Lyrics") and hasClickAction(), 15_000)
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Close lyrics")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("off by default: no source button", compose.onAllNodes(hasTestTag("lyricsSourceButton")).fetchSemanticsNodes().isEmpty())

        runBlocking { Fixture.container.settings.setLyricsSourceButton(true) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("lyricsSourceButton")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("lyricsSourceButton")).onFirst().performClick()
        compose.waitForText("Lyrics source")
    }
}
