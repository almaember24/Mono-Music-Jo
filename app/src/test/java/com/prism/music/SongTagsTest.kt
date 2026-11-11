package com.prism.music

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.TagStore
import com.prism.music.data.TagsFile
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tags (1.8.3): the listener's own labels on songs. Added to one or many, taken off, renamed (joining a tag of that name),
 * deleted; a name differing only in case is the same tag; a backup carries them with their songs, and a restore of the
 * history alone keeps this phone's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SongTagsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = Room.inMemoryDatabaseBuilder(context, PrismDatabase::class.java).allowMainThreadQueries().build()
    private val dir = File(context.filesDir, "tags-test").apply { deleteRecursively(); mkdirs() }
    private val store = TagStore(db, scope, dir)

    @After fun close() { db.close(); dir.deleteRecursively() }

    private val a = Song("a", "Alpha", listOf(ArtistRef("Ana", "UCa")), thumbnail = "https://a.jpg")
    private val b = Song("b", "Bravo", listOf(ArtistRef("Bo", null)), thumbnail = "https://b.jpg")
    private val c = Song("c", "Charlie")
    private val file = Song("${Song.LOCAL_PREFIX}7", "On the phone")
    private val episode = Song("rss:1", "An episode")

    private fun names() = runBlocking { db.tags().summaries().map { it.tag } }
    private fun songsOf(tag: String) = runBlocking { store.songs(tag).first().map { it.id } }

    @Test fun aTagTakesOneOrManyAndShowsTheirSongs() = runBlocking {
        assertEquals(1, store.add("gym", listOf(a)))
        Thread.sleep(5)
        assertEquals("only the new ones count", 2, store.add("gym", listOf(a, b, file)))
        assertEquals("newest tagged first", listOf("b", file.id, "a"), songsOf("gym"))
        assertEquals("episodes from a feed aren't tagged", 0, store.add("gym", listOf(episode)))
        // Its songs are kept with their names and covers, for the tag's page.
        assertEquals("Alpha", db.songs().get("a")!!.title)
        val s = db.tags().summaries().single()
        assertEquals(3, s.songs)
        assertEquals("the newest song's cover", "https://b.jpg", s.thumbnail)
        assertEquals(listOf("gym"), store.tagsOf("a").first())
    }

    @Test fun aNameDifferingOnlyInCaseIsTheSameTag() = runBlocking {
        store.add("Rainy day", listOf(a))
        store.add("  rainy   DAY ", listOf(b))
        assertEquals(listOf("Rainy day"), names())
        assertEquals(setOf("a", "b"), songsOf("Rainy day").toSet())
    }

    @Test fun takingATagOffOneOrMany() = runBlocking {
        store.add("gym", listOf(a, b, c))
        store.add("chill", listOf(a))
        assertEquals(2, store.remove("gym", listOf("a", "b")))
        assertEquals(listOf("c"), songsOf("gym"))
        assertEquals("another tag stays", listOf("a"), songsOf("chill"))
        store.remove("gym", listOf("c"))
        assertEquals("a tag with no songs is gone", listOf("chill"), names())
    }

    @Test fun renamingAndDeleting() = runBlocking {
        store.add("gym", listOf(a, b))
        assertEquals("Workout", store.rename("gym", "Workout"))
        assertEquals(listOf("Workout"), names())
        assertEquals(setOf("a", "b"), songsOf("Workout").toSet())
        // Renamed to a tag that's there already: they join (a song in both is there once).
        store.add("run", listOf(b, c))
        assertEquals("the name it joined", "run", store.rename("Workout", "RUN"))
        assertEquals(listOf("run"), names())
        assertEquals(setOf("a", "b", "c"), songsOf("run").toSet())
        assertNull("an empty name renames nothing", store.rename("run", "   "))
        store.delete("run")
        assertTrue(names().isEmpty())
        assertEquals("the songs themselves stay", "Bravo", db.songs().get("b")!!.title)
    }

    @Test fun namesAreTidied() {
        assertEquals("rainy day", TagStore.clean("  rainy \t day "))
        assertNull(TagStore.clean("   "))
        assertEquals(TagStore.MAX_LENGTH, TagStore.clean("x".repeat(80))!!.length)
    }

    @Test fun aBackupCarriesTheTagsWithTheirSongs() = runBlocking {
        store.add("gym", listOf(a, b))
        store.add("chill", listOf(a))
        val text = store.exportJson(store.export())
        // Another phone, that never had these songs: the tags come with them.
        val other = Room.inMemoryDatabaseBuilder(context, PrismDatabase::class.java).allowMainThreadQueries().build()
        try {
            val there = TagStore(other, scope, dir)
            there.add("old", listOf(c))
            there.replaceWith(InnerTube.json.decodeFromString(TagsFile.serializer(), text))
            assertEquals(setOf("gym", "chill"), other.tags().summaries().map { it.tag }.toSet())
            assertEquals("in the same order", listOf("a", "b"), there.songs("gym").first().map { it.id })
            assertEquals("Ana", there.songs("chill").first().single().artists.single().name)
        } finally { other.close() }
    }

    @Test fun aRestoredBackupsTagsAreReadInOnce() = runBlocking {
        store.add("gym", listOf(a))
        val backup = store.exportJson(store.export())
        store.delete("gym")
        store.add("mine", listOf(b))
        File(dir, TagStore.PENDING).writeText(backup)
        store.readPending()
        assertEquals(listOf("gym"), names())
        assertFalse("read once", File(dir, TagStore.PENDING).exists())
        store.readPending()
        assertEquals(listOf("gym"), names())
    }

    /** Before Room opens it (a restore of the history alone): the tags read straight from the file. */
    @Test fun theTagsReadFromADatabaseFile() {
        val name = "tags-read.db"
        context.deleteDatabase(name)
        val onDisk = Room.databaseBuilder(context, PrismDatabase::class.java, name).allowMainThreadQueries().build()
        runBlocking { TagStore(onDisk, scope, dir).add("gym", listOf(a, b)) }
        onDisk.close()
        val read = TagStore.readFrom(context.getDatabasePath(name))!!
        assertEquals(listOf("gym"), read.tags.map { it.tag })
        assertEquals(setOf("Alpha", "Bravo"), read.tags.single().songs.map { it.song.title }.toSet())
        assertNull("not a database", TagStore.readFrom(File(dir, "nothing.db").apply { writeText("old history") }))
    }
}
