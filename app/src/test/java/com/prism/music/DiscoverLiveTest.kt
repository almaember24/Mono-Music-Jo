package com.prism.music

import com.prism.music.data.foryou.Discovery
import com.prism.music.data.foryou.Heard
import com.prism.music.data.foryou.LocalRadio
import com.prism.music.data.foryou.MixMaker
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * New to you and Deep cuts from real YouTube Music answers, signed out (1.8.2): a listener who knows the top songs of three
 * rock bands and three pop acts. Prints what it made, to read by eye; asserts the rules hold on real data. Live.
 */
@Category(LiveNetwork::class)
class DiscoverLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val innerTube = InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) })
    private val ytm = YouTubeMusic(innerTube)

    private val sources = object : Discovery.Sources, MixMaker.Sources {
        override suspend fun radio(seed: Song) = ytm.next(seed.id, "RDAMVM${seed.id}").songsOnly().songs
        override suspend fun related(seed: Song) = ytm.related(seed.id, seed.title)
        override suspend fun artistTop(artist: ArtistItem) = ytm.artistTopSongs(artist.id)
        // As [com.prism.music.data.foryou.Discover] asks them.
        override suspend fun catalog(artist: Discovery.LovedArtist): Discovery.Catalog? {
            val id = artist.id ?: ytm.artistIdByName(artist.name) ?: return null
            val shelves = ytm.artist(id).shelves
            val top = shelves.firstOrNull { s -> s.items.any { it is SongItem && !it.song.isVideo } }?.items.orEmpty().filterIsInstance<SongItem>().map { it.song }
            val all = shelves.flatMap { it.items }.filterIsInstance<AlbumItem>()
            return Discovery.Catalog(top, all.filter { it.subtitle.contains("Album") }.ifEmpty { all })
        }
        override suspend fun album(item: AlbumItem) = ytm.album(item.id)
    }

    @Test fun realNewToYouAndDeepCuts() = runBlocking {
        val names = listOf("Foo Fighters", "Green Day", "Blink-182", "NSYNC", "Britney Spears", "Backstreet Boys")
        val favorites = names.flatMap { name ->
            val artist = ytm.search(name, SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>().first()
            ytm.artistTopSongs(artist.id).take(8).mapIndexed { i, s -> Discovery.Favorite(s, 1.0 - i * 0.05) }
        }
        println("[live] knows ${favorites.size} songs: " + favorites.take(6).joinToString { "${it.song.title} (${it.song.primaryArtist})" })
        val heard = Heard.of(favorites.map { it.song.id }, favorites.map { it.song.title to it.song.primaryArtist })
        val l = Discovery.Listener(favorites, Discovery.lovedArtists(favorites, emptyList()), heard)
        val maker = Discovery(sources)

        val fresh = maker.newToYou(l, "2026-11-02")
        println("[live] New to you: ${fresh.songs.size} songs, ${fresh.songs.map { it.primaryArtist }.distinct().size} artists, seeds ${fresh.seeds}")
        fresh.songs.forEach { println("[live]   ${it.title} - ${it.primaryArtist}") }
        assertTrue("New to you: about 30 (${fresh.songs.size})", fresh.songs.size >= 20)
        assertTrue("none known", fresh.songs.none { heard.has(it) })
        assertTrue("many artists", fresh.songs.map { it.primaryArtist.lowercase() }.distinct().size >= 8)
        assertEquals("no song twice", fresh.songs.size, fresh.songs.distinctBy { LocalRadio.sameSong(it) }.size)

        val deep = maker.deepCuts(l, "2026-11-02")
        println("[live] Deep cuts: ${deep.songs.size} songs from ${deep.seeds}")
        deep.songs.forEach { println("[live]   ${it.title} - ${it.artistText} ${if (it.isVideo) "(video)" else ""}") }
        assertTrue("Deep cuts: enough to be a mix (${deep.songs.size})", deep.songs.size >= Discovery.MIN_SONGS)
        assertTrue("none known", deep.songs.none { heard.has(it) })
        // As YouTube credits them ("*NSYNC", "blink-182").
        val loved = l.artists.map { it.name.lowercase() }.toSet()
        assertTrue("by the artists loved", deep.songs.all { s -> s.artists.any { it.name.lowercase() in loved } })
    }
}
