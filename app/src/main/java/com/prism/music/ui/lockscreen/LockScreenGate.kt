package com.prism.music.ui.lockscreen

import com.prism.music.R
import com.prism.music.tr
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LockLayout
import com.prism.music.data.prefs.LockPaused
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The lock screen's rules, apart from Android so they can be tested. */
internal object LockScreenRules {
    const val TEN_MINUTES_MS = 10 * 60_000L
    /** "Off" while paused still lets a pause be undone: it isn't taken away from under the finger that pressed it. */
    const val OFF_GRACE_MS = 3_000L

    /**
     * "Up next" (1.8.3, D47): the next [count] songs in the order they'll play (shuffle followed), as "Title · Artist".
     * None while the song is hidden: the songs ahead would say what's playing.
     */
    fun upNextLines(queue: List<com.prism.music.playback.QueueEntry>, order: List<Int>, hideSong: Boolean, count: Int = 3): List<String> {
        if (hideSong) return emptyList()
        val byIndex = queue.associateBy { it.index }
        return order.asSequence().mapNotNull { byIndex[it]?.song }.take(count)
            .map { s -> listOf(s.title, s.artistText).filter { it.isNotBlank() }.joinToString(" · ") }.toList()
    }

    /** Performance mode always gets Quiet; a hidden song has no lyrics to show, so the Lyrics layout falls back to Cover. */
    fun layoutFor(chosen: LockLayout, performanceMode: Boolean, hideSong: Boolean): LockLayout = when {
        performanceMode -> LockLayout.QUIET
        hideSong && chosen == LockLayout.LYRICS -> LockLayout.COVER
        else -> chosen
    }

    /** Whether it stays, given when the music was paused ([pausedSince]; 0: it's playing), at [now]. [slept]: the phone has slept since the pause. */
    fun stays(stay: LockPaused, pausedSince: Long, now: Long, slept: Boolean = false): Boolean {
        if (pausedSince == 0L) return true
        val paused = now - pausedSince
        return when (stay) {
            LockPaused.OFF -> paused < OFF_GRACE_MS
            LockPaused.TEN_MINUTES -> paused < TEN_MINUTES_MS
            // The gate closes it as the screen goes off.
            LockPaused.UNTIL_SLEEP -> !slept
        }
    }

    /** How long after the screen goes off, in order, to look again for the phone to have locked (its lock delay). */
    fun nextLookMs(sinceOffMs: Long): Long? = when {
        sinceOffMs < 1_000 -> 200L
        sinceOffMs < 15_000 -> 1_000L
        // Android's longest lock delay is 30 minutes; a phone with no lock at all stops being asked after that.
        sinceOffMs < 35 * 60_000 -> 15_000L
        else -> null
    }
}

/**
 * When Prism's own lock screen goes up. Created by the playback service; it does nothing unless the setting is on and music
 * is playing (or was paused within the setting's time). Android lets an app start a screen from the background only with
 * "Display over other apps" allowed, and then the way that's ready as the phone wakes is to start it as the screen goes
 * off (see [show]): it's drawn once, stopped, and shown by Android as the screen comes on. Nothing runs while the screen
 * is off but a slow look for the phone having locked, on the main thread's clock, while the music is keeping the phone
 * awake anyway.
 */
class LockScreenGate(private val context: Context, private val settings: Flow<AppSettings>, private val player: Player, private val scope: CoroutineScope) : Player.Listener {
    companion object {
        const val TAG = "PrismLock"
        private const val CHANNEL = "lock_screen"
        private const val FALLBACK_ID = 7711

        /** When the music was paused (elapsed realtime), 0 while it plays: the activity reads it as it comes back on screen. */
        @Volatile
        internal var pausedSince = 0L
            private set

        /** Whether a screen can be started as the phone goes to sleep: "Display over other apps", from Android 10. */
        fun canStartDirectly(context: Context): Boolean = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)

        /** From Android 13 that permission is a "restricted setting" for an app installed from a file, as Prism always is. */
        val mayBeRestricted: Boolean get() = Build.VERSION.SDK_INT >= 33

        /** Android's page for that permission (from Android 11 the whole list of apps, whatever is asked for), else Prism's App info. */
        fun openPermissionPage(context: Context) {
            val page = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            openOverPrism(context, page).onFailure { openAppInfo(context) }
        }

        /** Prism's App info: where a phone's "restricted setting" is lifted for an app that wasn't installed from a store. */
        fun openAppInfo(context: Context) {
            openOverPrism(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }

        /** In Prism's own task, so Back comes straight back to the steps (in its own, a phone's Back can stay in Settings). */
        private fun openOverPrism(context: Context, page: Intent) = runCatching { context.startActivity(page) }
            .recoverCatching { context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

        /** On the notification's intent: this start says nothing about whether a direct one works. */
        internal const val FROM_NOTIFICATION = "from_notification"

        /**
         * A direct start has gone out and nothing has come of it yet: when the screen comes on with it still unanswered,
         * the phone quietly refused, and the notification's way is used until the next sleep.
         */
        @Volatile
        private var triedDirect = false

        /** The screen came up from a direct start: that way works, however long ago it was and whatever's closed it since. */
        internal fun directStartWorked(context: Context) {
            triedDirect = false
            LockScreenRefusals.clear(context)
        }

        internal fun cancelFallback(context: Context) {
            NotificationManagerCompat.from(context).cancel(FALLBACK_ID)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private var settingsJob: Job? = null
    private var enabled = false
    private var stay = LockPaused.TEN_MINUTES
    /** It played since the service started: a queue restored paused at start-up doesn't bring up a lock screen. */
    private var played = false
    private var registered = false
    /** The screen went off at (0: it's on), for the looks at the keyguard. */
    private var offAt = 0L
    /** "Until the phone sleeps", and it has with the music paused: it stays away until the music plays again. */
    private var sleptPaused = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOff()
                Intent.ACTION_SCREEN_ON -> screenOn()
            }
        }
    }

    private val lookAtKeyguard = object : Runnable {
        override fun run() {
            if (offAt == 0L) return
            if (keyguard.isKeyguardLocked) { show(woken = false); return }
            val wait = LockScreenRules.nextLookMs(SystemClock.elapsedRealtime() - offAt) ?: return
            handler.postDelayed(this, wait)
        }
    }

    private val pausedTooLong = Runnable { update() }

    fun start() {
        player.addListener(this)
        settingsJob = scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            settings.map { it.behavior.lockScreen to it.behavior.lockScreenPaused }.distinctUntilChanged().collect { (on, paused) ->
                enabled = on
                stay = paused
                update()
            }
        }
    }

    fun stop() {
        player.removeListener(this)
        settingsJob?.cancel()
        enabled = false
        played = false
        pausedSince = 0L
        sleptPaused = false
        triedDirect = false
        handler.removeCallbacksAndMessages(null)
        unregister()
        cancelFallback(context)
        LockScreenActivity.live?.finish()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = update()
    override fun onPlaybackStateChanged(playbackState: Int) = update()
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = update()

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        // Started from headphones or a watch with the phone locked: up it comes, whether the screen is on or off.
        if (isPlaying && registered && keyguard.isKeyguardLocked && LockScreenActivity.live == null) {
            show(woken = context.getSystemService(android.os.PowerManager::class.java).isInteractive)
        }
    }

    /** Playing, or about to be: a song loading isn't a pause. */
    private fun wantsToPlay() = player.playWhenReady && player.mediaItemCount > 0 &&
        (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

    private fun update() {
        handler.removeCallbacks(pausedTooLong)
        val now = SystemClock.elapsedRealtime()
        if (player.mediaItemCount == 0) { played = false; pausedSince = 0L; sleptPaused = false }
        else if (wantsToPlay()) { played = true; pausedSince = 0L; sleptPaused = false }
        else if (played && pausedSince == 0L) pausedSince = now
        val wanted = enabled && played && LockScreenRules.stays(stay, pausedSince, now, sleptPaused)
        if (wanted) {
            register()
            if (pausedSince != 0L) when (stay) {
                LockPaused.OFF -> handler.postDelayed(pausedTooLong, LockScreenRules.OFF_GRACE_MS - (now - pausedSince))
                LockPaused.TEN_MINUTES -> handler.postDelayed(pausedTooLong, LockScreenRules.TEN_MINUTES_MS - (now - pausedSince))
                LockPaused.UNTIL_SLEEP -> {}
            }
        } else {
            unregister()
            offAt = 0L
            handler.removeCallbacks(lookAtKeyguard)
            cancelFallback(context)
            LockScreenActivity.live?.finish()
        }
    }

    private fun register() {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregister() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun screenOff() {
        if (pausedSince != 0L && stay == LockPaused.UNTIL_SLEEP) {
            // "Until the phone sleeps": it just has.
            sleptPaused = true
            LockScreenActivity.live?.finish()
            unregister()
            return
        }
        offAt = SystemClock.elapsedRealtime()
        triedDirect = false
        handler.removeCallbacks(lookAtKeyguard)
        if (LockScreenActivity.live != null) return
        // With a lock delay the phone isn't locked yet: waking it in that time must find the listener where they were,
        // with nothing to close. So it only goes up once the phone has locked.
        lookAtKeyguard.run()
    }

    private fun screenOn() {
        offAt = 0L
        handler.removeCallbacks(lookAtKeyguard)
        if (LockScreenActivity.live == null && keyguard.isKeyguardLocked) show(woken = true)
    }

    /** Calls and alarms stay above it: while one rings or goes on, it doesn't go up at all. */
    private fun somethingMoreImportant(): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE) return true
        return am.activePlaybackConfigurations.any {
            it.audioAttributes.usage == AudioAttributes.USAGE_ALARM || it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        }
    }

    /**
     * Up it goes. Started directly while allowed: as the screen goes off it's ready before the phone next wakes, with no
     * notification. Otherwise, or when a phone quietly refused the direct start, a full-screen notification as the screen
     * comes on, only over a locked phone (on an unlocked one Android makes it a banner), and taken away at once.
     */
    private fun show(woken: Boolean) {
        if (LockScreenActivity.live != null || somethingMoreImportant()) return
        val refused = woken && triedDirect
        val direct = canStartDirectly(context) && !refused
        Log.i(TAG, "showing, way=${if (direct) "direct" else "full-screen"} woken=$woken")
        // D43: allowed, started, and still not up as the phone wakes: the phone refused it. The settings page says so.
        if (refused && canStartDirectly(context)) LockScreenRefusals.note(context)
        val start = Intent(context, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (direct) {
            triedDirect = true
            runCatching { context.startActivity(start) }.onFailure { Log.w(TAG, "couldn't start: $it") }
        } else if (woken) {
            postFallback(start.putExtra(FROM_NOTIFICATION, true))
        }
    }

    private fun postFallback(start: Intent) {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 34 && !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) return
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(tr(R.string.lock_screen_channel)).setDescription(tr(R.string.lock_screen_channel_detail))
                .setSound(null, null).setVibrationEnabled(false).build(),
        )
        val options = if (Build.VERSION.SDK_INT >= 35) {
            ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        } else null
        val pending = PendingIntent.getActivity(context, FALLBACK_ID, start, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, options)
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.lock_screen_title))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setFullScreenIntent(pending, true)
            .build()
        @Suppress("MissingPermission")
        runCatching { nm.notify(FALLBACK_ID, note) }
        // Never left behind: Android fires it again at the next sleep, waking the screen.
        handler.postDelayed({ cancelFallback(context) }, 1500L)
    }
}
