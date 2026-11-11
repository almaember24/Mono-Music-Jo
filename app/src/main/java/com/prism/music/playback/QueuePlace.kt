package com.prism.music.playback

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * #388 (1.8.3): where playback is in the queue, kept every [EVERY_MS] while playing. queue.json is only written when
 * the queue or the song changes (and on pause), so after a crash or Android closing Prism for memory the song started
 * again near 0. This is a few bytes in a file of its own; the queue's list isn't written again for it.
 */
@Serializable
data class QueuePlace(val songId: String, val index: Int, val positionMs: Long)

class PlaceKeeper(private val write: (QueuePlace) -> Unit) {
    private var last: QueuePlace? = null

    /** Called every [EVERY_MS] while playing: writes [now] only when playback moved on since the last write. */
    fun tick(now: QueuePlace?) {
        if (now == null || now.songId.isEmpty()) return
        val l = last
        if (l != null && l.songId == now.songId && l.index == now.index && abs(now.positionMs - l.positionMs) < MIN_MOVE_MS) return
        last = now
        write(now)
    }

    /** The queue itself was just saved with its own place: the next tick compares with that. */
    fun saved(now: QueuePlace?) { last = now }

    companion object {
        const val EVERY_MS = 10_000L
        private const val MIN_MOVE_MS = 2_000L

        /**
         * Where a restored queue starts: the kept place when it's newer than the queue file and its song is still in the
         * queue (at its index, else wherever it is), else the queue file's own place.
         */
        fun start(songIds: List<String>, index: Int, positionMs: Long, place: QueuePlace?, placeIsNewer: Boolean): Pair<Int, Long> {
            if (place == null || !placeIsNewer || songIds.isEmpty()) return index to positionMs
            val at = if (songIds.getOrNull(place.index) == place.songId) place.index else songIds.indexOf(place.songId)
            return if (at < 0) index to positionMs else at to place.positionMs.coerceAtLeast(0)
        }
    }
}
