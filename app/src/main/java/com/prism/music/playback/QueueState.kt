package com.prism.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class QueueKind { SINGLE, ALBUM, PLAYLIST, RADIO, LIBRARY, REPLAY, OTHER }

data class QueueSource(
    val kind: QueueKind,
    val title: String,
    /** Seed for autoplay / radio continuation. */
    val seedId: String? = null,
    /** The album, playlist or artist the queue was started from, so a play button for it can show pause. */
    val originId: String? = null,
    /** The album or playlist itself, as a card that opens it: Home's Recently played can list it (#196). */
    val page: com.prism.music.data.model.BrowseItem? = null,
) {
    /** A radio started from one song: it keeps going with similar music, Autoplay or not. */
    val isSongRadio: Boolean get() = kind == QueueKind.RADIO && seedId != null
}

/** Shared queue bookkeeping between the UI-facing [PlayerConnection] and [PlaybackService]. */
class QueueState {
    val source = MutableStateFlow(QueueSource(QueueKind.OTHER, ""))
    /** audio track id -> official music video id */
    val counterparts = ConcurrentHashMap<String, String>()
    /** ids YouTube had no music video for: not asked again for an hour (a bad moment shouldn't hide one all session). */
    val noVideo = NoVideo()

    class NoVideo {
        private val at = ConcurrentHashMap<String, Long>()
        fun add(id: String) { at[id] = System.currentTimeMillis() }
        operator fun contains(id: String): Boolean {
            val t = at[id] ?: return false
            if (System.currentTimeMillis() - t < RETRY_MS) return true
            at.remove(id)
            return false
        }
        private companion object { const val RETRY_MS = 60 * 60_000L }
    }
    @Volatile var autoplayContinuation: String? = null
    /** The other song's radio this queue moved on to when its own ran dry (No repeats this session), and for which queue. */
    @Volatile private var reseeded: Pair<QueueSource, String>? = null
    fun reseed(source: QueueSource, seed: String) { reseeded = source to seed }
    /** The radio [source] moved on to, if it did. A new queue is a new source, so it starts from its own again. */
    fun reseededFor(source: QueueSource): String? = reseeded?.takeIf { it.first === source }?.second
    @Volatile var autoplayLoading = false
    /** A long list's later pages are still on their way into the queue (#356): autoplay's suggestions wait for them. */
    @Volatile var filling = false
    /** Index in the queue where autoplay suggestions begin (-1 when none). */
    val autoplayStart = MutableStateFlow(-1)
    /** Songs the listener added themselves (Play next, Add to queue) and hasn't reached yet: "Next in queue". */
    val userQueued = MutableStateFlow<Set<String>>(emptySet())
    /** Playing along with a Listen Together host: their queue decides what's next (no autoplay or skipping here). */
    @Volatile var followingHost = false
    /** Smart Shuffle: shuffle with recommendations mixed in (the shuffle button's third state). */
    val smartShuffle = MutableStateFlow(false)
    /** The songs Smart Shuffle added (marked ✦ in the queue, counted as "smart" plays). */
    val smartPicks = MutableStateFlow<Set<String>>(emptySet())

    /** Queues Smart Shuffle can add to: the listener's own lists, not radios (already all recommendations). */
    fun smartAllowed(): Boolean = !followingHost && source.value.kind in setOf(QueueKind.PLAYLIST, QueueKind.ALBUM, QueueKind.LIBRARY)
}
