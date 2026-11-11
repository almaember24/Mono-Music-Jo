package com.prism.music

import com.prism.music.ui.theme.Mono
import com.prism.music.ui.components.quietGraphicsLayers
import com.prism.music.ui.components.tabTint
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import com.prism.music.ui.components.customizable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.MiniPlayerStyle
import com.prism.music.data.prefs.NavBarStyle
import com.prism.music.data.prefs.NavLabels
import com.prism.music.data.prefs.NavTab
import com.prism.music.download.SmartDownloadWorker
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Navigator
import com.prism.music.ui.Routes
import com.prism.music.ui.player.MiniPlayer
import com.prism.music.ui.player.NowPlayingScreen
import com.prism.music.ui.screens.ArtistScreen
import com.prism.music.ui.screens.BrowseScreen
import com.prism.music.ui.screens.CatalogueScreen
import com.prism.music.ui.screens.CollectionScreen
import com.prism.music.ui.screens.CollectionType
import com.prism.music.ui.screens.EqualizerScreen
import com.prism.music.ui.screens.HomeScreen
import com.prism.music.ui.screens.LibraryScreen
import com.prism.music.ui.screens.LoginScreen
import com.prism.music.ui.screens.ReplayScreen
import com.prism.music.ui.screens.SearchScreen
import com.prism.music.ui.screens.SettingsScreen
import com.prism.music.ui.screens.WelcomeScreen
import com.prism.music.ui.theme.BackdropScreen
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalHazeState
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTheme
import com.prism.music.ui.theme.ScreenBackdrop
import com.prism.music.ui.theme.hairline
import com.prism.music.ui.theme.ink3
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.selected
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The app's screen. Declared in the manifest as [PrismActivity], so that ".MainActivity" can be the classic app-icon
 * alias (#20) and home-screen icons made before stay valid.
 */
/** A volume key press on a cast speaker or TV, out of 100. */
private const val VOLUME_KEY_STEP = 4
/** How .prismlook files are sent (a zip: the Look's code and any photos or font it carries). */
private const val LOOK_MIME = "application/zip"

open class MainActivity : ComponentActivity() {
    // Prism's language, when picked in Prism (below Android 13; AppLanguage).
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.prism.music.AppLanguage.wrap(base))

    // Each time Prism comes to the front (at most hourly): it can stay alive for days, with Home never built again (1.8.1.1).
    override fun onResume() {
        super.onResume()
        container.updates.check()
    }
    private val deepLink = MutableStateFlow<Uri?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Prism TV (1.8.0): on a TV, Prism's screens are TvActivity's (made for a remote); whatever opened this one (a
        // link, the media notification, an old shortcut) opens that instead.
        if (com.prism.music.ui.tv.TvDevice.isTv(this)) {
            // Added to its flags, not in their place: a shared file's or link's read permission comes with them.
            startActivity(Intent(intent).setComponent(android.content.ComponentName(this, TvActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
            return
        }
        val c = container
        c.player.connect()
        // The downloads start in the background (their index and cache come off the disk) while the first frame draws;
        // screens read c.downloadState meanwhile, so they never wait for them (1.7.9.3: Home's first frame waited 3.4 s
        // on a slow phone). Media3's download manager still answers on the main thread.
        c.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.downloads } }
        // Not asked here any more (a prompt before the welcome screen, with no reason given): the tour or the first
        // download asks, through this.
        if (c.returningUser == null) c.returningUser = c.settings.current.let { it.onboarded || it.isLoggedIn }
        c.notificationPrompt = { if (Build.VERSION.SDK_INT >= 33) runOnUiThread { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) } }
        SmartDownloadWorker.schedule(this, c.settings.current.smartDownloads)
        // Tidies the smart-download queue (it only adds more if it's been a while).
        if (c.settings.current.smartDownloads) SmartDownloadWorker.runNow(this, force = false)
        // Lossless files added while Prism is closed are picked up by LosslessSyncWorker, scheduled by LosslessSync
        // while the lossless add-on is on.
        if (c.settings.current.isLoggedIn) c.library.syncInBackground()
        // A link, Look or Blend shared to Prism is handled once. Android hands the activity the intent it was first
        // started with again when it comes back after being closed (state restored, opened from Recents, after an
        // update), which showed a shared Look's dialog again on every restart (found in 1.7.7's release check).
        val fresh = savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        deepLink.value = if (fresh) linkIn(intent) else null
        if (intent.action != Intent.ACTION_MAIN) setIntent(Intent(this, javaClass).setAction(Intent.ACTION_MAIN))
        setContent {
            CompositionLocalProvider(
                LocalContainer provides c,
                com.prism.music.ui.player.LocalPip provides inPip.value,
                com.prism.music.ui.theme.LocalWindowLayout provides com.prism.music.ui.theme.rememberWindowLayout(),
            ) {
                PrismRoot(deepLink)
            }
        }
        quietGraphicsLayers()
    }

    // ---------------------------------------------------------------- Picture-in-picture

    /** True while Prism is shown as a picture-in-picture window (a music video floating over other apps). */
    private val inPip = androidx.compose.runtime.mutableStateOf(false)
    /** A music video is playing on the open player, so leaving Prism floats it. */
    private var pipEligible = false
    private var pipAspect = android.util.Rational(16, 9)

    private val pipSupported by lazy { packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    /** Settings → Controls & gestures → Picture-in-picture: "Only with its button" (#167) never floats the video by itself. */
    private val pipOnLeave: Boolean get() = container.settings.current.behavior.pip == com.prism.music.data.prefs.PipMode.AUTO

    private fun pipParams(): android.app.PictureInPictureParams = android.app.PictureInPictureParams.Builder()
        .setAspectRatio(pipAspect)
        .apply { if (Build.VERSION.SDK_INT >= 31) { setAutoEnterEnabled(pipEligible && pipOnLeave); setSeamlessResizeEnabled(true) } }
        .build()

    /** The player says whether a music video is playing on it, and its shape ([width] × [height], 0 if unknown). */
    fun setPipEligible(eligible: Boolean, width: Int = 0, height: Int = 0) {
        if (!pipSupported) return
        pipEligible = eligible
        // Android only takes ratios between 1:2.39 and 2.39:1.
        if (width > 0 && height > 0) pipAspect = android.util.Rational(width, height).let { r ->
            when {
                r.toFloat() > 2.39f -> android.util.Rational(239, 100)
                r.toFloat() < 1 / 2.39f -> android.util.Rational(100, 239)
                else -> r
            }
        }
        // Android 12+ floats the video by itself when the listener goes Home.
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** Floats the music video now (the player's PiP button). False if Android or the listener's settings won't allow it. */
    fun enterPip(): Boolean = pipSupported && runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)

    /** A touch or key: the listener is here (desk mode's ambient screen waits, or goes). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        com.prism.music.ui.tv.Idle.touched()
    }

    /** Every touch: on slow phones background song scans wait while the listener scrolls (#343). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        com.prism.music.playback.MixTempo.touched(ev.actionMasked)
        return super.dispatchTouchEvent(ev)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12 there's no auto-enter: going Home with a video playing floats it here.
        if (Build.VERSION.SDK_INT < 31 && pipEligible && pipOnLeave) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkIn(intent)?.let { deepLink.value = it }
    }

    /** While casting, the volume keys turn the speaker or TV up and down (with Prism open; elsewhere, the media session does). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val cast = container.cast
        if (cast.casting && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            cast.adjustVolume(if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) VOLUME_KEY_STEP else -VOLUME_KEY_STEP)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (container.cast.casting && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** A music.youtube.com link opened in Prism, or a YouTube / YouTube Music link shared to it. */
    private fun linkIn(intent: Intent?): Uri? {
        // A friend's Blend code, shared to Prism from a chat: Blend asks whether to make it.
        val blendCode = if (intent?.action == Intent.ACTION_SEND) com.prism.music.data.blend.BlendCode.findIn(intent.getStringExtra(Intent.EXTRA_TEXT)) else null
        if (blendCode != null) {
            container.blends.pending.value = blendCode
            return Uri.parse("prism://blends")
        }
        // A Look (1.7.3): a code in a message, or a .prismlook file sent or opened. Shown first, with Apply and Save.
        val lookCode = if (intent?.action == Intent.ACTION_SEND) com.prism.music.data.looks.LookCode.findIn(intent.getStringExtra(Intent.EXTRA_TEXT)) else null
        if (lookCode != null) {
            container.looks.decode(lookCode)?.let { container.looks.pending.value = it }
                ?: Toast.makeText(this, tr(R.string.that_look_code_couldn_t_be), Toast.LENGTH_SHORT).show()
            return Uri.parse("prism://looks")
        }
        lookFile(intent)?.let { file ->
            container.scope.launch(Dispatchers.Main) {
                val look = runCatching { contentResolver.openInputStream(file)?.use { container.looks.readFile(it) } }
                look.getOrNull()?.let { container.looks.pending.value = it }
                    ?: Toast.makeText(this@MainActivity, look.exceptionOrNull()?.message ?: tr(R.string.that_file_isn_t_a_prism), Toast.LENGTH_SHORT).show()
            }
            return Uri.parse("prism://looks")
        }
        return linkOf(intent)
    }

    /** A .prismlook file sent to Prism or opened with it. */
    private fun lookFile(intent: Intent?): Uri? {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }
            else -> null
        } ?: return null
        val type = intent?.type ?: contentResolver.getType(uri)
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
        return uri.takeIf { name?.endsWith("." + com.prism.music.data.looks.LookStore.FILE_EXTENSION, ignoreCase = true) == true || type == LOOK_MIME }
    }

    private fun linkOf(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> com.prism.music.data.ShareLinks.findIn(intent.getStringExtra(Intent.EXTRA_TEXT))?.let(Uri::parse).also {
            if (it == null) Toast.makeText(this, tr(R.string.that_isn_t_a_youtube_or), Toast.LENGTH_SHORT).show()
        }
        else -> intent?.data
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** [name]: the listener's own name for it (1.7.6), else its own. */
private fun NavTab.tab(name: String? = null): Tab {
    val label = name ?: label
    return when (this) {
        NavTab.HOME -> Tab(Routes.HOME, label, PrismIcons.Home, PrismIcons.HomeFilled)
        NavTab.SEARCH -> Tab(Routes.SEARCH, label, PrismIcons.Search, PrismIcons.SearchBold)
        NavTab.LIBRARY -> Tab(Routes.LIBRARY, label, PrismIcons.Library, PrismIcons.LibraryFilled)
        NavTab.REPLAY -> Tab(Routes.REPLAY, label, PrismIcons.Replay, PrismIcons.ReplayFilled)
        NavTab.SETTINGS -> Tab(Routes.SETTINGS, label, PrismIcons.Settings, PrismIcons.SettingsFilled)
        NavTab.LOCAL -> Tab(Routes.LOCAL, label, PrismIcons.Phone, PrismIcons.Phone)
        NavTab.DOWNLOADS -> Tab(Routes.DOWNLOADS, label, PrismIcons.Download, PrismIcons.DownloadDone)
        NavTab.LIKED -> Tab(Routes.liked(), label, PrismIcons.Heart, PrismIcons.HeartFilled)
        NavTab.PODCASTS -> Tab(Routes.PODCASTS, label, PrismIcons.Podcasts, PrismIcons.Podcasts)
    }
}

/** Heights of the floating chrome, so pages can leave room for it. */
private fun navHeight(style: NavBarStyle, size: com.prism.music.data.prefs.NavBarSize): Dp = when (style) {
    NavBarStyle.FLOATING -> size.floating.dp
    NavBarStyle.DOCKED -> (size.docked + 2).dp
    NavBarStyle.MINIMAL -> size.minimal.dp
}

/**
 * The pages' transitions with "Slide between screens" (#208): all null when it's off, and the fades apply. [rtl]
 * mirrors them, so a page always comes in from where reading goes.
 */
internal class PageSlides(on: Boolean, rtl: Boolean) {
    private val side = if (rtl) -1 else 1
    private val ms = 360
    val enter: EnterTransition? = if (!on) null else slideInHorizontally(tween(ms)) { it * side } + fadeIn(tween(ms / 2))
    val exit: ExitTransition? = if (!on) null else slideOutHorizontally(tween(ms)) { -it * side / 4 } + fadeOut(tween(ms))
    val popEnter: EnterTransition? = if (!on) null else slideInHorizontally(tween(ms)) { -it * side / 4 } + fadeIn(tween(ms))
    val popExit: ExitTransition? = if (!on) null else slideOutHorizontally(tween(ms)) { it * side } + fadeOut(tween(ms / 2, delayMillis = ms / 2))
}

private fun miniHeight(style: MiniPlayerStyle): Dp = when (style) {
    MiniPlayerStyle.CARD -> 64.dp
    MiniPlayerStyle.SLIM -> 54.dp
    MiniPlayerStyle.PILL -> 56.dp
}

@Composable
private fun rememberArtworkColor(song: Song?, settings: com.prism.music.data.prefs.AppSettings): Color? {
    val context = LocalContext.current
    var color by remember { mutableStateOf<Color?>(null) }
    val swatch = settings.artworkSwatch
    val vivid = settings.artworkVivid
    // "New albums only" (1.7.2): songs from the same album keep the color; otherwise every cover is looked at.
    // The cover picked for the song or its album (#305) gives the colors too.
    val art = song?.let { com.prism.music.data.PlaylistCovers.artOf(it) }
    val picked = song?.let { com.prism.music.data.PlaylistCovers.shared?.picked(it) }
    val key = if (settings.artworkChange == com.prism.music.data.prefs.ArtworkChange.ALBUM) (song?.album?.id ?: song?.album?.title ?: art) to picked else art
    LaunchedEffect(key, swatch, vivid) {
        val url = art ?: return@LaunchedEffect
        // One palette for both: the accent's swatch, and the light "From the cover" (#123; also what its tile in Color shows).
        val made = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(hiRes(url, 160)).allowHardware(false).build()
                val bmp = (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                val p = Palette.from(bmp).generate()
                // A tint on a speck of the picture isn't the cover's color (#236): swatches under 2% of it are left out.
                val total = p.swatches.sumOf { it.population }.coerceAtLeast(1)
                val stops = com.prism.music.ui.theme.SpectrumPresets.fromCover(
                    listOfNotNull(p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch, p.lightMutedSwatch, p.dominantSwatch)
                        .filter { it.population * 50 >= total }.map { Mono.gray(it.rgb or (0xFF shl 24)) },
                )
                com.prism.music.ui.theme.pickSwatch(p, swatch, vivid)?.let { Color(Mono.gray(it)) } to stops
            }.getOrNull()
        } ?: return@LaunchedEffect
        made.first?.let { color = it }
        // The cover light fades as the accent does (#254), unless motion is reduced (Performance mode too) or "At once".
        val stops = com.prism.music.ui.theme.SpectrumPresets
        val old = stops.coverStops
        val ms = if (settings.reduceMotion) 0 else settings.artworkChange.ms
        if (ms <= 0 || old.size < 2 || settings.spectrumPreset != stops.COVER) { stops.coverStops = made.second; return@LaunchedEffect }
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (true) {
            val t = ((androidx.compose.runtime.withFrameMillis { it } - start) / ms.toFloat()).coerceIn(0f, 1f)
            stops.coverStops = stops.fadeStops(old, made.second, androidx.compose.animation.core.FastOutSlowInEasing.transform(t))
            if (t >= 1f) break
            // About 25 a second, not every frame: everything drawn in the light redraws with each step.
            kotlinx.coroutines.delay(40)
        }
        stops.coverStops = made.second
    }
    return color
}

@Composable
private fun PrismRoot(deepLink: MutableStateFlow<Uri?>) {
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val song by c.player.currentSong.collectAsState()
    val artColor = rememberArtworkColor(song, settings)
    val context = LocalContext.current

    PrismTheme(settings, artColor) {
        // System bar icons follow Prism's theme, not the phone's (they differ when Appearance isn't "System").
        val dark = com.prism.music.ui.theme.LocalIsDark.current
        val rootView = androidx.compose.ui.platform.LocalView.current
        LaunchedEffect(dark) {
            (rootView.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, rootView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        // Settings → Layout & navigation → "Hide the status bar" (#115), and again each time Prism comes back to the front.
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        androidx.compose.runtime.DisposableEffect(settings.hideStatusBar, settings.hideStatusBarPlayer, lifecycle) {
            val bars = com.prism.music.ui.components.SystemBars
            bars.apply(rootView, bars.hidden(settings))
            val watcher = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) bars.apply(rootView, bars.hidden(c.settings.current))
            }
            lifecycle.addObserver(watcher)
            onDispose { lifecycle.removeObserver(watcher) }
        }
        // Settings → Controls & gestures → "Lock to portrait" (#190): phones stay upright; tablets keep their own layouts.
        // A full-screen video still turns sideways (it sets its own and puts this back after).
        val tablet = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
        androidx.compose.runtime.LaunchedEffect(settings.behavior.lockPortrait, tablet) {
            (rootView.context as? android.app.Activity)?.requestedOrientation =
                if (settings.behavior.lockPortrait && !tablet) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val navController = rememberNavController()
        var playerOpen by rememberSaveable { mutableStateOf(false) }
        val navigator = remember(navController) {
            Navigator(navController, openPlayer = { playerOpen = true }, playMix = { mix ->
                c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.radio(mix.seedVideoId, mix.id, mix.params, keepFirst = true) } }.getOrNull()
                    if (r == null || r.songs.isEmpty()) {
                        Toast.makeText(context, tr(R.string.couldn_t_start, mix.title), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    // Stations like "Listen together" open on a live stream: found out now (the stream is resolved anyway), it starts as one.
                    val first = r.songs.first()
                    val live = first.isLive || kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        kotlinx.coroutines.withTimeoutOrNull(8_000) { runCatching { c.streams.isLive(first.id) }.getOrDefault(false) } ?: false
                    }
                    val songs = if (live && !first.isLive) listOf(first.copy(isLive = true)) + r.songs.drop(1) else r.songs
                    c.queue.counterparts.putAll(r.counterparts)
                    c.player.playQueue(songs, 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.RADIO, mix.title))
                }
            })
        }
        // Playback that should be watched (a music video picked from a list) brings the player up.
        LaunchedEffect(Unit) { c.player.showPlayer.collect { playerOpen = true } }
        com.prism.music.ui.components.LyricsToFilesHost()
        val haze = rememberHazeState()
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        // The artist picker right after the welcome screen is part of setting up too: no tab bar or rail over it (its
        // Done button sat under the tab bar on phones, which it measures itself against when it's opened from Settings).
        val onboardingPicker = (route?.startsWith(Routes.PICK_ARTISTS) == true || route?.startsWith(Routes.ACCOUNT) == true) &&
            backStack?.arguments?.getBoolean("onboarding") == true
        val showChrome = route != Routes.LOGIN && route != Routes.LOGIN_ADD && route != Routes.DISCORD_LOGIN && route != Routes.WELCOME && !onboardingPicker
        val tabs = settings.navTabs.map { it.tab(settings.tabNames[it]) }

        val together by c.together.state.collectAsState()
        val inSession = together is com.prism.music.playback.together.TogetherState.Hosting || together is com.prism.music.playback.together.TogetherState.Guest
        // Wide windows (tablets, foldables, a phone on its side) put the tabs in a rail down the start edge.
        val window = com.prism.music.ui.theme.LocalWindowLayout.current
        // #264 (1.8.0): on a tablet the listener picks bottom or side; phones (compact) always have the tabs at the bottom.
        val useRail = showChrome && window.rail && settings.behavior.tabBarPosition != com.prism.music.data.prefs.TabBarPosition.BOTTOM
        val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
        val cutoutStart = WindowInsets.displayCutout.union(WindowInsets.navigationBars).asPaddingValues().calculateStartPadding(layoutDirection)
        val railInset = if (useRail) cutoutStart + RAIL_MARGIN + RAIL_WIDTH + 4.dp else 0.dp
        // Room the pages leave at the bottom for the bar, the mini player and the system's own bar.
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomPadding = navInset +
            (if (useRail) 16.dp else navHeight(settings.navStyle, settings.navBarSize) + (if (settings.navStyle == NavBarStyle.DOCKED) 0.dp else 16.dp)) +
            (if (song != null) miniHeight(settings.miniPlayerStyle) + 8.dp else 0.dp) +
            (if (inSession) 42.dp else 0.dp)

        // Decided once: the tab Prism opens on is the root every other tab sits on, and the welcome
        // screen is a real destination, so signing in from it navigates inside a graph that already exists.
        val root = remember { (settings.startTab.takeIf { it in settings.navTabs } ?: settings.navTabs.first()).tab().route }
        val start = remember { if (!settings.onboarded && !settings.isLoggedIn) Routes.WELCOME else root }
        val goRoot: () -> Unit = {
            navController.navigate(root) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }

        // The tab bar shrinks while you scroll down a page and grows back as you scroll up.
        var barCompact by remember { mutableStateOf(false) }
        val barScroll = remember {
            object : NestedScrollConnection {
                var acc = 0f
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val dy = available.y
                    if (dy == 0f || abs(available.x) > abs(dy)) return Offset.Zero
                    acc = if ((acc < 0) == (dy < 0)) acc + dy else dy
                    if (acc < -28f) barCompact = true else if (acc > 28f) barCompact = false
                    return Offset.Zero
                }
            }
        }
        LaunchedEffect(route) { barCompact = false }
        val barT by animateFloatAsState(
            if (barCompact && settings.navHideOnScroll) 1f else 0f,
            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "bar",
        )

        LaunchedEffect(Unit) {
            c.player.errors.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
        LaunchedEffect(Unit) {
            c.together.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
        LaunchedEffect(Unit) {
            c.cast.events.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
        LaunchedEffect(Unit) {
            c.handoff.events.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
        com.prism.music.ui.components.HandoffDialogs()
        // Desk mode (#265, 1.8.0, off unless switched on): the ambient screen while charging and playing.
        com.prism.music.ui.tv.AmbientHost(onTv = false)
        LaunchedEffect(Unit) {
            deepLink.collect { uri -> if (uri != null) { handleDeepLink(uri, navigator, c); deepLink.value = null } }
        }

        val selectTab: (String) -> Unit = { r ->
            // The tab whose pages are showing: the first tab page above the root, else the root's own.
            val tabRoutes = tabs.map { it.route }.toSet()
            // Optional arguments aside ("liked?genre={genre}" is the Liked songs tab).
            val stack = navController.currentBackStack.value.mapNotNull { it.destination.route?.substringBefore('?') }
            val currentTab = stack.drop(1).firstOrNull { it in tabRoutes } ?: root
            when {
                // The root tab always means its top, from anywhere.
                r == root -> if (!navController.popBackStack(root, inclusive = false, saveState = true)) goRoot()
                // Tapping the tab you're in takes you back to its top.
                r == currentTab -> navController.popBackStack(r, inclusive = false)
                // Every other tab keeps its own pages: leaving Settings on Appearance comes back to Appearance.
                else -> navController.navigate(r) {
                    popUpTo(root) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }

        // "Customize this" (1.7.3): the element tapped and, for a Home section, which one.
        var customizing by remember { mutableStateOf<Pair<com.prism.music.ui.components.CustomizeTarget, String?>?>(null) }
        // What's under the full player holds its purely visual motion still while the player hides it.
        val playerCover = remember { com.prism.music.ui.components.PlayerCover() }
        val hiddenByPlayer = remember(playerCover) { { playerCover.hides } }
        // The playing bars stand still while the song is paused (#302); read where they're drawn.
        val musicPlaying = c.player.isPlaying.collectAsState()
        val playingNow = remember(musicPlaying) { { musicPlaying.value } }
        // What the song rows mark (liked, downloaded, video saved): collected here once, not by every row in view.
        val rowMarks = com.prism.music.ui.components.rememberRowMarks(c.library.likedIds, c.downloadState, c.downloadStandIns, c.videoState) { s -> c.downloads.playsFromPhone(s) }
        // Select many (1.8.3): the songs picked in a list, one selection for the app; Back ends it.
        val selection = remember { com.prism.music.ui.components.SongSelection() }
        androidx.activity.compose.BackHandler(selection.selecting && !playerOpen) { selection.clear() }
        CompositionLocalProvider(
            com.prism.music.ui.components.LocalRowMarks provides rowMarks,
            com.prism.music.ui.components.LocalSongSelection provides selection,
            com.prism.music.ui.components.LocalPlayerCover provides playerCover,
            com.prism.music.ui.components.LocalMusicPlaying provides playingNow,
            LocalNavigator provides navigator,
            LocalHazeState provides haze,
            com.prism.music.ui.theme.LocalChromeStart provides railInset,
            com.prism.music.ui.components.LocalCustomize provides { t, k -> customizing = t to k },
        ) {
            // While the full player is open it covers everything else, so TalkBack shouldn't reach the screen, tabs and
            // mini player under it (they're only drawn, not hidden, to keep the player's slide smooth).
            val covered = playerOpen && song != null
            val underPlayer = if (covered) Modifier.semantics { hideFromAccessibility() } else Modifier
            // "Hide it on the player" (Reddit, 1.7.9.1): the status bar goes as the player opens and comes back as it closes.
            LaunchedEffect(covered, settings.hideStatusBarPlayer) {
                val bars = com.prism.music.ui.components.SystemBars
                bars.playerUp = covered
                if (settings.hideStatusBarPlayer) bars.apply(rootView, bars.hidden(c.settings.current))
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box(
                    Modifier.fillMaxSize().then(underPlayer).nestedScroll(barScroll)
                        // The page is copied only when glass will sample it (#343).
                        .then(if (com.prism.music.ui.theme.glassSamplesThePage(Build.VERSION.SDK_INT, settings.liquidGlass, settings.frostedGlass)) Modifier.hazeSource(haze) else Modifier)
                        .then(if (settings.swipeTabs && !useRail) swipeBetweenTabs(tabs, route, selectTab) else Modifier),
                ) {
                  CompositionLocalProvider(com.prism.music.ui.components.LocalHiddenByPlayer provides hiddenByPlayer) {
                    // The same cross-fade both ways, and for the back gesture too: left to its default,
                    // a predictive back (swipe from the edge) shrinks the page like a card instead.
                    // "Fades between screens" off (#227): pages swap at once, so a tap can't land on the one fading out.
                    val fadeMs = if (settings.behavior.screenFades) 700 else 0
                    // "Slide between screens" (#208, 1.7.9.2): a new page comes in from the side and Back takes it
                    // away the same way (from the left in right-to-left languages); the page underneath moves a little.
                    val slides = PageSlides(settings.behavior.screenFades && settings.behavior.pageSlide, androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl)
                    NavHost(
                        navController, startDestination = start,
                        enterTransition = { slides.enter ?: if (fadeMs == 0) EnterTransition.None else fadeIn(tween(fadeMs)) },
                        exitTransition = { slides.exit ?: if (fadeMs == 0) ExitTransition.None else fadeOut(tween(fadeMs)) },
                        popEnterTransition = { slides.popEnter ?: if (fadeMs == 0) EnterTransition.None else fadeIn(tween(fadeMs)) },
                        popExitTransition = { slides.popExit ?: if (fadeMs == 0) ExitTransition.None else fadeOut(tween(fadeMs)) },
                        predictivePopEnterTransition = { slides.popEnter ?: if (fadeMs == 0) EnterTransition.None else fadeIn(tween(fadeMs)) },
                        predictivePopExitTransition = { slides.popExit ?: if (fadeMs == 0) ExitTransition.None else fadeOut(tween(fadeMs)) },
                    ) {
                        composable(Routes.WELCOME) {
                            WelcomeScreen(
                                onSignIn = { navController.navigate(Routes.LOGIN) },
                                // A Prism Account (on this phone), then a few artists, so the mixes and Release Radar have
                                // something to start from.
                                onAccount = { navController.navigate(Routes.ACCOUNT + "?onboarding=true") },
                                onSkip = { navController.navigate(Routes.PICK_ARTISTS + "?onboarding=true") },
                            )
                        }
                        composable(
                            Routes.ACCOUNT + "?onboarding={onboarding}",
                            listOf(navArgument("onboarding") { type = NavType.BoolType; defaultValue = false }),
                        ) {
                            val onboarding = it.arguments?.getBoolean("onboarding") == true
                            ScreenBackdrop(BackdropScreen.SETTINGS) {
                                com.prism.music.ui.screens.PrismAccountScreen(onboarding, if (onboarding) 0.dp else bottomPadding) {
                                    if (onboarding) navController.navigate(Routes.PICK_ARTISTS + "?onboarding=true") else navController.popBackStack()
                                }
                            }
                        }
                        composable(
                            Routes.PICK_ARTISTS + "?onboarding={onboarding}",
                            listOf(navArgument("onboarding") { type = NavType.BoolType; defaultValue = false }),
                        ) {
                            val onboarding = it.arguments?.getBoolean("onboarding") == true
                            ScreenBackdrop(BackdropScreen.HOME) {
                                com.prism.music.ui.screens.PickArtistsScreen(onboarding, if (onboarding) 0.dp else bottomPadding) {
                                    if (onboarding) { c.settings.setOnboarded(true); goRoot() } else navController.popBackStack()
                                }
                            }
                        }
                        composable(Routes.NEW_RELEASES) { ScreenBackdrop(BackdropScreen.HOME) { com.prism.music.ui.screens.NewReleasesScreen(bottomPadding) } }
                        composable(Routes.EXCLUSIONS) { ScreenBackdrop(BackdropScreen.SETTINGS) { com.prism.music.ui.screens.ExclusionsScreen(bottomPadding) } }
                        // 1.8.1: Bring your history, and its "Needs a look".
                        composable(Routes.BRING_HISTORY) { ScreenBackdrop(BackdropScreen.SETTINGS) { com.prism.music.ui.screens.BringHistoryScreen(bottomPadding) } }
                        composable(Routes.IMPORT_REVIEW) { ScreenBackdrop(BackdropScreen.SETTINGS) { com.prism.music.ui.screens.ImportReviewScreen(bottomPadding) } }
                        composable(
                            Routes.RECOGNIZE + "?start={start}",
                            listOf(navArgument("start") { type = NavType.BoolType; defaultValue = false }),
                        ) {
                            ScreenBackdrop(BackdropScreen.SEARCH) { com.prism.music.ui.screens.RecognizeScreen(it.arguments?.getBoolean("start") == true, bottomPadding) }
                        }
                        composable(Routes.RECOGNIZED) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.RecognizedScreen(bottomPadding) } }
                        composable(Routes.BLENDS) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.BlendsScreen(bottomPadding) } }
                        composable(Routes.BLEND_SCAN) {
                            com.prism.music.ui.screens.BlendScanScreen { code ->
                                navController.popBackStack()
                                c.blends.pending.value = code
                            }
                        }
                        composable(Routes.LOOK_SCAN) {
                            com.prism.music.ui.screens.BlendScanScreen(tr(R.string.point_at_a_prism_look_s), com.prism.music.data.looks.LookCode::findIn) { code ->
                                navController.popBackStack()
                                c.looks.decode(code)?.let { c.looks.pending.value = it }
                                    ?: Toast.makeText(context, tr(R.string.that_look_code_couldn_t_be), Toast.LENGTH_SHORT).show()
                            }
                        }
                        composable("blend/{who}", listOf(navArgument("who") { type = NavType.StringType })) {
                            ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.BlendScreen(it.arguments?.getString("who")!!, bottomPadding) }
                        }
                        composable("mix/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            Beside { CollectionScreen(CollectionType.MIX, it.arguments?.getString("id")!!, null, bottomPadding) }
                        }
                        composable(Routes.HOME) { ScreenBackdrop(BackdropScreen.HOME) { HomeScreen(bottomPadding) } }
                        composable(Routes.SEARCH) { ScreenBackdrop(BackdropScreen.SEARCH) { SearchScreen(bottomPadding) } }
                        composable(Routes.LIBRARY) { ScreenBackdrop(BackdropScreen.LIBRARY) { LibraryScreen(bottomPadding) } }
                        composable(Routes.REPLAY) { ScreenBackdrop(BackdropScreen.REPLAY) { ReplayScreen(bottomPadding) } }
                        composable(Routes.SETTINGS) { ScreenBackdrop(BackdropScreen.SETTINGS) { SettingsScreen(bottomPadding) } }
                        composable(
                            Routes.BACKGROUNDS + "?player={player}",
                            listOf(navArgument("player") { type = NavType.BoolType; defaultValue = false }),
                        ) {
                            val player = it.arguments?.getBoolean("player") == true
                            ScreenBackdrop(BackdropScreen.SETTINGS) { com.prism.music.ui.screens.BackgroundsScreen(bottomPadding, forPlayer = player) }
                        }
                        composable(
                            "settings/{section}?find={find}",
                            listOf(
                                navArgument("section") { type = NavType.StringType },
                                navArgument("find") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) {
                            ScreenBackdrop(BackdropScreen.SETTINGS) {
                                SettingsScreen(bottomPadding, com.prism.music.ui.screens.SettingsSection.of(it.arguments?.getString("section")), find = it.arguments?.getString("find"))
                            }
                        }
                        composable(Routes.EQ) { ScreenBackdrop(BackdropScreen.SETTINGS) { EqualizerScreen(bottomPadding) } }
                        composable(Routes.CATALOGUE) { ScreenBackdrop(BackdropScreen.HOME) { CatalogueScreen(bottomPadding) } }
                        composable(Routes.MOODS) { ScreenBackdrop(BackdropScreen.SEARCH) { com.prism.music.ui.screens.MoodsScreen(bottomPadding) } }
                        composable(Routes.EXPLORE) { ScreenBackdrop(BackdropScreen.SEARCH) { com.prism.music.ui.screens.ExploreScreen(bottomPadding) } }
                        composable(Routes.IMPORT) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.ImportScreen(bottomPadding) } }
                        composable(Routes.DESIGN_GALLERY) { ScreenBackdrop(BackdropScreen.SETTINGS) { com.prism.music.ui.components.DesignGallery(bottomPadding) } }
                        composable(Routes.TOGETHER) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.TogetherScreen(bottomPadding) } }
                        composable(Routes.PODCASTS) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.PodcastsScreen(bottomPadding) } }
                        composable(Routes.UPLOADS) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.UploadsScreen(bottomPadding) } }
                        composable(Routes.SAVED) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.SavedWhilePlayingScreen(bottomPadding) } }
                        composable("podcast/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            Beside { com.prism.music.ui.screens.PodcastScreen(it.arguments?.getString("id")!!, bottomPadding) }
                        }
                        composable(Routes.DOWNLOADS) { Beside { CollectionScreen(CollectionType.DOWNLOADS, "downloads", null, bottomPadding) } }
                        composable("folder/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.FolderScreen(it.arguments?.getString("id")!!, bottomPadding) }
                        }
                        composable(Routes.LOCAL) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.LocalMusicScreen(bottomPadding) } }
                        composable(Routes.LOGIN) {
                            LoginScreen(
                                onDone = goRoot,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Routes.DISCORD_LOGIN) {
                            com.prism.music.ui.screens.DiscordLoginScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Routes.LOGIN_ADD) {
                            LoginScreen(
                                onDone = goRoot,
                                onBack = { navController.popBackStack() },
                                addAccount = true,
                            )
                        }
                        composable("album/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            Beside { CollectionScreen(CollectionType.ALBUM, it.arguments?.getString("id")!!, null, bottomPadding) }
                        }
                        composable("playlist/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            val id = it.arguments?.getString("id")!!
                            // A playlist made in Prism (kept on the phone) opens on the same page, from the phone.
                            val type = if (com.prism.music.data.PrismLibrary.isPrismList(id)) CollectionType.PRISM else CollectionType.PLAYLIST
                            // A tag found by Search (1.8.3) comes as a playlist: its own page.
                            if (id.startsWith(com.prism.music.ui.screens.TAG_PAGE)) Beside { CollectionScreen(CollectionType.TAG, id.removePrefix(com.prism.music.ui.screens.TAG_PAGE), null, bottomPadding) }
                            else Beside { CollectionScreen(type, id, null, bottomPadding) }
                        }
                        composable("tag/{name}", listOf(navArgument("name") { type = NavType.StringType })) {
                            Beside { CollectionScreen(CollectionType.TAG, it.arguments?.getString("name")!!, null, bottomPadding) }
                        }
                        composable("library-search?q={q}", listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                            Beside { com.prism.music.ui.screens.LibrarySearchScreen(it.arguments?.getString("q").orEmpty(), bottomPadding) }
                        }
                        composable("liked?genre={genre}", listOf(navArgument("genre") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                            Beside { CollectionScreen(CollectionType.LIKED, "liked", it.arguments?.getString("genre"), bottomPadding) }
                        }
                        composable("artist/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            Beside { ArtistScreen(it.arguments?.getString("id")!!, bottomPadding) }
                        }
                        composable(
                            "browse/{id}?params={params}&title={title}",
                            listOf(
                                navArgument("id") { type = NavType.StringType },
                                navArgument("params") { type = NavType.StringType; defaultValue = "" },
                                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                            ),
                        ) {
                            Beside {
                                BrowseScreen(
                                    it.arguments?.getString("id")!!,
                                    it.arguments?.getString("params")?.takeIf { p -> p.isNotBlank() },
                                    it.arguments?.getString("title") ?: "",
                                    bottomPadding,
                                )
                            }
                        }
                    }
                  }
                }

                // Wide windows: the tabs in a rail down the start edge, the mini player along the bottom beside it.
                CompositionLocalProvider(com.prism.music.ui.components.LocalHiddenByPlayer provides hiddenByPlayer) {
                if (useRail) {
                    NavRail(
                        tabs, route, settings, selectTab,
                        Modifier.align(Alignment.CenterStart).then(underPlayer).padding(start = cutoutStart + RAIL_MARGIN),
                    )
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(underPlayer)
                            .padding(start = railInset).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AnimatedVisibility(inSession && !playerOpen && route != Routes.TOGETHER, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                            TogetherPill(together, Modifier.padding(bottom = 8.dp)) { navigator.go(Routes.TOGETHER) }
                        }
                        AnimatedVisibility(song != null && !playerOpen && !selection.selecting, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                            MiniPlayer(Modifier.widthIn(max = 720.dp).customizable(com.prism.music.ui.components.CustomizeTarget.MINI_PLAYER)) { playerOpen = true }
                        }
                    }
                }
                // Select many: its bar in the place of the mini player and tabs while songs are picked.
                val picking = selection.selecting && showChrome
                if (picking) com.prism.music.ui.components.SelectionBar(
                    selection,
                    Modifier.align(Alignment.BottomCenter).then(underPlayer).padding(start = if (useRail) railInset else 0.dp)
                        .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                )
                // Floating chrome: mini player + tab bar
                if (showChrome && !useRail && !picking) {
                    val docked = settings.navStyle == NavBarStyle.DOCKED
                    val keyboard = WindowInsets.ime
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(underPlayer)
                            .graphicsLayer { alpha = if (com.prism.music.ui.theme.hiddenByKeyboard(keyboard.getBottom(this), size.height)) 0f else 1f }
                            .then(if (docked) Modifier else Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)),
                    ) {
                        AnimatedVisibility(inSession && !playerOpen && route != Routes.TOGETHER, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                            TogetherPill(together, Modifier.padding(bottom = 8.dp)) { navigator.go(Routes.TOGETHER) }
                        }
                        // "Joined to the tab bar" (1.7.6): one piece of glass, the mini player on top of the tabs.
                        val joined = settings.miniPlayerJoined && settings.navStyle == NavBarStyle.FLOATING
                        if (!joined) AnimatedVisibility(song != null && !playerOpen, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                            // Spectrum's bar (the Card style over the docked tab bar) sits right on the tab bar, edge to edge.
                            val flush = docked && settings.miniPlayerStyle == MiniPlayerStyle.CARD && !(settings.liquidGlass || settings.frostedGlass)
                            MiniPlayer(
                                (if (flush) Modifier else Modifier.padding(bottom = 8.dp).then(if (docked) Modifier.padding(horizontal = 10.dp) else Modifier))
                                    .customizable(com.prism.music.ui.components.CustomizeTarget.MINI_PLAYER),
                            ) { playerOpen = true }
                        }
                        val joinedMini: (@Composable () -> Unit)? = if (!joined) null else ({
                            AnimatedVisibility(song != null && !playerOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                MiniPlayer(Modifier.customizable(com.prism.music.ui.components.CustomizeTarget.MINI_PLAYER), bare = true) { playerOpen = true }
                            }
                        })
                        Box(Modifier.customizable(com.prism.music.ui.components.CustomizeTarget.TAB_BAR)) { TabBar(tabs, route, barT, settings, joinedMini, selectTab) }
                    }
                }
                }

                // "Cover flies into place" (#199): the player fades in while the mini player's cover flies to its spot.
                val flight = settings.view.coverFlight && !settings.reduceMotion
                // #223: and back the same way, when it's the player's cover that's showing. Watched through derivedStateOf as
                // the cover's place changes with every frame of a drag, and this scope mustn't recompose for each one.
                val coverKnown by remember { androidx.compose.runtime.derivedStateOf { com.prism.music.ui.player.CoverFlight.player != null } }
                val flyBack = com.prism.music.ui.player.CoverFlight.returnsWith(settings.view.coverFlight, settings.view.coverFlightBack, settings.reduceMotion) && coverKnown
                LaunchedEffect(playerOpen) {
                    if (!playerOpen) {
                        if (flyBack) com.prism.music.ui.player.CoverFlight.flyBack()
                        com.prism.music.ui.player.CoverFlight.player = null
                    } else if (flight) com.prism.music.ui.player.CoverFlight.fly()
                }
                // #354: what the player shows for this song, ready before it's opened.
                val artAhead = song?.takeIf { !playerOpen }?.let { c.covers.art(it) }
                if (artAhead != null) {
                    val warmContext = androidx.compose.ui.platform.LocalContext.current
                    LaunchedEffect(artAhead, settings.fullBleedArtwork, settings.playerBackground, settings.playerBlur) {
                        // Not as the song starts, whose own start comes first, nor for a song skipped straight past.
                        kotlinx.coroutines.delay(1_500)
                        runCatching {
                            com.prism.music.ui.player.warmPlayerArt(
                                warmContext, artAhead, settings.fullBleedArtwork,
                                meshToo = settings.playerBackground != com.prism.music.data.prefs.PlayerBackground.GRADIENT,
                                blurStrength = settings.playerBlur,
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    playerOpen && song != null,
                    enter = if (flight) fadeIn(tween(com.prism.music.ui.player.CoverFlight.FLIGHT_MS))
                        else com.prism.music.ui.player.playerSlideIn(settings.reduceMotion),
                    exit = if (flyBack) fadeOut(tween(com.prism.music.ui.player.CoverFlight.FLIGHT_MS))
                        else com.prism.music.ui.player.playerSlideOut(settings.reduceMotion),
                ) {
                    // Settled once it's done sliding in; no longer the moment it starts to leave.
                    val settled = transition.currentState == androidx.compose.animation.EnterExitState.Visible &&
                        transition.targetState == androidx.compose.animation.EnterExitState.Visible
                    val leaving = transition.targetState != androidx.compose.animation.EnterExitState.Visible
                    androidx.compose.runtime.SideEffect { playerCover.settled = settled; playerCover.leaving = leaving }
                    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { playerCover.settled = false; playerCover.leaving = false } }
                    Box(Modifier.customizable(com.prism.music.ui.components.CustomizeTarget.PLAYER)) { NowPlayingScreen { playerOpen = false } }
                }
                com.prism.music.ui.player.CoverFlightLayer(song?.let { com.prism.music.data.PlaylistCovers.artOf(it) })

                // Looks (1.7.3): "Undo" after each change to the look, and "Customize this" while it's on.
                // Over the player it sits at the top, under "Customize this": lower down it covered the Play button.
                com.prism.music.ui.components.LookUndoBar(
                    if (playerOpen && song != null) Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp).statusBarsPadding().padding(top = 56.dp)
                    else Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = bottomPadding + 8.dp),
                    overPlayer = playerOpen && song != null,
                )
                if (!(playerOpen && song != null)) com.prism.music.ui.components.ScheduleKeptBar(
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = bottomPadding + 8.dp),
                )
                com.prism.music.ui.components.CustomizingPill(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))
                customizing?.let { (target, key) ->
                    // Going to the whole settings page leaves the player (it would cover the page).
                    com.prism.music.ui.screens.CustomizeSheet(target, key, onLeave = { playerOpen = false }) { customizing = null }
                }

                // The tour (1.7): the whole of it after setting up a new install or the first time a release with tours
                // runs; later releases show only what's new since the last one seen. Settings → About takes it again.
                var tour by remember { mutableStateOf<com.prism.music.ui.tour.TourPlan?>(null) }
                LaunchedEffect(showChrome) {
                    if (showChrome && tour == null) {
                        kotlinx.coroutines.delay(600) // after the first page has drawn
                        tour = com.prism.music.ui.tour.pendingTour(context, c.returningUser == true)
                    }
                }
                LaunchedEffect(Unit) { c.tourRequests.collect { playerOpen = false; tour = com.prism.music.ui.tour.fullTour(c.returningUser == true) } }
                tour?.let { plan ->
                    com.prism.music.ui.tour.TourOverlay(plan, onOpen = { r -> playerOpen = false; navigator.go(r) }, onDone = { tour = null })
                }

                // Holiday Mode (1.8.0): once a season, the first time Prism opens in its month after setting up and the
                // tour; never over the welcome screen, the tour, the player or another question.
                var seasonAsk by remember { mutableStateOf<com.prism.music.data.looks.Season?>(null) }
                val lookPending by c.looks.pending.collectAsState()
                val blendPending by c.blends.pending.collectAsState()
                val clear = showChrome && tour == null && !playerOpen && customizing == null && lookPending == null && blendPending == null
                LaunchedEffect(clear) {
                    if (!clear || seasonAsk != null) return@LaunchedEffect
                    kotlinx.coroutines.delay(1_500) // after the tour would have come up
                    if (com.prism.music.ui.tour.pendingTour(context, c.returningUser == true) != null) return@LaunchedEffect
                    val today = com.prism.music.data.looks.Seasons.today()
                    val now = c.settings.current
                    val onLook = com.prism.music.data.looks.Seasons.current(today)?.let { com.prism.music.data.looks.BuiltInLooks.of(it.lookId) }
                        ?.takeIf { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { c.looks.isOn(it) } }?.id
                    seasonAsk = com.prism.music.data.looks.SeasonPrompt.due(today, com.prism.music.data.looks.SeasonPrompt.asked(context), now.onboarded || now.isLoggedIn, onLook)
                }
                seasonAsk?.let { season ->
                    com.prism.music.ui.screens.HolidayModeDialog(season) { yes ->
                        com.prism.music.data.looks.SeasonPrompt.markAsked(context, season, com.prism.music.data.looks.Seasons.today())
                        seasonAsk = null
                        if (yes) com.prism.music.data.looks.BuiltInLooks.of(season.lookId)?.let { look -> c.scope.launch { c.looks.apply(look) } }
                    }
                }
            }
        }
    }
}

/** Above the mini player while a Listen Together session is on: who with, and a way back to it. */
@Composable
private fun TogetherPill(state: com.prism.music.playback.together.TogetherState, modifier: Modifier, onClick: () -> Unit) {
    val (icon, text) = when (state) {
        is com.prism.music.playback.together.TogetherState.Hosting ->
            PrismIcons.Radio to tr(R.string.hosting_code, state.code) + when (state.guests.size) { 0 -> ""; 1 -> tr(R.string.t_1_listening); else -> tr(R.string.listening_3, state.guests.size) }
        is com.prism.music.playback.together.TogetherState.Guest ->
            PrismIcons.Groups to tr(R.string.with, state.hostName) + when {
                !state.listenHere -> tr(R.string.adding_songs)
                !state.inSync -> tr(R.string.out_of_step_2)
                else -> ""
            }
        else -> return
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.height(34.dp), shape = RoundedCornerShape(17.dp), elevation = 4.dp) {
            Row(
                Modifier.fillMaxHeight().clickable(onClickLabel = tr(R.string.open_listen_together), onClick = onClick).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

private val RAIL_WIDTH = 76.dp
private val RAIL_MARGIN = 12.dp

/** A page that draws its own background, kept clear of the navigation rail (the [ScreenBackdrop] pages do it themselves). */
@Composable
private fun Beside(content: @Composable () -> Unit) {
    val start = com.prism.music.ui.theme.LocalChromeStart.current
    Box(Modifier.fillMaxSize().padding(start = start)) {
        CompositionLocalProvider(com.prism.music.ui.theme.LocalChromeStart provides 0.dp) { content() }
    }
}

/**
 * The tabs on wide windows: a floating glass column down the start edge, the tab bar's twin (same tabs, icons and
 * label setting), so a tablet or a phone on its side keeps its height for the page.
 */
@Composable
private fun NavRail(tabs: List<Tab>, route: String?, settings: AppSettings, onSelect: (String) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    GlassSurface(modifier.width(RAIL_WIDTH), shape = RoundedCornerShape(RAIL_WIDTH / 2)) {
        Column(
            Modifier.padding(vertical = 8.dp, horizontal = 6.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEach { tab ->
                val selected = isSelected(tab, route)
                val fg = if (selected) scheme.primary else scheme.onSurfaceVariant
                val indicator by animateColorAsState(if (selected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent, label = "railTab")
                val showLabel = settings.navLabels == NavLabels.ALWAYS || settings.navLabels == NavLabels.WORDS || (settings.navLabels == NavLabels.SELECTED && selected)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(indicator)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = tab.label) { onSelect(tab.route) }
                        .padding(vertical = if (showLabel) 8.dp else 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(if (selected) tab.selectedIcon else tab.icon, tab.label, tint = fg, modifier = Modifier.size(24.dp).then(if (selected) Modifier.tabTint(settings.shapes.tabTint) else Modifier))
                    if (showLabel) Text(
                        tab.label, color = fg, style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

private fun isSelected(tab: Tab, route: String?): Boolean =
    route == tab.route || (tab.route == Routes.SETTINGS && route?.startsWith("settings/") == true) ||
        // A page with optional arguments ("liked?genre={genre}") is its tab's own route.
        (route != null && '?' in route && route.substringBefore('?') == tab.route)

/** @param compact 0 = full bar, 1 = slim bar (while scrolling down). */
@Composable
private fun TabBar(
    tabs: List<Tab>, route: String?, compact: Float, settings: AppSettings,
    /** The mini player joined on top of a floating bar, in the same glass (1.7.6). */
    joined: (@Composable () -> Unit)? = null,
    onSelect: (String) -> Unit,
) {
    val barSize = settings.navBarSize
    val tint = navFill(settings.navBarFill)
    when (settings.navStyle) {
        NavBarStyle.FLOATING -> {
            val full = barSize.floating.dp
            val height = lerp(full, full - 14.dp, compact)
            val tabsRow: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().height(height).padding(lerp(6.dp, 4.dp, compact)), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEach { tab -> TabItem(tab, isSelected(tab, route), settings.navLabels, compact, Modifier.weight(1f).fillMaxHeight()) { onSelect(tab.route) } }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (joined != null) GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), tint = tint) {
                    Column {
                        joined()
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.75.dp).background(MaterialTheme.colorScheme.hairline))
                        tabsRow()
                    }
                } else GlassSurface(Modifier.fillMaxWidth(1f - 0.30f * compact).height(height), shape = RoundedCornerShape(height / 2), tint = tint) {
                    tabsRow()
                }
            }
        }
        // Spectrum's bar (1.7): flat, a hairline on top, the split-light line over the current tab. Glass, if it's on.
        NavBarStyle.DOCKED -> {
            val scheme = MaterialTheme.colorScheme
            val row: @Composable () -> Unit = {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().height(lerp(barSize.docked.dp, barSize.docked.dp - 12.dp, compact)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab -> TabItem(tab, isSelected(tab, route), settings.navLabels, compact, Modifier.weight(1f).fillMaxHeight(), line = true) { onSelect(tab.route) } }
                }
            }
            if (settings.liquidGlass || settings.frostedGlass) GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(0.dp), elevation = 0.dp, tint = tint, refract = false) { row() }
            else Box(
                Modifier.fillMaxWidth().background(if (tint.isSpecified) tint else scheme.background)
                    .drawBehind { drawLine(scheme.hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx() * 0.75f) },
            ) { row() }
        }
        NavBarStyle.MINIMAL -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassSurface(Modifier.height(barSize.minimal.dp), shape = RoundedCornerShape((barSize.minimal / 2).dp), tint = tint) {
                    Row(Modifier.fillMaxHeight().padding(5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        tabs.forEach { tab -> MinimalTab(tab, isSelected(tab, route), (barSize.minimal - 10).dp) { onSelect(tab.route) } }
                    }
                }
            }
        }
    }
}

/**
 * Swipe between tabs (1.7.6, off by default): a sideways swipe across a tab's own page that nothing on it took (a
 * carousel, a pager, a slider take theirs) goes to the tab beside it. Watched after the page has had the touch, so it
 * never takes a swipe from what's under the finger.
 */
private fun swipeBetweenTabs(tabs: List<Tab>, route: String?, onSelect: (String) -> Unit): Modifier = Modifier.pointerInput(tabs, route) {
    val here = tabs.indexOfFirst { route == it.route || route?.substringBefore('?') == it.route }
    if (here < 0) return@pointerInput
    val far = 96.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Final)
        var dx = 0f
        var dy = 0f
        var taken = false
        while (true) {
            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (event.changes.size > 1) taken = true
            if (change.isConsumed) taken = true
            dx += change.position.x - change.previousPosition.x
            dy += change.position.y - change.previousPosition.y
            if (!change.pressed) break
        }
        if (taken || abs(dx) < far || abs(dx) < abs(dy) * 2.5f) return@awaitEachGesture
        val next = here + if (dx < 0) 1 else -1
        tabs.getOrNull(next)?.let { onSelect(it.route) }
    }
}

/** The tab bar's own background (1.7.6), the mini player's too: unspecified keeps the glass as it is. */
@Composable
internal fun navFill(fill: com.prism.music.data.prefs.NavBarFill): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = com.prism.music.ui.theme.LocalIsDark.current
    return when (fill) {
        com.prism.music.data.prefs.NavBarFill.GLASS -> Color.Unspecified
        com.prism.music.data.prefs.NavBarFill.LIGHT -> scheme.surfaceContainerHigh.copy(alpha = if (dark) 0.28f else 0.34f)
        com.prism.music.data.prefs.NavBarFill.SOLID -> scheme.surfaceContainerHigh
    }
}

/**
 * One tab: ink when it's the current one, quiet otherwise. [line]: Spectrum's mark, the split-light line along the
 * top of the current tab, instead of a tinted capsule behind it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TabItem(tab: Tab, selected: Boolean, labels: NavLabels, compact: Float, modifier: Modifier, line: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fg by animateColorAsState(if (selected) scheme.onSurface else scheme.ink3, label = "tabInk")
    // Aura × Spectrum: the current tab is lifted on the glass, with a short line of split light under it.
    val lifted = com.prism.music.ui.components.frostFill(2.6f)
    val indicator by animateColorAsState(if (selected && !line) lifted else Color.Transparent, label = "tab")
    val mark by animateFloatAsState(if (selected) 1f else 0f, tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD), label = "tabLine")
    val dark = com.prism.music.ui.theme.LocalIsDark.current
    // Holding the Settings tab switches "Customize this" on and off (1.7.3).
    val c = LocalContainer.current
    val click = Modifier.combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = tab.label,
        onLongClickLabel = if (tab.route == Routes.SETTINGS) tr(R.string.customize_this) else null,
        onLongClick = if (tab.route == Routes.SETTINGS) ({ c.customizing.value = !c.customizing.value }) else null,
        onClick = onClick,
    ).semantics { this.selected = selected }
    val icon = if (selected) tab.selectedIcon else tab.icon
    // The current tab's icon in ink, the accent or the light (1.7.7).
    val tabTint = com.prism.music.ui.theme.LocalAppSettings.current.shapes.tabTint
    val spectrumLine = Modifier.drawWithContent {
        drawContent()
        if (mark > 0f) {
            // Docked: a longer line along the top; floating: a short one under the label.
            val w = (if (line) 28.dp else 16.dp).toPx() * mark
            val x = (size.width - w) / 2
            val y = if (line) 0f else size.height - 5.dp.toPx()
            drawRoundRect(
                com.prism.music.ui.theme.PrismTokens.spectrum(dark, x, x + w),
                Offset(x, y), androidx.compose.ui.geometry.Size(w, 2.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
            )
        }
    }
    when (labels) {
        NavLabels.ALWAYS -> Column(
            modifier.clip(RoundedCornerShape(26.dp)).background(indicator).then(spectrumLine).then(click),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, tab.label, tint = fg, modifier = Modifier.size(24.dp).then(if (selected) Modifier.tabTint(tabTint) else Modifier))
            // #237: a long name ("Liked songs") gets smaller rather than cut off, kept clear of the rounded corners, and
            // its line keeps its full height with big text; the bar slimming down only hides it.
            if (compact < 0.98f) {
                val style = MaterialTheme.typography.labelSmall
                val lineHeight = with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() }.coerceAtLeast(16.dp)
                Box(Modifier.padding(top = 2.dp).height(lerp(lineHeight, 0.dp, compact)).clipToBounds().graphicsLayer { alpha = (1f - compact * 1.6f).coerceIn(0f, 1f) }) {
                    Text(
                        tab.label, color = fg, style = style, maxLines = 1, softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp), maxFontSize = style.fontSize),
                        modifier = Modifier.padding(horizontal = 6.dp).wrapContentHeight(unbounded = true),
                    )
                }
            }
        }
        // The picked tab's name under its icon, as "Always" places it (#291: it grew into a capsule with the name beside
        // the icon, which a long name pushed off the tab); the other tabs show their icons alone.
        NavLabels.SELECTED -> Column(
            modifier.then(spectrumLine).then(click),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(52.dp, 32.dp).clip(CircleShape).background(indicator), contentAlignment = Alignment.Center) {
                // 24 dp as in the other label styles (#272: 22 here made every icon a little smaller with "Selected").
                Icon(icon, tab.label, tint = fg, modifier = Modifier.size(24.dp).then(if (selected) Modifier.tabTint(tabTint) else Modifier))
            }
            AnimatedVisibility(selected && compact < 0.5f, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(
                    tab.label, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp), maxFontSize = MaterialTheme.typography.labelSmall.fontSize),
                    modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp),
                )
            }
        }
        NavLabels.NEVER -> Box(modifier.then(spectrumLine).then(click), contentAlignment = Alignment.Center) {
            Box(Modifier.size(52.dp, 40.dp).clip(CircleShape).background(indicator), contentAlignment = Alignment.Center) {
                Icon(icon, tab.label, tint = fg, modifier = Modifier.size(24.dp).then(if (selected) Modifier.tabTint(tabTint) else Modifier))
            }
        }
        // Words only (1.7.6): the tabs' names, no icons; the current one bolder.
        // Five or six tabs: the names get smaller rather than cut short.
        NavLabels.WORDS -> Box(modifier.then(spectrumLine).then(click), contentAlignment = Alignment.Center) {
            Box(Modifier.height(40.dp).clip(CircleShape).background(indicator).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(
                    tab.label, color = fg, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp), maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
    }
}

@Composable
private fun MinimalTab(tab: Tab, selected: Boolean, height: Dp = 44.dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.onSurface else Color.Transparent, label = "minTab")
    Box(
        Modifier.size(54.dp, height).clip(CircleShape).background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = tab.label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (selected) tab.selectedIcon else tab.icon, tab.label, tint = if (selected) scheme.surface else scheme.onSurfaceVariant, modifier = Modifier.size(23.dp))
    }
}

/** Opens music.youtube.com links shared into the app. */
private fun handleDeepLink(uri: Uri, nav: Navigator, c: AppContainer) {
    val v = uri.getQueryParameter("v")
    val list = uri.getQueryParameter("list")
    val path = uri.pathSegments
    when {
        // A Prism TV's QR code, read by the phone's camera (1.8.0): pairs with that TV (and offers it the sign-in).
        uri.scheme == "prism" && uri.host == "pair" -> c.handoff.pairFromLink(uri)
        // Back from Last.fm's page in the browser, with the token it gave for this listener (#133).
        uri.scheme == "prism" && uri.host == "lastfm" -> {
            // Last.fm's own page (Devices & connections), where its sign-in started (1.8.0: no longer on Recommendations).
            nav.go(Routes.settings(com.prism.music.ui.screens.SettingsSection.LASTFM.key))
            uri.getQueryParameter("token")?.takeIf { it.isNotBlank() }?.let { token ->
                c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    val ok = c.lastFm.finishSignIn(token)
                    android.widget.Toast.makeText(
                        c.app, if (ok) tr(R.string.connected_to_last_fm_as, c.lastFm.user.value) else tr(R.string.last_fm_didn_t_connect_try),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        // Back from Spotify's authorize page (1.8.1): the code already reached Prism on the phone's loopback address, so
        // this only brings its page back (where the connection shows).
        uri.scheme == "prism" && uri.host == "spotify" -> nav.go(Routes.settings(com.prism.music.ui.screens.SettingsSection.SPOTIFY.key))
        // A widget's cover or title: the player.
        uri.scheme == "prism" && uri.host == "player" -> nav.openPlayer()
        // Release Radar's notification.
        uri.scheme == "prism" && uri.host == "releases" -> nav.go(Routes.NEW_RELEASES)
        uri.scheme == "prism" && uri.host == "blends" -> nav.go(Routes.BLENDS)
        // Bring your history's matching notification (1.8.1).
        uri.scheme == "prism" && uri.host == "bring" -> nav.go(Routes.BRING_HISTORY)
        // A Look shared to Prism: Appearance → Looks, which shows it first.
        uri.scheme == "prism" && uri.host == "looks" -> nav.go(Routes.settings(com.prism.music.ui.screens.SettingsSection.LOOKS.key))
        // The Quick Settings tile: straight to listening.
        uri.scheme == "prism" && uri.host == "recognize" -> nav.go(Routes.RECOGNIZE + "?start=true")
        uri.scheme == "prism" && uri.host == "recognized" -> nav.go(Routes.RECOGNIZED)
        // App icon shortcuts (AppShortcuts).
        uri.scheme == "prism" && uri.host == "search" -> nav.go(Routes.SEARCH)
        uri.scheme == "prism" && uri.host == "liked" -> nav.go(Routes.liked())
        uri.scheme == "prism" && uri.host == "downloads" -> nav.go(Routes.DOWNLOADS)
        // The 1 December notification: that year's Rewind.
        uri.scheme == "prism" && uri.host == "rewind" -> {
            path.firstOrNull()?.toIntOrNull()?.let { c.rewindRequest.value = it }
            nav.go(Routes.REPLAY)
        }
        // A handoff pairing code's notification: Prism Connect's settings, where the code shows.
        uri.scheme == "prism" && uri.host == "connect" -> nav.go(Routes.settings(com.prism.music.ui.screens.SettingsSection.CONNECT.key))
        uri.scheme == "prism" && uri.host == "mix" && path.isNotEmpty() -> nav.go(Routes.mix(path[0]))
        v != null -> {
            nav.openPlayer()
            launchSingle(c, v)
        }
        list != null -> nav.go(Routes.playlist(list))
        path.firstOrNull() == "browse" && path.size > 1 -> {
            val id = path[1]
            if (id.startsWith("MPRE")) nav.go(Routes.album(id))
            else if (id.startsWith("MPSP")) nav.go(Routes.podcast(id))
            else nav.go(Routes.browse(id, null, ""))
        }
        path.firstOrNull() == "channel" && path.size > 1 -> nav.go(Routes.artist(path[1]))
    }
}

private fun launchSingle(c: AppContainer, videoId: String) = c.scope.launch {
    val r = runCatching { c.ytm.next(videoId) }.getOrNull()
    val song = r?.songs?.firstOrNull { it.id == videoId } ?: Song(videoId, tr(R.string.loading_2))
    withContext(Dispatchers.Main) { c.player.playSingle(song) }
}
