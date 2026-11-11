package com.prism.music

import com.prism.music.playback.QueueDrag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D46 (1.8.3): dragging a song in the queue lagged: every row it crossed moved it in the player (and the queue was read
 * again). The rows now trade places on screen, and one move in the player, on letting go, gives the same order.
 */
class QueueDragTest {
    private data class E(val index: Int, val uid: String)
    private val section = (10 until 30).map { E(it, "s$it") }
    private val uid: (E) -> String = { it.uid }

    /** The player's queue after moveMediaItem(from, to). */
    private fun moved(q: List<Int>, from: Int, to: Int) = q.toMutableList().apply { add(to, removeAt(from)) }

    @Test fun aDragIsOneMoveInThePlayerWithTheSameResult() {
        val queue = (0 until 40).toList()
        // The old way: one move per row crossed, five rows down from index 12.
        var old = queue
        var position = 12
        repeat(5) { old = moved(old, position, position + 1); position++ }

        var drag = QueueDrag("s12", 0)
        var moves = 0
        repeat(5) { drag = QueueDrag.step(section, drag, down = true, uid)!! }
        val target = QueueDrag.target(section, drag, uid)!!
        val now = moved(queue, 12, target.index).also { moves++ }
        assertEquals(1, moves)
        assertEquals(old, now)
        // What the list showed while dragging is the order the player ends up with.
        assertEquals(now.subList(10, 30).map { "s$it" }, QueueDrag.shown(section, drag, uid).map { it.uid })
    }

    @Test fun upAndBackToWhereItWasMovesNothing() {
        var drag = QueueDrag("s20", 0)
        repeat(3) { drag = QueueDrag.step(section, drag, down = false, uid)!! }
        repeat(3) { drag = QueueDrag.step(section, drag, down = true, uid)!! }
        assertNull(QueueDrag.target(section, drag, uid))
        assertEquals(section, QueueDrag.shown(section, drag, uid))
    }

    @Test fun theSectionsEndsAreLeftToTheOldWay() {
        assertNull(QueueDrag.step(section, QueueDrag("s10", 0), down = false, uid))
        assertNull(QueueDrag.step(section, QueueDrag("s29", 0), down = true, uid))
        assertEquals(QueueDrag("s28", 1), QueueDrag.step(section, QueueDrag("s28", 0), down = true, uid))
    }
}
