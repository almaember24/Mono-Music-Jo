package com.prism.music

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.ArtistFromPhone
import com.prism.music.data.OfflineLibrary
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.LibraryTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Artists' pages kept for offline, their size limits, and pages made from the phone (1.8.2). Uploads' tile too. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OfflineArtistsTest {
    private lateinit var store: OfflineLibrary

    @Before fun fresh() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.filesDir.resolve("offline").deleteRecursively()
        store = OfflineLibrary(app)
    }

    private fun song(i: Int, video: Boolean = false, album: AlbumRef? = AlbumRef("Album One", "MPREb_one")) =
        Song("song$i", "Song $i", listOf(ArtistRef("Test Artist", "UCartist")), album, 180 + i, "https://lh3.googleusercontent.com/s$i", isVideo = video)

    private fun page(id: String = "UCartist", name: String = "Test Artist", songs: Int = 3, description: String? = "Their story.") = ArtistPage(
        id = id, name = name, thumbnail = "https://lh3.googleusercontent.com/photo-$id", description = description, subscribers = "1M monthly audience",
        shelves = listOf(
            Shelf("Top songs", items = (1..songs).map { SongItem(song(it), plays = 1000L * it) }, moreBrowseId = "VLabc"),
            Shelf("Albums", items = listOf(AlbumItem("MPREb_one", "Album One", "Album • 2024", "https://lh3.googleusercontent.com/a1", playlistId = "OLAK1"))),
            Shelf("Playlists", items = listOf(PlaylistItem("PLone", "Theirs", "Playlist", null))),
            Shelf("Fans might also like", items = listOf(ArtistItem("UCother", "Other", "2M", null))),
        ),
        radioPlaylistId = "RDabc", shufflePlaylistId = "RDshuffle", views = "5 views", channelId = "UCchannel", subscribed = true,
    )

    @Test fun aPageComesBackAsItWasKept() {
        assertTrue(store.saveArtist(page()))
        val kept = store.artist("UCartist")!!
        assertEquals("Test Artist", kept.name)
        assertEquals("https://lh3.googleusercontent.com/photo-UCartist", kept.thumbnail)
        assertEquals("Their story.", kept.description)
        assertEquals("1M monthly audience", kept.subscribers)
        assertEquals("UCchannel", kept.channelId)
        assertTrue(kept.subscribed)
        assertEquals(listOf("Top songs", "Albums", "Playlists", "Fans might also like"), kept.shelves.map { it.title })
        val first = kept.shelves[0].items[0] as SongItem
        assertEquals(song(1), first.song)
        assertEquals(1000L, first.plays)
        assertEquals("OLAK1", (kept.shelves[1].items[0] as AlbumItem).playlistId)
        assertEquals("UCother", kept.shelves[3].items[0].id)
    }

    @Test fun whatOnlyTheNetworkCanUseIsntKept() {
        store.saveArtist(page())
        val kept = store.artist("UCartist")!!
        assertNull(kept.radioPlaylistId)
        assertNull(kept.shufflePlaylistId)
        assertNull("See all", kept.shelves[0].moreBrowseId)
    }

    @Test fun aPageWithNothingOnItIsntKept() {
        assertFalse(store.saveArtist(page().copy(shelves = emptyList())))
        assertFalse(store.hasArtist("UCartist"))
    }

    @Test fun shelvesAndBiographyHaveLimits() {
        store.saveArtist(page(songs = OfflineLibrary.MAX_ARTIST_ITEMS + 30, description = "x".repeat(OfflineLibrary.MAX_ARTIST_BIO * 2)))
        val kept = store.artist("UCartist")!!
        assertEquals(OfflineLibrary.MAX_ARTIST_ITEMS, kept.shelves[0].items.size)
        assertEquals(OfflineLibrary.MAX_ARTIST_BIO, kept.description!!.length)
    }

    @Test fun aHugePageIsCutDownAndOneStillTooBigIsntKept() {
        val long = "t".repeat(4_000)
        val big = page().copy(shelves = (1..20).map { n -> Shelf("Shelf $n", items = (1..40).map { i -> SongItem(Song("id$n$i", long)) }) })
        // A song's title is kept on its row and in the song, so 8 KB a row: 2 × 40 rows is over the limit, 2 × 12 isn't.
        assertTrue("shorter shelves, no biography", store.saveArtist(big.copy(shelves = big.shelves.take(2))))
        val cut = store.artist("UCartist")!!
        assertEquals(OfflineLibrary.TRIMMED_ARTIST_ITEMS, cut.shelves[0].items.size)
        assertNull(cut.description)
        assertFalse("still over the limit", store.saveArtist(big.copy(id = "UChuge")))
        assertFalse(store.hasArtist("UChuge"))
    }

    @Test fun onlyTheMostRecentlyOpenedStay() {
        val dir = ApplicationProvider.getApplicationContext<Application>().filesDir.resolve("offline/artists")
        repeat(OfflineLibrary.MAX_ARTISTS + 5) { i ->
            store.saveArtist(page(id = "UC$i", songs = 1))
            dir.resolve("UC$i.json").setLastModified(1_000_000L + i * 1000L)
        }
        store.saveArtist(page(id = "UClast", songs = 1))
        assertTrue(dir.list()!!.size <= OfflineLibrary.MAX_ARTISTS)
        assertTrue(store.hasArtist("UClast"))
        assertFalse(store.hasArtist("UC0"))
    }

    @Test fun anArtistCanBeFoundByNameUnderAnotherId() {
        store.saveArtist(page(id = "UCtopic", name = "Beyoncé & Co."))
        assertEquals("UCtopic", store.artistNamed("beyonce and co")?.id)
        assertNull(store.artistNamed("Someone Else"))
        assertNull(store.artistNamed(" "))
    }

    @Test fun anIdWithOddCharactersIsAFileName() {
        store.saveArtist(page(id = "name:AC/DC"))
        assertTrue(store.hasArtist("name:AC/DC"))
        assertEquals("name:AC/DC", store.artist("name:AC/DC")?.id)
    }

    @Test fun thePhotoStaysWhileTheCopyDoes() {
        store.saveArtist(page())
        assertTrue("https://lh3.googleusercontent.com/photo-UCartist" in store.covers())
    }

    @Test fun signingOutForgetsThem() {
        store.saveArtist(page())
        store.saveUploads(listOf(song(1)), emptyList(), emptyList())
        store.clear()
        assertFalse(store.hasArtist("UCartist"))
        assertNull(store.uploads())
    }

    @Test fun aDamagedCopyReadsAsNoCopy() {
        store.saveArtist(page())
        ApplicationProvider.getApplicationContext<Application>().filesDir.resolve("offline/artists/UCartist.json").writeText("{ not json")
        assertNull(store.artist("UCartist"))
        assertNull(store.artistNamed("Test Artist"))
    }

    /** A kept list from before duplicates were weeded out: read back as it was, its page crashed on the repeated key, every time, offline too. */
    @Test fun aKeptUploadsListWithRepeatsComesBackWithout() {
        val album = AlbumItem("MPREb_one", "Album One", "Album", null)
        val artist = ArtistItem("UCartist", "Test Artist", "3 songs", null)
        store.saveUploads(listOf(song(1), song(1), song(2)), listOf(album, album), listOf(artist, artist))
        val kept = store.uploads()!!
        assertEquals(listOf("song1", "song2"), kept.songs.map { it.id })
        assertEquals(listOf("MPREb_one"), kept.albums.map { it.id })
        assertEquals(listOf("UCartist"), kept.artists.map { it.id })
    }

    @Test fun uploadsListsComeBack() {
        val upload = song(7).copy(isUpload = true)
        store.saveUploads(listOf(upload), listOf(AlbumItem("A", "Alb", "Album • X", null, "ML")), listOf(ArtistItem("R", "Art", "1 song", null)))
        val kept = store.uploads()!!
        assertEquals(listOf(upload), kept.songs)
        assertEquals("ML", kept.albums.single().playlistId)
        assertEquals("Art", kept.artists.single().title)
    }

    @Test fun aPageFromThePhoneListsSongsAlbumsAndVideosApart() {
        val p = ArtistFromPhone.page("UCartist", "Test Artist", listOf(song(1), song(2), song(3, video = true), song(4, album = null)), "photo")
        assertEquals(listOf("Songs", "Albums", "Videos"), p.shelves.map { it.title })
        assertEquals(listOf("song1", "song2", "song4"), p.shelves[0].items.map { it.id })
        assertEquals("MPREb_one", p.shelves[1].items.single().id)
        assertEquals("song3", p.shelves[2].items.single().id)
        assertEquals("photo", p.thumbnail)
        assertNull(p.shufflePlaylistId)
        assertTrue("nothing on the phone: no shelves", ArtistFromPhone.page("U", "N", emptyList(), null).shelves.isEmpty())
    }

    /** Nobody's tiles change: a new install has no Uploads tile, and the tile only goes in for an account with uploads. */
    @Test fun theTileChangesNobodysLibrary() {
        assertFalse(LibraryTile.UPLOADS in LibraryTile.initial)
        assertFalse(LibraryTile.HISTORY in LibraryTile.initial)
        assertFalse(LibraryTile.SAVED in LibraryTile.initial)
        assertEquals(LibraryTile.entries.size - 3, LibraryTile.initial.size)
        assertFalse(LibraryTile.UPLOADS in LibraryTile.offered(false))
        assertTrue(LibraryTile.UPLOADS in LibraryTile.offered(true))
        val standard = listOf(LibraryTile.LIKED, LibraryTile.DOWNLOADS, LibraryTile.LOCAL, LibraryTile.REPLAY)
        assertEquals(listOf(LibraryTile.LIKED, LibraryTile.DOWNLOADS, LibraryTile.LOCAL, LibraryTile.UPLOADS, LibraryTile.REPLAY), LibraryTile.withUploads(standard))
        assertEquals(listOf(LibraryTile.REPLAY, LibraryTile.UPLOADS), LibraryTile.withUploads(listOf(LibraryTile.REPLAY)))
        val already = listOf(LibraryTile.UPLOADS, LibraryTile.LIKED)
        assertEquals(already, LibraryTile.withUploads(already))
        assertNotNull(LibraryTile.UPLOADS.label)
    }
}
