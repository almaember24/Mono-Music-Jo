package com.prism.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.SongVersions
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A file on the phone as a song's version (1.8.3, #243): picked, it plays from the file and is never asked of YouTube
 * (streams, downloads and scans use the song's own); kept; and "Original" goes back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FileVersionTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("versions-test", Context.MODE_PRIVATE)
    private val song = Song("abc", "Mr. Brightside", listOf(ArtistRef("The Killers")), durationSec = 222)
    private val file = Song("${Song.LOCAL_PREFIX}42", "Mr. Brightside", listOf(ArtistRef("The Killers")), durationSec = 223)
    private val video = Song("vid", "Mr. Brightside (Live)", listOf(ArtistRef("The Killers")), isVideo = true)

    @Test fun aFilePlaysFromTheFileAndIsNeverStreamed() {
        prefs.edit().clear().commit()
        val v = SongVersions(prefs)
        v.set(song.id, SongVersions.Pick(file.id, "Your file", file))
        assertEquals(file.id, v.fileOf(song.id))
        assertNull("YouTube is asked for the song's own", v.streamedOf(song.id))
        assertEquals("kept", file.id, SongVersions(prefs).fileOf(song.id))
        assertEquals("shows as the file", 223, v.shown(song).durationSec)

        v.set(song.id, SongVersions.Pick(video.id, "Video", video))
        assertNull(v.fileOf(song.id))
        assertEquals("a YouTube version is still streamed", video.id, v.streamedOf(song.id))

        v.set(song.id, null)
        assertNull(v.of(song.id))
        assertNull(SongVersions(prefs).fileOf(song.id))
    }
}
