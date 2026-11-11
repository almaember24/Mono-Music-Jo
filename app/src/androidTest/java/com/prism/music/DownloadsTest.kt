package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.media3.exoplayer.offline.Download
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A playlist's download button (#212, 1.7.9): with every song downloaded it removes them, and asks first. Cancel keeps
 * them; Delete removes them.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val songs = Fixture.songs(3)

    @Before fun start() {
        Fixture.ready()
        Fixture.prismPlaylist("Road trip", songs)
        Fixture.downloaded(songs)
        Fixture.launch()
    }

    private fun completed() = songs.count { Fixture.container.downloads.downloads.value[it.id]?.state == Download.STATE_COMPLETED }

    private fun openRoadTrip() {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Library") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Library") and hasClickAction()).onFirst().performClick()
        compose.clickWhenFound(hasText("Road trip") and hasClickAction(), 15_000)
        compose.waitForText("Test song 1")
        compose.waitUntil(10_000) { completed() == 3 }
    }

    /** The page's download button while every song is downloaded (named for what it does then). */
    private val removeButton = hasClickAction() and hasContentDescription("Remove downloads")

    @Test fun removingAPlaylistsDownloadsAsksFirst() {
        openRoadTrip()
        compose.clickWhenFound(removeButton)
        compose.waitForText("Delete the 3 songs of Road trip")
        compose.onAllNodes(hasText("Cancel") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        assertEquals("Cancel keeps them", 3, completed())

        compose.clickWhenFound(removeButton)
        compose.waitForText("Delete the 3 songs of Road trip")
        compose.onAllNodes(hasText("Delete") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { completed() == 0 }
    }
}
