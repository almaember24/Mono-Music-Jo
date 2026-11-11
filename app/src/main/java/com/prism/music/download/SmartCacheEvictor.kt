package com.prism.music.download

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.IdentityHashMap
import java.util.TreeSet

/**
 * The song cache's half of Smart Downloads: songs you've streamed stay on the phone in whatever of Smart Downloads'
 * space its picks don't use ([maxBytes], asked afresh each time, so it follows the setting and the picks without a
 * restart). When it's full, the songs least likely to come back go first: the longest unplayed, with liked songs
 * ([favoured]) held as if played [FAVOURED_BONUS_MS] later than they were.
 */
@OptIn(UnstableApi::class)
class SmartCacheEvictor(
    private val maxBytes: () -> Long,
    private val favoured: (String) -> Boolean,
    /** All of Smart Downloads' space; below 1 GB (256 and 512 MB, 1.8.3 D42) the floor shrinks with it. */
    private val totalBytes: () -> Long = { Long.MAX_VALUE },
) : CacheEvictor {
    /** Each span's eviction rank, fixed when it's added (the set's order mustn't change under it). */
    private val rank = IdentityHashMap<CacheSpan, Long>()
    private val spans = TreeSet<CacheSpan> { a, b ->
        val d = (rank[a] ?: a.lastTouchTimestamp).compareTo(rank[b] ?: b.lastTouchTimestamp)
        if (d != 0) d else a.compareTo(b)
    }
    private var size = 0L

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        rank[span] = span.lastTouchTimestamp + if (favoured(span.key)) FAVOURED_BONUS_MS else 0L
        spans.add(span)
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) size -= span.length
        rank.remove(span)
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /** Brings the cache within its space now (after the setting shrank, or the picks grew). */
    fun trim(cache: Cache) = synchronized(cache) { evict(cache, 0) }

    private fun evict(cache: Cache, required: Long) {
        val max = limit(maxBytes(), totalBytes())
        while (size + required > max && spans.isNotEmpty()) {
            val first = spans.first()
            cache.removeSpan(first)
            // removeSpan reports back through onSpanRemoved; if it didn't, don't spin on the same span.
            if (spans.contains(first)) { spans.remove(first); rank.remove(first); size -= first.length }
        }
    }

    companion object {
        /** However full Smart Downloads' picks are, songs being played always have this much room. */
        const val MIN_BYTES = 256L * 1024 * 1024
        /** Liked songs are kept as if they'd been played this much more recently. */
        const val FAVOURED_BONUS_MS = 30L * 86_400_000

        /**
         * The cache's room: what the picks leave, never under [MIN_BYTES], or under the picks' unused share
         * (a quarter) of a space smaller than 1 GB, so picks and cache together keep within a 256 or 512 MB choice.
         */
        fun limit(left: Long, total: Long): Long =
            left.coerceAtLeast(minOf(MIN_BYTES, (total * (1 - SmartDownloadWorker.PICK_SHARE)).toLong()))
    }
}
