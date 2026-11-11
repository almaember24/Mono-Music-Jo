package com.prism.music

import com.prism.music.data.local.LocalMusic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #207, #249: which folders Local files leaves out. */
class LocalFoldersTest {
    @Test fun folderFromAndroid10Up() {
        assertEquals("Music/Audiobooks/", LocalMusic.folderOf("Music/Audiobooks/", "external_primary"))
        assertEquals("1234-ABCD:Music/", LocalMusic.folderOf("Music/", "1234-abcd".uppercase()))
        assertEquals("", LocalMusic.folderOf("/", "external_primary"))
        assertEquals("", LocalMusic.folderOf(null, "external_primary"))
    }

    @Test fun folderBelowAndroid10ReadsTheSame() {
        assertEquals("Music/Audiobooks/", LocalMusic.folderOf("/storage/emulated/0/Music/Audiobooks/a.mp3", null))
        assertEquals("1234-ABCD:Music/", LocalMusic.folderOf("/storage/1234-ABCD/Music/a.mp3", null))
        assertEquals("", LocalMusic.folderOf("/storage/emulated/0/a.mp3", null))
        assertEquals("", LocalMusic.folderOf("/storage/1234-ABCD/a.mp3", null))
    }

    @Test fun aFolderAndWhatsInsideIt() {
        val skips = setOf("Music/Audiobooks/")
        assertTrue(LocalMusic.isSkipped("Music/Audiobooks/", skips))
        assertTrue(LocalMusic.isSkipped("Music/Audiobooks/Long one/", skips))
        assertTrue(LocalMusic.isSkipped("music/audiobooks/", skips))
        // A folder whose name only starts the same stays.
        assertFalse(LocalMusic.isSkipped("Music/Audiobooks 2/", skips))
        assertFalse(LocalMusic.isSkipped("Music/", skips))
        // The same path on a memory card is another folder.
        assertFalse(LocalMusic.isSkipped("1234-ABCD:Music/Audiobooks/", skips))
        // Files at the top of a storage have no folder to leave out.
        assertFalse(LocalMusic.isSkipped("", skips))
        assertFalse(LocalMusic.isSkipped("Music/", emptySet()))
    }

    @Test fun theTreeCountsWhatsBelow() {
        val rows = LocalMusic.folderRows(
            mapOf("Music/Old/A/" to 3, "Music/Old/B/" to 2, "Music/" to 1, "Download/" to 4, "1234-ABCD:Music/" to 7),
            setOf("Music/Old/"),
        )
        assertEquals(listOf("1234-ABCD:Music/", "Download/", "Music/", "Music/Old/", "Music/Old/A/", "Music/Old/B/"), rows.map { it.path })
        val by = rows.associateBy { it.path }
        assertEquals(6, by["Music/"]!!.songs)
        // A parent with no files of its own is listed, so all of it can be left out at once.
        assertEquals(5, by["Music/Old/"]!!.songs)
        assertEquals("Old", by["Music/Old/"]!!.name)
        assertEquals(1, by["Music/Old/"]!!.depth)
        assertEquals("1234-ABCD: Music", by["1234-ABCD:Music/"]!!.name)
        assertTrue(by["Music/Old/"]!!.skipped)
        assertFalse(by["Music/Old/"]!!.parentSkipped)
        assertTrue(by["Music/Old/A/"]!!.skipped)
        assertTrue(by["Music/Old/A/"]!!.parentSkipped)
        assertFalse(by["Music/"]!!.skipped)
    }

    /** A folder left out and later removed from the phone can still be ticked back. */
    @Test fun aFolderThatsGoneStaysListed() {
        val rows = LocalMusic.folderRows(mapOf("Music/" to 1), setOf("Podcasts/"))
        assertEquals(listOf("Music/", "Podcasts/"), rows.map { it.path })
        assertEquals(0, rows[1].songs)
        assertTrue(rows[1].skipped)
    }
}
