package com.prism.music

import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.LibraryRepository
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #414: a playlist made in Prism took about two minutes to show in Library. Each create asked YouTube for the library's
 * playlists once, and YouTube's list didn't have the new one yet. Now it's in the list at once and stays there through
 * every refresh until YouTube lists it (or ten minutes pass).
 */
@RunWith(RobolectricTestRunner::class)
class NewPlaylistShowsTest {
    private fun item(id: String, title: String = id, thumb: String? = null) = PlaylistItem(id, title, "", thumb)
    private fun pending(id: String, at: Long) = LibraryRepository.Companion.PendingPlaylist(item(id), at)

    @Test fun aPlaylistYouTubeDoesntListYetIsKeptOnTop() {
        val fetched = listOf<BrowseItem>(item("LM"), item("PLa"))
        val merged = LibraryRepository.mergePending(fetched, mapOf("PLnew" to pending("PLnew", 1_000)), now = 2_000)
        assertEquals(listOf("PLnew", "LM", "PLa"), merged.list.map { it.id })
        assertEquals(setOf("PLnew"), merged.still.keys)
    }

    @Test fun onceYouTubeListsItYouTubesCopyIsUsedAndItsNoLongerPending() {
        val fetched = listOf<BrowseItem>(item("LM"), item("PLnew", "Road trip", "thumb"), item("PLa"))
        val merged = LibraryRepository.mergePending(fetched, mapOf("PLnew" to pending("PLnew", 1_000)), now = 2_000)
        assertEquals(fetched, merged.list)
        assertEquals(emptySet<String>(), merged.still.keys)
        // A browse id ("VL" + the playlist id) counts as listed too.
        val vl = LibraryRepository.mergePending(listOf(item("VLPLnew")), mapOf("PLnew" to pending("PLnew", 1_000)), now = 2_000)
        assertEquals(listOf("VLPLnew"), vl.list.map { it.id })
    }

    @Test fun afterTenMinutesItsLeftToYouTube() {
        val tenMinutes = 10 * 60_000L
        val merged = LibraryRepository.mergePending(listOf(item("PLa")), mapOf("PLnew" to pending("PLnew", 1_000)), now = 1_000 + tenMinutes + 1)
        assertEquals(listOf("PLa"), merged.list.map { it.id })
        assertEquals(emptySet<String>(), merged.still.keys)
    }

    @Test fun severalNewOnesNewestFirst() {
        val merged = LibraryRepository.mergePending(
            listOf(item("PLa")), mapOf("PLone" to pending("PLone", 1_000), "PLtwo" to pending("PLtwo", 1_500)), now = 2_000,
        )
        assertEquals(listOf("PLtwo", "PLone", "PLa"), merged.list.map { it.id })
    }

    @Test fun aNewPlaylistShowsAtOnceAndOutlivesRefreshesThatDontListIt() {
        val app = ApplicationProvider.getApplicationContext<PrismApp>()
        val lib = app.container.library
        val old = item("PLold", "Old one")
        var listed = listOf<BrowseItem>(old)
        lib.fetchPlaylists = { listed }
        runBlocking { lib.reloadPlaylists() }
        assertEquals(listOf("PLold"), lib.playlists.value.map { it.id })

        lib.created("PLnew", "Road trip", 3)
        assertEquals("in the list straight away", listOf("PLnew", "PLold"), lib.playlists.value.map { it.id })
        assertEquals("Road trip", lib.playlists.value.first().title)

        // YouTube's library list doesn't have it yet: a refresh keeps it.
        runBlocking { lib.reloadPlaylists() }
        assertEquals("still there after a refresh", listOf("PLnew", "PLold"), lib.playlists.value.map { it.id })

        // Now YouTube lists it: its own entry (cover and all) takes over, once.
        listed = listOf(item("PLnew", "Road trip", "thumb"), old)
        runBlocking { lib.reloadPlaylists() }
        assertEquals(listOf("PLnew", "PLold"), lib.playlists.value.map { it.id })
        assertEquals("thumb", lib.playlists.value.first().thumbnail)
    }
}
