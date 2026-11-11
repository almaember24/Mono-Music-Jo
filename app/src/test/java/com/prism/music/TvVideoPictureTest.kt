package com.prism.music

import com.prism.music.playback.VIDEO_RETRY_WINDOW_MS
import com.prism.music.playback.VIDEO_WAIT_MAX_MS
import com.prism.music.playback.VideoWatch
import com.prism.music.playback.videoRetryAllowed
import com.prism.music.playback.videoWatchVerdict
import com.prism.music.ui.tv.TvVideoFrames
import com.prism.music.ui.tv.TvVideoStatus
import com.prism.music.ui.tv.tvVideoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's TV (1.8.0): after the next song, a fast skip or a long seek, the video screen often showed only the
 * cover. What it shows now, and when a video gives way to its song.
 */
class TvVideoPictureTest {
    @Test fun thePictureShowsOnlyOnceTheVideoHasAFrame() {
        // The video item with a frame since it started or moved: the picture.
        assertEquals(TvVideoStatus.PICTURE, tvVideoStatus(videoItem = true, frames = true, loading = false, failed = false, settled = true))
        // Just skipped to, or seeked far: the cover with a spinner until a frame comes (it used to stay the cover).
        assertEquals(TvVideoStatus.LOADING, tvVideoStatus(videoItem = true, frames = false, loading = false, failed = false, settled = true))
        // The song while its video is being found: a spinner, not "no video".
        assertEquals(TvVideoStatus.LOADING, tvVideoStatus(videoItem = false, frames = false, loading = true, failed = false, settled = true))
        assertEquals(TvVideoStatus.LOADING, tvVideoStatus(videoItem = false, frames = false, loading = false, failed = false, settled = false))
        // A frame from the song before (a late one) never shows the picture over a song playing as itself.
        assertEquals(TvVideoStatus.LOADING, tvVideoStatus(videoItem = false, frames = true, loading = false, failed = false, settled = false))
        // It stays the song: said why, not a silent cover.
        assertEquals(TvVideoStatus.NO_VIDEO, tvVideoStatus(videoItem = false, frames = false, loading = false, failed = false, settled = true))
        assertEquals(TvVideoStatus.FAILED, tvVideoStatus(videoItem = false, frames = false, loading = false, failed = true, settled = false))
    }

    @Test fun framesCountFromTheLastMove() {
        val frames = TvVideoFrames()
        val posted = ArrayList<Runnable>()
        frames.frame { posted += it }
        frames.frame { posted += it }
        // One post for the first frame since a move, not one per frame.
        assertEquals(1, posted.size)
        posted.forEach { it.run() }
        assertTrue(frames.shown)
        // A seek or a skip: no picture until a new frame.
        frames.reset()
        assertFalse(frames.shown)
        assertEquals(1, frames.resets)
        // A frame posted before the move but run after it doesn't count.
        frames.frame { posted += it }
        frames.reset()
        posted.last().run()
        assertFalse(frames.shown)
    }

    @Test fun aVideoStillLoadingIsWaitedForAndOneWithoutAPictureGivesWay() {
        assertEquals(VideoWatch.FINE, videoWatchVerdict(hasPicture = true, buffering = true, waitedMs = 20_000))
        // Still loading (a slow TV after a fast skip or a long seek): it used to go back to the song after 20 s.
        assertEquals(VideoWatch.WAIT, videoWatchVerdict(hasPicture = false, buffering = true, waitedMs = 20_000))
        assertEquals(VideoWatch.WAIT, videoWatchVerdict(hasPicture = false, buffering = true, waitedMs = VIDEO_WAIT_MAX_MS - 1))
        assertEquals(VideoWatch.GIVE_UP, videoWatchVerdict(hasPicture = false, buffering = true, waitedMs = VIDEO_WAIT_MAX_MS))
        // Playing on without a picture: the song.
        assertEquals(VideoWatch.GIVE_UP, videoWatchVerdict(hasPicture = false, buffering = false, waitedMs = 20_000))
    }

    @Test fun aFailedVideoGetsAFreshTryOnceAMinute() {
        assertTrue(videoRetryAllowed(null, 5_000))
        assertFalse(videoRetryAllowed(5_000, 5_000 + VIDEO_RETRY_WINDOW_MS - 1))
        // A refused link an hour later (a seek far into the video) gets its go, instead of the song for good.
        assertTrue(videoRetryAllowed(5_000, 5_000 + 60 * 60_000))
    }
}
