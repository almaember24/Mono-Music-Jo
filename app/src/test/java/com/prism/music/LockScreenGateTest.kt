package com.prism.music

import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.BehaviorPrefs
import com.prism.music.data.prefs.LockPaused
import com.prism.music.ui.lockscreen.LockScreenActivity
import com.prism.music.ui.lockscreen.LockScreenGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/** When the lock screen goes up, with the screen's broadcasts and the keyguard played by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@androidx.annotation.OptIn(UnstableApi::class)
class LockScreenGateTest {
    private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var playing = true
        var buffering = false

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(MediaItemData.Builder("song").build()))
            .setPlaybackState(if (buffering) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()

        fun change(block: FakePlayer.() -> Unit) {
            block()
            invalidateState()
        }
    }

    private val app: Application = RuntimeEnvironment.getApplication()
    private val player = FakePlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var gate: LockScreenGate? = null

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun start(stay: LockPaused) {
        ShadowSettings.setCanDrawOverlays(true)
        val settings = AppSettings(behavior = BehaviorPrefs(lockScreen = true, lockScreenPaused = stay))
        gate = LockScreenGate(app, flowOf(settings), player, scope).also { it.start() }
        idle()
    }

    private fun change(block: FakePlayer.() -> Unit) {
        player.change(block)
        idle()
    }

    private fun locked(on: Boolean) = shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(on)
    private fun interactive(on: Boolean) = shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(on)

    private fun screen(action: String) {
        app.sendBroadcast(Intent(action))
        idle()
    }

    private fun lockScreenStarted(): Boolean {
        var found = false
        while (true) {
            val next = shadowOf(app).nextStartedActivity ?: break
            if (next.component?.className == LockScreenActivity::class.java.name) found = true
        }
        return found
    }

    @After fun cleanUp() {
        gate?.stop()
        scope.cancel()
        idle()
    }

    /** It comes back with the music, not with the next player event and the next time the phone wakes. */
    @Test fun untilThePhoneSleepsItStaysAwayOnceItHas() {
        start(LockPaused.UNTIL_SLEEP)
        change { playing = false }
        screen(Intent.ACTION_SCREEN_OFF)
        // Asleep and paused, the player still has things to say.
        change { buffering = true }
        change { buffering = false }
        locked(true)
        screen(Intent.ACTION_SCREEN_ON)
        assertFalse("it came back with the music still paused", lockScreenStarted())

        interactive(true)
        change { playing = true }
        assertTrue("played again from the lock screen, it comes back", lockScreenStarted())
    }

    @Test fun beforeTheSleepItStaysWhilePaused() {
        start(LockPaused.UNTIL_SLEEP)
        change { playing = false }
        locked(true)
        screen(Intent.ACTION_SCREEN_ON)
        assertTrue(lockScreenStarted())
    }

    /** D43 (1.8.3): started as the screen went off, still not up as the phone wakes: the settings page says it was refused. */
    @Test fun aQuietlyRefusedStartIsNotedForTheSettingsPage() {
        com.prism.music.ui.lockscreen.LockScreenRefusals.clear(app)
        locked(true)
        interactive(false)
        start(LockPaused.TEN_MINUTES)
        screen(Intent.ACTION_SCREEN_OFF)
        assertTrue("started as the screen went off", lockScreenStarted())
        // The phone wakes and it never came up (a maker's own permission held it back).
        interactive(true)
        screen(Intent.ACTION_SCREEN_ON)
        assertTrue(com.prism.music.ui.lockscreen.LockScreenRefusals.lately(app))
        // The next one that comes up clears it.
        LockScreenGate.directStartWorked(app)
        assertFalse(com.prism.music.ui.lockscreen.LockScreenRefusals.lately(app))
    }

    /** A start that came up last time says nothing against starting it directly now. */
    @Test fun aDirectStartThatWorkedIsUsedAgain() {
        locked(true)
        interactive(false)
        start(LockPaused.TEN_MINUTES)
        screen(Intent.ACTION_SCREEN_OFF)
        assertTrue("up as the screen goes off", lockScreenStarted())
        LockScreenGate.directStartWorked(app)

        // It was closed since; then Android's own lock screen controls start the music again, the screen on.
        interactive(true)
        change { playing = false }
        change { playing = true }
        assertTrue("it went to the notification, or nowhere", lockScreenStarted())
    }

    /** A phone that quietly refused keeps the notification's way in. */
    @Test fun aDirectStartThatNeverCameUpIsntTriedAgainUntilTheNextSleep() {
        locked(true)
        interactive(false)
        start(LockPaused.TEN_MINUTES)
        screen(Intent.ACTION_SCREEN_OFF)
        assertTrue(lockScreenStarted())

        interactive(true)
        change { playing = false }
        change { playing = true }
        assertFalse(lockScreenStarted())
    }
}
