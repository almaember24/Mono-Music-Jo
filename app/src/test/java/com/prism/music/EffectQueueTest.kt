package com.prism.music

import com.prism.music.data.prefs.EqSettings
import com.prism.music.playback.AudioEffects
import com.prism.music.playback.EffectQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Audio effects run on their own thread (an ANR on the emulator: the main thread stuck in
 * DynamicsProcessing.setNumberArray while audioserver was hung). The caller never waits, work keeps its order, and a
 * burst of setting changes is one apply with the newest settings.
 */
class EffectQueueTest {
    /** A queue whose thread is stuck (as on a stalled audio server) until [unstick] counts down. */
    private fun stuckQueue(unstick: CountDownLatch): Pair<EffectQueue, java.util.concurrent.ExecutorService> {
        val executor = EffectQueue.thread()
        val queue = EffectQueue(executor)
        queue.run { unstick.await(10, TimeUnit.SECONDS) }
        return queue to executor
    }

    @Test fun theCallerNeverWaitsOnAStalledServer() {
        val unstick = CountDownLatch(1)
        val (queue, executor) = stuckQueue(unstick)
        val start = System.nanoTime()
        repeat(200) { queue.run { }; queue.applyLatest { } }
        val tookMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("handing work over took $tookMs ms", tookMs < 500)
        unstick.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun aBurstOfChangesIsOneApplyWithTheNewestSettings() {
        val unstick = CountDownLatch(1)
        val (queue, executor) = stuckQueue(unstick)
        val wanted = java.util.concurrent.atomic.AtomicInteger(0)
        val applied = Collections.synchronizedList(mutableListOf<Int>())
        for (i in 1..50) { wanted.set(i); queue.applyLatest { applied += wanted.get() } }
        unstick.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf(50), applied)
    }

    @Test fun workKeepsItsOrderAndCloseComesLast() {
        val unstick = CountDownLatch(1)
        val (queue, executor) = stuckQueue(unstick)
        val order = Collections.synchronizedList(mutableListOf<String>())
        queue.run { order += "attach" }
        queue.applyLatest { order += "apply" }
        queue.run { order += "release" }
        queue.close { order += "shutdown" }
        // After closing, more work is quietly dropped (the service is gone), not thrown.
        queue.run { order += "late" }
        queue.applyLatest { order += "late apply" }
        unstick.countDown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf("attach", "apply", "release", "shutdown"), order)
    }

    @Test fun aChangeWhileApplyingIsAppliedToo() {
        val executor = EffectQueue.thread()
        val queue = EffectQueue(executor)
        val inside = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        val wanted = java.util.concurrent.atomic.AtomicInteger(1)
        val applied = Collections.synchronizedList(mutableListOf<Int>())
        queue.applyLatest { val w = wanted.get(); inside.countDown(); goOn.await(5, TimeUnit.SECONDS); applied += w }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        wanted.set(2)
        queue.applyLatest { applied += wanted.get() }
        goOn.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2), applied)
    }

    @Test fun audioEffectsHandsEverythingOver() {
        val unstick = CountDownLatch(1)
        val executor = EffectQueue.thread()
        executor.execute { unstick.await(10, TimeUnit.SECONDS) }
        val effects = AudioEffects(android.content.ContextWrapper(null), executor)
        val start = System.nanoTime()
        effects.attach(42)
        repeat(100) { i -> effects.apply(EqSettings(enabled = true, preampDb = i / 10f)) }
        effects.setNormalization(-3f)
        effects.release()
        effects.shutdown()
        val tookMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("AudioEffects calls took $tookMs ms with the effects thread stuck", tookMs < 500)
        // What's shown (stats for nerds) follows the settings at once, before the thread gets to them.
        assertTrue(effects.activeSummary.contains("Normalize -3.0 dB"))
        unstick.countDown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
    }
}
