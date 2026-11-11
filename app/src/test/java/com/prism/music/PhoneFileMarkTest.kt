package com.prism.music

import android.net.Uri
import androidx.media3.exoplayer.offline.Download
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.local.LocalLossless
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.download.DownloadInfo
import com.prism.music.download.rowDownloaded
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #385 (1.8.3): rows played from a FLAC on the phone showed the downloaded mark only now and then. Playing matches the
 * file by the song, the mark went by id, and a song has more than one id across playlists. The mark now asks the same
 * matcher, so every id of the song is marked.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class PhoneFileMarkTest {
    private val flac = LocalLossless.Track(Uri.parse("content://media/external/audio/media/1"), "Get Lucky", "Daft Punk", 248_000L, "audio/flac", 30_000_000L)
    private val files = LocalLossless(ApplicationProvider.getApplicationContext()).apply { use(listOf(flac)) }
    private fun song(id: String, title: String = "Get Lucky", sec: Int = 248) = Song(id, title, listOf(ArtistRef("Daft Punk")), durationSec = sec)

    // Lossless sync matched the file to the album's id only.
    private val synced = mapOf("albumId" to DownloadInfo(song("albumId"), Download.STATE_COMPLETED, 100f, flac.size, lossless = true))
    private val fromPhone: (Song) -> Boolean = { s -> files.match(s, note = false) != null }

    @Test fun twoIdsForOneSongAreBothMarked() {
        assertTrue(rowDownloaded(song("albumId"), synced, emptyMap(), fromPhone))
        assertTrue("another playlist's id for the same song", rowDownloaded(song("otherId", "Get Lucky (Radio Edit)", 249), synced, emptyMap(), fromPhone))
        assertTrue("the old id-only mark missed it", synced["otherId"] == null)
    }

    @Test fun otherSongsVideosAndNoSyncAreNotMarked() {
        assertFalse(rowDownloaded(song("x", "One More Time", 320), synced, emptyMap(), fromPhone))
        assertFalse(rowDownloaded(song("v").copy(isVideo = true), synced, emptyMap(), fromPhone))
        // Lossless sync has nothing: the matcher isn't asked at all.
        assertFalse(rowDownloaded(song("otherId"), emptyMap(), emptyMap(), { error("not asked") }))
    }

    @Test fun askingFromARowIsntCountedAsAPlay() {
        files.match(song("otherId"), note = false)
        assertTrue(files.matched.isEmpty())
    }
}
