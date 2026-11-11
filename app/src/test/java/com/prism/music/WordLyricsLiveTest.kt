package com.prism.music

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.lyrics.WordFormat
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** The word-by-word sources: QQ (Better Lyrics "Portato"), NetEase YRC and KuGou KRC. */
@Category(LiveNetwork::class)
class WordLyricsLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))
    private val dao = object : LyricsDao {
        val rows = HashMap<String, LyricsEntity>()
        override suspend fun get(songId: String, source: String) = rows["$songId|$source"]
        override suspend fun put(e: LyricsEntity) { rows["${e.songId}|${e.source}"] = e }
        override suspend fun clearMisses() {}
        override suspend fun allFor(songId: String) = rows.values.filter { it.songId == songId && it.content.isNotEmpty() }
    }
    private val repo = LyricsRepository(http, dao, ytm, { settings })

    private fun song(id: String, title: String, artist: String, album: String, sec: Int) =
        Song(id, title, listOf(ArtistRef(artist)), AlbumRef(album), sec)

    @Test
    fun parsesAllThreeFormats() {
        val qrc = WordFormat.parse(WordFormat.Kind.QRC, "[0,2000]Song (0,500)- (500,500)Artist(1000,500)\n[29439,2239]Have (29439,144)you (29583,130)got (29713,363)col(30076,200)our(30276,249)")
        assertEquals(1, qrc.count { !it.isGap })
        assertEquals(listOf("Have ", "you ", "got ", "colour"), qrc.last().words.map { it.text })
        assertEquals(30076L, qrc.last().words.last().startMs)
        val yrc = WordFormat.parse(WordFormat.Kind.YRC, "[29210,4980](29210,210,0)Have (29420,150,0)you (29570,330,0)got")
        assertEquals("Have you got", yrc.last().text)
        val krc = WordFormat.parse(WordFormat.Kind.KRC, "[10000,3000]<0,500,0>I <500,400,0>can't <900,600,0>sleep")
        assertEquals(10_500L, krc.last().words[1].startMs)
        assertEquals("I can't sleep", krc.last().text)
    }

    @Test
    fun wordByWordFromEachSource() = runBlocking {
        val diwk = song("bpOSxM0rNPM", "Do I Wanna Know?", "Arctic Monkeys", "AM", 272)
        val bl = song("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", "After Hours", 200)
        for ((s, src) in listOf(diwk to LyricsSource.QQ, diwk to LyricsSource.NETEASE, bl to LyricsSource.KUGOU, diwk to LyricsSource.KUGOU)) {
            val l = repo.fetch(s, src)
            val sung = l?.lines?.filter { !it.isGap }.orEmpty()
            println("[words] ${src.label} · ${s.title}: ${sung.size} lines, word-synced=${l?.wordSynced}; first: ${sung.firstOrNull()?.words?.joinToString("|") { "${it.text.trim()}@${it.startMs}" }}")
            assertTrue("${src.label} gave word-synced lyrics for ${s.title}", l?.wordSynced == true && sung.size > 10)
        }
    }

    /** Downloads go for word-by-word lyrics even when a line-synced source comes first in the order. */
    @Test
    fun downloadsPreferWordByWord() = runBlocking {
        settings = settings.copy(lyricsOrder = listOf(LyricsSource.LRCLIB) + LyricsSource.entries.filter { it != LyricsSource.LRCLIB })
        for (s in listOf(
            song("bpOSxM0rNPM", "Do I Wanna Know?", "Arctic Monkeys", "AM", 272),
            song("TUVcZfQe-Kw", "Levitating", "Dua Lipa", "Future Nostalgia", 203),
        )) {
            val auto = repo.auto(s)
            val dl = repo.download(s)
            println("[download] ${s.title}: auto=${auto?.source} words=${auto?.wordSynced} -> download=${dl?.source} words=${dl?.wordSynced}")
            assertTrue("download found word-by-word lyrics for ${s.title}", dl?.wordSynced == true)
            // Once saved, the offline pick is the word-by-word one.
            assertTrue(repo.savedLevel(s) == 3)
        }
    }
}