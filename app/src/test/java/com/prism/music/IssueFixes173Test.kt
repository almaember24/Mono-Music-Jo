package com.prism.music

import com.prism.music.data.resilient
import com.prism.music.playback.toMediaItem
import com.prism.music.playback.unavailableHere
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** GitHub issues fixed for 1.7.3. (Robolectric: media items use android.net.Uri.) */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class IssueFixes173Test {
    /** #103: a database list whose read fails (a phone's cursor window giving out) is read again instead of closing Prism. */
    @Test
    fun aFailedLibraryReadIsReadAgain() = runBlocking {
        var tries = 0
        val songs = flow {
            tries++
            if (tries < 3) throw IllegalStateException("Couldn't read row 1248, col 0 from CursorWindow")
            emit(listOf("a", "b"))
        }.resilient("liked songs", stepMs = 1)
        assertEquals(listOf("a", "b"), songs.first())
        assertEquals(3, tries)
    }

    /** #101: the listener's own uploads are songs, not videos ("Songs, not videos" swapped them for YouTube's recording). */
    @Test
    fun uploadsAreSongs() {
        assertFalse(com.prism.music.data.innertube.Parser.isVideoType("MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"))
        assertFalse(com.prism.music.data.innertube.Parser.isVideoType("MUSIC_VIDEO_TYPE_ATV"))
        assertFalse(com.prism.music.data.innertube.Parser.isVideoType(null))
        assertTrue(com.prism.music.data.innertube.Parser.isVideoType("MUSIC_VIDEO_TYPE_OMV"))
        assertTrue(com.prism.music.data.innertube.Parser.isVideoType("MUSIC_VIDEO_TYPE_UGC"))
        // An upload goes to the player marked, so it's asked for as the account at once.
        val item = com.prism.music.data.model.Song("abc", "Intro (full)", isUpload = true).toMediaItem()
        assertEquals("1", item.localConfiguration!!.uri.getQueryParameter("upload"))
        assertEquals(null, com.prism.music.data.model.Song("abc", "A song").toMediaItem().localConfiguration!!.uri.getQueryParameter("upload"))
    }

    /** #93: YouTube's "not available" (where the listener is) is told apart from songs that need an account. */
    @Test
    fun unavailableSongsAreToldApart() {
        assertTrue(unavailableHere(IOException("This video is not available", org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException("Got error"))))
        assertTrue(unavailableHere(IOException("Video unavailable")))
        assertTrue(unavailableHere(IOException("The uploader has not made this video available in your country")))
        assertFalse(unavailableHere(IOException("Sign in to play age-restricted songs", org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException("This age-restricted video cannot be watched."))))
        assertFalse(unavailableHere(IOException("This song needs YouTube Music Premium: sign in with a Premium account to play it")))
        assertFalse(unavailableHere(IOException("Unable to resolve host \"rr1---sn.googlevideo.com\"")))
    }
}
