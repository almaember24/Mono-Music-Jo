package com.prism.music

import com.prism.music.data.innertube.Parser
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.SearchRank
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.8.0 (owner, on the TV: "upside down" didn't find Jack Johnson's song): search reads how often songs and videos have
 * been played, in every language, and a title shared by several artists leads with the most played, YouTube's card
 * included. The live side is [SearchAccuracyLiveTest]; these are the rules, offline, as YouTube answered 2026-11-01.
 */
class SearchPlaysTest {
    @Test fun playsAndViewsReadInEveryLanguage() {
        assertEquals(189_000_000L, SearchRank.count("189M plays"))
        assertEquals(9_800_000L, SearchRank.count("9.8M plays"))
        assertEquals(189_000_000L, SearchRank.count("189 M reproducciones"))
        assertEquals(79_630_000L, SearchRank.count("7963만회 재생"))
        assertEquals(36_000L, SearchRank.count("3.6만회 재생"))
        assertEquals(79_000_000L, SearchRank.count("79 Mio. Wiedergaben"))
        assertEquals(36_186L, SearchRank.count("36.186 Wiedergaben"))
        assertEquals(1_900_000L, SearchRank.count("1,9 Mio. Aufrufe"))
        assertEquals(52_000_000L, SearchRank.count("52 M de visualizaciones"))
        assertEquals(280_000L, SearchRank.count("조회수 28만회"))
        // A video's views come after its artist, whose name may hold a digit (Black Gryph0n), and before its length.
        assertEquals(52_000_000L, Parser.countAfterArtist("Black Gryph0n & Baasik • 52M views • 2:44", skip = 1))
        assertEquals(52_000_000L, Parser.countAfterArtist("Video • Black Gryph0n & Baasik • 52M views • 2:44", skip = 2))
        assertEquals(null, Parser.countAfterArtist("Blink-182 • 3:10", skip = 1))
    }

    private fun song(id: String, title: String, artist: String, plays: Long?, video: Boolean = false) =
        SongItem(Song(id, title, listOf(ArtistRef(artist)), isVideo = video), plays)

    private val trending = song("bg", "Upside Down", "Black Gryph0n", 79_000_000)
    private val jack = song("jj", "Upside Down", "Jack Johnson", 189_000_000)
    private val diana = song("dr", "Upside Down", "Diana Ross", 88_000_000)
    private val mix = song("mx", "Upside Down (Monster Mix)", "Dj Monster", 14_000)
    private val trendingVideo = song("bgv", "Upside Down", "Black Gryph0n", 52_000_000, video = true)
    private val jackVideo = song("jjv", "Upside Down", "Jack Johnson", 120_000_000, video = true)
    private val fanVideo = song("fan", "[BLENDER] UPSIDE DOWN short 3D animation", "BlitzOzhnik", 443_000, video = true)
    private val upsideDown = listOf(
        SearchFilter.SONGS to listOf(trending, jack, diana, mix),
        SearchFilter.VIDEOS to listOf(trendingVideo, fanVideo, jackVideo),
    )

    @Test fun aSharedTitleLeadsWithTheMostPlayed() {
        // YouTube's card is the trending video, which Prism turns into its song: 79M plays against Jack Johnson's 189M.
        val r = SearchRank.rank("upside down", upsideDown, card = trendingVideo)
        assertEquals("jj", r.top?.id)
        assertEquals(listOf("dr", "bg", "mx"), r.sections.first { it.first == SearchFilter.SONGS }.second.map { it.id })
        // The videos: Jack Johnson's (120M views) first; the rest where YouTube had them.
        assertEquals(listOf("jjv", "fan", "bgv"), r.sections.first { it.first == SearchFilter.VIDEOS }.second.map { it.id })
    }

    @Test fun theCardHoldsWhenItIsThatSongOrNearlyAsPlayed() {
        // The card is the most played song's own video: it stays (as its song).
        assertEquals("jj", SearchRank.rank("upside down", upsideDown, card = jackVideo).top?.id)
        // A card played nearly as often (2/3 or more) keeps YouTube's say.
        val close = song("bg", "Upside Down", "Black Gryph0n", 150_000_000)
        assertEquals("bg", SearchRank.rank("upside down", listOf(SearchFilter.SONGS to listOf(close, jack)), card = close).top?.id)
        // Without play counts (a request that had none), YouTube's card and order stand as before.
        val bare = listOf(SearchFilter.SONGS to listOf(trending, jack).map { SongItem(it.song) })
        assertEquals("bg", SearchRank.rank("upside down", bare, card = SongItem(trending.song)).top?.id)
    }

    @Test fun aCardThatDoesntNameTheHitGivesWay() {
        // "africa": YouTube's card was a playlist that only has the word in it; Toto's Africa is a hit.
        val toto = song("toto", "Africa", "TOTO", 1_900_000_000)
        val playlist = PlaylistItem("PLx", "Love Out Loud Africa", "Playlist • YouTube Music", null)
        assertEquals("toto", SearchRank.rank("africa", listOf(SearchFilter.SONGS to listOf(toto)), card = playlist).top?.id)
        // A typo's card stays: no song is called what was typed.
        val swift = ArtistItem("UCts", "Taylor Swift", "Artist • 440M monthly audience", null)
        assertEquals("UCts", SearchRank.rank("taylor swfit", listOf(SearchFilter.SONGS to listOf(toto)), card = swift).top?.id)
    }

    @Test fun anArtistOnlyCalledThatGivesWayToAFarBiggerSong() {
        val roarArtist = ArtistItem("UCroar", "ROAR", "Artist • 12M monthly audience", null)
        val roar = song("kp", "Roar", "Katy Perry", 5_000_000_000)
        assertEquals("kp", SearchRank.rank("roar", listOf(SearchFilter.SONGS to listOf(roar), SearchFilter.ARTISTS to listOf(roarArtist))).top?.id)
        // A big artist stays on top of a song of their name ("queen": the band, not a 100M-play song).
        val queen = ArtistItem("UCq", "Queen", "Artist • 50M monthly audience", null)
        val queenSong = song("nm", "Queen", "Nicki Minaj", 100_000_000)
        assertEquals("UCq", SearchRank.rank("queen", listOf(SearchFilter.SONGS to listOf(queenSong), SearchFilter.ARTISTS to listOf(queen))).top?.id)
        // And one the listener plays always does.
        val fan = SearchRank.Taste(listOf(SearchRank.KnownArtist("UCroar", "ROAR", null, 0.5)), emptyMap())
        assertEquals("UCroar", SearchRank.rank("roar", listOf(SearchFilter.SONGS to listOf(roar), SearchFilter.ARTISTS to listOf(roarArtist)), fan).top?.id)
    }

    @Test fun aSoundtrackAlbumGivesWayToTheSongItsTitleIsKnownFor() {
        // "yesterday", 2026-11-01: YouTube's card became the 2019 film's soundtrack album, which also outscores a song
        // on its own (an album weighs more than a song).
        val beatles = song("tb", "Yesterday (Remastered 2009)", "The Beatles", 300_000_000)
        val other = song("x", "Yesterday", "Someone", 2_000_000)
        val ost = AlbumItem("MPREb_y", "Yesterday (Original Motion Picture Soundtrack)", "Album • Himesh Patel • 2019", null)
        val rows = listOf(SearchFilter.SONGS to listOf(other, beatles), SearchFilter.ALBUMS to listOf(ost))
        assertEquals("tb", SearchRank.rank("yesterday", rows, card = ost).top?.id)
        assertEquals("tb", SearchRank.rank("yesterday", rows).top?.id)
        // From 20M plays; below that the soundtrack keeps YouTube's say.
        val smaller = song("tb", "Yesterday", "The Beatles", 30_000_000)
        assertEquals("tb", SearchRank.rank("yesterday", listOf(SearchFilter.SONGS to listOf(smaller), SearchFilter.ALBUMS to listOf(ost)), card = ost).top?.id)
        val small = song("tb", "Yesterday", "The Beatles", 5_000_000)
        assertEquals("MPREb_y", SearchRank.rank("yesterday", listOf(SearchFilter.SONGS to listOf(small), SearchFilter.ALBUMS to listOf(ost)), card = ost).top?.id)
    }

    @Test fun aNewAlbumGivesWayToABigSongAnOlderOneStands() {
        val year = java.time.Year.now().value
        // "africa", 2026-11-01: YouTube's card was a new album by someone new (twice: DavOlution's, Buddy DML's).
        val toto = song("toto", "Africa", "TOTO", 1_900_000_000)
        val fresh = AlbumItem("MPREb_af", "AFRICA", "Album • DavOlution • $year", null)
        assertEquals("toto", SearchRank.rank("africa", listOf(SearchFilter.SONGS to listOf(toto), SearchFilter.ALBUMS to listOf(fresh)), card = fresh).top?.id)
        val lastYear = AlbumItem("MPREb_af2", "AfricA", "Album • Buddy DML • ${year - 1}", null)
        assertEquals("toto", SearchRank.rank("africa", listOf(SearchFilter.SONGS to listOf(toto), SearchFilter.ALBUMS to listOf(lastYear)), card = lastYear).top?.id)
        // Under 100M plays the new album keeps YouTube's say.
        val smaller = song("toto", "Africa", "TOTO", 60_000_000)
        assertEquals("MPREb_af", SearchRank.rank("africa", listOf(SearchFilter.SONGS to listOf(smaller), SearchFilter.ALBUMS to listOf(fresh)), card = fresh).top?.id)
        // "AM": Arctic Monkeys' album (2013), not Nio Garcia's AM, though that song is played 491M times.
        val nio = song("ng", "AM", "Nio Garcia, Flow La Movie", 491_000_000)
        val am = AlbumItem("MPREb_am", "AM", "Album • Arctic Monkeys • 2013", null)
        assertEquals("MPREb_am", SearchRank.rank("am", listOf(SearchFilter.SONGS to listOf(nio), SearchFilter.ALBUMS to listOf(am)), card = am).top?.id)
        // "lemonade": Beyoncé's album (2016) against Internet Money's Lemonade (337M).
        val lemon = song("im", "Lemonade (feat. NAV)", "Internet Money", 337_000_000)
        val beyonce = AlbumItem("MPREb_lm", "Lemonade", "Album • Beyoncé • 2016", null)
        assertEquals("MPREb_lm", SearchRank.rank("lemonade", listOf(SearchFilter.SONGS to listOf(lemon), SearchFilter.ALBUMS to listOf(beyonce)), card = beyonce).top?.id)
    }

    @Test fun anAlbumByTheHitsOwnArtistStands() {
        // "after hours": The Weeknd's album stays the top, however big his title track.
        val song = song("aw", "After Hours", "The Weeknd", 900_000_000)
        val album = AlbumItem("MPREb_ah", "After Hours", "Album • The Weeknd • 2020", null)
        val rows = listOf(SearchFilter.SONGS to listOf(song), SearchFilter.ALBUMS to listOf(album))
        assertEquals("MPREb_ah", SearchRank.rank("after hours", rows, card = album).top?.id)
        assertEquals("MPREb_ah", SearchRank.rank("after hours", rows).top?.id)
    }

    @Test fun theSongOnYouTubesOwnPageCountsWhenTheSongSearchLeftItOut() {
        // "halo", 2026-11-01: the card was a Halo game soundtrack song (553K plays), the song search had no Beyoncé;
        // YouTube's unfiltered page did (2.7B).
        val amber = song("ac", "In Amber Clad", "Halo", 553_000)
        val lp = song("lp", "Halo (Live)", "LP", 133_000_000)
        val nomelon = song("nn", "HALO", "NOMELON NOLEMON", 8_100_000)
        val beyonce = song("bey", "Halo", "Beyoncé", 2_700_000_000)
        val ost = AlbumItem("MPREb_h", "Halo: Combat Evolved (Original Soundtrack)", "Album • Halo, Martin O'Donnell & Michael Salvatori • 2002", null)
        val artists = listOf(
            ArtistItem("UCo", "Oetil Halo", "Artist • 110 subscribers", null),
            ArtistItem("UCh", "Halo", "Artist • 20.4K subscribers", null),
        )
        val rows = listOf(
            SearchFilter.ALBUMS to listOf(ost), SearchFilter.ARTISTS to artists,
            SearchFilter.SONGS to listOf(amber, lp, song("ht", "Halo Theme Scorpion Mix", "Paul Lipson", 3_000_000), nomelon),
        )
        val page = listOf(beyonce, song("mv", "Halo", "MVMURO", 455_000), amber)
        val r = SearchRank.rank("halo", rows, card = amber, pageSongs = page)
        assertEquals("bey", r.top?.id)
        // Beyoncé's took LP's place (the first of the title), LP the next. Only songs of exactly the title, and known
        // ones, join: MVMURO's (455K) doesn't.
        assertEquals(listOf("ac", "ht", "lp", "nn"), r.sections.first { it.first == SearchFilter.SONGS }.second.map { it.id })
        // Without the page, as before: the most played song of the title the song search had.
        assertEquals("lp", SearchRank.rank("halo", rows, card = amber).top?.id)
    }

    @Test fun aTitleTrackStillGivesWayToItsAlbum() {
        val thriller = song("mj", "Thriller", "Michael Jackson", 1_000_000_000)
        val other = song("x", "Thriller", "Someone", 5_000_000)
        val album = AlbumItem("MPREb_t", "Thriller", "Album • Michael Jackson • 1982", null)
        val r = SearchRank.rank("thriller", listOf(SearchFilter.SONGS to listOf(other, thriller), SearchFilter.ALBUMS to listOf(album)))
        assertEquals("MPREb_t", r.top?.id)
    }
}
