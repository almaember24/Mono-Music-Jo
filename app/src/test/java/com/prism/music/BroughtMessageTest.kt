package com.prism.music

import com.prism.music.data.imports.HistoryImporter
import com.prism.music.data.imports.LibraryNews
import com.prism.music.ui.screens.broughtMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Bring your history says after an import (1.8.1): a repeat that brought only library items says what came in,
 * not "Already here: N plays"; plays and library both are both said; nothing new is "Already here".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BroughtMessageTest {
    private fun stats(stored: Int, already: Int, songs: Int = 0, library: LibraryNews = LibraryNews()) =
        HistoryImporter.Stats(read = stored + already, stored = stored, alreadyThere = already, prismsOwn = 0, songs = songs, newSongs = songs, library = library)

    @Test fun onlyLibraryItemsSayWhatCameIn() {
        assertEquals("Added 3 liked songs and 12 playlist songs.", broughtMessage(stats(0, 1_204, library = LibraryNews(liked = 3, playlistSongs = 12, playlistsGrowing = 2))))
        assertEquals("Added 1 liked song.", broughtMessage(stats(0, 5, library = LibraryNews(liked = 1))))
        assertEquals(
            "Added 2 liked songs, 1 playlist, 4 playlist songs, 1 saved album and 3 followed artists.",
            broughtMessage(stats(0, 5, library = LibraryNews(liked = 2, playlists = 1, playlistSongs = 4, albums = 1, artists = 3))),
        )
    }

    @Test fun playsAndLibraryAreBothSaid() {
        assertEquals(
            "Brought 312 plays of 40 songs. Prism is matching them now, on Wi-Fi. Added 2 liked songs.",
            broughtMessage(stats(312, 10, songs = 40, library = LibraryNews(liked = 2))),
        )
        assertEquals("Brought 312 plays of 40 songs. Prism is matching them now, on Wi-Fi.", broughtMessage(stats(312, 10, songs = 40)))
    }

    @Test fun nothingNewIsAlreadyHere() {
        assertEquals("Already here: 1,204 plays", broughtMessage(stats(0, 1_204)))
        assertEquals("Couldn't read that", broughtMessage(null))
    }
}
