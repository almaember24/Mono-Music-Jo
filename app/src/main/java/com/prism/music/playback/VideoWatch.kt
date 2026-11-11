package com.prism.music.playback

/*
 * When a music video that isn't showing gives way to its song (1.8.0, the owner's TV: after a fast skip or a long
 * seek the video often never came back, only the cover). The rules are plain functions so they're tested on the PC.
 */

/** How often the watchdog looks for the video's picture. */
const val VIDEO_WATCH_STEP_MS = 20_000L
/** How long a video still loading is waited for before its song plays instead. */
const val VIDEO_WAIT_MAX_MS = 80_000L
/** A video's streams are asked for afresh after an error at most this often (more errors sooner: back to the song). */
const val VIDEO_RETRY_WINDOW_MS = 60_000L

enum class VideoWatch { FINE, WAIT, GIVE_UP }

/**
 * The watchdog's verdict [waitedMs] after the video was switched on: it has a picture (fine); it's still loading and
 * hasn't been waited for too long (wait); or the song plays on without a picture, or loading never ends (give up).
 */
fun videoWatchVerdict(hasPicture: Boolean, buffering: Boolean, waitedMs: Long): VideoWatch = when {
    hasPicture -> VideoWatch.FINE
    buffering && waitedMs < VIDEO_WAIT_MAX_MS -> VideoWatch.WAIT
    else -> VideoWatch.GIVE_UP
}

/** Whether a video that just failed may have its streams asked for afresh: never yet, or not in the last minute. */
fun videoRetryAllowed(lastRetryAt: Long?, now: Long): Boolean = lastRetryAt == null || now - lastRetryAt >= VIDEO_RETRY_WINDOW_MS

/** A music video's retry: its streams asked for afresh, and with [forgetCachedSound], its sound's cached part dropped too. */
data class VideoRetry(val forgetCachedSound: Boolean)

/**
 * Whether a music video that failed gets another go ([videoRetryAllowed]) and how; null: back to the song. Refused (403,
 * 410), unreadable or a network failure is worth one. [unreadable] (#411): its bytes couldn't be read, so its sound's
 * cached part goes too, as a song's does: asking afresh alone read the same cached bytes again and failed at once.
 */
fun videoRetry(httpCode: Int?, unreadable: Boolean, network: Boolean, lastRetryAt: Long?, now: Long): VideoRetry? =
    if (videoRetryAllowed(lastRetryAt, now) && (httpCode == 403 || httpCode == 410 || unreadable || network)) VideoRetry(forgetCachedSound = unreadable)
    else null
