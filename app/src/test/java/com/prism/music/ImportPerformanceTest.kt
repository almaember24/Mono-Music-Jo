package com.prism.music

import androidx.room.Room
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.imports.CountedPlays
import com.prism.music.data.imports.HistoryImporter
import com.prism.music.data.imports.ImportSource
import com.prism.music.data.imports.ParsedPlay
import com.prism.music.data.imports.SpotifyExport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * A ten-year Spotify history (100,000 plays of 8,000 songs, ~30 MB of JSON) read and stored as Bring your history does:
 * fast, and the file never in memory whole. The plan's target is under 2 minutes and under 30 MB more on the budget
 * phone; on this JVM (Robolectric's native SQLite) it must stay under [MAX_SECONDS], holding under [MAX_MB] more at\n * any point (measured after a collection: what the import keeps, not the garbage it leaves).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class ImportPerformanceTest {
    private val plays = 100_000
    private val songs = 8_000

    private fun write(file: File) {
        val start = Instant.parse("2016-01-01T00:00:00Z").toEpochMilli()
        file.bufferedWriter().use { w ->
            w.write("[")
            for (i in 0 until plays) {
                val s = (i * 7919L % songs).toInt()
                val ts = Instant.ofEpochMilli(start + i * 3_150_000L).toString()
                if (i > 0) w.write(",\n")
                w.write("""{"ts":"$ts","platform":"android","ms_played":${120_000 + s % 120_000},"conn_country":"US","ip_addr":"203.0.113.7",""")
                w.write(""""master_metadata_track_name":"Song number $s","master_metadata_album_artist_name":"Artist ${s % 900}","master_metadata_album_album_name":"Album ${s % 2000}",""")
                w.write(""""spotify_track_uri":"spotify:track:${"%022d".format(s)}","episode_name":null,"episode_show_name":null,"spotify_episode_uri":null,""")
                w.write(""""reason_start":"trackdone","reason_end":"${if (i % 5 == 0) "fwdbtn" else "trackdone"}","shuffle":false,"skipped":${i % 5 == 0},"offline":false,"offline_timestamp":null,"incognito_mode":false}""")
            }
            w.write("]")
        }
    }

    private fun usedMb(): Double { val r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) / 1_048_576.0 }

    @Test fun aTenYearHistoryImportsQuicklyInLittleMemory() {
        val file = File.createTempFile("Streaming_History_Audio_2016-2026_0", ".json").also { it.deleteOnExit() }
        write(file)
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PrismDatabase::class.java).allowMainThreadQueries().build()
        val importer = HistoryImporter(db)
        System.gc(); Thread.sleep(200)
        val base = usedMb()
        var peak = base
        val started = System.nanoTime()
        val stats = runBlocking {
            val session = importer.session(ImportSource.SPOTIFY)
            val batch = ArrayList<ParsedPlay>(HistoryImporter.BATCH)
            var n = 0
            SpotifyExport.read("Streaming_History_Audio_2016-2026_0.json", file.inputStream()) { p ->
                batch += p
                if (batch.size >= HistoryImporter.BATCH) {
                    runBlocking { batch.forEach { session.add(it) } }
                    batch.clear()
                }
                // What's still held (garbage aside), every 20,000 plays: the import's real footprint.
                if (++n % 20_000 == 0) { System.gc(); peak = maxOf(peak, usedMb()) }
            }
            batch.forEach { session.add(it) }
            session.finish()
        }
        val seconds = (System.nanoTime() - started) / 1e9
        System.gc(); peak = maxOf(peak, usedMb())
        val counted = runBlocking { CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays()).count(0, Long.MAX_VALUE) }
        println("[import] ${"%,d".format(plays)} plays (${file.length() / 1_048_576} MB) in ${"%.1f".format(seconds)} s; heap +${"%.0f".format(peak - base)} MB at most; $stats")
        assertEquals(plays, stats.stored)
        assertEquals(songs, stats.songs)
        assertEquals(plays, counted)
        assertTrue("took ${"%.1f".format(seconds)} s", seconds < MAX_SECONDS)
        assertTrue("heap grew ${"%.0f".format(peak - base)} MB", peak - base < MAX_MB)

        // The same download brought again (a "fresh" one with nothing new): the preview and the import both pass every
        // play over, quickly and in little memory.
        System.gc(); Thread.sleep(200)
        val base2 = usedMb()
        var peak2 = base2
        val previewStarted = System.nanoTime()
        val fresh = runBlocking {
            val n = importer.novelty(ImportSource.SPOTIFY)
            SpotifyExport.read("Streaming_History_Audio_2016-2026_0.json", file.inputStream()) { n.add(it) }
            n.result()
        }
        val previewSeconds = (System.nanoTime() - previewStarted) / 1e9
        val againStarted = System.nanoTime()
        val again = runBlocking {
            val session = importer.session(ImportSource.SPOTIFY)
            val batch = ArrayList<ParsedPlay>(HistoryImporter.BATCH)
            var n = 0
            SpotifyExport.read("Streaming_History_Audio_2016-2026_0.json", file.inputStream()) { p ->
                batch += p
                if (batch.size >= HistoryImporter.BATCH) { runBlocking { batch.forEach { session.add(it) } }; batch.clear() }
                if (++n % 20_000 == 0) { System.gc(); peak2 = maxOf(peak2, usedMb()) }
            }
            batch.forEach { session.add(it) }
            session.finish()
        }
        val againSeconds = (System.nanoTime() - againStarted) / 1e9
        System.gc(); peak2 = maxOf(peak2, usedMb())
        println("[import] again: preview ${"%.1f".format(previewSeconds)} s, import ${"%.1f".format(againSeconds)} s; heap +${"%.0f".format(peak2 - base2)} MB at most; $again")
        val countedAgain = runBlocking { CountedPlays(listOf(ImportSource.SPOTIFY), db.counted(), db.plays()).count(0, Long.MAX_VALUE) }
        db.close()
        assertEquals("nothing new in it", 0, fresh.plays)
        assertEquals(0, again.stored)
        assertEquals(plays, again.alreadyThere)
        assertEquals(0, again.newSongs)
        assertEquals(plays, countedAgain)
        assertTrue("preview again took ${"%.1f".format(previewSeconds)} s", previewSeconds < MAX_REPEAT_SECONDS)
        assertTrue("import again took ${"%.1f".format(againSeconds)} s", againSeconds < MAX_REPEAT_SECONDS)
        assertTrue("heap grew ${"%.0f".format(peak2 - base2)} MB again", peak2 - base2 < MAX_MB)
    }

    companion object {
        const val MAX_SECONDS = 60.0
        /** A repeat import stores nothing: it must be quicker than the first. */
        const val MAX_REPEAT_SECONDS = 30.0
        const val MAX_MB = 30.0
    }
}
