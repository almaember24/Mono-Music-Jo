package com.prism.music

import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #275 (1.8.1.1): the DJ button can leave the queue's header, for room there (DJ mode can be one of the player's
 * buttons instead). Shown as before until it's turned off, kept when it is, and part of a Look like Queue headings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class QueueDjChipTest {
    private fun repo() = SettingsRepository(
        org.robolectric.RuntimeEnvironment.getApplication(),
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
    )

    @Test fun theQueueKeepsItsDjButtonUntilItsTurnedOff() {
        assertTrue(AppSettings().view.queueDjChip)
    }

    @Test fun turnedOffItStaysOff(): Unit = kotlinx.coroutines.runBlocking {
        val repo = repo()
        repo.setQueueDjChip(false)
        kotlinx.coroutines.withTimeout(5_000) { repo.flow.first { !it.view.queueDjChip } }
        repo.setQueueDjChip(true)
        kotlinx.coroutines.withTimeout(5_000) { repo.flow.first { it.view.queueDjChip } }
    }

    @Test fun itsPartOfALookAsQueueHeadingsIs() {
        val names = repo().lookKinds.keys
        assertTrue("queue_dj_chip" in names && "queue_headings" in names)
    }
}
