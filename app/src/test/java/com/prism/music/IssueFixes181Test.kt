package com.prism.music

import androidx.media3.exoplayer.offline.Download
import com.prism.music.data.UpdateChecker
import com.prism.music.data.UpdateProgress
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.download.DownloadInfo
import com.prism.music.download.forRow
import com.prism.music.playback.AlbumFill
import com.prism.music.ui.screens.curatedFor
import com.prism.music.ui.screens.matchShelves
import com.prism.music.ui.screens.quickPicksToShow
import com.prism.music.ui.screens.shelfKey
import com.prism.music.ui.screens.speedDialPages
import com.prism.music.ui.screens.speedDialTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.1's GitHub round: #312, #315, #297, #277, #295, #278. */
class IssueFixes181Test {
    private fun song(id: String, album: AlbumRef? = null) = Song(id, "Song $id", listOf(ArtistRef("A")), album = album)

    /** #315: a playlist's music video, downloaded as its song ("Songs, not videos"), shows as downloaded. */
    @Test fun aVideoDownloadedAsItsSongShowsAsDownloaded() {
        val done = DownloadInfo(song("song1"), Download.STATE_COMPLETED, 100f, 1)
        val all = mapOf("song1" to done)
        assertEquals(done, all.forRow("video1", mapOf("video1" to "song1")))
        assertEquals(done, all.forRow("song1", emptyMap()))
        assertNull(all.forRow("video2", mapOf("video1" to "song1")))
        // Its song taken off the phone: not downloaded any more.
        assertNull(emptyMap<String, DownloadInfo>().forRow("video1", mapOf("video1" to "song1")))
    }

    /** #312: a song from Speed dial (no album on its tile) takes its album from YouTube's queue for it. */
    @Test fun aSongWithoutItsAlbumGetsIt() {
        val bare = song("s1")
        assertTrue(AlbumFill.wanted(bare))
        assertFalse(AlbumFill.wanted(bare.copy(isVideo = true)))
        assertFalse(AlbumFill.wanted(song("s2", AlbumRef("Album", "MPREb_1"))))
        val album = AlbumRef("After Hours", "MPREb_after")
        assertEquals(album, AlbumFill.withAlbum(bare, listOf(song("s1", album), song("s9")))?.album)
        // Not this song, or no album: nothing.
        assertNull(AlbumFill.withAlbum(bare, listOf(song("other", album))))
        assertNull(AlbumFill.withAlbum(bare, listOf(song("s1"))))
    }

    /** #297: Quick picks come in once and stay; nothing shows while the set made around your artists is on its way. */
    @Test fun quickPicksChangeOnceAtMost() {
        val yt = listOf(song("y1"), song("y2"))
        val mine = listOf(song("m1"), song("m2"))
        assertEquals(emptyList<Song>(), quickPicksToShow(null, emptyList(), yt, ready = false))
        assertEquals(mine, quickPicksToShow(null, mine, yt, ready = false))
        assertEquals(yt, quickPicksToShow(null, emptyList(), yt, ready = true))
        // Shown already: what loads after doesn't replace it.
        assertEquals(yt, quickPicksToShow(yt, mine, listOf(song("fresh")), ready = true))
        assertEquals(listOf(song("m2")), quickPicksToShow(null, mine, yt, ready = true) { it.id != "m1" })
    }

    /** #297: pull to refresh shows new Quick picks, not the set made for the last refresh. */
    @Test fun pullToRefreshChangesQuickPicks() {
        val yt = listOf(song("y1"))
        val before = listOf(song("old"))
        assertEquals(before, quickPicksToShow(null, curatedFor(before, madeFor = 0, refresh = 0), yt, ready = false))
        // Refreshed (1): the old set isn't shown (or locked in) again while the new one is made.
        assertEquals(emptyList<Song>(), quickPicksToShow(null, curatedFor(before, madeFor = 0, refresh = 1), yt, ready = false))
        val after = listOf(song("new"))
        assertEquals(after, quickPicksToShow(null, curatedFor(after, madeFor = 1, refresh = 1), yt, ready = false))
    }

    /** #295: Speed dial's last page is filled from your own plays instead of being cut (18–26 tiles gave 2 pages). */
    @Test fun speedDialFillsItsLastPage() {
        val youtube: List<BrowseItem> = (1..20).map { PlaylistItem("yt$it", "P$it", "", null) }
        val mine: List<BrowseItem> = listOf(SongItem(song("yt1")), SongItem(song("m1")))
        val more: List<BrowseItem> = (1..10).map { SongItem(song("r$it")) }
        val tiles = speedDialTiles(youtube, mine, more, youtubeOnly = false)
        assertEquals(27, tiles.size)
        assertEquals(3, speedDialPages(tiles).size)
        // "YouTube Music's only": YouTube's own, cut to whole pages.
        assertEquals(2, speedDialPages(speedDialTiles(youtube, mine, more, youtubeOnly = true)).size)
        // A song whose album is a tile already isn't added beside it.
        val withAlbum = speedDialTiles(listOf(AlbumItem("MPREb_x", "X", "", null)), emptyList(), listOf(SongItem(song("s", AlbumRef("X", "MPREb_x")))), false)
        assertTrue(withAlbum.none { it.id == "s" })
    }

    /** #277: a section kept by its title is found again when YouTube names it in another language. */
    @Test fun aSectionIsFoundUnderItsTitleInAnotherLanguage() {
        val community = Shelf("De la comunidad", items = listOf(PlaylistItem("p", "P", "", null)), moreBrowseId = "FEmusic_community", moreParams = "abc")
        val other = Shelf("Otra", items = listOf(PlaylistItem("q", "Q", "", null)), moreBrowseId = "FEmusic_other")
        val key = shelfKey("From the community")
        val known = mapOf(key to "FEmusic_community|abc")
        assertEquals(community, matchShelves(listOf(key), listOf(other, community), known)[key])
        // By its own title when it's there.
        val english = community.copy(title = "From the community")
        assertEquals(english, matchShelves(listOf(key), listOf(community, english), known)[key])
        // Never a shelf another section has by its title.
        assertNull(matchShelves(listOf(key, shelfKey("De la comunidad")), listOf(community), known)[key])
    }

    /** #278: the update is looked for every hour, and a tap while one is under way doesn't start another. */
    @Test fun oneUpdateAtATime() {
        val hour = 3_600_000L
        assertTrue(UpdateChecker.dueToCheck(10 * hour, 8 * hour))
        assertFalse(UpdateChecker.dueToCheck(10 * hour, 10 * hour - hour / 2))
        assertTrue("a clock set back still checks", UpdateChecker.dueToCheck(hour, 5 * hour))
        assertEquals(UpdateChecker.InstallStep.WAIT, UpdateChecker.installStep(UpdateProgress.Downloading(0.4f), downloading = true))
        assertEquals(UpdateChecker.InstallStep.REOPEN, UpdateChecker.installStep(UpdateProgress.Installing, downloading = false))
        assertEquals(UpdateChecker.InstallStep.START, UpdateChecker.installStep(UpdateProgress.Idle, downloading = false))
        assertEquals(UpdateChecker.InstallStep.START, UpdateChecker.installStep(UpdateProgress.Failed("x"), downloading = false))
    }
}
