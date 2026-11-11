package com.prism.music.playback

/**
 * D46 (1.8.3): a song dragged in the queue. Each row it crossed used to move it in the player at once (and the whole
 * queue was read again), which lagged on long queues. Now the rows trade places on screen only, [offset] places from
 * where the song was in its section, and the player moves it once, when the finger lets go ([target]).
 */
data class QueueDrag(
    val uid: String,
    val offset: Int,
    /** Let go and sent to the player: still shown this way until the queue shows it moved (no flicker back meanwhile). */
    val settled: Boolean = false,
) {
    companion object {
        /** [section] as it shows while [drag] is under way: the dragged song [QueueDrag.offset] places from its own. */
        fun <T> shown(section: List<T>, drag: QueueDrag?, uid: (T) -> String): List<T> {
            if (drag == null || drag.offset == 0) return section
            val at = section.indexOfFirst { uid(it) == drag.uid }
            if (at < 0) return section
            val to = (at + drag.offset).coerceIn(0, section.lastIndex)
            return section.toMutableList().apply { add(to, removeAt(at)) }
        }

        /** One step down or up, or null at the section's end (the caller then does what it did before). */
        fun <T> step(section: List<T>, drag: QueueDrag, down: Boolean, uid: (T) -> String): QueueDrag? {
            val at = section.indexOfFirst { uid(it) == drag.uid }
            if (at < 0) return null
            val next = drag.offset + if (down) 1 else -1
            return if (at + next in section.indices) drag.copy(offset = next) else null
        }

        /** The song the dragged one ends up beside (in its place, pushed on by one), or null when it didn't move. */
        fun <T> target(section: List<T>, drag: QueueDrag, uid: (T) -> String): T? {
            if (drag.offset == 0) return null
            val at = section.indexOfFirst { uid(it) == drag.uid }
            return section.getOrNull(at + drag.offset).takeIf { at >= 0 }
        }
    }
}
