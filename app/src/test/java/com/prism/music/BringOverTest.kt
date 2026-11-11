package com.prism.music

import android.content.SharedPreferences
import androidx.room.Room
import com.prism.music.connect.BringSource
import com.prism.music.connect.BringTransfer
import com.prism.music.connect.HLink
import com.prism.music.connect.HandoffExpect
import com.prism.music.connect.HandoffLocal
import com.prism.music.connect.HandoffPeer
import com.prism.music.connect.HandoffRequest
import com.prism.music.connect.HandoffResult
import com.prism.music.connect.HandoffSnapshot
import com.prism.music.data.Backup
import com.prism.music.data.BringOver
import com.prism.music.data.FavoriteArtist
import com.prism.music.data.PrismLibrary
import com.prism.music.data.SavedAlbum
import com.prism.music.data.db.GenreEntity
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.toEntity
import com.prism.music.data.foryou.Excluded
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * "Bring your Prism" (1.8.0), the data: a phone's listening (the release check's seeded size: 1,253 likes, 1,426
 * plays, an 1,100-song Prism playlist) gathered, sent over a real loopback link and merged into a TV that already has
 * some of its own: likes and plays by song and time without doubles, playlists by id, the TV's own choices kept,
 * nothing of the phone's files, and bringing it twice the same as once. A broken bundle changes nothing.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class BringOverTest {
    private val app = RuntimeEnvironment.getApplication()
    private val phoneDb = Room.inMemoryDatabaseBuilder(app, PrismDatabase::class.java).allowMainThreadQueries().build()
    private val tvDb = Room.inMemoryDatabaseBuilder(app, PrismDatabase::class.java).allowMainThreadQueries().build()
    private val phonePrefs = { name: String -> app.getSharedPreferences("phone_$name", 0) }
    private val tvPrefs = { name: String -> app.getSharedPreferences("tv_$name", 0) }
    private val dirs = ArrayList<File>()
    private fun dir() = File.createTempFile("lib", "").apply { delete(); mkdirs() }.also { dirs += it }

    @After fun close() { phoneDb.close(); tvDb.close(); dirs.forEach { it.deleteRecursively() } }

    private fun id(i: Int) = "s" + i.toString().padStart(10, '0')
    private fun song(i: Int) = Song(
        id(i), "Song $i", listOf(ArtistRef("Artist ${i % 40}", "UC" + (i % 40).toString().padStart(22, '0'))),
        AlbumRef("Album ${i % 90}", "MPREb_${i % 90}"), 180 + i % 120, "https://lh3.googleusercontent.com/cover$i",
    )
    private val base = 1_760_000_000_000L
    private val localSong = Song("local:42", "My Demo Tape", listOf(ArtistRef("Me")))

    /** The release check's seeded phone: 1,253 likes, 1,426 plays, a local file played and liked. */
    private fun seedPhone(): PrismLibrary = runBlocking {
        val songs = phoneDb.songs()
        (0 until 1_253).forEach { i -> songs.upsert(song(i).toEntity().copy(liked = true, likedAt = base + i * 1_000L, lastPlayed = base + i)) }
        (1_253 until 1_600).forEach { i -> songs.upsert(song(i).toEntity().copy(lastPlayed = base + i)) }
        songs.upsert(localSong.toEntity().copy(liked = true, likedAt = base))
        (0 until 1_426).forEach { i ->
            phoneDb.plays().insert(PlayEventEntity(songId = id((i * 7) % 1_600), timestamp = base + i * 60_000L, playedMs = 150_000, source = if (i % 9 == 0) "dj" else null))
        }
        phoneDb.plays().insert(PlayEventEntity(songId = localSong.id, timestamp = base + 5, playedMs = 90_000))
        (0 until 40).forEach { a -> phoneDb.meta().putGenre(GenreEntity("artist3:artist $a", if (a % 2 == 0) "Rock" else "Pop", base)) }
        phoneDb.meta().putGenre(GenreEntity("song2:local:42", "Lo-fi", base))
        // Choices kept in saved preferences.
        val favorites = ListSerializer(FavoriteArtist.serializer())
        phonePrefs("artist_prefs").edit().putString("favorites", InnerTube.json.encodeToString(favorites, (0 until 5).map { FavoriteArtist("UCfav$it", "Fav $it", null, base + it) }))
            .putString("sig:UCfav1", "AUTOGRAPH").commit()
        phonePrefs("song_versions").edit().putString(id(3), "${id(1_500)}\tArtist 3 · Live").commit()
        phonePrefs("lyric_offsets").edit().putLong(id(1), 400).putLong(id(2), -250).putLong("local:42", 900).commit()
        phonePrefs("best_part_mine").edit().putLong(id(4), 61_000).commit()
        phonePrefs("exclusions").edit().putString("artists", InnerTube.json.encodeToString(ListSerializer(Excluded.serializer()), listOf(Excluded("UCkids", "Kids Songs")))).commit()
        phonePrefs("dislikes").edit().putStringSet("ids", setOf(id(1_590), id(1_591))).commit()
        // Prism's own playlists: "Everything I like" (1,100 songs and a file), "Road trip" in the folder "Weekend".
        PrismLibrary(dir()).apply {
            create("Everything I like", (0 until 1_100).map(::song) + localSong)
            val road = create("Road trip", (1_200 until 1_230).map(::song))
            createFolder("Weekend", road)
            saveAlbum(SavedAlbum("MPREb_album1", "Album One", "Artist 1 · 2024", "https://lh3.googleusercontent.com/a1", "OLAK5uy_one", base))
        }
    }

    /** A TV with some listening of its own (5 of its likes and 5 of its plays are the phone's too). */
    private fun seedTv(): PrismLibrary = runBlocking {
        (1_248 until 1_258).forEach { i -> tvDb.songs().upsert(song(i).toEntity().copy(liked = true, likedAt = base - 10_000)) }
        (0 until 20).forEach { i ->
            val phoneOne = i < 5
            tvDb.plays().insert(PlayEventEntity(songId = if (phoneOne) id((i * 7) % 1_600) else id(1_248 + i % 10), timestamp = if (phoneOne) base + i * 60_000L else base - i * 1_000L, playedMs = 150_000))
        }
        // Rows for the phone's songs the TV played (a play of a song the TV knows).
        (0 until 5).forEach { i -> tvDb.songs().upsert(song((i * 7) % 1_600).toEntity()) }
        tvPrefs("lyric_offsets").edit().putLong(id(1), -1_000).commit()
        tvPrefs("dislikes").edit().putStringSet("ids", setOf(id(1_590), id(7))).commit()
        PrismLibrary(dir()).apply { create("TV mix", (1_248 until 1_253).map(::song)) }
    }

    /** The TV's side of the link, merging with the real code into the TV's database, preferences and library. */
    private class Tv(val merge: (File) -> String?) : HandoffLocal {
        override val name = "Living room TV"
        override val device = "tv-1"
        override val isTv = true
        val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun secretFor(device: String) = keys[device]
        override fun savePairing(device: String, name: String, secret: String) { keys[device] = secret }
        override fun forget(device: String) { keys.remove(device) }
        override fun showCode(code: String?, from: String) = Unit
        override fun pairingCode(from: String): String? = null
        override fun snapshot(): HandoffSnapshot? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? = null
        override fun stepAside() = Unit
        override fun bringStarting(from: String, size: Long): String? = null
        override fun bringArrived(file: File, from: String): String? = merge(file)
    }

    private class Phone : HandoffLocal {
        override val name = "Alex's Pixel"
        override val device = "phone-1"
        val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun secretFor(device: String) = keys[device]
        override fun savePairing(device: String, name: String, secret: String) { keys[device] = secret }
        override fun forget(device: String) { keys.remove(device) }
        override fun showCode(code: String?, from: String) = Unit
        override fun snapshot(): HandoffSnapshot? = null
        override fun takeOver(s: HandoffSnapshot, from: String): String? = null
        override fun stepAside() = Unit
    }

    private fun send(tv: Tv, phone: Phone, bundle: File): HandoffResult {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val t = thread { runCatching { server.accept().use { s -> HLink(s).use { HandoffPeer(tv).serve(it) } } } }
        try {
            return HLink(Socket(InetAddress.getLoopbackAddress(), server.localPort)).use { link ->
                HandoffPeer(phone).request(link, HandoffRequest.Bring(BringSource.of(bundle)), HandoffExpect(device = tv.device)) { null }
            }
        } finally { t.join(30_000); server.close() }
    }

    @Test fun theSeededLibraryComesOverWholeAndTwiceIsOnce() {
        val phoneLib = seedPhone()
        val tvLib = seedTv()
        // The phone gathers it (as Backup.bringBundle does) into the backup's format.
        var t0 = System.nanoTime()
        val content = runBlocking { BringOver.collect(phoneDb, phonePrefs, phoneLib.forBringing(), null) }
        val bundle = File.createTempFile("bring", ".zip").also { dirs += it }
        bundle.outputStream().buffered().use { Backup.writeBring(it, content, app.packageName) }
        val gatherMs = (System.nanoTime() - t0) / 1_000_000
        val raw = BringOver.historyJson(content.history).length + BringOver.prefsJson(content.prefs).length + (content.library?.length ?: 0)
        println("bring bundle: ${content.history.songs.size} songs, ${content.history.plays.size} plays, ${content.history.genres.size} genres; " +
            "$raw bytes of JSON, ${bundle.length()} bytes zipped (${BringTransfer.chunksFor(bundle.length())} chunks); gathered in $gatherMs ms")
        // Nothing of the phone's files: not the song, its plays, its genre, its timing or its place in a playlist.
        assertTrue(content.history.songs.none { it.id.startsWith("local:") } && content.history.plays.none { it.songId.startsWith("local:") })
        assertTrue(content.history.genres.none { it.key.contains("local:") })
        assertTrue(content.prefs["lyric_offsets"]!!.keys.none { it.startsWith("local:") })
        assertTrue(!content.library!!.contains("My Demo Tape"))

        // Sent over a loopback link, paired, and merged in on the TV with the real code.
        val tv = Tv { f ->
            val merged = runBlocking { Backup.readBring(f.inputStream().buffered())?.let { BringOver.merge(it, tvDb, tvPrefs, tvLib) } }
            println("merged: $merged")
            if (merged != null) null else "not a bundle"
        }
        val phone = Phone()
        val key = com.prism.music.connect.LinkKeys.hex(com.prism.music.connect.LinkKeys.nonce() + com.prism.music.connect.LinkKeys.nonce())
        tv.keys[phone.device] = key; phone.keys[tv.device] = key
        t0 = System.nanoTime()
        assertEquals(HandoffResult.Done(), send(tv, phone, bundle))
        println("bring over loopback (send, check, merge): ${(System.nanoTime() - t0) / 1_000_000} ms")

        runBlocking {
            // Likes: the phone's 1,253 and the TV's 10, 5 of them both (one like each).
            val liked = tvDb.songs().liked()
            assertEquals(1_253 + 5, liked.size)
            // A like on both keeps the earlier time (the TV's here), one only on the phone keeps the phone's.
            assertEquals(base - 10_000, liked.first { it.id == id(1_250) }.likedAt)
            assertEquals(base + 3_000, liked.first { it.id == id(3) }.likedAt)
            // Plays: the TV's 20 and the phone's 1,426, the 5 on both once.
            val plays = tvDb.plays().events(0, Long.MAX_VALUE)
            assertEquals(20 + 1_426 - 5, plays.size)
            assertTrue(plays.none { it.songId.startsWith("local:") })
            // The phone's DJ plays came with their source (the one also on the TV is the TV's, without one).
            assertEquals(1_426 / 9 + 1 - 1, plays.count { it.source == "dj" })
            assertEquals(null, tvDb.songs().get("local:42"))
            assertEquals("Rock", tvDb.meta().genre("artist3:artist 0")?.genre)
        }
        // Preferences: the TV's own timing for a song stays; the phone's others come; lists joined.
        assertEquals(-1_000L, tvPrefs("lyric_offsets").getLong(id(1), 0))
        assertEquals(-250L, tvPrefs("lyric_offsets").getLong(id(2), 0))
        assertTrue(!tvPrefs("lyric_offsets").contains("local:42"))
        assertEquals(61_000L, tvPrefs("best_part_mine").getLong(id(4), -1))
        assertEquals("${id(1_500)}\tArtist 3 · Live", tvPrefs("song_versions").getString(id(3), null))
        assertEquals(setOf(id(1_590), id(1_591), id(7)), tvPrefs("dislikes").getStringSet("ids", null))
        assertEquals("AUTOGRAPH", tvPrefs("artist_prefs").getString("sig:UCfav1", null))
        assertTrue(tvPrefs("artist_prefs").getString("favorites", "")!!.contains("UCfav4"))
        assertTrue(tvPrefs("exclusions").getString("artists", "")!!.contains("UCkids"))
        // Playlists: the TV's own stays, the phone's two come (the file left out), with their folder and saved album.
        val lists = tvLib.lists.value.map { it.title }.toSet()
        assertEquals(setOf("TV mix", "Everything I like", "Road trip"), lists)
        assertEquals(1_100, tvLib.lists.value.first { it.title == "Everything I like" }.songs.size)
        assertEquals("Weekend", tvLib.folders.value.single().name)
        assertEquals(tvLib.lists.value.first { it.title == "Road trip" }.id, tvLib.folders.value.single().playlists.single())
        assertTrue(tvLib.isSaved("MPREb_album1"))

        // Brought again: nothing doubles.
        assertEquals(HandoffResult.Done(), send(tv, phone, bundle))
        runBlocking {
            assertEquals(1_253 + 5, tvDb.songs().liked().size)
            assertEquals(20 + 1_426 - 5, tvDb.plays().events(0, Long.MAX_VALUE).size)
        }
        assertEquals(3, tvLib.lists.value.size)
        assertEquals(1, tvLib.folders.value.size)
    }

    @Test fun aBrokenOrForeignBundleChangesNothing() {
        val phoneLib = seedPhone()
        seedTv()
        val content = runBlocking { BringOver.collect(phoneDb, phonePrefs, phoneLib.forBringing(), null) }
        val whole = ByteArrayOutputStream().also { Backup.writeBring(it, content, app.packageName) }.toByteArray()
        // Cut short (a transfer that never finished would never get this far: the hash is checked first; this is the
        // reader's own guard).
        assertNull(Backup.readBring(whole.copyOf(whole.size / 2).inputStream()))
        // A backup (not marked as a bundle) is not one.
        val backup = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("prism-backup.json")); z.write("""{"format":2,"app":"${app.packageName}"}""".toByteArray()); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("bring/history.json")); z.write(BringOver.historyJson(content.history).toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertNull(Backup.readBring(backup.inputStream()))
        assertNull(Backup.readBring("not a zip at all".byteInputStream()))
        runBlocking {
            assertEquals(10, tvDb.songs().liked().size)
            assertEquals(20, tvDb.plays().events(0, Long.MAX_VALUE).size)
        }
        // The whole one does read.
        assertEquals(content.history.plays.size, Backup.readBring(whole.inputStream())!!.history.plays.size)
    }

    @Test fun songsMergeByTheirRules() {
        val tvOnly = song(1).toEntity().copy(liked = true, likedAt = 500, lastPlayed = 900)
        val phoneLater = song(1).toEntity().copy(liked = true, likedAt = 700, lastPlayed = 2_000, thumbnail = "https://lh3.googleusercontent.com/other")
        val merged = BringOver.mergeSong(tvOnly, phoneLater)!!
        assertEquals(500L, merged.likedAt)
        assertEquals(2_000L, merged.lastPlayed)
        // The TV's own details stay.
        assertEquals(tvOnly.thumbnail, merged.thumbnail)
        // A like only on the phone comes with its time; nothing new: no change.
        assertEquals(700L, BringOver.mergeSong(tvOnly.copy(liked = false, likedAt = 0), phoneLater)!!.likedAt)
        assertNull(BringOver.mergeSong(tvOnly, tvOnly.copy(lastPlayed = 100)))
        // A preference of a kind the TV doesn't keep, or of a file, is left out.
        assertEquals(emptyMap<String, String>(), BringOver.mergePrefs("lyric_offsets", emptyMap<String, Any>(), mapOf("local:9" to "l:5", id(9) to "x:5")))
        assertEquals(emptyMap<String, String>(), BringOver.mergePrefs("discord", emptyMap<String, Any>(), mapOf("token" to "s:secret")))
    }

    private fun SharedPreferences.dump() = all.toString()
}
