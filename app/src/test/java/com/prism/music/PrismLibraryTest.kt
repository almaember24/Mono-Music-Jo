package com.prism.music

import com.prism.music.data.PrismLibrary
import com.prism.music.data.SavedAlbum
import com.prism.music.data.SmartField
import com.prism.music.data.SmartMatch
import com.prism.music.data.SmartRule
import com.prism.music.data.SmartRules
import com.prism.music.data.SmartSort
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Playlists and saved albums kept on the phone (no account): edits, and that they're still there next time. */
class PrismLibraryTest {
    private fun song(id: String) = Song(id, "Song $id", listOf(ArtistRef("Artist")), durationSec = 200)

    @Test
    fun playlistsAreMadeEditedAndKept() {
        val dir = Files.createTempDirectory("prismlib").toFile()
        val lib = PrismLibrary(dir)
        val id = lib.create("Road trip", listOf(song("a"), song("b"), song("a")))
        assertTrue(PrismLibrary.isPrismList(id))
        assertEquals(listOf("a", "b"), lib.list(id)!!.songs.map { it.id })

        assertEquals("only the new one is added", 1, lib.add(id, listOf(song("b"), song("c"))))
        lib.remove(id, "a")
        lib.reorder(id, listOf(song("c"), song("b")))
        lib.rename(id, "  Summer  ", "Windows down")
        lib.create("Second")

        // A fresh store reads what the first one wrote.
        val again = PrismLibrary(dir)
        val l = again.list(id)!!
        assertEquals("Summer", l.title)
        assertEquals("Windows down", l.description)
        assertEquals(listOf("c", "b"), l.songs.map { it.id })
        assertEquals(2, again.lists.value.size)
        assertEquals("the last one changed comes first", "Second", again.lists.value.first().title)

        again.delete(id)
        assertNull(PrismLibrary(dir).list(id))
        dir.deleteRecursively()
    }

    @Test
    fun albumsAreSavedOnThePhone() {
        val dir = Files.createTempDirectory("prismlib").toFile()
        val lib = PrismLibrary(dir)
        lib.saveAlbum(SavedAlbum("MPREb_1", "Hot Fuss", "Album • The Killers • 2004", null, "OLAK5uy_1", 1L))
        lib.saveAlbum(SavedAlbum("MPREb_2", "Sam's Town", "Album • The Killers • 2006", null, null, 2L))
        lib.saveAlbum(SavedAlbum("MPREb_1", "Hot Fuss", "Album • The Killers • 2004", null, "OLAK5uy_1", 3L))
        assertEquals(listOf("MPREb_1", "MPREb_2"), PrismLibrary(dir).albums.value.map { it.id })
        lib.unsaveAlbum("MPREb_1")
        assertFalse(PrismLibrary(dir).isSaved("MPREb_1"))
        assertTrue(PrismLibrary(dir).isSaved("MPREb_2"))
        dir.deleteRecursively()
    }

    /**
     * A smart playlist's rules are kept in prism.json (so a Library backup carries them); songs can't be added by hand,
     * and what it found is kept without moving it up the list.
     */
    @Test
    fun smartPlaylistsKeepTheirRules() {
        val dir = Files.createTempDirectory("prismlib").toFile()
        val lib = PrismLibrary(dir)
        val rules = SmartRules(
            listOf(SmartRule(SmartField.BPM, min = 120.0, max = 140.0), SmartRule(SmartField.TAG, not = true, text = "gym")),
            SmartMatch.ANY, SmartSort.ENERGY, null,
        )
        val id = lib.createSmart("Fast", rules)
        val plain = lib.create("Plain")
        assertEquals("nothing added by hand", 0, lib.add(id, listOf(song("a"))))
        lib.keepFound(id, listOf(song("b"), song("c")))
        assertEquals("finding songs doesn't move it up", plain, lib.lists.value.first().id)

        val again = PrismLibrary(dir).list(id)!!
        assertEquals(rules, again.smart)
        assertEquals(listOf("b", "c"), again.songs.map { it.id })
        assertNull("a plain playlist has no rules", PrismLibrary(dir).list(plain)!!.smart)

        lib.setSmart(id, "Faster", rules.copy(limit = 25))
        assertEquals(25, PrismLibrary(dir).list(id)!!.smart!!.limit)
        assertEquals("Faster", PrismLibrary(dir).list(id)!!.title)
        dir.deleteRecursively()
    }

    /** A playlist copied from YouTube Music again is brought up to date: new songs in, removed ones out, no repeats. */
    @Test
    fun aCopiedPlaylistIsReplacedNotAddedTo() {
        val dir = Files.createTempDirectory("prismlib").toFile()
        val lib = PrismLibrary(dir)
        val id = lib.create("Road trip", listOf(song("a"), song("b")), "From YouTube Music")
        lib.replace(id, listOf(song("b"), song("c"), song("c")))
        assertEquals(listOf("b", "c"), PrismLibrary(dir).list(id)!!.songs.map { it.id })
        assertEquals("From YouTube Music", PrismLibrary(dir).list(id)!!.description)
        dir.deleteRecursively()
    }
}
