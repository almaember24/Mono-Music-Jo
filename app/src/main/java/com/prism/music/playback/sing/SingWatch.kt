package com.prism.music.playback.sing

import androidx.media3.common.Player

/**
 * Sing's watchdog rules (1.8.1, Reddit: Sing on a Google TV Streamer stopped the music, which sat "loading" till Prism
 * was reinstalled): when the player counts as stuck, and when it's been stuck long enough ([limitMs]) for Sing to let go
 * of the song in hand. One [check] each time the player is looked at.
 */
internal class SingWatch(private val limitMs: Long) {
    /** When the player was first seen stuck (ms, elapsed realtime), or -1. */
    private var since = -1L

    /** The player is [stuck] now ([nowMs]): true once it's been so for [limitMs] running. */
    fun check(stuck: Boolean, nowMs: Long): Boolean {
        if (!stuck) { since = -1; return false }
        if (since < 0) since = nowMs
        return nowMs - since >= limitMs
    }

    /** How long the player's been stuck (0: it isn't). */
    fun stuckFor(nowMs: Long): Long = if (since < 0) 0 else nowMs - since

    fun reset() { since = -1 }

    companion object {
        /** How long the player may sit loading while Sing works before Sing lets go. */
        const val STUCK_MS = 12_000L
        /** Sing switched off while the player has sat loading this long: the song loads again at once. */
        const val OFF_STUCK_MS = 2_000L

        /** It should be playing, and it's loading or stopped on an error. */
        fun stuck(playWhenReady: Boolean, state: Int, error: Boolean): Boolean =
            playWhenReady && (state == Player.STATE_BUFFERING || (state == Player.STATE_IDLE && error))

        /**
         * What the watchdog counts: [stuck] only while Sing is [busy] with a song. An error with Sing idle (offline, a
         * refused link) is the player's own, retried or skipped by the playback service: Sing letting go would race it.
         */
        fun counts(busy: Boolean, playWhenReady: Boolean, state: Int, error: Boolean): Boolean =
            busy && stuck(playWhenReady, state, error)

        /**
         * Sing's engine isn't loaded on this version: it took Prism down as it loaded twice running ([unfinished] loads
         * that never finished), or did so before on this same version ([brokenOn]). A new version tries it again.
         */
        fun engineBroken(unfinished: Int, brokenOn: Int, version: Int): Boolean = brokenOn == version || unfinished >= 2
    }
}
