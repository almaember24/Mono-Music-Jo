package com.prism.music.ui.player

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.prism.music.data.canvas.CanvasArtwork
import com.prism.music.ui.theme.LocalContainer

/**
 * Plays a canvas clip — silent, looping, following the transport — on a
 * TextureView. The video is scaled with an explicit transform (a TextureView
 * otherwise stretches its frames to its own bounds), and nothing is shown
 * until a real frame lands, so a slow or failed clip just leaves the still art.
 */
@OptIn(UnstableApi::class)
@Composable
fun CanvasPlayer(
    canvas: CanvasArtwork,
    /** The song's cover is saved: what plays is kept on the phone for good. */
    saved: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** Share of the height, from the bottom, over which the clip dissolves. */
    bottomFade: Float = 0f,
    /** Extra alpha from the caller (e.g. as the player collapses into a panel). */
    presentationAlpha: () -> Float = { 1f },
    onCoverChanged: (Float) -> Unit = {},
    /** Something covers the player (a sheet): the clip holds its frame meanwhile. */
    hold: Boolean = false,
) {
    val context = LocalContext.current
    val c = LocalContainer.current
    // #384 (1.8.3): one state for every clip this player shows. It was remembered per canvas, but the player's and the
    // video view's listeners are set up once and kept writing the first song's, so the next song's clip never showed.
    val clip = remember { ClipState(canvas) }
    LaunchedEffect(canvas) { clip.show(canvas) }
    val url = clip.url
    var texture by remember { mutableStateOf<TextureView?>(null) }

    // The player is borrowed from [CanvasPlayers] and handed back when the clip leaves the screen, so opening the
    // player again picks up the same one, clip and decoder (#343: a new player each time made a new video decoder
    // and checked every rendition against the phone's decoders again, many times a minute while it was opened and
    // closed).
    val held = remember { CanvasPlayers.take(context, c, url) }
    val player = held.player
    androidx.compose.runtime.SideEffect { held.source.clip = url; held.source.keep = saved }
    DisposableEffect(held) { onDispose { CanvasPlayers.give(held) } }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                clip.videoSize(videoSize.width * videoSize.pixelWidthHeightRatio, videoSize.height)
                texture?.cropTo(clip.aspect)
            }

            override fun onPlayerError(error: PlaybackException) = clip.failed()
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(url) {
        clip.rendered = false
        // The same clip as last time the player was open: it carries on, no new loading or decoder.
        if (CanvasPlayers.playing(player, url)) {
            val v = player.videoSize
            clip.videoSize(v.width * v.pixelWidthHeightRatio, v.height)
            texture?.cropTo(clip.aspect)
            return@LaunchedEffect
        }
        // A new clip: nothing shows until its own size and first frame are known (not the last clip's).
        clip.aspect = 0f
        val item = MediaItem.Builder().setUri(url)
        if (url.substringBefore('?').endsWith(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(item.build())
        player.prepare()
    }

    // Only decode while the app is visible; pausing the song keeps the clip moving
    // (like Apple Music) but leaving the app stops it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(foreground, isPlaying, hold) { player.playWhenReady = foreground && !hold }

    val alpha by animateFloatAsState(if (clip.rendered) 1f else 0f, tween(320), label = "canvasAlpha")
    val cover by rememberUpdatedState(onCoverChanged)
    // Held, the still cover shows in the clip's place (the video view is taken off screen, below).
    LaunchedEffect(alpha, hold) { cover(if (hold) 0f else alpha * presentationAlpha()) }
    DisposableEffect(Unit) { onDispose { cover(0f) } }

    AndroidView(
        factory = { ctx ->
            val tv = TextureView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isOpaque = false
                this.alpha = 0f
                player.setVideoTextureView(this)
                val delegate = surfaceTextureListener
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { delegate?.onSurfaceTextureAvailable(s, w, h) }
                    override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                        delegate?.onSurfaceTextureSizeChanged(s, w, h)
                        cropTo(clip.aspect)
                    }
                    override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                        clip.rendered = false
                        return delegate?.onSurfaceTextureDestroyed(s) ?: true
                    }
                    override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                        delegate?.onSurfaceTextureUpdated(s)
                        if (clip.frame()) cropTo(clip.aspect)
                    }
                }
            }
            texture = tv
            FadingFrame(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addView(tv)
            }
        },
        update = { frame ->
            val tv = frame.getChildAt(0) as TextureView
            // Under a sheet the video view isn't drawn at all: paused, it was still a live layer (with its fade effect)
            // in the window, and dragging the ⋯ sheet over an animated cover kept sticking.
            frame.visibility = if (hold) android.view.View.INVISIBLE else android.view.View.VISIBLE
            tv.alpha = alpha * presentationAlpha()
            tv.cropTo(clip.aspect)
            if (Build.VERSION.SDK_INT >= 31) tv.bottomFade(bottomFade) else frame.fade = bottomFade
        },
        modifier = modifier,
    )
}

/**
 * What a [CanvasPlayer] knows about the clip it shows now (#384, 1.8.3): one object for every clip, so the listeners set
 * up once (the player's, the video view's) always reach the current clip's state.
 */
internal class ClipState(first: CanvasArtwork) {
    var canvas by mutableStateOf(first); private set
    var url by mutableStateOf(first.url); private set
    var rendered by mutableStateOf(false)
    var aspect by mutableFloatStateOf(0f)

    /** The song's clip changed: start over with it. */
    fun show(next: CanvasArtwork) {
        if (next == canvas) return
        // The same clip under another song (an album's one video for all its tracks): it plays on as it is; resetting
        // here would hide it for good, since nothing loads again to show it.
        if (next.url == canvas.url) { canvas = next; return }
        canvas = next; url = next.url; rendered = false; aspect = 0f
    }

    fun videoSize(width: Float, height: Int) { aspect = if (width > 0 && height > 0) width / height else 0f }

    /** A frame landed: the clip shows once its size is known. True when it has just become visible. */
    fun frame(): Boolean {
        if (rendered || aspect <= 0f) return false
        rendered = true
        return true
    }

    /** The clip couldn't play: its other rendition, else the still cover stays. */
    fun failed() {
        val alt = canvas.fallbackUrl
        if (alt != null && alt != url) url = alt else rendered = false
    }
}

/**
 * The animated covers' players, kept between uses (#343, 1.8.1.1): a clip leaving the screen (the player closing) hands
 * its player back paused and without a surface, and the next one to show takes it, with its clip and decoder still
 * there. One kept at a time, let go after [IDLE_RELEASE_MS] unused. Main thread only.
 */
@OptIn(UnstableApi::class)
internal object CanvasPlayers {
    /** What the player's data source reads, set by whichever [CanvasPlayer] holds it now. */
    class Source { @Volatile var clip: String = ""; @Volatile var keep: Boolean = false }
    class Held(val player: ExoPlayer, val source: Source)

    private const val IDLE_RELEASE_MS = 30_000L
    /** The player can show the clip twice at once (behind everything and in the lyrics' ground): two kept at most. */
    private const val KEPT = 2
    private val parked = ArrayList<Held>(KEPT)
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val letGo = Runnable { parked.forEach { it.player.release() }; parked.clear() }

    /** A kept player, the one already on [url] if there is one, else a new one. */
    fun take(context: Context, c: com.prism.music.AppContainer, url: String): Held {
        (parked.firstOrNull { it.source.clip == url } ?: parked.lastOrNull())?.let { parked.remove(it); return it }
        val source = Source()
        // Clips come from the phone when they've played before (or were saved), else stream and are kept.
        val sources = DefaultMediaSourceFactory(c.canvasStore.dataSourceFactory({ source.clip }) { source.keep })
        val side = com.prism.music.data.canvas.canvasMaxSide(context)
        val player = ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(sources)
            // A clip of a few seconds, looping, mostly read from the phone: a few seconds ahead is plenty. With the
            // player's defaults (50 s, up to 128 MB for video) it kept loading further loops of the same clip and
            // held ~50 MB of it in memory (heap dump on the budget emulator, 1.7.6).
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(4_000, 10_000, 1_000, 2_000)
                    .setTargetBufferBytes(8 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build()
            )
            .build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                // Always the same rendition (H.264, the best up to canvasMaxSide), so what was kept last
                // time, or saved ahead by CanvasStore (which picks the same one), is what plays.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setMaxVideoSize(side, side)
                    .clearViewportSizeConstraints()
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                    .setForceHighestSupportedBitrate(true)
                    .build()
            }
        return Held(player, source)
    }

    fun give(held: Held) {
        held.player.playWhenReady = false
        held.player.clearVideoSurface()
        if (parked.size >= KEPT) { held.player.release(); return }
        parked += held
        // Let go once nothing has taken them back for a while (each hand-back starts the wait again).
        main.removeCallbacks(letGo)
        main.postDelayed(letGo, IDLE_RELEASE_MS)
    }

    /** The player has [url] loaded (not failed or stopped): showing it again needs nothing new. */
    fun playing(player: Player, url: String): Boolean = loadedAlready(player.currentMediaItem?.localConfiguration?.uri?.toString(), player.playbackState, player.playerError != null, url)

    internal fun loadedAlready(current: String?, state: Int, failed: Boolean, url: String): Boolean =
        current == url && !failed && state != Player.STATE_IDLE
}

/** Centre-crop the video into the view (a TextureView stretches by default). */
internal fun TextureView.cropTo(clipAspect: Float) {
    if (width <= 0 || height <= 0 || clipAspect <= 0f) { setTransform(Matrix()); return }
    val viewAspect = width.toFloat() / height
    val m = Matrix()
    if (clipAspect > viewAspect) m.setScale(clipAspect / viewAspect, 1f, width / 2f, height / 2f)
    else m.setScale(1f, viewAspect / clipAspect, width / 2f, height / 2f)
    setTransform(m)
}

/** Dissolve the bottom edge on the view's own node (a Compose mask can't reach video frames). */
@RequiresApi(31)
internal fun android.view.View.bottomFade(fraction: Float) {
    if (fraction <= 0.001f || height <= 0) { setRenderEffect(null); return }
    val h = height.toFloat()
    val gradient = LinearGradient(0f, h * (1f - fraction), 0f, h, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
    setRenderEffect(RenderEffect.createBlendModeEffect(RenderEffect.createOffsetEffect(0f, 0f), RenderEffect.createShaderEffect(gradient), BlendMode.DST_IN))
}

/** Pre-API-31 bottom fade: draw the child into a layer and mask it. */
internal class FadingFrame(context: Context) : FrameLayout(context) {
    /** The fade asked for (Android 12+ draws it as a render effect instead of [fade]). */
    var wanted = 0f
    var fade = 0f
        set(v) { if (v != field) { field = v; invalidate() } }
    private val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    override fun dispatchDraw(canvas: Canvas) {
        if (fade <= 0.001f) { super.dispatchDraw(canvas); return }
        val h = height.toFloat()
        paint.shader = LinearGradient(0f, h * (1f - fade), 0f, h, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), h, null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), h, paint)
        canvas.restoreToCount(layer)
    }
}
