package com.prism.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * The shuffle order, kept so that what's been heard stays behind the playing song and Previous goes back to the song
 * played last. Media3's own order slots every song added later (autoplay's suggestions, Add to queue) in at a random
 * place, often among the songs already heard, so Previous landed on one that had never played. Here songs added
 * straight after the playing one ([current], Play next) come next, and anything else goes at the end of the order.
 * [order] lists queue indices in play order.
 */
@OptIn(UnstableApi::class)
class HistoryShuffleOrder(private val order: IntArray, private val current: () -> Int) : ShuffleOrder {
    private val positions = IntArray(order.size).also { for ((pos, i) in order.withIndex()) it[i] = pos }

    override fun getLength() = order.size
    override fun getNextIndex(index: Int) = positions[index].let { if (it + 1 < order.size) order[it + 1] else C.INDEX_UNSET }
    override fun getPreviousIndex(index: Int) = positions[index].let { if (it > 0) order[it - 1] else C.INDEX_UNSET }
    override fun getLastIndex() = if (order.isEmpty()) C.INDEX_UNSET else order.last()
    override fun getFirstIndex() = if (order.isEmpty()) C.INDEX_UNSET else order.first()

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val added = (insertionIndex until insertionIndex + insertionCount).toList()
        if (order.isEmpty()) return HistoryShuffleOrder(added.shuffled().toIntArray(), current)
        val shifted = order.map { if (it >= insertionIndex) it + insertionCount else it }.toMutableList()
        // [current] is read before the songs go in, so it's still the old queue's index.
        val cur = current().takeIf { it in order.indices }
        val before = insertionIndex - 1
        val at = when {
            cur == null -> shifted.size
            // Play next.
            insertionIndex == cur + 1 -> positions[cur] + 1
            // Added at the end of the queue (autoplay, Add to queue): the end of the order too.
            insertionIndex >= order.size -> shifted.size
            // Next to a song still to come (a song swapped for its video goes in beside itself): beside it here too.
            before >= 0 && positions[before] > positions[cur] -> positions[before] + 1
            else -> shifted.size
        }
        shifted.addAll(at, added)
        return HistoryShuffleOrder(shifted.toIntArray(), current)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        val kept = order.filter { it < indexFrom || it >= indexToExclusive }.map { if (it >= indexToExclusive) it - count else it }
        return HistoryShuffleOrder(kept.toIntArray(), current)
    }

    /** A song dragged to another place in the queue keeps its place in the play order. */
    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder {
        val queue = order.indices.toMutableList()
        val moved = queue.subList(indexFrom, indexToExclusive).toList()
        queue.removeAll(moved)
        queue.addAll(newIndexFrom, moved)
        val newIndexOf = IntArray(order.size).also { for ((newIndex, old) in queue.withIndex()) it[old] = newIndex }
        return HistoryShuffleOrder(order.map { newIndexOf[it] }.toIntArray(), current)
    }

    /** A new queue: a new order, starting from the song it starts on. */
    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder = shuffled(insertionCount, startIndex, current)

    override fun cloneAndClear(): ShuffleOrder = HistoryShuffleOrder(IntArray(0), current)

    /**
     * The order with [target] moved to straight after [from]: jumping to a song in the queue makes it the next one
     * played, so Previous from it comes back to [from].
     */
    fun playingNext(from: Int, target: Int): HistoryShuffleOrder {
        if (from !in order.indices || target !in order.indices || from == target) return this
        val list = order.toMutableList()
        list.remove(target)
        list.add(list.indexOf(from) + 1, target)
        return HistoryShuffleOrder(list.toIntArray(), current)
    }

    /**
     * The order with [index] moved to just after (or before) [beside]: a song dragged in the shuffled queue moves in the
     * play order, which is what the queue shows while shuffled. Nothing moves when either isn't in the order.
     */
    fun placed(index: Int, beside: Int, after: Boolean): HistoryShuffleOrder {
        if (index !in order.indices || beside !in order.indices || index == beside) return this
        val list = order.toMutableList()
        list.remove(index)
        list.add(list.indexOf(beside) + if (after) 1 else 0, index)
        return HistoryShuffleOrder(list.toIntArray(), current)
    }

    /**
     * The order with [added] (songs just put in the queue) scattered among the songs still to come, each at a random
     * place after [playing] and the [keep] songs straight after it: a long list started from its first page shuffles
     * across all of it as the rest arrives (#356). What's been heard stays where it is.
     */
    fun scattered(added: Collection<Int>, playing: Int, keep: Int = 0, random: kotlin.random.Random = kotlin.random.Random.Default): HistoryShuffleOrder {
        val adding = added.toHashSet()
        val list = order.filterNot { it in adding }.toMutableList()
        val first = (list.indexOf(playing) + 1 + keep).coerceAtMost(list.size)
        added.shuffled(random).forEach { list.add(random.nextInt(first, list.size + 1), it) }
        return HistoryShuffleOrder(list.toIntArray(), current)
    }

    companion object {
        /** A fresh random order of [count] songs with [first] (if it's one of them) at the start. */
        fun shuffled(count: Int, first: Int, current: () -> Int): HistoryShuffleOrder {
            val rest = (0 until count).filter { it != first }.shuffled()
            return HistoryShuffleOrder((if (first in 0 until count) listOf(first) + rest else rest).toIntArray(), current)
        }
    }
}
