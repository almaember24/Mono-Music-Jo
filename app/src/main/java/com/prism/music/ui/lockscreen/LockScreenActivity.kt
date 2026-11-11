package com.prism.music.ui.lockscreen

import com.prism.music.R
import com.prism.music.tr
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.prism.music.PrismActivity
import com.prism.music.container
import com.prism.music.data.PlaylistCovers
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.FontChoice
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.rememberFrost
import com.prism.music.ui.components.tickWhileShown
import com.prism.music.ui.player.currentOutput
import com.prism.music.ui.player.outputLabel
import com.prism.music.ui.player.rememberLyricClock
import com.prism.music.ui.player.rememberLyricsLook
import com.prism.music.ui.player.rememberLyricsState
import com.prism.music.ui.player.rememberPlayerVolume
import com.prism.music.ui.player.rememberPositionState
import com.prism.music.ui.screens.LookTheme
import com.prism.music.ui.theme.AppFonts
import com.prism.music.ui.theme.CoverShapes
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Prism's own lock screen, shown over Android's while something plays (1.8.2; off by default; [LockScreenGate] starts it).
 *
 * It can only do what the [LockScreenActions] say: like, previous, play / pause, next, seek, volume, and ask to unlock.
 * There is no navigation in here and nothing that opens a library, search, settings or a song's menu, because whoever is
 * holding the phone isn't necessarily its owner. Unlocking closes it, leaving the listener where they were.
 */
class LockScreenActivity : ComponentActivity() {
    companion object {
        /** The one that's up, if any; the gate asks before starting another and closes it when the music ends. */
        @Volatile
        internal var live: LockScreenActivity? = null
            private set

        /** For in-app tests on a phone with no lock: behave as if it were locked. */
        @VisibleForTesting
        internal var pretendLocked = false
    }

    private val c by lazy { container }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }

    /** What to do once the phone is unlocked (opening the full player, after a tap on the cover). */
    private var afterUnlock: (() -> Unit)? = null

    /** Android's unlock prompt is up: this may stop behind it, and the prompt says what comes next. */
    private var asking = false

    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.prism.music.AppLanguage.wrap(base))

    // Fingerprint, face or the code: the phone is the listener's again, and they carry on where they were.
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = done()
    }

    private var startedAt = 0L
    private var drawnSinceStart = false
    private val firstDraw = ViewTreeObserver.OnDrawListener {
        if (!drawnSinceStart && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            drawnSinceStart = true
            Log.i(LockScreenGate.TAG, "first frame ${SystemClock.elapsedRealtime() - startedAt} ms after start, interactive=${power.isInteractive}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        startedAt = SystemClock.elapsedRealtime()
        // The page is always dark, so the bars' icons are always light, even when the phone is in light mode.
        val darkBars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = darkBars, navigationBarStyle = darkBars)
        super.onCreate(savedInstanceState)
        live = this
        if (!intent.getBooleanExtra(LockScreenGate.FROM_NOTIFICATION, false)) LockScreenGate.directStartWorked(this)
        // The manifest's showWhenLocked starts at Android 8.1; Android 8.0 only has the window flag.
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 27) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        // Upright on a phone, as Android's lock screen is; a tablet stays however it's standing.
        if (resources.configuration.smallestScreenWidthDp >= 600) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        LockScreenGate.cancelFallback(this)
        // Exported on purpose: System UI sends this one, not the system's own id, so a not-exported receiver never hears it
        // (a Pixel on Android 17, 1.8.2's check). It's a protected broadcast: no other app can send it.
        ContextCompat.registerReceiver(this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        c.player.connect()
        window.decorView.viewTreeObserver.addOnDrawListener(firstDraw)
        setContent {
            val settings by c.settings.flow.collectAsState()
            CompositionLocalProvider(LocalContainer provides c) {
                LockTheme(settings) { LockScreenHost(settings, ::finishNow, ::unlock) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startedAt = SystemClock.elapsedRealtime()
        drawnSinceStart = false
        // Woken during the lock delay (never locked), or the music has been paused too long for it to stay: Android's own.
        if (!mayShow()) finishNow()
    }

    override fun onStop() {
        super.onStop()
        // Stopped with the screen off: that's going to sleep, ready for the next wake. With the screen on, something took its
        // place (Home, the camera, a call): left alive, the gate would take it for still up and never bring it back.
        val covered = power.isInteractive && !asking && !isChangingConfigurations
        Log.i(LockScreenGate.TAG, "stopped, covered=$covered asking=$asking interactive=${power.isInteractive}")
        if (covered) finishNow()
    }

    private fun mayShow(): Boolean {
        val b = c.settings.current.behavior
        if (!b.lockScreen && !pretendLocked) return false
        if (!LockScreenRules.stays(b.lockScreenPaused, LockScreenGate.pausedSince, SystemClock.elapsedRealtime())) return false
        return pretendLocked || !power.isInteractive || keyguard.isKeyguardLocked
    }

    /**
     * Asks Android to unlock: nothing to do on a swipe lock, the code or fingerprint on a secure one. While it asks, this
     * stays behind Android's prompt, and a cancelled prompt comes back to it.
     */
    private fun unlock(then: (() -> Unit)?) {
        afterUnlock = then
        asking = true
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { asking = false; done() }
            override fun onDismissCancelled() {
                Log.i(LockScreenGate.TAG, "unlock prompt cancelled")
                asking = false
                afterUnlock = null
                promptGone()
            }
            // Not locked after all (a lock delay, or unlocked a moment ago): nothing stands in the way.
            override fun onDismissError() {
                asking = false
                if (pretendLocked || !keyguard.isKeyguardLocked) done() else { afterUnlock = null; promptGone() }
            }
        })
    }

    /** The prompt went without unlocking, and took this off screen with it: Android's lock screen is what's left. */
    private fun promptGone() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && power.isInteractive) finishNow()
    }

    private fun done() {
        val then = afterUnlock
        afterUnlock = null
        then?.invoke()
        finishNow()
    }

    private fun finishNow() {
        if (!isFinishing) finishAndRemoveTask()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        runCatching { window.decorView.viewTreeObserver.removeOnDrawListener(firstDraw) }
        if (live === this) live = null
        super.onDestroy()
    }
}

/**
 * The listener's own theme for the app's globals (the light, its contrast), and a dark scheme for what's on it: the lock
 * screen is white on dark like the player, without touching the theme the rest of the app is drawn in.
 */
@Composable
internal fun LockTheme(settings: AppSettings, content: @Composable () -> Unit) {
    PrismTheme(settings, null) { LookTheme(settings, true) { content() } }
}

/** The real thing: the player's state made into a [LockScreenContent], inside the swipe that unlocks. */
@Composable
private fun LockScreenHost(settings: AppSettings, close: () -> Unit, unlock: ((() -> Unit)?) -> Unit) {
    val c = LocalContainer.current
    val pc = c.player
    val context = LocalContext.current
    val song by pc.currentSong.collectAsState()
    val current = song
    if (current == null) {
        // The queue was emptied: there's nothing for it to control. (Black until then: the connection may be on its way.)
        Box(Modifier.fillMaxSize().background(Color.Black))
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(EMPTY_GRACE_MS)
            if (pc.currentSong.value == null) close()
        }
        return
    }
    val hide = settings.behavior.lockScreenHideSong
    val layout = LockScreenRules.layoutFor(settings.behavior.lockScreenLayout, settings.performanceMode, hide)
    val playing by pc.isPlaying.collectAsState()
    val buffering by pc.buffering.collectAsState()
    val likedIds by c.library.likedIds.collectAsState()
    val position = rememberPositionState(fast = false)
    val duration by remember { derivedStateOf { position.value.let { pc.duration } } }
    val volume = rememberPlayerVolume()
    val art = remember(current.id) { PlaylistCovers.artOf(current) }
    val frost = if (hide || layout == com.prism.music.data.prefs.LockLayout.QUIET) null else rememberFrost(art)?.image
    val (time, date) = rememberClockText()
    val output = lockOutput(settings)
    val lyrics = if (layout == com.prism.music.data.prefs.LockLayout.LYRICS) rememberLockLyrics(current, art) else null
    // 1.8.3 (D47): read only when switched on, so the queue isn't watched otherwise.
    val repeat = if (settings.behavior.lockScreenRepeat) pc.repeatMode.collectAsState().value else null
    val upNext = if (settings.behavior.lockScreenUpNext && !hide) {
        val queue by pc.queue.collectAsState()
        val order by pc.upNext.collectAsState()
        remember(queue, order) { LockScreenRules.upNextLines(queue, order, hideSong = false) }
    } else emptyList()
    val content = LockScreenContent(
        title = if (hide) tr(R.string.lock_screen_hidden_title) else current.title,
        artist = if (hide) "" else current.artistText,
        frost = frost,
        cover = if (hide) { m, shape -> HiddenCover(m, shape) } else { m, shape -> Artwork(art, m, shape = shape) },
        coverShape = lockCoverShape(settings),
        playing = playing,
        loading = buffering,
        liked = current.id in likedIds,
        // Liking says which song it is, so it goes with the name.
        showLike = settings.behavior.lockScreenLike && !hide && !current.isLocal,
        showVolume = settings.behavior.lockScreenVolume,
        output = if (settings.behavior.lockScreenOutput) output else null,
        position = { position.value },
        durationMs = duration.coerceAtLeast(0L),
        seekable = !current.isLive && duration > 0,
        volume = { volume.level },
        time = time,
        date = date,
        lyrics = lyrics,
        repeat = repeat,
        upNext = upNext,
    )
    val actions = remember(current.id, duration) {
        LockScreenActions(
            onCover = { unlock { context.startActivity(Intent(context, PrismActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("prism://player")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            onLike = { c.library.toggleLike(current) },
            onPrevious = { pc.previous() },
            onPlayPause = { pc.togglePlay() },
            onNext = { pc.next() },
            onSeek = { f -> pc.seekTo((f * pc.duration).toLong()) },
            onVolume = { v -> volume.drag(v) },
            onVolumeDone = { volume.release() },
            onRepeat = { pc.cycleRepeat() },
        )
    }
    SwipeToUnlock(onUnlock = { unlock(null) }) { LockScreenLayout(layout, content, actions) }
}

/** Once the queue is empty, how long before there's nothing to show (a queue being replaced is empty for a moment). */
private const val EMPTY_GRACE_MS = 1500L

/** The screen follows the finger up; far enough, or flicked, it asks to unlock and comes back to rest behind the prompt. */
@Composable
private fun SwipeToUnlock(onUnlock: () -> Unit, content: @Composable () -> Unit) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reach = with(density) { 96.dp.toPx() }
    val flick = with(density) { 900.dp.toPx() }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            var lastAt = 0L
            var speed = 0f
            detectVerticalDragGestures(
                onDragStart = { lastAt = SystemClock.uptimeMillis(); speed = 0f },
                onDragEnd = {
                    val far = -offset.value >= reach || speed <= -flick
                    scope.launch { offset.animateTo(0f) }
                    if (far) onUnlock()
                },
                onDragCancel = { scope.launch { offset.animateTo(0f) } },
            ) { change, dy ->
                change.consume()
                val now = SystemClock.uptimeMillis()
                if (now > lastAt) speed = dy / (now - lastAt) * 1000f
                lastAt = now
                scope.launch { offset.snapTo((offset.value + dy).coerceAtMost(0f)) }
            }
        },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationY = offset.value * 0.6f
                alpha = 1f - (abs(offset.value) / (reach * 3f)).coerceIn(0f, 0.6f)
            },
        ) { content() }
    }
}

/** The cover when the song is hidden: nothing of the song, just Prism's light. */
@Composable
internal fun HiddenCover(modifier: Modifier, shape: Shape) {
    Box(modifier.clip(shape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        Icon(PrismIcons.Song, null, Modifier.size(40.dp).padding(2.dp), tint = Color.White.copy(alpha = 0.6f))
    }
}

/** The cover's shape on the player: the Look's corners and shape. */
internal fun lockCoverShape(s: AppSettings): Shape = CoverShapes.of(s.shapes.player, s.playerArtCorners.dp)

/** The clock and the date, as the phone writes them (no AM / PM, as lock screens have it). Looked at every second, changed every minute. */
@Composable
private fun rememberClockText(): Pair<String, String> {
    val context = LocalContext.current
    val locale = remember { Locale.getDefault() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val timePattern = remember {
        val skeleton = if (android.text.format.DateFormat.is24HourFormat(context)) "Hm" else "hm"
        android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton).replace("a", "").replace("B", "").trim(' ', ' ', ' ')
    }
    val datePattern = remember { android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd") }
    fun texts(): Pair<String, String> {
        val now = Date()
        return SimpleDateFormat(timePattern, locale).format(now) to SimpleDateFormat(datePattern, locale).format(now).uppercase(locale)
    }
    var shown by remember { mutableStateOf(texts()) }
    LaunchedEffect(lifecycle) {
        var minute = -1L
        lifecycle.tickWhileShown(1000) {
            val m = System.currentTimeMillis() / 60_000
            if (m != minute) { minute = m; shown = texts() }
        }
    }
    return shown
}

/** Where the sound is going, read only (no tapping through to the picker); looked at only while the screen is on. */
@Composable
private fun lockOutput(settings: AppSettings): LockOutput? {
    val c = LocalContainer.current
    val context = LocalContext.current
    val preferred by c.player.preferredOutput.collectAsState()
    val profile by c.account.profile.collectAsState()
    val castState by c.cast.state.collectAsState()
    val phoneName = (profile?.name ?: settings.accountName).trim().split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.let { tr(R.string.s_phone, it) } ?: tr(R.string.this_phone)
    var current by remember { mutableStateOf<AudioDeviceInfo?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (settings.behavior.lockScreenOutput) {
        LaunchedEffect(preferred, lifecycle) {
            lifecycle.tickWhileShown(1500) { current = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { currentOutput(context, preferred) } }
        }
    }
    when (val cs = castState) {
        is com.prism.music.connect.CastState.Casting -> return LockOutput(PrismIcons.Cast, tr(R.string.playing_on, cs.renderer.name))
        is com.prism.music.connect.CastState.Connecting -> return LockOutput(PrismIcons.Cast, tr(R.string.connecting_to, cs.renderer.name))
        else -> {}
    }
    val device = current ?: return LockOutput(PrismIcons.Phone, phoneName)
    return LockOutput(outputIconOf(device), outputLabel(device, phoneName))
}

private fun outputIconOf(d: AudioDeviceInfo) = when (d.type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> PrismIcons.Phone
    AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_DOCK -> PrismIcons.Speaker
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> PrismIcons.Usb
    else -> PrismIcons.Headphones
}

/** The song's synced lyrics as the Lyrics layout's slot; null while there are none (the cover takes their place). */
@Composable
private fun rememberLockLyrics(song: Song, art: String?): (@Composable (Modifier) -> Unit)? {
    val s = LocalAppSettings.current
    val state = rememberLyricsState(song)
    val look = rememberLyricsLook(s, art).withoutCard(s.lyricsOthersAlpha)
    val lines = remember(state.lines, look.uppercase) { state.lines.map(look::shown) }
    if (lines.none { it.timeMs >= 0 }) return null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val axis = lockLyricsAxis(s)
    val playing by LocalContainer.current.player.isPlaying.collectAsState()
    // The word sweep runs only while it can be seen.
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val watch = androidx.lifecycle.LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch) }
    }
    val clock = rememberLyricClock(active = started && playing)
    return { modifier -> LockLyricLines(lines, clock, look, axis, modifier) }
}

/** The lyrics' typeface on its weight axis (as immersive mode): null for System and imported fonts. */
@Composable
private fun lockLyricsAxis(s: AppSettings): FontFamily? {
    val choice = if (s.lyricsFont.isEmpty()) s.fontChoice else FontChoice.entries.firstOrNull { it.name == s.lyricsFont }
    return remember(choice) { choice?.let(AppFonts::axis) }
}
