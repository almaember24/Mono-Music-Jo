package com.prism.music

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.prism.music.playback.NoteVideoLegFailures
import com.prism.music.playback.VideoLegs
import com.prism.music.playback.VideoRetry
import com.prism.music.playback.videoRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * GitHub #411: a music video failed with "Skipping atom with length > 2147483647" and the log couldn't say whether its
 * picture or its sound did, nor what the picture was streamed as; and its retry asked for fresh links but read the
 * sound's same cached bytes again.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoLegFailureTest {
    @Before fun clear() { VideoLegs.last = null }

    private fun failure(asked: String, opened: String, e: IOException) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(1, DataSpec(Uri.parse(asked)), Uri.parse(opened), emptyMap(), 0, 0, 0),
        MediaLoadData(C.DATA_TYPE_MEDIA), e, 1,
    )

    private val unreadable = ParserException.createForUnsupportedContainerFeature("Skipping atom with length > 2147483647 (unsupported).")

    @Test fun thePicturesFailureNamesItsItagClientAndLength() {
        val policy = NoteVideoLegFailures(picture = true, DefaultLoadErrorHandlingPolicy()) { 5_000 }
        val delay = policy.getRetryDelayMsFor(failure(
            "prism://video/52Gg9CqhbP8",
            "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-x&itag=137&c=VISIONOS&clen=41817896", unreadable,
        ))
        assertEquals("handled as before (an unreadable file isn't retried)", C.TIME_UNSET, delay)
        val f = VideoLegs.lastFor("52Gg9CqhbP8", 6_000)!!
        assertTrue(f.picture)
        assertEquals("its picture failed (itag 137, client VISIONOS, clen 41817896: ParserException)", f.describe())
        assertNull("another video's failure isn't this one's", VideoLegs.lastFor("other", 6_000))
        assertNull("an old failure isn't the one reported now", VideoLegs.lastFor("52Gg9CqhbP8", 5_000 + VideoLegs.VIDEO_LEG_FAILURE_MS + 1))
    }

    @Test fun theSoundsFailureSaysItWasReadFromThePhone() {
        val policy = NoteVideoLegFailures(picture = false, DefaultLoadErrorHandlingPolicy()) { 5_000 }
        policy.getRetryDelayMsFor(failure("prism://audio/52Gg9CqhbP8?leg=video", "prism://audio/52Gg9CqhbP8?leg=video", unreadable))
        val f = VideoLegs.lastFor("52Gg9CqhbP8", 5_000)!!
        assertFalse(f.picture)
        assertTrue(f.describe(), f.describe().startsWith("its sound failed (read from the phone"))
    }

    @Test fun aSongsOwnFailureIsNotAVideos() {
        NoteVideoLegFailures(picture = false, DefaultLoadErrorHandlingPolicy()) { 5_000 }
            .getRetryDelayMsFor(failure("prism://audio/mSFKMCMTcow", "https://rr1---sn-test.googlevideo.com/videoplayback?itag=251", unreadable))
        assertNull(VideoLegs.last)
    }

    @Test fun anUnreadableVideosRetryDropsItsSoundsCachedPart() {
        assertEquals(VideoRetry(forgetCachedSound = true), videoRetry(null, unreadable = true, network = false, lastRetryAt = null, now = 1_000))
        assertEquals(VideoRetry(forgetCachedSound = false), videoRetry(403, unreadable = false, network = false, lastRetryAt = null, now = 1_000))
        assertEquals(VideoRetry(forgetCachedSound = false), videoRetry(null, unreadable = false, network = true, lastRetryAt = null, now = 1_000))
        assertNull("a second failure within the minute goes back to the song", videoRetry(null, unreadable = true, network = false, lastRetryAt = 1_000, now = 2_000))
        assertNull("nothing a retry helps", videoRetry(404, unreadable = false, network = false, lastRetryAt = null, now = 1_000))
    }
}
