package com.prism.music

import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.imports.HistoryImports
import com.prism.music.data.imports.ImportSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bring your history (1.8.1) end to end: a Spotify data download (a small zip written as Spotify writes it) is looked
 * at and brought in, the import shows on its page, Replay counts it only once switched on and then says "Includes
 * Spotify", and removing it takes it away. Matching needs YouTube's answers, which these tests don't have: the plays
 * stay unmatched, and still count in Replay's time (Spotify knows how long each was heard).
 */
@RunWith(AndroidJUnit4::class)
class BringHistoryTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container

    private val now = java.time.Instant.now()
    private fun ts(daysAgo: Long) = now.minus(java.time.Duration.ofDays(daysAgo)).toString()
    private val blindingLights get() = """{"ts":"${ts(3)}","ms_played":200040,"master_metadata_track_name":"Blinding Lights","master_metadata_album_artist_name":"The Weeknd","master_metadata_album_album_name":"After Hours","spotify_track_uri":"spotify:track:0VjIjW4GlUZAMYd2vXMi3b","reason_start":"clickrow","reason_end":"trackdone","skipped":false}"""
    private val antiHero get() = """{"ts":"${ts(2)}","ms_played":268000,"master_metadata_track_name":"Anti-Hero","master_metadata_album_artist_name":"Taylor Swift","master_metadata_album_album_name":"Midnights","spotify_track_uri":"spotify:track:0V3wPSX9ygBnCm8psDIegu","reason_start":"trackdone","reason_end":"trackdone","skipped":false}"""
    private val podcast get() = """{"ts":"${ts(1)}","ms_played":1800000,"master_metadata_track_name":null,"master_metadata_album_artist_name":null,"episode_name":"An episode","episode_show_name":"A podcast","reason_end":"endplay"}"""
    private val levitating get() = """{"ts":"${ts(1)}","ms_played":203000,"master_metadata_track_name":"Levitating","master_metadata_album_artist_name":"Dua Lipa","master_metadata_album_album_name":"Future Nostalgia","spotify_track_uri":"spotify:track:463CkQjx2Zk1yXoBuierM9","reason_start":"trackdone","reason_end":"trackdone","skipped":false}"""

    /** A Spotify data download as Spotify writes it, holding [plays] (extended history entries). */
    private fun zip(name: String = "my_spotify_data.zip", plays: List<String> = listOf(blindingLights, antiHero, podcast)): Uri {
        val history = plays.joinToString(",\n", "[", "]")
        val library = """{"tracks":[{"artist":"The Weeknd","album":"After Hours","track":"Blinding Lights","uri":"spotify:track:0VjIjW4GlUZAMYd2vXMi3b"}],"albums":[],"artists":[]}"""
        val playlists = """{"playlists":[{"name":"Bring test","lastModifiedDate":"2024-01-01","items":[{"track":{"trackName":"Anti-Hero","artistName":"Taylor Swift","albumName":"Midnights","trackUri":"spotify:track:0V3wPSX9ygBnCm8psDIegu"}}]}]}"""
        val file = File(Fixture.context.cacheDir, name)
        ZipOutputStream(file.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("Spotify Extended Streaming History/Streaming_History_Audio_2025-2026_0.json", history)
            put("Spotify Account Data/YourLibrary.json", library)
            put("Spotify Account Data/Playlist1.json", playlists)
        }
        return Uri.fromFile(file)
    }

    @Before fun start() {
        Fixture.ready()
        // As before any import (read when Prism first asks for its imports, below).
        Fixture.context.getSharedPreferences(HistoryImports.PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { ImportSource.entries.forEach { c.imports.remove(it) } }
    }

    @After fun clean() {
        runBlocking { ImportSource.entries.forEach { c.imports.remove(it) } }
    }

    @Test fun aSpotifyDownloadIsBroughtCountedAndRemoved() {
        val uri = zip()
        val stats = runBlocking {
            val preview = c.imports.preview(ImportSource.SPOTIFY, listOf(uri))
            assertEquals("the podcast isn't a play", 2, preview.plays)
            assertEquals(1, preview.library.liked.size)
            assertEquals(1, preview.library.playlists.size)
            c.imports.bring(ImportSource.SPOTIFY, listOf(uri), HistoryImports.Choice(), preview)
        }
        assertEquals(2, stats?.stored)
        assertTrue("the first import lets it shape the mixes", c.imports.teach.value)
        assertTrue("Replay counts Prism only until asked", c.imports.counted.value.isEmpty())
        val prismOnly = runBlocking { c.imports.replay().totalMs(0, Long.MAX_VALUE) }

        Fixture.launch()
        compose.openSettings()
        compose.clickWhenFound(hasText("Recommendations & Replay") and hasClickAction())
        compose.clickWhenFound(hasText("Bring your history") and hasClickAction())
        seeText("Your imports")
        seeText("2 plays")

        // Back on Recommendations & Replay: count Spotify.
        androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).pressBack()
        seeText("Count history from")
        compose.clickWhenFound(hasText("Spotify") and hasClickAction())
        compose.waitUntil(5_000) { ImportSource.SPOTIFY in c.imports.counted.value }
        assertEquals(prismOnly + 200_040 + 268_000, runBlocking { c.imports.replay().totalMs(0, Long.MAX_VALUE) })

        // Replay says what it counts.
        // The Replay tab (its name may be hidden by the tab bar's label style; the tab still carries it).
        compose.clickWhenFound((hasText("Replay") or hasContentDescription("Replay")) and hasClickAction())
        seeText("Includes Spotify")

        // Removed from its page. Replay is a tab: Back from it goes Home, and the Settings tab then reopens where it was
        // (tabs keep their pages), Recommendations & Replay.
        androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.clickWhenFound(hasContentDescription("Settings") and hasClickAction())
        if (compose.onAllNodes(hasText("Bring your history") and hasClickAction()).fetchSemanticsNodes().isEmpty()) {
            compose.clickWhenFound(hasText("Recommendations & Replay") and hasClickAction())
        }
        compose.clickWhenFound(hasText("Bring your history") and hasClickAction())
        compose.clickWhenFound(hasContentDescription("Remove") and hasClickAction())
        compose.clickWhenFound(hasText("Remove") and hasClickAction() and !hasContentDescription("Remove"))
        compose.waitUntil(10_000) { runBlocking { c.db.imports().summaries().isEmpty() } }
        assertTrue("no longer counted", c.imports.counted.value.isEmpty())
        assertEquals(prismOnly, runBlocking { c.imports.replay().totalMs(0, Long.MAX_VALUE) })
    }

    /**
     * A newer download of the same kind (1.8.1): the page says what's new in it ("1 new play since your last import"),
     * brings only that, says "Nothing new in this download" for the same one again, and shows when Spotify was last
     * brought in with the way to ask for a fresh download and the reminder's choices.
     */
    @Test fun aNewerDownloadBringsOnlyWhatsNew() {
        val first = zip("spotify_first.zip", listOf(blindingLights, antiHero))
        runBlocking {
            val preview = c.imports.preview(ImportSource.SPOTIFY, listOf(first))
            assertEquals(2, preview.newPlays)
            c.imports.bring(ImportSource.SPOTIFY, listOf(first), HistoryImports.Choice(), preview)
        }
        assertTrue("the import is dated", ImportSource.SPOTIFY in c.imports.lastImports.value)

        Fixture.launch()
        compose.openSettings()
        compose.clickWhenFound(hasText("Recommendations & Replay") and hasClickAction())
        compose.clickWhenFound(hasText("Bring your history") and hasClickAction())
        seeText("Last Spotify import")
        seeText("(today)")
        seeText("Request a fresh download")
        seeText("Remind me to bring a fresh download")
        seeText("3 months")

        // The newer download: the same two plays, and one more (as picked in the file picker).
        val second = zip("spotify_second.zip", listOf(blindingLights, antiHero, levitating))
        c.imports.picked.value = HistoryImports.Picked(ImportSource.SPOTIFY, listOf(second))
        seeText("1 new play since your last import")
        seeText("2 already here")
        seeText("Bring them")
        compose.clickWhenFound(hasText("Bring them") and hasClickAction())
        compose.waitUntil(15_000) { runBlocking { c.db.imports().summaries().firstOrNull()?.plays } == 3 }

        // The same download again.
        c.imports.picked.value = HistoryImports.Picked(ImportSource.SPOTIFY, listOf(second))
        seeText("Nothing new in this download")
        assertEquals(3, runBlocking { c.db.imports().summaries().single().plays })
    }

    /** Waits for [text], scrolling the page's lists to it: the page builds only what's on screen. */
    private fun seeText(text: String, timeoutMs: Long = 10_000) {
        // Group headings are shown in capitals ("YOUR IMPORTS").
        val matcher = hasText(text, substring = true, ignoreCase = true)
        val end = System.currentTimeMillis() + timeoutMs
        while (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            if (System.currentTimeMillis() > end) {
                val shown = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                    .mapNotNull { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() }
                val stored = runBlocking { c.db.imports().summaries() }
                throw AssertionError("Not found: $text. Stored: $stored. On screen: ${shown.joinToString(" | ").take(1500)}")
            }
            compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())[i].performScrollToNode(matcher) }
            }
            compose.waitForIdle()
            Thread.sleep(200)
        }
    }
}