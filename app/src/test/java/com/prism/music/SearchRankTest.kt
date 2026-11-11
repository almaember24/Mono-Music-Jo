package com.prism.music

import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.SearchRank
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankTest {
    private val ep = AlbumItem("MPREb_ep", "A Day", "EP • Polar Colors • 2021", null)
    private val adtr = ArtistItem("UCadtr", "A Day to Remember", "Artist • 2.1M monthly audience", null)
    private val song = SongItem(Song("vid1", "A Day", listOf(ArtistRef("Someone Else"))))
    private val results = listOf(
        SearchFilter.SONGS to listOf(song),
        SearchFilter.ALBUMS to listOf(ep),
        SearchFilter.ARTISTS to listOf(adtr),
    )

    private val fan = SearchRank.Taste(listOf(SearchRank.KnownArtist("UCadtr", "A Day to Remember", null, 0.6)), emptyMap())

    /** [SearchRank.norm] as it was before #355, three Regexes compiled per call. */
    private fun normAsItWas(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), "")
        .replace(Regex("\\s+"), " ").trim()

    /** #355: walked by hand now, it has to read every name just as the Regexes did. */
    @Test fun normReadsEveryNameAsBefore() {
        val names = listOf(
            "", " ", "   ", "a", "Beyoncé", "AC/DC", "Guns N' Roses", "P!nk", "Simon & Garfunkel", "&", "&&", "a&&b", "a & b", " & x", "x &", "a&",
            "  leading and trailing  ", "tab\tseparated", "line\nbreak", "non breaking", "a ! b", "! a", "a !b", "a - b", "(what) [ever]",
            "Motörhead", "Sigur Rós", "Björk", "Ñandú", "ÅSTRÖM", "İstanbul", "ß", "ﬁ ligature", "Ⅷ", "½ life", "١٢٣", "٢٠٢٤",
            "فيروز", "عَمْرو دِياب", "मोहम्मद रफ़ी", "तू ही रे", "아이유", "방탄소년단", "宇多田ヒカル", "ｱｲｳ", "周杰倫", "Тату", "Μίκης", "ไทย", "עברית",
            "song 🎵 name", "🎵", "a🎵b", "𝕬𝖇𝖈", "𠮷野家", "\uD83D", "á", "ẹ̈", "z‍w", "﻿bom", "'quoted'", "“curly”", "a_b", "a-b", "1,000", "#1",
        )
        for (n in names) assertEquals("reading [$n]", normAsItWas(n), SearchRank.norm(n))
    }

    private val known = SearchRank.Taste(
        listOf(
            SearchRank.KnownArtist(null, "Daft Punk", null, 0.4),
            SearchRank.KnownArtist(null, "Dafne", null, 0.9),
            SearchRank.KnownArtist(null, "A Day to Remember", null, 0.6),
            SearchRank.KnownArtist(null, "Punk Rockers", null, 0.1),
        ),
        emptyMap(),
    )

    /** #355: what a letter offers: the listener's own searches first, then their artists, best known first. */
    @Test fun lettersOfferOwnSearchesThenBestKnownArtists() {
        assertEquals(listOf("daft punk get lucky", "Dafne", "Daft Punk"), SearchRank.predict("daf", known, listOf("Daft Punk get lucky".lowercase(), "other")))
        assertEquals(listOf("Dafne"), SearchRank.predict("daf", known, emptyList(), limit = 1))
        // A word inside the name counts from three letters.
        assertEquals(listOf("Daft Punk", "Punk Rockers"), SearchRank.predict("pun", known, emptyList()))
        assertEquals(listOf("Punk Rockers"), SearchRank.predict("pu", known, emptyList()))
        // Initials.
        assertEquals(listOf("A Day to Remember"), SearchRank.predict("adt", known, emptyList()))
        // What's already typed in full isn't offered, and nothing is for nothing.
        assertEquals(emptyList<String>(), SearchRank.predict("dafne", known, emptyList()))
        assertEquals(emptyList<String>(), SearchRank.predict(" ", known, listOf("x")))
        // The same name twice comes once, as the listener's own search.
        assertEquals(listOf("DAFNE"), SearchRank.predict("dafn", known, listOf("DAFNE")))
    }

    @Test fun exactMatchWinsWithoutTaste() {
        assertEquals("MPREb_ep", SearchRank.rank("A Day", results).top?.id)
    }

    @Test fun abbreviationsFindTheArtist() {
        // "adtr": a playlist and a single literally called ADTR used to beat A Day to Remember.
        val r = SearchRank.rank("adtr", listOf(
            SearchFilter.PLAYLISTS to listOf(com.prism.music.data.model.PlaylistItem("PLx", "ADTR", "Playlist • someone", null)),
            SearchFilter.ALBUMS to listOf(AlbumItem("MPREb_x", "ADTR", "Single • Nobody • 2024", null)),
            SearchFilter.ARTISTS to listOf(adtr),
        ))
        assertEquals("UCadtr", r.top?.id)
    }

    @Test fun youTubesTopCardBeatsALiteralNearMiss() {
        // "rhcp": YouTube's card is the band; an unknown artist's single titled "RHCP" mustn't take the top.
        val band = ArtistItem("UCrhcp", "Red Hot Chili Peppers", "Artist • 40.8M monthly audience", null)
        val single = AlbumItem("MPREb_s", "RHCP", "Single • 100% Studio • 2024", null)
        assertEquals("UCrhcp", SearchRank.rank("rhcp", listOf(SearchFilter.ALBUMS to listOf(single)), card = band).top?.id)
        // A typo the per-kind searches miss entirely.
        val swift = ArtistItem("UCts", "Taylor Swift", "Artist • 440M monthly audience", null)
        assertEquals("UCts", SearchRank.rank("taylor swfit", listOf(SearchFilter.SONGS to listOf(song)), card = swift).top?.id)
    }

    @Test fun aKnockOffCardDoesntWin() {
        // Signed out, YouTube's card for "blinding lights" can be a reggae flip; the real song is in the Songs results.
        val flip = SongItem(Song("flip", "Blinding Lights (Reggae Flip)", listOf(ArtistRef("FlipVersion"))))
        val real = SongItem(Song("real", "Blinding Lights", listOf(ArtistRef("The Weeknd"))))
        val r = SearchRank.rank("blinding lights", listOf(SearchFilter.SONGS to listOf(flip, real)), card = flip)
        assertEquals("real", r.top?.id)
    }

    @Test fun aVideoCardGivesWayToItsSong() {
        val video = SongItem(Song("vid", "HUMBLE.", listOf(ArtistRef("Kendrick Lamar")), isVideo = true))
        val track = SongItem(Song("trk", "HUMBLE.", listOf(ArtistRef("Kendrick Lamar"))))
        val r = SearchRank.rank("humble kendrick", listOf(SearchFilter.SONGS to listOf(track), SearchFilter.VIDEOS to listOf(video)), card = video)
        assertEquals("trk", r.top?.id)
    }

    @Test fun anArtistTheListenerPlaysComesFirst() {
        assertEquals("UCadtr", SearchRank.rank("A Day", results, fan).top?.id)
    }

    @Test fun aKnownArtistMissingFromResultsIsAdded() {
        val r = SearchRank.rank("a day", results.filter { it.first != SearchFilter.ARTISTS }, fan)
        assertEquals("UCadtr", r.top?.id)
    }

    @Test fun partialWordsDontBorrowTaste() {
        // "halo" is a prefix of Halocene, not a word of it: the exact song still wins.
        val halo = SongItem(Song("halo", "Halo", listOf(ArtistRef("Beyoncé"))))
        val halocene = ArtistItem("UChalocene", "Halocene", "Artist • 300K monthly audience", null)
        val taste = SearchRank.Taste(listOf(SearchRank.KnownArtist("UChalocene", "Halocene", null, 0.6)), emptyMap())
        val r = SearchRank.rank("halo", listOf(SearchFilter.SONGS to listOf(halo), SearchFilter.ARTISTS to listOf(halocene)), taste)
        assertEquals("halo", r.top?.id)
        assertTrue(SearchRank.knownArtists("halo", taste).isEmpty())
    }

    @Test fun liveStreamsAlwaysGetTheLastRowButNotTheTop() {
        val stream = SongItem(Song("lofi", "lofi hip hop radio", listOf(ArtistRef("Lofi Girl")), isVideo = true, isLive = true))
        val r = SearchRank.rank("A Day", results + (SearchFilter.LIVE to listOf(stream)))
        assertEquals("MPREb_ep", r.top?.id)
        assertEquals(SearchFilter.LIVE, r.sections.last().first)
        assertTrue(r.sections.size > 1)
        // With nothing else found, a stream can lead.
        assertEquals("lofi", SearchRank.rank("lofi", listOf(SearchFilter.LIVE to listOf(stream))).top?.id)
    }

    @Test fun aTitleTrackGivesWayToItsAlbum() {
        val track = Song("ah", "After Hours", listOf(ArtistRef("The Weeknd")), album = com.prism.music.data.model.AlbumRef("After Hours", "MPREb_ah"))
        assertTrue(SearchRank.namesItsAlbum("after hours", track))
        assertTrue(!SearchRank.namesItsAlbum("blinding lights", track.copy(title = "Blinding Lights")))
        val r = SearchRank.rank("after hours", listOf(SearchFilter.SONGS to listOf(SongItem(track))))
        val album = AlbumItem("MPREb_ah", "After Hours", "Album • The Weeknd • 2020", null)
        val moved = SearchRank.withAlbumOnTop(r, album)
        assertEquals("MPREb_ah", moved.top?.id)
        assertEquals("ah", moved.sections.first { it.first == SearchFilter.SONGS }.second.first().id)
    }
}
