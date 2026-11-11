package com.prism.music

import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.quickPicks
import com.prism.music.data.model.speedDial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #162 / #163 (1.7.9.2): Home's Quick picks and Speed dial in another language, from the shapes a signed-in Spanish
 * Home had on 2026-10-30. "Vuelve a escucharlo" (Listen again) is headed with the account's name and no longer
 * stacked, so it went unfound: Speed dial showed only your own plays and "YouTube Music's only" did nothing.
 */
class HomeShelvesTest {
    private fun songs(n: Int) = List(n) { SongItem(Song(id = "s$it", title = "Song $it")) }
    private fun albums(n: Int) = List(n) { AlbumItem(id = "a$it", title = "Album $it", subtitle = "", thumbnail = null) }

    private val owner = "Sam Rivera"
    private val spanish = listOf(
        Shelf("Vuelve a escucharlo", strapline = owner, items = songs(8) + albums(8)),
        Shelf("Favoritos olvidados", items = albums(9)),
        Shelf("Mixes personalizados", items = albums(8)),
        Shelf("Selecciones rápidas", items = songs(20), rows = true, stacked = true),
        Shelf("Escuchas de larga duración", items = songs(11), rows = true, stacked = true),
    )

    @Test fun speedDialIsTheShelfWithYourName() {
        assertEquals("Vuelve a escucharlo", spanish.speedDial(owner)?.title)
        // Without the name (signed out, or before it's known) nothing like it is there.
        assertNull(spanish.speedDial(null))
    }

    @Test fun quickPicksIsTheFirstRowsShelfThatIsntYours() {
        assertEquals("Selecciones rápidas", spanish.quickPicks(owner)?.title)
        // A "Listen again" laid out as song rows, with your name, is never taken for Quick picks.
        val yoursAsRows = listOf(Shelf("Vuelve a escucharlo", strapline = owner, items = songs(16), rows = true)) + spanish.drop(1)
        assertEquals("Selecciones rápidas", yoursAsRows.quickPicks(owner)?.title)
    }

    /**
     * #266 (English (India), 1.7.9.2): Speed dial had only saved playlists and albums, never a song. Of several shelves
     * with your name (or stacked), the one holding songs is Speed dial.
     */
    @Test fun speedDialIsYourShelfWithSongsInIt() {
        val playlists = List(6) { com.prism.music.data.model.PlaylistItem(id = "p$it", title = "Playlist $it", subtitle = "", thumbnail = null) }
        val two = listOf(
            Shelf("From your library", strapline = owner, items = albums(5) + playlists),
            Shelf("Marcación rápida", strapline = owner, items = albums(4) + songs(12) + playlists),
        )
        assertEquals("Marcación rápida", two.speedDial(owner)?.title)
        // Stacked shelves alone (no name known): the one with songs too.
        val stacked = listOf(Shelf("Albums", items = albums(9), stacked = true), Shelf("Dial", items = songs(9) + albums(9), stacked = true))
        assertEquals("Dial", stacked.speedDial(null)?.title)
        // Yours without songs still beats someone else's stacked shelf: it may be all there is.
        val only = listOf(Shelf("Mine", strapline = owner, items = albums(9)), Shelf("Long listens", items = songs(9), stacked = true))
        assertEquals("Mine", only.speedDial(owner)?.title)
        assertEquals("song=3 album=2", com.prism.music.ui.screens.shelfKinds(Shelf("x", items = songs(3) + albums(2))))
    }

    @Test fun englishTitlesStillWin() {
        val english = listOf(Shelf("Listen again", strapline = owner, items = songs(16)), Shelf("Quick picks", items = songs(20), rows = true, stacked = true))
        assertEquals("Listen again", english.speedDial(owner)?.title)
        assertEquals("Quick picks", english.quickPicks(owner)?.title)
    }
}
