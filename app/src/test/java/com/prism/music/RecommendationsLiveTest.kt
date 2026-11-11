package com.prism.music

import com.prism.music.data.foryou.LocalRadio
import com.prism.music.data.foryou.MadeForYou
import com.prism.music.data.foryou.MixMaker
import com.prism.music.data.foryou.Variety
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * Daily Mixes made from real YouTube Music answers (1.7.9): a listener of three rock bands and of three pop acts,
 * NSYNC most of all. Two days of each mix are made and printed; the new songs must come from many artists, most of
 * them new to the listener, and the two days must differ. Live.
 */
@Category(LiveNetwork::class)
class RecommendationsLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val innerTube = InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) })
    private val ytm = YouTubeMusic(innerTube)

    private val sources = object : MixMaker.Sources {
        override suspend fun radio(seed: Song) = ytm.next(seed.id, "RDAMVM${seed.id}").songsOnly().songs
        override suspend fun related(seed: Song) = ytm.related(seed.id, seed.title)
        override suspend fun artistTop(artist: ArtistItem) = ytm.artistTopSongs(artist.id)
    }

    @Test fun realMixesFindNewMusicThatFits() = runBlocking {
        val groups = mapOf("rock" to listOf("Foo Fighters", "Green Day", "Blink-182"), "pop" to listOf("NSYNC", "Britney Spears", "Backstreet Boys"))
        val library = groups.values.flatten().associateWith { name ->
            val artist = ytm.search(name, SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>().first()
            ytm.artistTopSongs(artist.id).take(10)
        }
        val known = library.flatMap { (artist, songs) -> songs.mapIndexed { i, s -> MixMaker.Known(s, if (artist == "NSYNC") 0.9 - i * 0.03 else 0.5 - i * 0.02) } }
        val maker = MixMaker(sources)
        for ((name, artists) in groups) {
            val members = artists.map { it.lowercase() }.toSet()
            // What day 1 served rests on day 2, as RecFeedback has it in the app.
            val served = HashSet<String>()
            val days = listOf("2026-11-02", "2026-11-03").map { day ->
                val taste = MixMaker.Taste(
                    known.map { k -> k.copy(servedDaysAgo = if (k.song.id in served) 1 else null) },
                    played = known.flatMap { listOf(it.song.id, LocalRadio.sameSong(it.song)) }.toSet(), refused = { _, _ -> false },
                    servedDaysAgo = { if (it in served) 1 else null },
                )
                maker.make(MadeForYou.dailyRecipe("daily:$name", artists.size), taste, day) { it.song.primaryArtist.lowercase() in members }
                    .also { m -> served += m.songs.map { it.id } }
            }
            days.forEachIndexed { d, m ->
                println("[live] $name day ${d + 1}: ${m.songs.size} songs, ${m.familiar.size} known, ${m.fresh.size} new from ${m.fresh.map { it.primaryArtist }.distinct().size} artists; seeds ${m.seeds.map { it.title }}")
                println("[live]   new: " + m.fresh.take(15).joinToString { "${it.title} – ${it.primaryArtist}" })
            }
            val overlap = Variety.overlap(days[0].songs.map { it.id }, days[1].songs.map { it.id })
            println("[live] $name: day 2 shares %.0f%% with day 1".format(overlap * 100))
            for (m in days) {
                assertTrue("$name: new songs found (${m.fresh.size})", m.fresh.size >= 15)
                assertTrue("$name: from many artists", m.fresh.map { it.primaryArtist }.distinct().size >= 8)
                assertTrue("$name: none of them the listener's own", m.fresh.none { f -> known.any { it.song.id == f.id } })
            }
            assertTrue("$name: the two days differ", overlap < 0.5)
        }
    }
}
