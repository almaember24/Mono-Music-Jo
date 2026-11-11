package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.seasonal.HolidayMixMaker
import com.prism.music.data.seasonal.HolidayMixes
import com.prism.music.data.seasonal.YouTubeHolidaySources
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/**
 * 1.8.0: every holiday mix made from YouTube Music, signed out (no cookie in these settings, and the lookups are
 * anonymous anyway): at least 30 songs, under a tenth of them knock-offs, no artist more than three times. Live; prints
 * each mix's first ten songs.
 */
@Category(LiveNetwork::class)
class HolidayMixesLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun everyMixIsFullAndVaried() = runBlocking {
        val maker = HolidayMixMaker(YouTubeHolidaySources(ytm))
        val problems = ArrayList<String>()
        val only = System.getenv("PRISM_HOLIDAY_ONLY")?.split(',')?.map { it.trim() }?.toSet()
        for (mix in HolidayMixes.all.filter { only == null || it.key in only }) {
            val found = maker.find(mix)
            val songs = HolidayMixMaker.assemble(mix, found, "2026-W49")
            val name = tr(mix.name)
            found.forEach { f ->
                val left = f.songs.mapNotNull { HolidayMixMaker.whyNot(mix, it, f.seed.playlist) }.groupingBy { it }.eachCount()
                println("[holiday]   seed \"${f.seed.query}\": ${f.songs.size} found" + (if (f.lists.isNotEmpty()) " from ${f.lists}" else "") + ", left out $left")
            }
            // Judged more broadly than the mix maker does: those words anywhere in the title or the artist.
            val knockoffs = songs.count { s ->
                HolidayMixMaker.isKnockoff(s, mix.asks) ||
                    BROAD.findAll(com.prism.music.data.innertube.SearchRank.norm(s.title + " " + s.artistText)).any { it.value !in mix.asks }
            }
            val worst = songs.groupingBy { it.primaryArtist.lowercase() }.eachCount().maxByOrNull { it.value }
            println("[holiday] $name (${mix.key}): ${songs.size} songs, ${songs.map { it.primaryArtist.lowercase() }.distinct().size} artists, " +
                "knock-offs $knockoffs, most by one artist: ${worst?.key} ×${worst?.value}")
            songs.take(10).forEachIndexed { i, s -> println("[holiday]   ${i + 1}. ${s.title} — ${s.artistText}") }
            if (songs.size < 30) problems += "$name: ${songs.size} songs"
            if (knockoffs * 10 >= songs.size.coerceAtLeast(1)) problems += "$name: $knockoffs knock-offs"
            if ((worst?.value ?: 0) > HolidayMixMaker.PER_ARTIST) problems += "$name: ${worst?.key} ×${worst?.value}"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private companion object {
        val BROAD = Regex("\\b(karaoke|cover|covers|tribute|sped up|slowed|nightcore|8d|made famous|in the style of|backing track)\\b")
    }
}
