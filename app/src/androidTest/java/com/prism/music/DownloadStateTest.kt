package com.prism.music

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.7.9.3 (1.7.8's check: Home's first frame waited 3.4 s on a slow phone for the downloads to read their index): the
 * downloads start in the background at launch and screens read [AppContainer.downloadState]. It fills in by itself.
 */
@RunWith(AndroidJUnit4::class)
class DownloadStateTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun downloadedSongsShowWithoutAScreenStartingTheDownloads() {
        Fixture.ready()
        val songs = Fixture.songs(3, "Kept song")
        Fixture.downloaded(songs)
        Fixture.launch()
        compose.waitUntil(15_000) { Fixture.container.downloadState.value.keys.containsAll(songs.map { it.id }) }
        assertEquals(songs.map { it.id }.toSet(), Fixture.container.downloadState.value.keys.intersect(songs.map { it.id }.toSet()))
    }
}
