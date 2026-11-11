package com.prism.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.SongVersions
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live, so only with `record=true` (it plays real audio from YouTube): 1.7.9's check, "Woke Up Older" by The Wonder
 * Years played as another version and then back as its own gave a playback error and wouldn't play again. Picked
 * while the song is still loading, both ways, it plays on each time.
 */
@Live
@RunWith(AndroidJUnit4::class)
class SongVersionLiveTest {
    @Test fun aSongPlaysOnAfterAnotherVersionAndBack() = runBlocking {
        assumeTrue("live test: run with record=true", InstrumentationRegistry.getArguments().getString("record") == "true")
        Fixture.ready()
        Fixture.launch()
        val c = Fixture.container
        val errors = mutableListOf<String>()
        val watch = launch(Dispatchers.Main) { c.player.errors.collect { errors += it } }
        suspend fun pos() = withContext(Dispatchers.Main) { c.player.position }
        suspend fun playsOn(what: String, from: Long) {
            val end = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < end && pos() < from + 4_000) kotlinx.coroutines.delay(200)
            assertTrue("$what: plays on from $from ms (now ${pos()} ms; errors $errors)", pos() >= from + 4_000)
        }
        val (song, others) = withContext(Dispatchers.IO) {
            val all = c.ytm.search("Woke Up Older The Wonder Years", SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }
            val song = all.first { it.title.equals("Woke Up Older", ignoreCase = true) && !it.isVideo }
            song to SongVersions.candidates(song, all + c.ytm.search("Woke Up Older The Wonder Years", SearchFilter.VIDEOS).items.filterIsInstance<SongItem>().map { it.song }).take(4)
        }
        android.util.Log.i("PrismTest", "song ${song.id} ${song.durationSec}s; versions ${others.joinToString { "${it.id} ${it.durationSec}s video=${it.isVideo} ${it.artistText}" }}")
        withContext(Dispatchers.Main) {
            c.player.playQueue(listOf(song), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.SINGLE, "test"))
        }
        playsOn("its own recording", 0)
        // Picked while the song is still loading (it's loaded whole), as a listener does a few seconds in, then back.
        for (other in others) {
            c.pickVersion(song, SongVersions.Pick(other.id, "test"))
            playsOn("as ${other.id}", pos())
            c.pickVersion(song, null)
            playsOn("back from ${other.id}", pos())
            assertEquals(song.id, c.player.currentSong.value?.id)
        }
        assertTrue("no playback errors: $errors", errors.isEmpty())
        watch.cancel()
    }

    /**
     * The same with the song downloaded (Smart Downloads keep a favourite's songs): a pick fetches the download again as
     * the version, and going back while that download is still coming down left the version's part under the song,
     * read before the stream.
     */
    @Test fun aDownloadedSongPlaysOnAfterAnotherVersionAndBack() = runBlocking {
        assumeTrue("live test: run with record=true", InstrumentationRegistry.getArguments().getString("record") == "true")
        Fixture.ready()
        Fixture.container.settings.editNow { it[androidx.datastore.preferences.core.booleanPreferencesKey("download_wifi_only")] = false }
        Fixture.launch()
        val c = Fixture.container
        val errors = mutableListOf<String>()
        val watch = launch(Dispatchers.Main) { c.player.errors.collect { errors += it } }
        suspend fun pos() = withContext(Dispatchers.Main) { c.player.position }
        suspend fun playsOn(what: String, from: Long) {
            val end = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < end && pos() < from + 4_000) kotlinx.coroutines.delay(200)
            assertTrue("$what: plays on from $from ms (now ${pos()} ms; errors $errors)", pos() >= from + 4_000)
        }
        val (song, other) = withContext(Dispatchers.IO) {
            val all = c.ytm.search("Woke Up Older The Wonder Years", SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }
            val song = all.first { it.title.equals("Woke Up Older", ignoreCase = true) && !it.isVideo }
            song to SongVersions.candidates(song, all).first()
        }
        c.downloads.download(song)
        val end = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < end && !c.downloads.isDownloaded(song.id)) kotlinx.coroutines.delay(500)
        assertTrue("downloaded", c.downloads.isDownloaded(song.id))
        withContext(Dispatchers.Main) {
            c.player.playQueue(listOf(song), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.SINGLE, "test"))
        }
        playsOn("its own recording", 0)
        c.pickVersion(song, SongVersions.Pick(other.id, "test"))
        playsOn("as ${other.id}", pos())
        // Back while the version's download is still coming down.
        c.pickVersion(song, null)
        playsOn("back as itself", pos())
        kotlinx.coroutines.delay(5_000)
        playsOn("still as itself", pos())
        assertEquals(song.id, c.player.currentSong.value?.id)
        assertTrue("no playback errors: $errors", errors.isEmpty())
        watch.cancel()
    }
}
