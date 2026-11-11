package com.prism.music

import android.net.Uri
import com.prism.music.data.local.LocalMusic
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.7.8's local-files fixes. (Robolectric: local tracks carry an android.net.Uri.) */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class IssueFixes178LocalTest {
    private fun track(id: Long, album: String?, trackNo: Int = 0, year: Int = 0) = LocalMusic.Track(
        Song(id = Song.LOCAL_PREFIX + id, title = "Song $id", artists = listOf(ArtistRef("Someone $id")), album = album?.let { AlbumRef(it) }),
        Uri.parse("content://media/external/audio/media/$id"), 7, trackNo, year, 0, "audio/mp4", 1,
    )

    /** #143: an "album" that's only the folder's name (Android names untagged songs' albums after the folder). */
    @Test
    fun albumsNamedAfterTheFolderAreCheckedAgainstTheFile() {
        val folders = mapOf(1L to "Quick Share", 2L to "Quick Share", 3L to "Homogenic", 4L to "Download")
        val candidates = LocalMusic.untaggedCandidates(
            listOf(
                track(1, "Quick Share"),
                // Same folder name but numbered: a real album in a folder of its own name.
                track(2, "Quick Share", trackNo = 3, year = 2019),
                track(3, "Homogenic", trackNo = 1, year = 1997),
                // A tagged album in a folder named otherwise.
                track(4, "Fragile"),
            ),
            folders,
        )
        assertEquals(listOf(Song.LOCAL_PREFIX + 1), candidates.map { it.song.id })
    }

    @Test
    fun folderNames() {
        assertEquals("Quick Share", LocalMusic.folderName("Download/Quick Share/"))
        assertEquals("Quick Share", LocalMusic.folderName("/storage/emulated/0/Download/Quick Share/a song.m4a"))
        assertEquals("Music", LocalMusic.folderName("Music/"))
        assertNull(LocalMusic.folderName(""))
        assertNull(LocalMusic.folderName(null))
    }
}
