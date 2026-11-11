package com.prism.music

import android.os.StrictMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.podcast.RssPodcasts
import com.prism.music.data.prefs.LyricsSource
import com.prism.music.data.prefs.QuickPicksSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 1.8.2's code review, on a device: what only shows with the real downloads, pages and main thread. */
@RunWith(AndroidJUnit4::class)
class ReviewFixes182AppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = Fixture.container

    private fun openSetting(title: String, query: String) {
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.clickWhenFound(hasText(title) and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
    }

    // ------------------------------------------------------------ Kept playlists: what's deleted stays deleted

    /** A kept playlist with its two songs here, the list looked at again whenever [look] runs. */
    private fun keptPlaylist(songs: List<com.prism.music.data.model.Song>): String {
        Fixture.ready()
        val id = Fixture.prismPlaylist("Road trip", songs)
        Fixture.downloaded(songs)
        c.settings.setDownloadWifiOnly(false)
        Fixture.launch()
        compose.waitUntil(10_000) { c.downloadState.value.keys.containsAll(songs.map { it.id }) }
        c.keptPlaylists.set(id, true)
        compose.waitUntil(5_000) { id in c.settings.current.behavior.keptPlaylists }
        return id
    }

    private fun known() = c.downloads.knownIds().orEmpty()

    @Test fun aDeletedSongStaysDeletedAndANewOneStillComesDown() {
        val all = Fixture.songs(3)
        val id = keptPlaylist(all.take(2))
        val deleted = all[0].id

        c.downloads.remove(deleted)
        compose.waitUntil(10_000) { deleted !in known() }
        c.keptPlaylists.fromPage(id, all.take(2))
        Thread.sleep(1_500)
        assertFalse("it came back by itself", deleted in known())

        c.prismLibrary.add(id, listOf(all[2]))
        c.keptPlaylists.fromPage(id, all)
        compose.waitUntil(10_000) { all[2].id in known() }
        assertFalse("still gone", deleted in known())
        assertTrue("the other one's still here", all[1].id in known())
    }

    /** Downloaded by hand after all: it's the listener's again, and deleting it again counts again. */
    @Test fun aSongDownloadedAgainByHandIsForgiven() {
        val all = Fixture.songs(2)
        val id = keptPlaylist(all)
        c.downloads.remove(all[0].id)
        compose.waitUntil(10_000) { all[0].id !in known() }
        c.downloads.download(all[0])
        compose.waitUntil(10_000) { all[0].id in known() }
        c.keptPlaylists.fromPage(id, all)
        c.downloads.remove(all[0].id)
        compose.waitUntil(10_000) { all[0].id !in known() }
        c.keptPlaylists.fromPage(id, all)
        Thread.sleep(1_500)
        assertFalse(all[0].id in known())
    }

    @Test fun deletingEveryDownloadStopsKeepingEveryList() {
        val all = Fixture.songs(2)
        val id = keptPlaylist(all)
        val second = Fixture.prismPlaylist("Second", all.take(1))
        c.keptPlaylists.set(second, true)
        compose.waitUntil(5_000) { second in c.settings.current.behavior.keptPlaylists }

        c.downloads.removeAll()
        compose.waitUntil(10_000) { all.none { it.id in known() } }
        compose.waitUntil(5_000) { c.settings.current.behavior.keptPlaylists.isEmpty() }
        c.keptPlaylists.fromPage(id, all)
        Thread.sleep(1_500)
        assertTrue("nothing came back: ${known()}", all.none { it.id in known() })
    }

    // ------------------------------------------------------------ Podcasts by RSS, off the main thread

    /** One big file holds every show: opening Podcasts or removing a show doesn't read or write it on the main thread. */
    @Test fun podcastsReadAndRemoveShowsOffTheMainThread() {
        Fixture.ready()
        val dir = File(c.app.filesDir, RssPodcasts.DIR).apply { mkdirs() }
        File(dir, RssPodcasts.FILE).writeText("""{"shows":[{"id":"rss:000000000000","feedUrl":"https://pod.example.com/feed.xml","title":"Test Radio","episodes":[{"id":"rss:000000000000:0000000000000000","title":"One","audioUrl":"https://pod.example.com/1.mp3"}]}]}""")
        val onMain = CopyOnWriteArrayList<String>()
        val notes = Executors.newSingleThreadExecutor()
        instrumentation.runOnMainSync {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyListener(notes) { v ->
                    v.stackTrace.firstOrNull { it.className.startsWith(RssPodcasts::class.java.name) }?.let { onMain += "${it.methodName}: ${v.javaClass.simpleName}" }
                }.build(),
            )
        }
        try {
            Fixture.launch()
            compose.clickWhenFound(hasContentDescription(tr(R.string.podcasts)), 20_000)
            compose.clickWhenFound(hasText("Test Radio") and hasClickAction(), 15_000)
            compose.clickWhenFound(hasContentDescription(tr(R.string.rss_remove_show)), 15_000)
            compose.waitForText(tr(R.string.rss_remove_show_detail))
            compose.onNode(hasText(tr(R.string.remove)) and hasClickAction()).performClick()
            compose.waitUntil(10_000) { c.rssPodcasts.shows.value.isEmpty() }
            compose.waitForText(tr(R.string.rss_add_a_podcast), 15_000)
            assertFalse("the file says it's gone", "Test Radio" in File(dir, RssPodcasts.FILE).readText())
            Thread.sleep(500)
            assertEquals("on the main thread", emptyList<String>(), onMain.toList())
        } finally {
            instrumentation.runOnMainSync { StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX) }
            notes.shutdown()
        }
    }

    // ------------------------------------------------------------ Settings

    /** The last source on, here as in the Order row's own switch: with none left, every switch said off and all were asked. */
    @Test fun readingYourFilesCantBeTheLastSourceSwitchedOff() {
        Fixture.ready()
        c.settings.setEmbeddedLyrics(true)
        c.settings.setLyricsOff(LyricsSource.entries.toSet() - LyricsSource.EMBEDDED)
        compose.waitUntil(5_000) { c.settings.current.lyricsSources == listOf(LyricsSource.EMBEDDED) }
        Fixture.launch()
        val title = tr(R.string.read_lyrics_from_your_music_files)
        openSetting(title, "read lyrics from your music")
        val row = hasText(title) and isToggleable()
        compose.waitUntil(5_000) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        // Switched off, a tap has nothing to act on: it's the same guard as the Order row's switch.
        compose.onNode(row).assertIsNotEnabled()
        assertEquals(listOf(LyricsSource.EMBEDDED), c.settings.current.lyricsSources)

        // With another on, it's an ordinary switch.
        c.settings.setLyricsOff(LyricsSource.entries.toSet() - LyricsSource.EMBEDDED - LyricsSource.LRCLIB)
        compose.waitUntil(5_000) { c.settings.current.lyricsSources.size == 2 }
        compose.waitForIdle()
        compose.onNode(row).assertIsEnabled()
        compose.onNode(row).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { !c.settings.current.behavior.embeddedLyrics }
    }

    /** The search result opens Home & Library's Home group: the setting has to be there. */
    @Test fun quickPicksFromIsOnThePageItsResultOpens() {
        Fixture.ready()
        Fixture.launch()
        openSetting(tr(R.string.quick_picks_from), "quick picks from")
        compose.waitForText(tr(R.string.quick_picks_from), 5_000)
        val other = QuickPicksSource.entries.first { it != c.settings.current.behavior.quickPicksSource }
        compose.onAllNodes(hasText(other.label) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { c.settings.current.behavior.quickPicksSource == other }
    }
}
