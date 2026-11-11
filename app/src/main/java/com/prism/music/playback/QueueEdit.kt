package com.prism.music.playback

/** Moving several of the queue's songs at once (R11, #159), worked out apart from the player so it can be tested. */
object QueueEdit {
    /** The player's moves (from, to) in order, the queue's order after them, and where autoplay's suggestions start. */
    class Plan(val moves: List<Pair<Int, Int>>, val order: List<Int>, val autoplayStart: Int)

    /**
     * Moves the songs at [indices] (of a queue of [size]) one after another: each comes out, and [to] gives its new place
     * in a running copy of the queue's order (the songs' original indices). The songs moved are the listener's from then
     * on, so autoplay's suggestions ([autoplayStart], -1 for none) start again at the first of theirs that stayed.
     */
    fun plan(size: Int, indices: List<Int>, autoplayStart: Int, to: (MutableList<Int>, Int) -> Int): Plan {
        val list = MutableList(size) { it }
        val moves = ArrayList<Pair<Int, Int>>()
        for (s in indices) {
            val from = list.indexOf(s)
            if (from < 0) continue
            list.removeAt(from)
            val at = to(list, s).coerceIn(0, list.size)
            list.add(at, s)
            moves += from to at
        }
        val moved = indices.toSet()
        val auto = if (autoplayStart < 0) -1 else list.indexOfFirst { it >= autoplayStart && it !in moved }.let { if (it < 0) list.size else it }
        return Plan(moves, list, auto)
    }

    /**
     * #368: Play next on a song already coming up moves it up instead of putting it in twice. [ahead]: the ids of the
     * songs still to come, in play order; [wanted]: the songs tapped. The places in [ahead] of the copies that make way
     * (the first copy of each song, so a list that holds a song twice keeps its other copy), lowest first.
     */
    fun copiesMovingUp(ahead: List<String>, wanted: List<String>): List<Int> {
        val taken = java.util.TreeSet<Int>()
        for (id in wanted) ahead.indices.firstOrNull { it !in taken && ahead[it] == id }?.let { taken += it }
        return taken.toList()
    }

    /** The queue's sections, which between them list the songs in exactly the order they play. */
    class Sections<T>(val queued: List<T>, val upcoming: List<T>, val autoplay: List<T>)

    /**
     * #368: [order] (what's still to come, in play order) under the queue's headings. "Next in queue" is the listener's
     * songs that really come next; the rest stay where they play. It used to pick the listener's songs out from anywhere:
     * a song added to the queue was listed first though it plays after the list's own songs (Next seemed to skip it), and
     * a song tapped with Play next had its other copy, further down, listed right beside it.
     */
    fun <T> sections(order: List<T>, isQueued: (T) -> Boolean, isAutoplay: (T) -> Boolean): Sections<T> {
        val queued = order.takeWhile(isQueued)
        val rest = order.drop(queued.size)
        val upcoming = rest.takeWhile { !isAutoplay(it) }
        return Sections(queued, upcoming, rest.drop(upcoming.size))
    }

    /**
     * Where Undo puts a song taken out of the queue back (#149; Reddit, 1.8.1.1: "Undo does nothing"): its place in the
     * play order as it is when Undo is tapped. [order]: the songs' ids in play order now (the shuffled order while
     * shuffled); [now]: the playing song's place in it (-1: none). [after] / [before]: the songs it played after and
     * before when it went; [ahead]: it was still to come then; [near]: its place in the play order then.
     *
     * It goes back beside those songs (the nearest, for a song in the queue twice), on its own side of the playing song.
     * It used to go back by its old index, or (shuffled, 1.8.0) beside its neighbours matched as the player's items, so
     * once the song before it had finished (or a neighbour's item changed, as the next song's does when its stream is
     * lined up) it landed among the songs already played, or at the end of the shuffled order: out of sight.
     */
    fun undoSpot(order: List<String>, now: Int, after: String?, before: String?, ahead: Boolean, near: Int): Int {
        fun nearest(id: String?) = id?.let { order.indices.filter { i -> order[i] == it }.minByOrNull { i -> kotlin.math.abs(i - near) } }
        fun fits(spot: Int) = now < 0 || (if (ahead) spot > now else spot <= now)
        nearest(after)?.let { it + 1 }?.takeIf(::fits)?.let { return it }
        nearest(before)?.takeIf(::fits)?.let { return it }
        return when {
            now < 0 -> near.coerceIn(0, order.size)
            ahead -> near.coerceIn(now + 1, order.size)
            else -> near.coerceIn(0, now)
        }
    }
}
