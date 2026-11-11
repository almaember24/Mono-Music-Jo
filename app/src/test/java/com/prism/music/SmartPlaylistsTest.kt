package com.prism.music

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.SmartField
import com.prism.music.data.SmartMatch
import com.prism.music.data.SmartPlaylists
import com.prism.music.data.SmartQuery
import com.prism.music.data.SmartRule
import com.prism.music.data.SmartRules
import com.prism.music.data.SmartSort
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.SongTagEntity
import com.prism.music.data.db.SongTraitsEntity
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smart playlists (1.8.3): rules made into one SQL query over the listener's songs, their measurements, tags and plays;
 * downloads, the phone's files and genres come in through a temporary table. Each rule, combined with all / any,
 * sorted and cut, and the count of songs the Sound rules can't see yet; quick on 10,000 songs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SmartPlaylistsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, PrismDatabase::class.java).allowMainThreadQueries().build()
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private val a = Song("a", "Alpha", listOf(ArtistRef("Ana Lane", "UCa")), AlbumRef("Morning", "MPa"))
    private val b = Song("b", "Bravo", listOf(ArtistRef("Bo", null)))
    private val c = Song("c", "Charlie", listOf(ArtistRef("Cee", null)))
    private val d = Song("d", "Delta", listOf(ArtistRef("Dee", null)))
    private val file = Song("${Song.LOCAL_PREFIX}1", "Echo file")
    private val down = Song("f", "Foxtrot download")
    private val stranger = Song("x", "Never mine")

    private var downloads = listOf(down)
    private var files = listOf(file)
    private var genres = mapOf("a" to "Pop", "c" to "Rock", "f" to "Pop")
    private val smart = SmartPlaylists(db, { downloads }, { files }, { songs -> genres.filterKeys { k -> songs.any { it.id == k } } })

    @Before fun seed() = runBlocking {
        db.songs().upsertAll(listOf(a.toEntity().copy(liked = true, likedAt = now - day), b.toEntity().copy(lastPlayed = now - 2 * day), c.toEntity(), d.toEntity(), stranger.toEntity()))
        // b: five plays, the first a month ago, the last two days ago; c: one, 100 days ago.
        listOf(30, 20, 10, 5, 2).forEach { db.plays().insert(PlayEventEntity(songId = "b", timestamp = now - it * day, playedMs = 60_000)) }
        db.plays().insert(PlayEventEntity(songId = "c", timestamp = now - 100 * day, playedMs = 60_000))
        db.tags().add(listOf(SongTagEntity("gym", "d", now), SongTagEntity("gym", "a", now)))
        db.traits().put(SongTraitsEntity("a", 128.0, true, "A minor", "8A", -8f, 0.8f, true, now))
        db.traits().put(SongTraitsEntity("b", 90.0, true, "E minor", "9A", -12f, 0.3f, true, now))
        db.traits().put(SongTraitsEntity("c", 140.0, true, "D major", "3B", -6f, 0.9f, true, now))
    }

    @After fun close() = db.close()

    private fun ids(vararg rules: SmartRule, match: SmartMatch = SmartMatch.ALL, sort: SmartSort = SmartSort.TITLE, limit: Int? = null) =
        runBlocking { smart.run(SmartRules(rules.toList(), match, sort, limit), now).songs.map { it.id } }

    @Test fun withoutRulesItsAllTheListenersSongs() {
        // Liked, played, tagged, downloaded or a file; never a song only passed by.
        assertEquals(listOf("a", "b", "c", "d", file.id, "f"), ids())
    }

    @Test fun soundRules() {
        assertEquals(listOf("a", "c"), ids(SmartRule(SmartField.BPM, min = 120.0, max = 150.0)))
        assertEquals("open-ended", listOf("b"), ids(SmartRule(SmartField.BPM, max = 100.0)))
        assertEquals(listOf("a"), ids(SmartRule(SmartField.KEY, text = "8a")))
        assertEquals("8A goes with 7A, 9A and 8B", listOf("a", "b"), ids(SmartRule(SmartField.GOES_WITH, text = "8A")))
        assertEquals(listOf("a", "c"), ids(SmartRule(SmartField.ENERGY, min = 0.7)))
        assertEquals(listOf("a", "c"), ids(SmartRule(SmartField.LOUDNESS, min = -9.0)))
        assertEquals(listOf("7A", "6A", "8A", "7B"), SmartQuery.goesWith("7A"))
        assertEquals("round the wheel", listOf("12B", "11B", "1B", "12A"), SmartQuery.goesWith("12B"))
    }

    @Test fun libraryRules() {
        assertEquals(listOf("a"), ids(SmartRule(SmartField.LIKED)))
        assertEquals(listOf("b", "c", "d", file.id, "f"), ids(SmartRule(SmartField.LIKED, not = true)))
        assertEquals(listOf("f"), ids(SmartRule(SmartField.DOWNLOADED)))
        assertEquals(listOf(file.id), ids(SmartRule(SmartField.ON_PHONE)))
        assertEquals(listOf("a", "d"), ids(SmartRule(SmartField.TAG, text = "GYM")))
        assertEquals(listOf("b", "c", file.id, "f"), ids(SmartRule(SmartField.TAG, not = true, text = "gym")))
        assertEquals(listOf("a"), ids(SmartRule(SmartField.ARTIST, text = "ana")))
        assertEquals(listOf("a"), ids(SmartRule(SmartField.ALBUM, text = "morn")))
        assertEquals("% is a letter here", emptyList<String>(), ids(SmartRule(SmartField.ARTIST, text = "%")))
        assertEquals(listOf("a", "f"), ids(SmartRule(SmartField.GENRE, text = "pop")))
    }

    /** Review of 1.8.3: songs with no row in the downloads/files/genres table, or no album, still count for "isn't". */
    @Test fun isntKeepsSongsTheFlagsTableDoesntList() {
        assertEquals(listOf("a", "b", "c", "d", file.id), ids(SmartRule(SmartField.DOWNLOADED, not = true)))
        assertEquals(listOf("a", "b", "c", "d", "f"), ids(SmartRule(SmartField.ON_PHONE, not = true)))
        assertEquals(listOf("b", "c", "d", file.id), ids(SmartRule(SmartField.GENRE, not = true, text = "pop")))
        assertEquals("songs without an album aren't in Morning either", listOf("b", "c", "d", file.id, "f"), ids(SmartRule(SmartField.ALBUM, not = true, text = "morn")))
    }

    /** Review of 1.8.3: each Genre rule tests its own genre, not "any genre a rule names". */
    @Test fun genreRulesAreEachTheirOwn() {
        assertEquals(listOf("c"), ids(SmartRule(SmartField.GENRE, text = "Rock"), SmartRule(SmartField.GENRE, not = true, text = "Pop")))
        assertEquals("a song has one genre", emptyList<String>(), ids(SmartRule(SmartField.GENRE, text = "rock"), SmartRule(SmartField.GENRE, text = "pop")))
        assertEquals(listOf("a", "c", "f"), ids(SmartRule(SmartField.GENRE, text = "rock"), SmartRule(SmartField.GENRE, text = "pop"), match = SmartMatch.ANY))
    }

    /**
     * Review of 1.8.3: the table was refilled only when the flags' hash or count changed. A download moving from b to d,
     * both Jazz, kept both ("b" 98 xor 5 + "d" 100 xor 4 = 98 xor 4 + 100 xor 5): the old table, b still downloaded.
     */
    @Test fun aDownloadMovingBetweenTwoSongsIsSeen() {
        genres = genres + mapOf("b" to "Jazz", "d" to "Jazz")
        val rules = arrayOf(SmartRule(SmartField.GENRE, text = "jazz"), SmartRule(SmartField.DOWNLOADED))
        downloads = listOf(down, b)
        assertEquals(listOf("b"), ids(*rules))
        downloads = listOf(down, d)
        assertEquals(listOf("d"), ids(*rules))
    }

    @Test fun listeningRules() {
        assertEquals(listOf("b"), ids(SmartRule(SmartField.PLAYS, min = 2.0)))
        assertEquals(listOf("a", "c", "d", file.id, "f"), ids(SmartRule(SmartField.PLAYS, max = 1.0)))
        assertEquals(listOf("a", "d", file.id, "f"), ids(SmartRule(SmartField.NEVER_PLAYED)))
        assertEquals(listOf("b"), ids(SmartRule(SmartField.LAST_PLAYED, min = 7.0)))
        assertEquals("played, but not lately", listOf("c"), ids(SmartRule(SmartField.LAST_PLAYED, not = true, min = 7.0)))
        assertEquals("liked yesterday", listOf("a"), ids(SmartRule(SmartField.ADDED, min = 7.0)))
        assertEquals(listOf("b", "c"), ids(SmartRule(SmartField.ADDED, not = true, min = 7.0)))
    }

    @Test fun allOrAny() {
        val fast = SmartRule(SmartField.BPM, min = 120.0)
        val liked = SmartRule(SmartField.LIKED)
        assertEquals(listOf("a"), ids(fast, liked))
        assertEquals(listOf("a", "c"), ids(fast, liked, match = SmartMatch.ANY))
        assertEquals(listOf("a", "b", "c"), ids(fast, SmartRule(SmartField.PLAYS, min = 1.0), match = SmartMatch.ANY))
    }

    @Test fun sortedAndCut() {
        assertEquals(listOf("b", "c"), ids(sort = SmartSort.MOST_PLAYED, limit = 2))
        assertEquals(listOf("b", "a", "c"), ids(SmartRule(SmartField.BPM, min = 1.0), sort = SmartSort.BPM))
        assertEquals(listOf("c", "a", "b"), ids(SmartRule(SmartField.BPM, min = 1.0), sort = SmartSort.ENERGY))
        assertEquals("liked or first played, newest first", listOf("a", "b", "c"), ids(SmartRule(SmartField.ADDED, min = 365.0, not = false), sort = SmartSort.RECENTLY_ADDED))
        val random = ids(sort = SmartSort.RANDOM, limit = 4)
        assertEquals(4, random.size)
        assertEquals("the same all day", random, ids(sort = SmartSort.RANDOM, limit = 4))
    }

    @Test fun songsNotMeasuredYetAreCounted() = runBlocking {
        val fast = SmartRules(listOf(SmartRule(SmartField.BPM, min = 120.0)))
        assertEquals("d, the file and the download have no measurement", 3, smart.run(fast, now).unmeasured)
        val fastAndTagged = SmartRules(listOf(SmartRule(SmartField.BPM, min = 120.0), SmartRule(SmartField.TAG, text = "gym")))
        assertEquals("with all: only those the other rules keep", 1, smart.run(fastAndTagged, now).unmeasured)
        assertEquals(listOf("d"), smart.unmeasuredIds(fastAndTagged, 10, now))
        assertEquals("no Sound rule, nothing to measure", 0, smart.run(SmartRules(listOf(SmartRule(SmartField.LIKED))), now).unmeasured)
    }

    @Test fun followsWhatThePhoneHas() {
        // The temporary table is kept between runs: it must follow downloads and files that come and go.
        assertEquals(listOf("f"), ids(SmartRule(SmartField.DOWNLOADED)))
        downloads = listOf(down, b)
        assertEquals(listOf("b", "f"), ids(SmartRule(SmartField.DOWNLOADED)))
        downloads = emptyList()
        assertEquals(emptyList<String>(), ids(SmartRule(SmartField.DOWNLOADED)))
        files = emptyList()
        assertEquals("a gone file isn't on the phone", emptyList<String>(), ids(SmartRule(SmartField.ON_PHONE)))
        downloads = listOf(down)
        assertEquals(listOf("f"), ids(SmartRule(SmartField.DOWNLOADED)))
    }

    @Test fun quickOnTenThousandSongs() = runBlocking {
        val many = (0 until 10_000).map { i -> Song("s$i", "Song $i", listOf(ArtistRef("Artist ${i % 300}", null))) }
        db.songs().upsertAll(many.map { it.toEntity().copy(liked = it.id.hashCode() % 3 == 0, likedAt = now - (it.id.hashCode() % 500) * day, lastPlayed = now) })
        many.chunked(1000).forEach { part -> part.forEachIndexed { i, s -> if (i % 2 == 0) db.traits().put(SongTraitsEntity(s.id, 80.0 + i % 90, true, null, "${i % 12 + 1}A", -10f, (i % 100) / 100f, true, now)) } }
        many.take(3000).forEachIndexed { i, s -> db.plays().insert(PlayEventEntity(songId = s.id, timestamp = now - (i % 200) * day, playedMs = 60_000)) }
        downloads = many.take(2000)
        val rules = SmartRules(
            listOf(SmartRule(SmartField.BPM, min = 110.0, max = 140.0), SmartRule(SmartField.LIKED), SmartRule(SmartField.LAST_PLAYED, min = 90.0), SmartRule(SmartField.ENERGY, min = 0.3)),
            SmartMatch.ANY, SmartSort.MOST_PLAYED, 50,
        )
        smart.run(rules, now)
        val times = (1..5).map { val t = System.nanoTime(); smart.run(rules, now); (System.nanoTime() - t) / 1_000_000 }
        println("smart playlist on 10,000 songs: ${times.joinToString()} ms")
        assertTrue("under 100 ms: $times", times.min() < 100)
    }
}
