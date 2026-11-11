package com.prism.music

import android.app.Application
import com.prism.music.data.artistSongsOnPhone
import com.prism.music.data.foryou.RadioTune
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A database that can't be read (#103's CursorWindow failure on a low-memory phone, here its tables gone) mustn't close
 * Prism from where nothing catches it: the service's scope and a page's produceState have no handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BadReadsTest {
    private val c by lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val song = Song("abc", "Some song", listOf(ArtistRef("Someone")), durationSec = 200)

    private fun breakTheDatabase() {
        val db = c.db.openHelper.writableDatabase
        listOf("play_events", "songs", "imported_plays").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
    }

    @Test fun aTunedRadioGoesOnNotKnowingWhatsBeenHeard() {
        breakTheDatabase()
        val signals = runBlocking { c.discover.radioSignals(listOf(song), RadioTune(newness = 0.5f)) }
        assertNull("what it can't read stays unknown, and doesn't move a song", signals.heard(song))
    }

    @Test fun anArtistPageStillOpens() {
        breakTheDatabase()
        assertEquals(emptyList<Song>(), runBlocking { c.artistSongsOnPhone("UCsomeone", "Someone") }.filter { it.id == song.id })
    }

    @Test fun aReadsFailureHasItsFallbackAndACancellationStaysOne() {
        assertEquals("kept", com.prism.music.data.readOrElse<String>("a test", "kept") { throw android.database.sqlite.SQLiteException("no") })
        assertEquals("read", com.prism.music.data.readOrElse("a test", "kept") { "read" })
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            com.prism.music.data.readOrElse("a test", "kept") { throw kotlinx.coroutines.CancellationException("stop") }
        }
    }
}
