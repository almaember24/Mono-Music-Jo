package com.prism.music

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.prism.music.data.db.PrismDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every older database version upgrades to the current one without losing the listener's history. Each version's
 * schema is exported by Room into app/schemas; this builds a database exactly as that version left it (with a song,
 * a like and plays in it), opens it with the app's migrations, and checks the rows survived. Room itself refuses to
 * open it if a migration's result doesn't match the current schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    private val schemaDir = listOf("schemas", "app/schemas").map { File(it, "com.prism.music.data.db.PrismDatabase") }.first { it.isDirectory }
    private val versions = schemaDir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()

    @Test
    fun everyVersionUpgradesKeepingHistory() {
        val latest = versions.last()
        for (from in versions.dropLast(1)) {
            val context = RuntimeEnvironment.getApplication()
            val name = "migrate-from-$from.db"
            context.deleteDatabase(name)
            createAt(context.getDatabasePath(name), from)
            val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
                .addMigrations(*PrismDatabase.MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            runBlocking {
                val song = db.songs().get("song1")
                assertEquals("v$from: song kept", "Title", song?.title)
                assertTrue("v$from: like kept", song!!.liked)
                assertEquals("v$from: plays kept", 2, db.plays().count(0, Long.MAX_VALUE))
                assertEquals("v$from: play time kept", 200_000L, db.plays().totalMs(0, Long.MAX_VALUE))
                assertEquals("v$from: top song", "song1", db.plays().topSongs(0, Long.MAX_VALUE, 5).single().songId)
            }
            assertEquals(latest, db.openHelper.readableDatabase.version)
            db.close()
        }
    }

    /**
     * v8: songs get their main artist. A joint credit counts for its first artist (not as an artist of its own), a
     * file without an artist ("Unknown artist") counts for none, and genres guessed for such files go.
     */
    @Test
    fun v8CountsSongsUnderTheirMainArtist() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migrate-v8-artists.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        createAt(file, 7)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            fun song(id: String, artist: String, artistId: String?) = db.insert("songs", null, ContentValues().apply {
                put("id", id); put("title", "T $id"); put("artistName", artist); put("artistId", artistId)
                put("durationSec", 200); put("isVideo", 0); put("liked", 0); put("likedAt", 0L); put("lastPlayed", 0L); put("smartDownload", 0)
            })
            fun play(id: String, ms: Long) = db.insert("play_events", null, ContentValues().apply {
                put("songId", id); put("timestamp", 3_000L); put("playedMs", ms)
            })
            song("joint", "The Kid LAROI, Justin Bieber", "UCkid"); play("joint", 150_000)
            song("solo", "The Kid LAROI", "UCkid"); play("solo", 100_000)
            song("local:7", "Unknown artist", null); play("local:7", 500_000)
            db.insert("genres", null, ContentValues().apply { put("key", "song2:local:7"); put("genre", "Dance & electronic"); put("fetchedAt", 1L) })
            db.insert("genres", null, ContentValues().apply { put("key", "song2:joint"); put("genre", "Pop"); put("fetchedAt", 1L) })
        }
        val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        runBlocking {
            val artists = db.plays().topArtists(0, Long.MAX_VALUE, 10)
            assertEquals("joint credit counts for its main artist", listOf("The Kid LAROI", "Artist"), artists.map { it.artistName })
            assertEquals(250_000L, artists.first().ms)
            assertEquals("no 'Unknown artist'", 2, db.plays().artistCount(0, Long.MAX_VALUE))
            assertTrue("a file without an artist has none", db.songs().get("local:7")!!.toSong().artists.isEmpty())
            assertEquals("Unknown artist", db.songs().get("local:7")!!.toSong().artistText)
            assertEquals(listOf("The Kid LAROI", "Justin Bieber"), db.songs().get("joint")!!.toSong().artists.map { it.name })
            assertEquals("the guessed genre went", null, db.meta().genre("song2:local:7"))
            assertEquals("real genres stay", "Pop", db.meta().genre("song2:joint")?.genre)
        }
        db.close()
    }

    /**
     * v9 (1.8.1): imported histories get their own tables and the counted_plays view, and Prism's plays read through
     * the view exactly as from play_events.
     */
    @Test
    fun v9AddsImportedHistoryApart() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migrate-v9-imports.db"
        context.deleteDatabase(name)
        createAt(context.getDatabasePath(name), 8)
        val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        runBlocking {
            val prism = listOf("prism")
            assertEquals(db.plays().totalMs(0, Long.MAX_VALUE), db.counted().totalMs(prism, 0, Long.MAX_VALUE))
            assertEquals(db.plays().topSongs(0, Long.MAX_VALUE, 5), db.counted().topSongs(prism, 0, Long.MAX_VALUE, 5))
            db.imports().insertPlays(listOf(com.prism.music.data.db.ImportedPlayEntity(
                origin = "spotify", timestamp = 5_000L, playedMs = 60_000, title = "Title", artist = "Artist", songKey = "title|artist", matchedId = "song1",
            )))
            assertEquals("not counted unless asked", 200_000L, db.counted().totalMs(prism, 0, Long.MAX_VALUE))
            assertEquals(260_000L, db.counted().totalMs(prism + "spotify", 0, Long.MAX_VALUE))
            assertEquals(3, db.counted().topSongs(prism + "spotify", 0, Long.MAX_VALUE, 5).single().plays)
        }
        db.close()
    }

    /** v10 (1.8.3): what's measured of songs gets its own table, empty until the copy from the prefs fills it. */
    @Test
    fun v10AddsSongTraits() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migrate-v10-traits.db"
        context.deleteDatabase(name)
        createAt(context.getDatabasePath(name), 9)
        val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        runBlocking {
            assertEquals(0, db.traits().count())
            db.traits().put(com.prism.music.data.db.SongTraitsEntity("song1", 120.0, true, null, "8A", -9f, 0.6f, whole = true, measuredAt = 1L))
            assertEquals(120.0, db.traits().get("song1")!!.bpm!!, 1e-9)
            assertEquals("history kept", 2, db.plays().count(0, Long.MAX_VALUE))
        }
        db.close()
    }

    /** v11 (1.8.3): the listener's tags on songs, a table of their own. */
    @Test
    fun v11AddsSongTags() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migrate-v11-tags.db"
        context.deleteDatabase(name)
        createAt(context.getDatabasePath(name), 10)
        val db = Room.databaseBuilder(context, PrismDatabase::class.java, name)
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        runBlocking {
            assertEquals(emptyList<String>(), db.tags().names())
            db.tags().add(listOf(com.prism.music.data.db.SongTagEntity("gym", "song1", 1L)))
            assertEquals(listOf("gym"), db.tags().names())
            assertEquals("history kept", 2, db.plays().count(0, Long.MAX_VALUE))
        }
        db.close()
    }

    @Test
    fun currentSchemaIsExported() {
        // A version bump without its exported schema would leave the next migration untestable.
        val fresh = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PrismDatabase::class.java).allowMainThreadQueries().build()
        val current = fresh.openHelper.writableDatabase.version
        fresh.close()
        assertEquals(current, versions.last())
        assertEquals("one migration per version step", current - 1, PrismDatabase.MIGRATIONS.size)
    }

    /** Builds the database as version [version] of Prism created it, from the exported schema. */
    private fun createAt(file: File, version: Int) {
        val schema = Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject["database"]!!.jsonObject
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (entity in schema["entities"]!!.jsonArray) {
            val e = entity.jsonObject
            val table = e.str("tableName")
            db.execSQL(e.str("createSql").replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.str("createSql").replace("\${TABLE_NAME}", table)) }
        }
        // Views too (v9's counted_plays): a database from 9 on has them, and Room checks them after a migration.
        schema["views"]?.jsonArray?.forEach { v ->
            val view = v.jsonObject
            db.execSQL(view.str("createSql").replace("\${VIEW_NAME}", view.str("viewName")))
        }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = schema["version"]!!.jsonPrimitive.int
        db.insert("songs", null, ContentValues().apply {
            put("id", "song1"); put("title", "Title"); put("artistName", "Artist"); put("artistId", "UC1")
            put("durationSec", 200); put("isVideo", 0); put("liked", 1); put("likedAt", 5L); put("lastPlayed", 10L); put("smartDownload", 0)
        })
        for (t in listOf(1_000L, 2_000L)) db.insert("play_events", null, ContentValues().apply {
            put("songId", "song1"); put("timestamp", t); put("playedMs", 100_000L)
        })
        db.close()
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
}
