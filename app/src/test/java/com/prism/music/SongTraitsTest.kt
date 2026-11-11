package com.prism.music

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.SongTraits
import com.prism.music.data.TraitsSink
import com.prism.music.data.TraitsStore
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.data.stream.StreamResolver
import com.prism.music.playback.MixTempo
import com.prism.music.playback.MusicKey
import com.prism.music.playback.Tempo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.3: what's measured of songs goes into one table (`song_traits`) that smart playlists filter on: from every scan,
 * the whole song's measurement winning over DJ mode's quick one, and from what earlier versions kept in prefs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SongTraitsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, PrismDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.traits()

    @After fun close() = db.close()

    private val steady = Tempo(124.0, 6.0, 0, 200_000, 500, 199_000)
    private fun whole(id: String, energy: Float = 0.7f) = SongTraits(id, steady, MusicKey(9, minor = true), -8f, energy, whole = true)
    private fun quick(id: String) = SongTraits(id, Tempo(90.0, 2.0, 0, 0, 0, 0), null, -12f, 0.3f, whole = false)

    @Test fun aRowHoldsWhatWasMeasured() {
        val e = whole("a").entity()
        assertEquals(124.0, e.bpm!!, 1e-9)
        assertTrue(e.bpmSure)
        assertEquals("8A", e.camelot)
        assertEquals(-8f, e.lufs!!, 0f)
        assertEquals(0.7f, e.energy!!, 0f)
        val unsure = quick("b").entity()
        assertFalse("a beat under the steady mark isn't sure", unsure.bpmSure)
        assertNull(unsure.camelot)
        assertNull("no beat: no BPM", SongTraits("c", null, null, Float.NaN, null, whole = true).entity().bpm)
        assertNull("NaN loudness isn't kept", SongTraits("c", null, null, Float.NaN, null, whole = true).entity().lufs)
    }

    @Test fun theWholeSongsMeasurementWins() = runBlocking {
        runBlocking { TraitsStore(dao, this).record(whole("a")) }
        runBlocking { TraitsStore(dao, this).record(quick("a")) }
        assertTrue("a quick scan doesn't replace the whole song's", dao.get("a")!!.whole)
        runBlocking { TraitsStore(dao, this).record(quick("b")) }
        runBlocking { TraitsStore(dao, this).record(whole("b", energy = 0.9f)) }
        assertEquals("the whole song's replaces a quick one", 0.9f, dao.get("b")!!.energy!!, 0f)
        runBlocking { TraitsStore(dao, this).forget("b") }
        assertNull(dao.get("b"))
    }

    @Test fun theCopyFillsOnlyWhatsMissing() = runBlocking {
        val store = TraitsStore(dao, CoroutineScope(Dispatchers.Unconfined))
        dao.put(whole("newer", energy = 0.9f).entity())
        val read = { id: String -> if (id == "gone") null else whole(id, energy = 0.1f) }
        assertEquals("only the missing are read and copied", 1, store.copyMissing(listOf("newer", "old", "gone"), read))
        assertEquals("a newer row stays", 0.9f, dao.get("newer")!!.energy!!, 0f)
        assertNotNull(dao.get("old"))
        assertEquals("nothing to do when the table has them all", 0, store.copyMissing(listOf("newer", "old"), read))
    }

    /**
     * Review of 1.8.3: a restored history can hold more rows than the prefs list songs, but for other songs. The copy
     * went by the count and skipped, and Measure now couldn't help (the prefs say they're measured).
     */
    @Test fun theCopyGoesBySongsNotByCount() = runBlocking {
        val store = TraitsStore(dao, CoroutineScope(Dispatchers.Unconfined))
        (1..5).forEach { dao.put(whole("restored$it").entity()) }
        assertEquals(2, store.copyMissing(listOf("p1", "p2"), { whole(it) }))
        assertNotNull(dao.get("p1"))
        assertNotNull(dao.get("p2"))
    }

    /** Review of 1.8.3: a key under the clear mark is kept as a guess, but isn't a Camelot hour rules match. */
    @Test fun anUnclearKeyIsntFiltered() {
        val e = SongTraits("a", steady, MusicKey(9, minor = true, confidence = 0.01f), -8f, 0.5f, whole = true).entity()
        assertNull(e.camelot)
        assertNotNull("the guess stays", e.key)
    }

    @Test fun earlierVersionsMeasurementsReadFromThePrefs() {
        fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        prefs("mix_tempo").putString("w", steady.encode()).commit()
        prefs("song_loudness").putFloat("w", -9f).commit()
        prefs("song_key").putString("w", MusicKey(0, minor = false).encode()).commit()
        prefs("dj_quick").putString("q", "100.0|4.0|-11.0").commit()
        prefs("dj_quick").putString("q0", "0.0|0.0|NaN").commit()
        val recorded = ArrayList<SongTraits>()
        val settings = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val http = OkHttpClient()
        val mix = MixTempo(context, http, StreamResolver(context, http, settings, InnerTube(http, { settings.current })), traits = TraitsSink { recorded += it })

        assertEquals(setOf("w", "q", "q0"), mix.measuredIds())
        val w = mix.traitsFromPrefs("w")!!
        assertTrue(w.whole)
        assertEquals(124.0, w.tempo!!.bpm, 1e-9)
        assertEquals("8B", w.key!!.camelot)
        assertNotNull("energy from loudness and beat", w.energy)
        val q = mix.traitsFromPrefs("q")!!
        assertFalse(q.whole)
        assertEquals(100.0, q.entity().bpm!!, 1e-9)
        assertTrue(q.entity().bpmSure)
        assertEquals(-11f, q.lufs!!, 0f)
        val none = mix.traitsFromPrefs("q0")!!
        assertNull("no beat found", none.entity().bpm)
        assertNull("no loudness, no energy", none.energy)
        assertNull(mix.traitsFromPrefs("never"))
        assertTrue("nothing recorded by reading", recorded.isEmpty())
    }
}
