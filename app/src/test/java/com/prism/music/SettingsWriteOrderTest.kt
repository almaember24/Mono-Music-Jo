package com.prism.music

import androidx.datastore.preferences.core.booleanPreferencesKey
import com.prism.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The settings race (1.8.3.1): a change still waiting in the write queue landed after an editNow (a backup restore, a
 * Look applied, a sign-in) and undid part of it. editNow now waits its turn in the same queue.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class SettingsWriteOrderTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val pureBlack = booleanPreferencesKey("pure_black")
    private val haptics = booleanPreferencesKey("haptics")

    /** A writer held back until [open]: what's asked for meanwhile waits in the queue, as on a busy phone. */
    private class Gate {
        private val latch = CountDownLatch(1)
        private val pool = Executors.newSingleThreadExecutor().also { it.execute { latch.await() } }
        val dispatcher = pool.asCoroutineDispatcher()
        fun open() = latch.countDown()
        fun close() { open(); pool.shutdown() }
    }

    private fun repo(gate: Gate? = null, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) =
        if (gate == null) SettingsRepository(app, scope) else SettingsRepository(app, scope, gate.dispatcher)

    // The store is one per process: what a test writes is cleared after it (see BackupPartsTest).
    @After fun clear() = runBlocking { withTimeout(10_000) { repo().editNow { it.clear() } } }

    /** Everything asked for so far has landed: a last change is asked for and waited for (the queue keeps order). */
    private suspend fun settle(r: SettingsRepository) {
        val flip = !r.stored.value.haptics
        r.setHaptics(flip)
        withTimeout(10_000) { r.stored.first { it.haptics == flip } }
    }

    private suspend fun stored(r: SettingsRepository) = r.forBackup(settings = true, accounts = false)

    /**
     * A change queued behind a held-back writer, then editNow of another value for the same key: once everything has
     * landed, editNow's value is the one kept. Before 1.8.3.1 editNow wrote at once and the queued change landed after.
     */
    private fun queuedChangeThenEditNow(editNow: suspend (SettingsRepository) -> Unit) = runBlocking {
        val gate = Gate()
        try {
            val r = repo(gate)
            r.setPureBlack(true)
            val now = CoroutineScope(Dispatchers.Default).async { editNow(r) }
            // The old editNow didn't wait for the queue and is done here already; the new one waits for the writer.
            withTimeoutOrNull(1_000) { now.await() }
            gate.open()
            withTimeout(10_000) { now.await() }
            settle(r)
            // (A Look stores a default as no value at all, so it's read back as the settings see it.)
            assertFalse("editNow's value stays", r.stored.value.pureBlack)
        } finally { gate.close() }
    }

    @Test fun aQueuedChangeCantUndoAnEditNow() = queuedChangeThenEditNow { r -> r.editNow { it[pureBlack] = false } }

    @Test fun aQueuedChangeCantUndoARestore() =
        queuedChangeThenEditNow { r -> r.fromBackup(mapOf("pure_black" to "b:false"), settings = true, accounts = false) }

    @Test fun aQueuedChangeCantUndoALook() = queuedChangeThenEditNow { r -> r.applyLook(mapOf("pure_black" to "b:false")) }

    /** What was queued before editNow has landed by the time editNow returns (sign-in then a read of the store). */
    @Test fun editNowReturnsAfterWhatWasQueuedBeforeIt() = runBlocking {
        val r = repo()
        r.setPureBlack(true)
        withTimeout(10_000) { r.editNow { it[haptics] = false } }
        assertEquals("b:true", stored(r)["pure_black"])
        assertEquals("b:false", stored(r)["haptics"])
    }

    /** From the callers' own places: the main thread's runBlocking, the IO pool, a plain thread (Handoff), many at once. */
    @Test fun editNowDoesntHangFromCallersContexts() {
        val r = repo()
        // Main thread (Robolectric's test thread), blocking as Handoff does.
        runBlocking { withTimeout(10_000) { r.editNow { it[pureBlack] = true } } }
        runBlocking {
            withTimeout(10_000) {
                // The IO pool, as LibraryRepository.switchAccount and the restore at start-up.
                withContext(Dispatchers.IO) { r.saveIdentity(1, "sync", null) }
                // Many at once, mixed with queued changes.
                (1..50).map { i ->
                    async(Dispatchers.Default) {
                        r.setHaptics(i % 2 == 0)
                        r.editNow { it[pureBlack] = i % 2 == 0 }
                    }
                }.awaitAll()
            }
        }
        // A plain thread blocking on it (Handoff.signIn), then a queued change after it.
        val done = CountDownLatch(1)
        Thread {
            runBlocking {
                r.fromBackup(mapOf("cookie" to "s:SID=x"), settings = false, accounts = true)
                r.setOnboarded(true)
            }
            done.countDown()
        }.start()
        assertTrue("a plain thread's editNow finished", done.await(10, TimeUnit.SECONDS))
        runBlocking { withTimeout(10_000) { r.stored.first { it.onboarded && it.cookie == "SID=x" } } }
    }

    /** An editNow that fails tells its caller, writes none of itself, and the queue carries on after it. */
    @Test fun aFailingEditNowReachesItsCallerAndTheQueueGoesOn() = runBlocking {
        val r = repo()
        withTimeout(10_000) { r.editNow { it[pureBlack] = false; it[haptics] = true } }
        val error = runCatching { withTimeout(10_000) { r.editNow { it[pureBlack] = true; error("broken") } } }.exceptionOrNull()
        assertNotNull("the caller sees the error", error)
        assertEquals("broken", error!!.message)
        assertEquals("nothing of it written", "b:false", stored(r)["pure_black"])
        settle(r)
        withTimeout(10_000) { r.editNow { it[pureBlack] = true } }
        assertEquals("b:true", stored(r)["pure_black"])
    }

    /** Once the repository's scope has ended (Robolectric ends the app's after each test) editNow still writes. */
    @Test fun editNowAfterTheScopeEndedDoesntHang() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = repo(scope = scope)
        scope.launch { }.join()
        scope.cancel()
        withTimeout(10_000) { r.editNow { it[pureBlack] = true } }
        assertEquals("b:true", stored(r)["pure_black"])
    }
}
