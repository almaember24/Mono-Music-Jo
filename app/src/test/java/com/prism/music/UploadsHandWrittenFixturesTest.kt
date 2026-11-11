package com.prism.music

import com.prism.music.data.innertube.Parser
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.CollectionKind
import com.prism.music.data.model.Song
import com.prism.music.ui.screens.matchesWords
import com.prism.music.ui.screens.sortUploadedAlbums
import com.prism.music.ui.screens.sortUploadedArtists
import com.prism.music.ui.screens.sortUploadedSongs
import com.prism.music.ui.screens.uploadAlbumArtist
import com.prism.music.ui.screens.uploadArtistCount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library → Uploads' reading of YouTube's answers, against answers TYPED BY HAND from what ytmusicapi reads
 * (app/src/test/resources/uploads-handwritten/README.txt). This shows Prism reads that documented shape, and gives up
 * softly on any other. It does not show YouTube still answers that way: no account with uploads was at hand.
 */
class UploadsHandWrittenFixturesTest {
    private fun answer(name: String): JsonElement =
        Json.parseToJsonElement(javaClass.getResource("/uploads-handwritten/$name.json")!!.readText())

    private val coldplay = "FEmusic_library_privately_owned_artist_detaila_po_CICr2crg7OWpchIIY29sZHBsYXk"

    @Test fun songsRowsAreReadAndTheShuffleRowIsLeftOut() {
        val page = Parser.uploadSongRows(answer("tracks"))
        assertEquals("every row counts as a row", 3, page.rows)
        assertEquals(listOf("Uise6RPKoek", "Dtffhy8WJgw"), page.items.map { it.id })
        assertEquals("HAND-TOKEN-2", page.next)
        val s = page.items[0]
        assertEquals("A Sky Full Of Stars", s.title)
        assertEquals(listOf(ArtistRef("Coldplay", coldplay), ArtistRef("Avicii", "FEmusic_library_privately_owned_artist_detaila_po_AVICII")), s.artists)
        assertEquals("Ghost Stories", s.album?.title)
        assertEquals("FEmusic_library_privately_owned_release_detailb_po_GHOST", s.album?.id)
        assertEquals(268, s.durationSec)
        assertEquals("https://lh3.googleusercontent.com/upload-one=w120-h120", s.thumbnail)
        assertTrue("plays as the listener's own upload", s.isUpload)
        assertFalse(s.isVideo)
        assertNull("a row with no album column", page.items[1].album)
    }

    @Test fun aContinuationPageReadsTheSameWay() {
        val page = Parser.uploadSongRows(answer("tracks-next"))
        assertEquals(listOf("pageTwo0001"), page.items.map { it.id })
        assertEquals(listOf(ArtistRef("Nobody Linked", null)), page.items[0].artists)
        assertNull(page.next)
    }

    @Test fun albumCardsAreRead() {
        val page = Parser.uploadAlbumCards(answer("releases"))
        assertEquals(3, page.rows)
        assertEquals("the card with no title or link is left out", 2, page.items.size)
        val ghost = page.items[0]
        assertEquals("FEmusic_library_privately_owned_release_detailb_po_GHOST", ghost.id)
        assertEquals("Ghost Stories", ghost.title)
        assertEquals("Album • Coldplay • 2014", ghost.subtitle)
        assertEquals("MLPRb_po_GHOST", ghost.playlistId)
        assertEquals("https://lh3.googleusercontent.com/ghost=w226-h226", ghost.thumbnail)
        assertEquals("the link can be on the card rather than its title", "FEmusic_library_privately_owned_release_detailb_po_18M", page.items[1].id)
        assertEquals("the kind reads in English, whatever the language", "Album • Calvin Harris", page.items[1].subtitle)
    }

    @Test fun artistRowsAreRead() {
        val page = Parser.uploadArtistRows(answer("artists"))
        assertEquals(2, page.rows)
        assertEquals(1, page.items.size)
        assertEquals(coldplay, page.items[0].id)
        assertEquals("Coldplay", page.items[0].title)
        assertEquals("12 songs", page.items[0].subtitle)
        assertEquals("HAND-ARTISTS-2", page.next)
    }

    @Test fun anAlbumPageIsRead() {
        val id = "FEmusic_library_privately_owned_release_detailb_po_18M"
        val page = Parser.uploadAlbumPage(id, answer("release-detail"))
        assertEquals(id, page.id)
        assertEquals(CollectionKind.ALBUM, page.kind)
        assertEquals("18 Months", page.title)
        assertEquals("Calvin Harris", page.subtitle)
        assertEquals("Album • 2012", page.secondSubtitle)
        assertEquals("Album", page.releaseKind)
        assertEquals(2012, page.year)
        assertEquals("MLPRb_po_18M", page.playlistId)
        val s = page.songs.single()
        assertEquals("FVo-UZoPygI", s.id)
        assertEquals("a track with no artist column takes the album's", "Calvin Harris", s.primaryArtist)
        assertEquals("a track with no album column is on this album", id, s.album?.id)
        assertEquals("https://lh3.googleusercontent.com/18m=w544-h544", s.thumbnail)
    }

    @Test fun anArtistPageIsMadeFromTheirSongs() {
        val songs = Parser.uploadSongRows(answer("tracks")).items
        val page = Parser.uploadArtistPage(coldplay, answer("no-uploads"), songs)
        assertEquals("the name comes from the song that links this artist", "Coldplay", page.name)
        assertEquals(listOf("Songs", "Albums"), page.shelves.map { it.title })
        assertEquals(1, page.shelves[1].items.size)
        assertEquals("Ghost Stories", page.shelves[1].items[0].title)
        assertNull("nothing of YouTube's to shuffle or mix", page.shufflePlaylistId)
        assertNull(page.radioPlaylistId)
    }

    @Test fun anAccountWithoutUploadsReadsAsEmpty() {
        val page = Parser.uploadSongRows(answer("no-uploads"))
        assertEquals(0, page.rows)
        assertTrue(page.items.isEmpty())
        assertTrue(Parser.uploadAlbumCards(answer("no-uploads")).items.isEmpty())
        assertTrue(Parser.uploadArtistRows(answer("no-uploads")).items.isEmpty())
    }

    /** What decides "no tile, and a line in the diagnostics": rows came and none could be read. */
    @Test fun anotherShapeGivesNothingRatherThanAWrongSong() {
        val page = Parser.uploadSongRows(answer("other-shape"))
        assertEquals(2, page.rows)
        assertTrue(page.items.isEmpty())
        assertTrue(Parser.uploadArtistRows(answer("other-shape")).items.isEmpty())
        val album = Parser.uploadAlbumPage("FEmusic_library_privately_owned_release_detailb_po_X", answer("other-shape"))
        assertEquals("Album", album.title)
        assertTrue(album.songs.isEmpty())
    }

    @Test fun uploadPagesAreToldByTheirIds() {
        assertTrue(Parser.isUploadArtist(coldplay))
        assertFalse(Parser.isUploadAlbum(coldplay))
        assertTrue(Parser.isUploadAlbum("FEmusic_library_privately_owned_release_detailb_po_GHOST"))
        assertFalse(Parser.isUploadAlbum("MPREb_xyz"))
        assertFalse(Parser.isUploadArtist("UCabc"))
        assertFalse(Parser.isUploadArtist(null))
    }

    private fun song(title: String, artist: String, album: String?, sec: Int) = Song(
        title.lowercase().replace(" ", ""), title, listOf(ArtistRef(artist)), album?.let { com.prism.music.data.model.AlbumRef(it) }, sec, isUpload = true,
    )

    @Test fun theListsSortAndSearch() {
        val a = song("Beta", "Zed", "Second", 100)
        val b = song("Alpha", "Yan", null, 300)
        val c = song("Gamma", "Xia", "First", 200)
        val songs = listOf(a, b, c)
        assertEquals(songs, sortUploadedSongs(songs, "LIBRARY"))
        assertEquals(listOf(b, a, c), sortUploadedSongs(songs, "TITLE"))
        assertEquals(listOf(c, b, a), sortUploadedSongs(songs, "ARTIST"))
        assertEquals("no album goes last", listOf(c, a, b), sortUploadedSongs(songs, "ALBUM"))
        assertEquals(listOf(b, c, a), sortUploadedSongs(songs, "LENGTH"))

        val one = AlbumItem("1", "Xylo", "Album • Bert • 2001", null)
        val two = AlbumItem("2", "Able", "Album • Carl • 2020", null)
        assertEquals(listOf(two, one), sortUploadedAlbums(listOf(one, two), "TITLE"))
        assertEquals(listOf(one, two), sortUploadedAlbums(listOf(one, two), "ARTIST"))
        assertEquals(listOf(two, one), sortUploadedAlbums(listOf(one, two), "YEAR"))
        assertEquals("Bert", uploadAlbumArtist(one.subtitle))
        assertEquals("Solo", uploadAlbumArtist("Solo"))

        val p = ArtistItem("p", "The Police", "3 songs", null)
        val q = ArtistItem("q", "Queen", "12 songs", null)
        val r = ArtistItem("r", "Zed", "", null)
        assertEquals(listOf(p, q, r), sortUploadedArtists(listOf(q, p, r), songs, "NAME"))
        assertEquals(listOf(q, p, r), sortUploadedArtists(listOf(p, q, r), songs, "MOST"))
        assertEquals("with no count on the row, the songs are counted", 1, uploadArtistCount(r, songs))

        assertTrue(matchesWords("ghost cold", "Ghost Stories Coldplay"))
        assertFalse(matchesWords("ghost queen", "Ghost Stories Coldplay"))
        assertTrue("nothing typed matches all", matchesWords("  ", "anything"))
    }
}
