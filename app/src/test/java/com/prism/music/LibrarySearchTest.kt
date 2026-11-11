package com.prism.music

import com.prism.music.data.LibEntry
import com.prism.music.data.LibFilter
import com.prism.music.data.LibKind
import com.prism.music.data.LibrarySearch
import com.prism.music.data.ModelStore
import com.prism.music.data.TitleSpellings
import com.prism.music.data.db.RomanizedDao
import com.prism.music.data.db.RomanizedEntity
import com.prism.music.data.lyrics.Romanizer
import com.prism.music.data.lyrics.roman.Japanese
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Searching your library (1.8.3): every word anywhere, one kind or all, songs narrowed to liked / downloaded / on this
 * phone; titles in other scripts found by their Latin spelling (#363), spelled on the phone (kanji only with the
 * Japanese dictionary); quick on 10,000 songs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibrarySearchTest {
    private fun s(id: String, title: String, artist: String = "Someone") = Song(id, title, listOf(ArtistRef(artist)))
    private val liked = listOf(s("a", "Mr. Brightside", "The Killers"), s("b", "Beyoncé Song", "Beyoncé"))
    private val downloaded = listOf(s("b", "Beyoncé Song", "Beyoncé"), s("c", "Brightest Day"))
    private val files = listOf(s("${Song.LOCAL_PREFIX}1", "Bright Lights", "Local Band"))
    private val others = listOf(s("d", "Not Bright At All"))

    private fun entries(): List<LibEntry> = LibrarySearch.songEntries(liked, downloaded, files, others) + listOf(
        LibEntry(LibKind.PLAYLIST, PlaylistItem("PL1", "Bright mornings", "Playlist", null), listOf("Bright mornings")),
        LibEntry(LibKind.ARTIST, ArtistItem("UC1", "The Killers", "Artist", null), listOf("The Killers")),
        LibEntry(LibKind.ALBUM, AlbumItem("MP1", "Hot Fuss", "Album • The Killers", null), listOf("Hot Fuss", "The Killers")),
        LibEntry(LibKind.TAG, PlaylistItem("tag:bright", "bright", "", null), listOf("bright")),
    )
    private fun find(q: String, f: LibFilter = LibFilter(), spellings: Map<String, String> = emptyMap()) =
        LibrarySearch.find(LibrarySearch.Index(entries(), spellings), q, f).map { it.item.id }

    @Test fun everyKindSongsFirstBestFirst() {
        // Songs (titles starting with it first), then playlists, tags, artists, albums.
        assertEquals(listOf("c", "${Song.LOCAL_PREFIX}1", "a", "d", "PL1", "tag:bright"), find("bright"))
        assertEquals("accents and capitals don't matter", listOf("b"), find("BEYONCE"))
        assertEquals("every word, any order", listOf("a", "UC1", "MP1"), find("killers"))
        assertEquals(listOf("a"), find("killers brightside"))
    }

    @Test fun oneKindOrWhereTheSongsAre() {
        assertEquals(listOf("MP1"), find("killers", LibFilter(LibKind.ALBUM)))
        assertEquals(listOf("a"), find("bright", LibFilter(liked = true)))
        assertEquals(listOf("c"), find("bright", LibFilter(downloaded = true)))
        assertEquals(listOf("${Song.LOCAL_PREFIX}1"), find("bright", LibFilter(onPhone = true)))
        assertEquals("both must hold", listOf("b"), find("", LibFilter(liked = true, downloaded = true)))
        assertEquals("a song filter leaves only songs", emptyList<String>(), find("killers", LibFilter(LibKind.ARTIST, liked = true)))
        assertEquals("nothing typed, nothing filtered: everything", entries().size, find("").size)
    }

    // ---- Romanized titles (#363)

    private val saved = HashMap<String, RomanizedEntity>()
    private val dao = object : RomanizedDao {
        override suspend fun get(key: String) = saved[key]
        override suspend fun put(e: RomanizedEntity) { saved[e.key] = e }
    }
    private val http = OkHttpClient()
    private fun romanizer(reader: Japanese? = null) =
        Romanizer(http, dao, ModelStore(RuntimeEnvironment.getApplication(), http), CoroutineScope(Dispatchers.Default), japaneseForTests = reader)

    @Test fun titlesAreSpelledOnThePhone() = runBlocking {
        val r = romanizer()
        assertTrue(r.spellTitle("とりのうた")!!.replace(" ", "").contains("torinouta"))
        assertTrue(r.spellTitle("사랑")!!.contains("sarang"))
        assertTrue(r.spellTitle("Кино")!!.lowercase().contains("kino"))
        assertNull("Latin needs none", r.spellTitle("Hot Fuss"))
        assertNull("kanji wait for the Japanese dictionary", r.spellTitle("鳥の歌"))
        val withDictionary = romanizer(Japanese.bundled()).spellTitle("鳥の歌")!!.replace(" ", "")
        assertTrue(withDictionary, withDictionary.contains("torinouta"))
    }

    @Test fun aRomanizedTitleIsFoundByItsSpelling() = runBlocking {
        val song = s("j", "とりのうた", "Lia")
        val korean = s("k", "사랑", "IU")
        val spellings = TitleSpellings(dao, CoroutineScope(Dispatchers.Default), { false }) { romanizer().spellTitle(it) }
        spellings.want(listOf(song.title, korean.title, "Hot Fuss"))
        val until = System.currentTimeMillis() + 10_000
        while (spellings.known.value.size < 2 && System.currentTimeMillis() < until) Thread.sleep(20)
        val index = LibrarySearch.Index(LibrarySearch.songEntries(listOf(song, korean), emptyList(), emptyList(), emptyList()), spellings.known.value)
        assertEquals(listOf("j"), LibrarySearch.find(index, "tori no uta", LibFilter()).map { it.item.id })
        assertEquals(listOf("k"), LibrarySearch.find(index, "sarang", LibFilter()).map { it.item.id })
        assertEquals("the script itself still finds it", listOf("j"), LibrarySearch.find(index, "とりのうた", LibFilter()).map { it.item.id })
        // Kept: spelled once, read from the table after.
        assertTrue(saved.keys.any { it.endsWith(":とりのうた") })
        assertTrue("Latin titles aren't spelled or kept", saved.keys.none { it.endsWith(":Hot Fuss") })
    }

    @Test fun quickOnTenThousandSongs() {
        val many = (0 until 10_000).map { s("s$it", "Song number $it", "Artist ${it % 300}") }
        val index = LibrarySearch.Index(LibrarySearch.songEntries(many.take(3000), many.take(1000), emptyList(), many), emptyMap())
        LibrarySearch.find(index, "artist 12", LibFilter())
        val times = (1..5).map { q ->
            val t = System.nanoTime()
            LibrarySearch.find(index, "number ${q}9 artist", LibFilter(liked = q % 2 == 0))
            (System.nanoTime() - t) / 1_000_000
        }
        println("library search on 10,000 songs: ${times.joinToString()} ms")
        assertTrue("under 50 ms: $times", times.min() < 50)
    }
}
