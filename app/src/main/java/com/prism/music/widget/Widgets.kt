package com.prism.music.widget

import com.prism.music.ui.theme.Mono
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.MainActivity
import com.prism.music.R
import com.prism.music.container
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The home screen widgets' looks; each is its own widget in the launcher's list. */
enum class WidgetStyle(val layout: Int, val provider: Class<out AppWidgetProvider>, val artCorner: Float) {
    /** Prism's own: the cover as frosted glass behind it. */
    PRISM(R.layout.widget_prism, PrismWidget::class.java, 0.16f),
    /** After Spotify's: a slim near-black bar with a green play button. */
    SPOTIFY(R.layout.widget_spotify, SpotifyStyleWidget::class.java, 0.08f),
    /** After Apple Music's: a big cover on a card the color of the cover. */
    APPLE(R.layout.widget_apple, AppleStyleWidget::class.java, 0.11f),
    /** After YouTube Music's: dark grey, thumbs up, a red progress line. */
    YTMUSIC(R.layout.widget_ytmusic, YtMusicStyleWidget::class.java, 0.11f),
    /** A small square (#34): the cover fills it, the title over its foot, and play. */
    COVER(R.layout.widget_cover, CoverWidget::class.java, 0f),
    /** A round disc (#34): the cover in a ring that fills as the song plays, play in the middle. */
    DISC(R.layout.widget_disc, DiscWidget::class.java, 0.5f),
    /** A slim bar (#34) in the cover's color: the cover, the title, and previous / play / next. */
    STRIP(R.layout.widget_strip, StripWidget::class.java, 0.18f),
}

abstract class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) = Widgets.refresh(context)
    override fun onDeleted(context: Context, ids: IntArray) = WidgetOptionsStore.remove(context, ids)
}

class PrismWidget : NowPlayingWidget()
class SpotifyStyleWidget : NowPlayingWidget()
class AppleStyleWidget : NowPlayingWidget()
class YtMusicStyleWidget : NowPlayingWidget()
class CoverWidget : NowPlayingWidget()
class DiscWidget : NowPlayingWidget()
class StripWidget : NowPlayingWidget()

/** The widgets' buttons that go to the player (a like doesn't need it). */
private val PLAYER_ACTIONS = setOf(Widgets.ACTION_PLAY, Widgets.ACTION_NEXT, Widgets.ACTION_PREVIOUS, RecognizeWidgets.ACTION_PLAY)

/** The widgets' buttons. Tapping one counts as using Prism, so playback may start from here. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        when (intent.action) {
            Widgets.ACTION_PLAY -> c.player.togglePlay()
            Widgets.ACTION_NEXT -> c.player.next()
            Widgets.ACTION_PREVIOUS -> c.player.previous()
            RecognizeWidgets.ACTION_PLAY -> RecognizeWidgets.songFrom(intent)?.let(c.player::playSingle)
            Widgets.ACTION_LIKE -> (c.player.currentSong.value ?: Widgets.lastSong(context))?.let { song ->
                c.library.toggleLike(song)
                // The heart shows the change at once; the service confirms it when the like lands.
                Widgets.update(context, song, c.player.isPlaying.value, song.id !in c.library.likedIds.value, null, null)
            }
        }
        // With Prism not running, the player is reached a moment later (Reddit R4: widgets couldn't pause or skip): the
        // broadcast is held until the command has been handed over, so Android doesn't stop Prism first.
        if (intent.action !in PLAYER_ACTIONS) return
        val pending = goAsync()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var finished = false
        fun finish() { if (!finished) { finished = true; pending.finish() } }
        handler.postDelayed(::finish, 9_000)
        c.player.afterPending { handler.post(::finish) }
    }
}

/**
 * Draws every placed widget from what's playing. The playback service pushes changes ([update]): a new song,
 * play/pause, a like, and the position every 15 s while playing. The last song is kept so widgets still show it
 * after a restart; tapping play then picks the saved queue back up.
 */
object Widgets {
    const val ACTION_PLAY = "com.prism.music.widget.PLAY"
    const val ACTION_NEXT = "com.prism.music.widget.NEXT"
    const val ACTION_PREVIOUS = "com.prism.music.widget.PREVIOUS"
    const val ACTION_LIKE = "com.prism.music.widget.LIKE"

    private class State(val song: Song?, val playing: Boolean, val liked: Boolean, val positionMs: Long, val durationMs: Long)

    /** Prism's own widgets whose play button sits in the spectrum ring, and whose progress is in split light. */
    private val RINGED = setOf(WidgetStyle.PRISM, WidgetStyle.COVER, WidgetStyle.STRIP)
    private val SPLIT_PROGRESS = setOf(WidgetStyle.PRISM, WidgetStyle.COVER, WidgetStyle.DISC)

    /** Whether [style]'s play button or progress can take another color ([WidgetOptions.buttons]): Android 12+. */
    fun hasButtonColor(style: WidgetStyle) = Build.VERSION.SDK_INT >= 31 && (style in RINGED || style in SPLIT_PROGRESS)

    /** Appearance → Color → Spectrum is White light. */
    fun whiteLight(context: Context): Boolean = runCatching {
        context.container.settings.current.lightStyle == com.prism.music.data.prefs.LightStyle.WHITE
    }.getOrDefault(false)

    /** The listener's light's first and middle colors (as drawn on dark), or null for Prism's own light. */
    private fun lightColors(context: Context): Pair<Int, Int>? = runCatching {
        val s = context.container.settings.current
        val light = com.prism.music.ui.theme.SpectrumPresets.light(s.spectrumPreset, s.spectrumStops, s.spectrumBands)
        if (light === com.prism.music.ui.theme.SpectrumPresets.PRISM_LIGHT) return null
        val run = light.colors
        run.first().second.toArgb() to com.prism.music.ui.theme.SpectrumPresets.sample(run.map { it.second }, 0.5f).toArgb()
    }.getOrNull()

    /** [argb] as a grey of the same lightness. */
    private fun grey(argb: Int): Int = FloatArray(3).also { ColorUtils.colorToHSL(argb, it); it[1] = 0f }.let { ColorUtils.HSLToColor(it) }

    /** The cover in each form the widgets use, for one song's picture. */
    private class Art(val url: String, val square: Map<WidgetStyle, Bitmap>, val frost: Bitmap?, val color: Int, val vivid: Int = color)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    @Volatile private var state: State? = null
    @Volatile private var art: Art? = null
    private var job: Job? = null
    /** The cover being loaded, by its address: a refresh while it loads waits for the same load, never starts over. */
    private var artLoad: Pair<String, kotlinx.coroutines.Deferred<Art?>>? = null

    private fun placed(context: Context): Map<WidgetStyle, IntArray> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyMap()
        return WidgetStyle.entries.associateWith { runCatching { manager.getAppWidgetIds(ComponentName(context, it.provider)) }.getOrDefault(IntArray(0)) }
            .filterValues { it.isNotEmpty() }
    }

    /** Whether any Prism widget is on the home screen (so the service can skip the work when none is). */
    fun anyPlaced(context: Context): Boolean = placed(context).isNotEmpty()

    /** What's playing changed. [positionMs]/[durationMs] null keep the last known ones. */
    fun update(context: Context, song: Song?, playing: Boolean, liked: Boolean, positionMs: Long?, durationMs: Long?) {
        val app = context.applicationContext
        if (!anyPlaced(app)) return
        val before = state
        state = State(
            song, playing, liked,
            positionMs ?: before?.positionMs?.takeIf { before.song?.id == song?.id } ?: 0L,
            durationMs ?: before?.durationMs?.takeIf { before.song?.id == song?.id } ?: (song?.durationSec ?: 0) * 1000L,
        )
        if (song != null && song.id != before?.song?.id) saveLast(app, song)
        refresh(app)
    }

    /** Draws the widgets again from the latest state (or the last song played, after a restart). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            lock.withLock {
                val s = state ?: State(lastSong(app), playing = false, liked = false, 0L, 0L)
                val url = s.song?.let { com.prism.music.data.PlaylistCovers.artOf(it) }
                val manager = AppWidgetManager.getInstance(app) ?: return@withLock
                fun draw(a: Art?) = placed(app).forEach { (style, ids) ->
                    // Widgets left as designed share one update; each with settings of its own (1.7.7) gets its own.
                    val (plain, own) = ids.partition { WidgetOptionsStore.get(app, it).isDefault }
                    if (plain.isNotEmpty()) runCatching { manager.updateAppWidget(plain.toIntArray(), views(app, style, s, a)) }
                    own.forEach { id -> runCatching { manager.updateAppWidget(id, views(app, style, s, a, WidgetOptionsStore.get(app, id))) } }
                }
                if (art?.url != url) {
                    // A new song shows at once, its cover once it's loaded (#217: the widget waited for the cover, and
                    // each update meanwhile started that load over, so the new song showed seconds late).
                    draw(null)
                    // Offline with a cover Coil hasn't kept: the widget keeps Prism's mark.
                    art = url?.let { u ->
                        val load = artLoad?.takeIf { it.first == u }?.second ?: scope.async { loadArt(app, u) }.also { artLoad = u to it }
                        load.await()
                    }
                }
                draw(art)
            }
        }
    }

    /**
     * [style]'s widget showing what's playing now, with one widget's own settings ([o]); for the settings screen's
     * preview of it ([WidgetConfigActivity]).
     */
    suspend fun preview(context: Context, style: WidgetStyle, o: WidgetOptions): RemoteViews {
        val app = context.applicationContext
        val s = state ?: State(lastSong(app), playing = false, liked = false, 0L, 0L)
        // The cover picked for the song or its album (#305), else its own.
        val shownArt = s.song?.let { com.prism.music.data.PlaylistCovers.artOf(it) }
        val a = art?.takeIf { it.url == shownArt } ?: shownArt?.let { loadArt(app, it) }
        return views(app, style, s, a, o)
    }

    private fun views(context: Context, style: WidgetStyle, s: State, art: Art?, o: WidgetOptions = WidgetOptions()): RemoteViews {
        val v = RemoteViews(context.packageName, style.layout)
        // As the version picked to play in its place (#328).
        val song = s.song?.let { com.prism.music.data.SongVersions.shownOf(it) }
        v.setTextViewText(R.id.widget_title, song?.title ?: com.prism.music.tr(R.string.widget_nothing))
        v.setTextViewText(R.id.widget_artist, song?.artistText ?: com.prism.music.tr(R.string.widget_tap_to_play))
        val cover = art?.square?.get(style)
        if (cover != null) v.setImageViewBitmap(R.id.widget_art, cover) else v.setImageViewResource(R.id.widget_art, R.drawable.ic_notification)
        v.setImageViewResource(R.id.widget_play, if (s.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        v.setContentDescription(R.id.widget_play, com.prism.music.tr(if (s.playing) R.string.widget_pause else R.string.widget_play))
        val heart = when (style) {
            WidgetStyle.YTMUSIC -> if (s.liked) R.drawable.ic_w_thumb else R.drawable.ic_w_thumb_outline
            else -> if (s.liked) R.drawable.ic_w_heart else R.drawable.ic_w_heart_outline
        }
        v.setImageViewResource(R.id.widget_like, heart)
        if (style == WidgetStyle.SPOTIFY) v.setInt(R.id.widget_like, "setColorFilter", if (s.liked) 0xFF929292.toInt() else Color.WHITE)
        val progress = if (s.durationMs > 0) (s.positionMs * 1000 / s.durationMs).toInt().coerceIn(0, 1000) else 0
        if (style != WidgetStyle.APPLE) v.setProgressBar(R.id.widget_progress, 1000, progress, false)
        // White light (Appearance → Color): Prism's own widgets in white and grey instead of the split light.
        val white = whiteLight(context)
        if (style in RINGED) v.setInt(R.id.widget_play, "setBackgroundResource", if (white) R.drawable.widget_circle else R.drawable.widget_play_ring)
        if (style in SPLIT_PROGRESS) {
            v.setViewVisibility(R.id.widget_progress, if (white) android.view.View.GONE else android.view.View.VISIBLE)
            v.setViewVisibility(R.id.widget_progress_white, if (white) android.view.View.VISIBLE else android.view.View.GONE)
            v.setProgressBar(R.id.widget_progress_white, 1000, progress, false)
        }
        // Your light (1.7.7, held over from 1.7.2): a widget can't be handed a gradient, so on Android 12+ another light
        // than Prism's own tints the progress with its middle color and the play ring with its first.
        if (!white && Build.VERSION.SDK_INT >= 31) lightColors(context)?.let { (first, middle) ->
            if (style in SPLIT_PROGRESS) v.setColorStateList(R.id.widget_progress, "setProgressTintList", android.content.res.ColorStateList.valueOf(middle))
            if (style in RINGED) v.setColorStateList(R.id.widget_play, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(first))
        }

        when (style) {
            WidgetStyle.PRISM -> {
                // Frosted cover behind (Android 12+ rounds it for us); older launchers get the plain dark card.
                if (art?.frost != null && Build.VERSION.SDK_INT >= 31) v.setImageViewBitmap(R.id.widget_bg, art.frost)
                else v.setImageViewBitmap(R.id.widget_bg, null)
                v.setTextColor(R.id.widget_label, art?.color?.takeIf { !white }?.let { ColorUtils.blendARGB(it, Color.WHITE, 0.55f) } ?: 0xB3FFFFFF.toInt())
            }
            WidgetStyle.APPLE -> v.setInt(R.id.widget_tint, "setColorFilter", art?.color ?: 0xFF3A3A3A.toInt())
            WidgetStyle.STRIP -> v.setInt(R.id.widget_tint, "setColorFilter", art?.color?.let { if (white) grey(it) else it } ?: 0xFF2B2B2B.toInt())
            else -> Unit
        }

        fun broadcast(action: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, WidgetActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.widget_play, broadcast(ACTION_PLAY, 1))
        v.setOnClickPendingIntent(R.id.widget_next, broadcast(ACTION_NEXT, 2))
        v.setOnClickPendingIntent(R.id.widget_prev, broadcast(ACTION_PREVIOUS, 3))
        v.setOnClickPendingIntent(R.id.widget_like, broadcast(ACTION_LIKE, 4))
        val open = PendingIntent.getActivity(
            context, 5,
            Intent(context, com.prism.music.PrismActivity::class.java).setData(Uri.parse("prism://player"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.widget_open, open)
        v.setOnClickPendingIntent(R.id.widget_art, open)
        if (style == WidgetStyle.PRISM) v.setOnClickPendingIntent(R.id.widget_logo, open)
        if (!o.isDefault) applyOptions(v, style, o, art)
        return v
    }

    /**
     * One widget's own settings (1.7.7) over its style. Every layout has the same view ids (the art, title, artist,
     * buttons; the progress bar in all but Apple's), so only ids each layout has are touched.
     */
    private fun applyOptions(v: RemoteViews, style: WidgetStyle, o: WidgetOptions, art: Art?) {
        val gone = android.view.View.GONE
        // The cover is the Cover and Disc widgets themselves.
        if (!o.cover && style != WidgetStyle.COVER && style != WidgetStyle.DISC) v.setViewVisibility(R.id.widget_art, gone)
        if (!o.like) v.setViewVisibility(R.id.widget_like, gone)
        if (!o.skips) { v.setViewVisibility(R.id.widget_prev, gone); v.setViewVisibility(R.id.widget_next, gone) }
        if (!o.progress && style != WidgetStyle.APPLE) {
            v.setViewVisibility(R.id.widget_progress, gone)
            if (style in SPLIT_PROGRESS) v.setViewVisibility(R.id.widget_progress_white, gone)
        }
        when (o.text) {
            WidgetText.AUTO -> Unit
            WidgetText.WHITE, WidgetText.BLACK -> {
                val ink = if (o.text == WidgetText.WHITE) Color.WHITE else 0xFF111111.toInt()
                v.setTextColor(R.id.widget_title, ink)
                v.setTextColor(R.id.widget_artist, ColorUtils.setAlphaComponent(ink, 0xB3))
            }
        }
        if (o.buttons != WidgetButtons.LIGHT && hasButtonColor(style)) {
            val c = when (o.buttons) {
                WidgetButtons.COVER -> art?.vivid ?: Color.WHITE
                WidgetButtons.COLOR -> o.buttonColor or 0xFF000000.toInt()
                else -> Color.WHITE
            }
            val tint = android.content.res.ColorStateList.valueOf(c)
            if (style in RINGED) {
                // A plain disc in the color (the split-light ring would clash with it), the icon dark or white on it.
                v.setInt(R.id.widget_play, "setBackgroundResource", R.drawable.widget_circle)
                v.setColorStateList(R.id.widget_play, "setBackgroundTintList", tint)
                v.setInt(R.id.widget_play, "setColorFilter", if (ColorUtils.calculateLuminance(c) > 0.4) 0xFF111111.toInt() else Color.WHITE)
            }
            if (style in SPLIT_PROGRESS && o.progress) {
                v.setViewVisibility(R.id.widget_progress, android.view.View.VISIBLE)
                v.setViewVisibility(R.id.widget_progress_white, gone)
                v.setColorStateList(R.id.widget_progress, "setProgressTintList", tint)
            }
        }
        if (o.background == WidgetBackground.DESIGN) return
        // The style's own color layers go (Prism's frost, Apple's and Strip's tint); the panel takes the new color.
        if (style == WidgetStyle.PRISM) v.setViewVisibility(R.id.widget_bg, gone)
        if (style == WidgetStyle.APPLE || style == WidgetStyle.STRIP) v.setViewVisibility(R.id.widget_tint, gone)
        val base = when (o.background) {
            WidgetBackground.COLOR -> o.color
            WidgetBackground.COVER -> art?.color ?: 0xFF2B2B2B.toInt()
            else -> Color.TRANSPARENT
        }
        // The panel: Android's widget background view, or the Disc's round button.
        val root = if (style == WidgetStyle.DISC) R.id.widget_open else android.R.id.background
        val color = if (o.background == WidgetBackground.CLEAR) Color.TRANSPARENT
            else ColorUtils.setAlphaComponent(base, (o.opacity.coerceIn(0.2f, 1f) * 255).toInt())
        if (Build.VERSION.SDK_INT >= 31) {
            v.setColorStateList(root, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(color))
        } else if (o.background == WidgetBackground.CLEAR) {
            v.setInt(root, "setBackgroundResource", 0)
        } else if (style != WidgetStyle.DISC) {
            // Before Android 12 a view's tint can't be set from a widget: a plain panel in the color.
            v.setInt(root, "setBackgroundColor", color)
        }
    }

    private suspend fun loadArt(context: Context, url: String): Art? = runCatching {
        val req = ImageRequest.Builder(context).data(hiRes(url, 544)).size(440).allowHardware(false).build()
        val bmp = (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap() ?: return null
        // The Cover widget's fills a 2×2 square, so it's bigger; the rest stay small (a widget's update has to fit
        // through a ~1 MB pipe, the Prism widget's frost included).
        val big = centerSquare(bmp, 400)
        val square = Bitmap.createScaledBitmap(big, 300, 300, true)
        val palette = Palette.from(square).maximumColorCount(16).generate()
        val base = Mono.gray((palette.dominantSwatch ?: palette.mutedSwatch ?: palette.vibrantSwatch)?.rgb ?: 0xFF3A3A3A.toInt())
        // Deep enough for white text on it, like Apple Music's cards.
        val card = if (ColorUtils.calculateLuminance(base) > 0.35) ColorUtils.blendARGB(base, Color.BLACK, 0.45f) else base
        Art(
            url = url,
            square = WidgetStyle.entries.associateWith { rounded(if (it == WidgetStyle.COVER) big else square, it.artCorner) },
            frost = frost(square),
            color = card or 0xFF000000.toInt(),
            // The play button in the cover's color: its brightest lively color, so it stands out on the card.
            vivid = Mono.gray(((palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.lightMutedSwatch)?.rgb ?: base) or 0xFF000000.toInt()),
        )
    }.getOrNull()

    /** A cover as a small rounded square ([corner] of its side), for the Recognize widget. */
    suspend fun squareCover(context: Context, url: String, px: Int, corner: Float): Bitmap? = runCatching {
        val req = ImageRequest.Builder(context).data(hiRes(url, 544)).size(px * 2).allowHardware(false).build()
        val bmp = (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap() ?: return null
        rounded(centerSquare(bmp, px), corner)
    }.getOrNull()

    private fun centerSquare(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(crop, size, size, true)
    }

    /** The cover with rounded corners ([corner] of its side), so they're round on every Android version. */
    private fun rounded(src: Bitmap, corner: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val r = src.width * corner
        Canvas(out).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), r, r, paint)
        return out
    }

    /** A soft frosted version of the cover: shrunk right down and stretched back up, twice. */
    private fun frost(src: Bitmap): Bitmap {
        val tiny = Bitmap.createScaledBitmap(src, 10, 10, true)
        val mid = Bitmap.createScaledBitmap(tiny, 40, 40, true)
        return Bitmap.createScaledBitmap(mid, 240, 240, true)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("widget_state", Context.MODE_PRIVATE)

    private fun saveLast(context: Context, song: Song) {
        prefs(context).edit().putString("song", InnerTube.json.encodeToString(Song.serializer(), song)).apply()
    }

    fun lastSong(context: Context): Song? = prefs(context).getString("song", null)?.let {
        runCatching { InnerTube.json.decodeFromString(Song.serializer(), it) }.getOrNull()
    }
}
