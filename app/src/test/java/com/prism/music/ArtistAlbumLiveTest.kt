package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.Parser
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.theme.ArtistTypography
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.experimental.categories.Category

/** Album stars (Apple Music's "popular" marks) and artist-page data, live against YouTube Music. */
@Category(LiveNetwork::class)
class ArtistAlbumLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))

    @Test
    fun playCounts() {
        assertEquals(54_000_000L, Parser.parsePlays("54M plays"))
        assertEquals(4_500_000_000L, Parser.parsePlays("4.5B plays"))
        assertEquals(1_200L, Parser.parsePlays("1.2K plays"))
        assertEquals(812L, Parser.parsePlays("812 plays"))
        assertEquals(null, Parser.parsePlays("Taylor Swift"))
        assertEquals(444_000_000L, ArtistTypography.parseAudience("444M monthly audience"))
    }

    @Test
    fun albumStars() = runBlocking {
        for (q in listOf("After Hours The Weeknd", "1989 Taylor Swift Version", "Rumours Fleetwood Mac", "AM Arctic Monkeys", "good kid m.A.A.d city")) {
            val hit = ytm.search(q, SearchFilter.ALBUMS).items.filterIsInstance<AlbumItem>().first()
            val album = ytm.album(hit.id)
            val stars = album.popularIds
            println("[album] ${album.title} — ${album.trackPlays.size}/${album.songs.size} tracks with plays, ${stars.size} starred")
            album.songs.forEach { s -> println("   ${if (s.id in stars) "★" else " "} ${s.title}  ${album.trackPlays[s.id]?.let { "%,d".format(it) } ?: "-"}") }
            assertTrue("play counts parsed", album.trackPlays.size >= album.songs.size - 1)
            assertTrue("some but not most tracks starred", stars.isNotEmpty() && stars.size <= (album.songs.size + 2) / 3)
        }
    }

    @Test
    fun topResultsAreRelevant() = runBlocking {
        val expect = mapOf(
            "AM" to ("AM" to AlbumItem::class),
            "taylor swift" to ("Taylor Swift" to ArtistItem::class),
            "blinding lights" to ("Blinding Lights" to com.prism.music.data.model.SongItem::class),
            "do i wanna know arctic monkeys" to ("Do I Wanna Know?" to com.prism.music.data.model.SongItem::class),
            "after hours" to ("After Hours" to AlbumItem::class),
            "1989" to ("1989" to AlbumItem::class),
            "kendrick" to ("Kendrick Lamar" to ArtistItem::class),
            "ok computer" to ("OK Computer" to AlbumItem::class),
        )
        for ((q, want) in expect) {
            val r = ytm.searchTop(q)
            println("[search] '$q' -> top: ${r.top?.title} (${r.top?.let { it::class.simpleName }}, ${r.top?.subtitle}); sections: " +
                r.sections.joinToString { "${it.first.label}(${it.second.size})" })
            assertTrue("'$q' top is ${want.first}", r.top != null && want.second.isInstance(r.top) && r.top!!.title.startsWith(want.first, ignoreCase = true))
        }
    }

    @Test
    fun artistAndAlbumExtras() = runBlocking {
        val a = ytm.artist(ytm.search("Taylor Swift", SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>().first().id)
        println("[artist] ${a.name} · subscribe channel ${a.channelId} · subscribed=${a.subscribed}")
        assertTrue("subscribe channel", a.channelId?.startsWith("UC") == true)
        val album = ytm.album(a.shelves.first { it.title.equals("Albums", true) }.items.first().id)
        println("[album] ${album.title} · ${album.releaseKind} ${album.year} · explicit=${album.explicit} · artist photo=${album.artistThumbnail != null} · other versions: ${album.otherVersions.joinToString { it.title }}")
        assertTrue("kind and year", album.releaseKind != null && album.year != null)
        assertTrue("artist photo", album.artistThumbnail != null)
        // Explicit albums carry YouTube's badge (this one's tracks are marked explicit, so the album is too).
        assertEquals(album.songs.any { it.explicit }, album.explicit)
        // Signed out, there's no library bookmark to report.
        assertEquals(null, album.savedToLibrary)
    }

    @Test
    fun artistSections() = runBlocking {
        for (name in listOf("Taylor Swift", "Kendrick Lamar", "Arctic Monkeys")) {
            val a = ytm.artist(ytm.search(name, SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>().first().id)
            println("[artist] ${a.name} · ${a.subscribers} · views=${a.views} · bio=${a.description?.take(80)}")
            a.shelves.forEach { s -> println("   ${s.title}: " + s.items.take(2).joinToString { "${it.title} (${it.subtitle})" }) }
            assertTrue(a.description?.length ?: 0 > 100)
        }
    }
}
