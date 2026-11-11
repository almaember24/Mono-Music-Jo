package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.data.db.mergeUploads
import com.prism.music.ui.theme.PrismIcons
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.prism.music.AppContainer
import com.prism.music.data.Habits
import com.prism.music.data.Personality
import com.prism.music.data.ReplayStats
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.SongPlays
import com.prism.music.data.db.mainArtist
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.playback.PrismMediaSourceFactory
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.playback.toMediaItem
import com.prism.music.ui.components.PlayingBars
import com.prism.music.ui.components.recordInto
import com.prism.music.ui.components.shareLayer
import com.prism.music.ui.theme.ArtistType
import com.prism.music.ui.theme.ArtistTypography
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------- Data

/** Everything the Replay story shows, gathered once when it opens. */
internal class ReplayStory(
    val period: Period,
    /** "2026", "October", "Last 30 days", "All time". */
    val title: String,
    val totalMs: Long,
    val previousMs: Long?,
    val previousLabel: String?,
    val plays: Int,
    val activeDays: Int,
    val songCount: Int,
    val artistCount: Int,
    val songs: List<SongPlays>,
    val artists: List<ArtistPlays>,
    val genres: List<Pair<String, Long>>,
    val genreSongs: Map<String, SongPlays>,
    val habits: Habits,
    /** Artists heard for the first time in this period (null for all time, where everyone is new). */
    val newArtists: List<ArtistPlays>?,
    val personality: Personality,
    val topArtistImage: String?,
    val topArtistType: ArtistType,
    /** A year's story: its Rewind, with the parts only a year has. */
    val rewind: Boolean = false,
    val months: List<com.prism.music.data.db.MonthTotal> = emptyList(),
    val topAlbum: com.prism.music.data.db.AlbumPlays? = null,
    val firstSong: com.prism.music.data.db.FirstPlay? = null,
    val seasons: List<com.prism.music.data.SeasonGenre> = emptyList(),
    val prism: com.prism.music.data.PrismYear = com.prism.music.data.PrismYear(),
    val topLyric: String? = null,
    val previousTopArtist: String? = null,
    val previousSongCount: Int? = null,
    /** "Includes Spotify" when an imported history is counted (1.8.1): every card says so. */
    val includes: String? = null,
) {
    /** "PRISM REWIND" for a year, "PRISM REPLAY" otherwise. */
    val brandName: String get() = if (rewind) tr(R.string.prism_rewind) else tr(R.string.prism_replay)
    /** [brandName] and what else it counts. */
    val brand: String get() = brandName + (includes?.let { " · " + it.uppercase() } ?: "")
}

/** The facts [StoryParts.pick] chooses this story's slides by. */
internal fun ReplayStory.facts() = com.prism.music.data.StoryFacts(
    rewind = rewind, totalMs = totalMs, plays = plays, activeDays = activeDays, songs = songs.size, artists = artists.size,
    genres = genres.size, seasonsWithGenre = seasons.count { it.genre != null }, album = topAlbum,
    monthsWithListening = months.count { it.ms > 0 }, firstSong = firstSong != null, newArtists = newArtists?.size ?: 0,
    prism = prism.any, previous = previousMs != null,
)

/** A year's Rewind as its story: kept (or just worked out) by [com.prism.music.data.Rewinds], plus the top artist's type. */
private suspend fun loadRewindStory(c: AppContainer, year: Int): ReplayStory = withContext(Dispatchers.IO) {
    val s = c.rewind.snapshot(year)
    val lead = s.artists.firstOrNull()
    val leadPage = lead?.artistId?.let { id -> withTimeoutOrNull(5_000) { runCatching { c.ytm.artist(id) }.getOrNull() } }
    val leadGenre = lead?.let { withTimeoutOrNull(3_000) { runCatching { c.meta.artistGenre(it.artistName) }.getOrNull() } }
    val leadType = if (lead?.artistId != null) ArtistTypography.forArtist(lead.artistId, leadGenre, ArtistTypography.parseAudience(leadPage?.subscribers)) else ArtistTypography.standard
    val photos = c.scope.launch {
        c.library.fetchArtistPhotos((s.artists.take(5) + (s.newArtists ?: emptyList()).take(6)).map { it.artistId to it.artistName })
    }
    withTimeoutOrNull(6_000) { photos.join() }
    val genreSongs = s.genreSongs.mapNotNull { (g, id) -> s.songs.firstOrNull { it.songId == id }?.let { g to it } }.toMap()
    ReplayStory(
        period = Period.year(year), title = "$year",
        totalMs = s.totalMs, previousMs = s.previousMs, previousLabel = if (s.previousMs != null) "${year - 1}" else null,
        plays = s.plays, activeDays = s.activeDays, songCount = s.songCount, artistCount = s.artistCount,
        songs = s.songs, artists = s.artists, genres = s.genres.map { it.genre to it.ms }, genreSongs = genreSongs,
        habits = s.habits(), newArtists = s.newArtists, personality = s.personality,
        topArtistImage = s.topArtistImage ?: leadPage?.thumbnail, topArtistType = leadType,
        rewind = true, months = s.months, topAlbum = s.topAlbum, firstSong = s.firstSong, seasons = s.seasons, prism = s.prism,
        topLyric = s.topLyric, previousTopArtist = s.previousTopArtist, previousSongCount = s.previousSongCount,
        includes = includesLine(c),
    )
}

/** "Includes Spotify" while an imported history is counted (1.8.1). */
private fun includesLine(c: AppContainer): String? =
    c.imports.counted.value.takeIf { it.isNotEmpty() }?.let { s -> tr(R.string.imp_includes, s.sortedBy { it.ordinal }.joinToString(", ") { it.label }) }

internal suspend fun loadReplayStory(c: AppContainer, period: Period): ReplayStory {
    if (period.isYear) return loadRewindStory(c, period.year)
    return loadPeriodStory(c, period)
}

private suspend fun loadPeriodStory(c: AppContainer, period: Period): ReplayStory = withContext(Dispatchers.IO) {
    val (from, to) = range(period)
    // Prism's plays, plus the imported sources the listener counts (1.8.1).
    val dao = c.imports.replay()
    val songs = dao.topSongs(from, to, 150).mergeUploads().take(100)
    val artists = dao.topArtists(from, to, 20)
    val totalMs = dao.totalMs(from, to)
    val genreMap = c.meta.cachedGenres(songs.map { it.toSong() })
    val genres = songs.groupBy { genreMap[it.songId] ?: "" }.filterKeys { it.isNotEmpty() }
        .mapValues { (_, v) -> v.sumOf { it.ms } }.toList().sortedByDescending { it.second }.take(5)
    val genreSongs = genres.associate { (g, _) -> g to songs.first { genreMap[it.songId] == g } }

    // The same stretch of time one step back, for "up 24% on last year".
    val (prevFrom, prevTo, prevLabel) = Calendar.getInstance().let {
        fun shift(t: Long, field: Int, by: Int) = Calendar.getInstance().apply { timeInMillis = t; add(field, by) }.timeInMillis
        when (period.kind) {
            Period.Kind.MONTH -> Triple(shift(from, Calendar.MONTH, -1), shift(to, Calendar.MONTH, -1), tr(R.string.the_same_time_last_month))
            Period.Kind.MONTH_OF -> Triple(shift(from, Calendar.MONTH, -1), from - 1, tr(R.string.the_month_before))
            Period.Kind.DAYS30 -> Triple(from - 30L * 86_400_000, from - 1, tr(R.string.the_30_days_before))
            Period.Kind.TODAY -> Triple(from - 86_400_000, to - 86_400_000, tr(R.string.this_time_yesterday))
            Period.Kind.WEEK -> Triple(from - 7 * 86_400_000L, to - 7 * 86_400_000L, tr(R.string.the_same_time_last_week))
            // Any days: as many days just before them.
            Period.Kind.DAYS -> (to - from).let { span -> Triple(from - span - 1, from - 1, trp(R.plurals.the_n_days_before, (span / 86_400_000 + 1).toInt())) }
            Period.Kind.YEAR, Period.Kind.ALL -> Triple(0L, -1L, null)
        }
    }
    val previousMs = if (prevLabel != null) dao.totalMs(prevFrom, prevTo).takeIf { it > 60_000 } else null

    val habits = ReplayStats.habits(dao.events(from, to))
    val artistCount = dao.artistCount(from, to)
    val newArtists = if (period == Period.ALL || dao.firstEvent()?.let { it >= from } == true) null else dao.newArtists(from, to)
    val months = if (period == Period.ALL) emptyList() else dao.monthly(from, to)
    val activeDays = dao.activeDays(from, to)
    val plays = dao.count(from, to)
    val songCount = dao.songCount(from, to)
    val top = songs.firstOrNull()
    val genreTotal = genres.sumOf { it.second }.coerceAtLeast(1)

    // The top artist's photo and signature typeface, as on their artist page.
    val lead = artists.firstOrNull()
    val leadPage = lead?.artistId?.let { id -> withTimeoutOrNull(5_000) { runCatching { c.ytm.artist(id) }.getOrNull() } }
    val leadGenre = lead?.let { withTimeoutOrNull(3_000) { runCatching { c.meta.artistGenre(it.artistName) }.getOrNull() } }
    val leadType = if (lead?.artistId != null) ArtistTypography.forArtist(lead.artistId, leadGenre, ArtistTypography.parseAudience(leadPage?.subscribers)) else ArtistTypography.standard
    // Artist photos for the artist slides; the story waits a little for them rather than showing album covers.
    val photos = c.scope.launch {
        c.library.fetchArtistPhotos((artists.take(5) + (newArtists ?: emptyList()).take(6)).map { it.artistId to it.artistName })
    }
    withTimeoutOrNull(6_000) { photos.join() }

    ReplayStory(
        period = period,
        title = when (period.kind) {
            Period.Kind.YEAR -> "${period.year}"
            Period.Kind.MONTH -> Calendar.getInstance().getDisplayName(Calendar.MONTH, Calendar.LONG, java.util.Locale.getDefault()) ?: tr(R.string.this_month)
            Period.Kind.MONTH_OF -> java.time.Month.of(period.month + 1).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
            else -> period.label
        },
        totalMs = totalMs, previousMs = previousMs, previousLabel = prevLabel,
        plays = plays, activeDays = activeDays, songCount = songCount, artistCount = artistCount,
        songs = songs, artists = artists, genres = genres, genreSongs = genreSongs, habits = habits, newArtists = newArtists,
        personality = ReplayStats.personality(
            totalMs = totalMs, topFiveArtistsMs = artists.take(5).sumOf { it.ms }, artistCount = artistCount,
            newArtistCount = newArtists?.size, plays = plays, songCount = songCount,
            topSongPlays = top?.plays ?: 0, topSongTitle = top?.title, activeDays = activeDays,
            topGenreShare = genres.firstOrNull()?.let { it.second.toDouble() / genreTotal }, habits = habits,
        ),
        topArtistImage = leadPage?.thumbnail,
        topArtistType = leadType,
        months = months,
        includes = includesLine(c),
    )
}

// ---------------------------------------------------------------- Slides

private typealias Kind = com.prism.music.data.StoryPart

private class Slide(val kind: Kind, val song: SongPlays?, val colors: List<Color>)

private fun palette(vararg hex: Long) = hex.map { Color(it) }

/** The story's slides ([com.prism.music.data.StoryParts.pick] says which), each with its colors and the song under it. */
private fun slidesFor(s: ReplayStory): List<Slide> {
    val songs = s.songs
    fun byArtist(name: String?) = songs.firstOrNull { it.mainArtist == name }
    fun byTitle(title: String?) = songs.firstOrNull { it.title == title }
    return com.prism.music.data.StoryParts.pick(s.facts()).map { part ->
        when (part) {
            Kind.INTRO -> Slide(part, songs.getOrNull(2) ?: songs.firstOrNull(), palette(0xFF111111, 0xFF787878, 0xFF797979, 0xFFBBBBBB))
            Kind.MINUTES -> Slide(part, songs.getOrNull(3) ?: songs.firstOrNull(), palette(0xFF191919, 0xFF959595, 0xFFD3D3D3, 0xFF898989))
            Kind.SEASONS -> Slide(part, byTitle(s.seasons.lastOrNull { it.genre != null }?.topSong) ?: songs.getOrNull(5) ?: songs.firstOrNull(), palette(0xFF131313, 0xFFBBBBBB, 0xFFB6B6B6, 0xFFBBBBBB))
            Kind.GENRES -> Slide(part, s.genreSongs[s.genres.first().first], palette(0xFF151515, 0xFF939393, 0xFFA4A4A4, 0xFF787878))
            Kind.TOP_ARTIST -> Slide(part, byArtist(s.artists.first().artistName), palette(0xFF0C0C0C, 0xFF787878, 0xFF8F8F8F, 0xFF797979))
            Kind.TOP_ARTISTS -> Slide(part, byArtist(s.artists[1].artistName), palette(0xFF171717, 0xFF787878, 0xFFA3A3A3, 0xFF808080))
            Kind.TOP_SONG -> Slide(part, songs.first(), palette(0xFF0C0C0C, 0xFF747474, 0xFFA4A4A4, 0xFF767676))
            Kind.TOP_SONGS -> Slide(part, songs.first(), palette(0xFF191919, 0xFF747474, 0xFFA4A4A4, 0xFFCBCBCB))
            Kind.TOP_ALBUM -> Slide(part, songs.firstOrNull { it.albumTitle == s.topAlbum?.albumTitle } ?: songs.firstOrNull(), palette(0xFF161616, 0xFF8E8E8E, 0xFFCBCBCB, 0xFF828282))
            Kind.MONTHS -> Slide(part, songs.getOrNull(6) ?: songs.firstOrNull(), palette(0xFF121212, 0xFF7D7D7D, 0xFF929292, 0xFFA3A3A3))
            Kind.FIRST_SONG -> Slide(part, songs.firstOrNull { it.songId == s.firstSong?.songId } ?: songs.getOrNull(7) ?: songs.firstOrNull(), palette(0xFF191919, 0xFFA4A4A4, 0xFFA6A6A6, 0xFFD4D4D4))
            Kind.HABITS -> Slide(part, songs.getOrNull(4) ?: songs.firstOrNull(), palette(0xFF0E0E0E, 0xFF787878, 0xFF989898, 0xFFCCCCCC))
            Kind.DISCOVERY -> Slide(part, byArtist(s.newArtists!!.first().artistName), palette(0xFF181818, 0xFF989898, 0xFFCECECE, 0xFF7D7D7D))
            Kind.PRISM_YEAR -> Slide(part, songs.getOrNull(8) ?: songs.firstOrNull(), palette(0xFF0F0F0F, 0xFFA6A6A6, 0xFFC2C2C2, 0xFFA4A4A4))
            Kind.YEAR_COMPARE -> Slide(part, songs.getOrNull(9) ?: songs.firstOrNull(), palette(0xFF131313, 0xFF8F8F8F, 0xFF787878, 0xFF959595))
            Kind.PERSONALITY -> Slide(part, songs.getOrNull(1) ?: songs.firstOrNull(), palette(0xFF1A1A1A, 0xFF6A6A6A, 0xFFB1B1B1, 0xFF787878))
            Kind.SUMMARY -> Slide(part, songs.firstOrNull(), palette(0xFF0D0D0D, 0xFF787878, 0xFF797979, 0xFF8F8F8F))
        }
    }
}

// ---------------------------------------------------------------- Background music

/**
 * Plays a stretch of each slide's song from around its chorus, crossfading
 * between them, on its own player so the main queue is left alone.
 */
@OptIn(UnstableApi::class)
private class StoryAudio(context: Context, c: AppContainer) {
    private val bestParts = c.bestParts
    private val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(PrismMediaSourceFactory(c.playbackDataSourceFactory(), c.videoDataSourceFactory()))
        .build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_ONE }
    var current by mutableStateOf<Song?>(null)
        private set
    var muted = false
        set(v) { field = v; player.volume = if (v) 0f else player.volume.coerceAtLeast(if (current != null) 1f else 0f) }
    private var job: Job? = null

    fun play(song: Song, scope: kotlinx.coroutines.CoroutineScope) {
        if (song.id == current?.id) return
        current = song
        job?.cancel()
        job = scope.launch {
            ramp(0f, 300)
            // Its best part (the most replayed stretch, or the chorus in the lyrics); else roughly where most choruses land.
            val best = withTimeoutOrNull(2_500) { runCatching { bestParts.startMs(song) }.getOrNull() }
            val start = best ?: if (song.durationSec > 90) (song.durationSec * 0.33).toLong().coerceIn(30, 80) * 1000 else 0L
            player.setMediaItem(song.toMediaItem(), start)
            player.prepare()
            player.play()
            withTimeoutOrNull(8_000) { while (player.playbackState != Player.STATE_READY) delay(40) }
            ramp(1f, 1_400)
        }
    }

    private suspend fun ramp(to: Float, ms: Long) {
        val from = player.volume
        val steps = 28
        for (i in 1..steps) {
            player.volume = if (muted) 0f else from + (to - from) * i / steps
            delay(ms / steps)
        }
    }

    fun release() {
        job?.cancel()
        player.release()
    }
}

// ---------------------------------------------------------------- The story

@Composable
internal fun ReplayStoryDialog(story: ReplayStory, onDismiss: () -> Unit) {
    StoryDialog(onDismiss) {
        // #287: in the app's font when "Stories in your font" is on.
        val own = if (com.prism.music.ui.theme.LocalAppSettings.current.view.replayStoryFont) (androidx.compose.material3.MaterialTheme.typography.displayLarge.fontFamily ?: androidx.compose.ui.text.font.FontFamily.Default) else null
        androidx.compose.runtime.CompositionLocalProvider(LocalStoryFont provides own) { StoryContent(story, onDismiss) }
    }
}

/** The story's full-screen window. The screen stays on while it's open (#412): its slides run on a timer, untouched. */
@Composable
internal fun StoryDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The dialog's own view: its window is the one on top while the story plays.
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        // A story is a phone-shaped thing: on a wide window it stays 9:16 in the middle, on black.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val width = minOf(maxWidth, maxHeight * 9f / 16f)
            Box(Modifier.width(width).fillMaxHeight()) { content() }
        }
    }
}

@Composable
private fun StoryContent(story: ReplayStory, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val slides = remember(story) { slidesFor(story) }
    var index by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val audio = remember { StoryAudio(context, c) }
    val handedOff = remember { booleanArrayOf(false) }
    val slideLayer = rememberGraphicsLayer()

    // Like the big streaming apps: your music pauses for the story and picks up again after it.
    DisposableEffect(Unit) {
        val main = c.player.player
        val wasPlaying = main?.isPlaying == true
        main?.pause()
        onDispose {
            audio.release()
            if (wasPlaying && !handedOff[0]) c.player.player?.play()
        }
    }

    fun go(i: Int) {
        when {
            i in slides.indices -> { progress = 0f; index = i }
            i < 0 -> progress = 0f
        }
    }

    LaunchedEffect(index) { slides[index].song?.let { audio.play(it.toSong(), scope) } }
    LaunchedEffect(muted) { audio.muted = muted }
    LaunchedEffect(index, paused) {
        val ms = slides[index].kind.seconds * 1000f
        if (paused || ms == 0f) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (progress < 1f) {
            withFrameMillis { now -> progress = (progress + (now - last) / ms).coerceAtMost(1f); last = now }
        }
        go(index + 1)
    }

    val slide = slides[index]
    val base by animateColorAsState(slide.colors[0], tween(700), label = "base")
    val c1 by animateColorAsState(slide.colors[1], tween(700), label = "c1")
    val c2 by animateColorAsState(slide.colors[2], tween(700), label = "c2")
    val c3 by animateColorAsState(slide.colors[3], tween(700), label = "c3")

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                translationY = drag.coerceAtLeast(0f) * 0.6f
                val s = 1f - (drag.coerceAtLeast(0f) / 4000f).coerceAtMost(0.12f)
                scaleX = s; scaleY = s
            }
            .clip(RoundedCornerShape(if (drag > 0) 28.dp else 0.dp))
            .background(base),
    ) {
        // The slide (backdrop and all, not the controls over it) is also recorded, for sharing it as a picture.
        Box(Modifier.fillMaxSize().recordInto(slideLayer).background(base)) {
        Backdrop(base, c1, c2, c3)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(slides) {
                    detectTapGestures(
                        onPress = { paused = true; tryAwaitRelease(); paused = false },
                        onTap = { o -> if (o.x < size.width * 0.3f) go(index - 1) else go(index + 1) },
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (drag > 260f) onDismiss() else drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { _, d -> drag = (drag + d).coerceAtLeast(0f) }
                },
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = { (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 1.06f)) togetherWith fadeOut(tween(250)) },
                label = "slide",
            ) { i ->
                SlideView(slides[i], story, onReplay = { go(0) }, onPlayAll = {
                    handedOff[0] = true
                    c.player.playQueue(story.songs.map { it.toSong() }, 0, QueueSource(QueueKind.REPLAY, (if (story.rewind) tr(R.string.rewind_2) else tr(R.string.replay_4)) + story.title))
                    onDismiss()
                })
            }
        }
        }

        // Progress, title, mute and close.
        Column(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                slides.forEachIndexed { i, _ ->
                    val fill = when { i < index -> 1f; i == index -> progress; else -> 0f }
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${story.brand} · ${story.title.uppercase()}", Modifier.weight(1f).padding(start = 4.dp),
                    style = kicker(12.sp), color = Color.White.copy(alpha = 0.85f), maxLines = 1,
                )
                // Any slide as a picture (the summary has its own card too).
                IconButton(onClick = {
                    paused = true
                    scope.launch {
                        val name = if (story.rewind) tr(R.string.rewind_3) else tr(R.string.replay_2)
                        if (!shareLayer(context, slideLayer, "prism-${name.lowercase()}-${index + 1}.png", tr(R.string.my_prism, name, story.title), tr(R.string.share_this_slide))) {
                            Toast.makeText(context, tr(R.string.couldn_t_share_this_slide), Toast.LENGTH_SHORT).show()
                        }
                        paused = false
                    }
                }) {
                    Icon(PrismIcons.Share, tr(R.string.share_this_slide), tint = Color.White)
                }
                IconButton(onClick = { muted = !muted }) {
                    Icon(if (muted) PrismIcons.VolumeOff else PrismIcons.VolumeUp, if (muted) tr(R.string.unmute) else tr(R.string.mute), tint = Color.White)
                }
                IconButton(onClick = onDismiss) { Icon(PrismIcons.Close, tr(R.string.close), tint = Color.White) }
            }
        }

        // What's playing, Spotify-style.
        audio.current?.let { song ->
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(hiRes(song.thumbnail, 120), null, Modifier.size(28.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(8.dp))
                // The story's own music, not the player's: it plays while the story shows.
                if (!muted) PlayingBars(Modifier.size(12.dp), playing = true)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${song.title} · ${song.primaryArtist}", color = Color.White, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp),
                )
            }
        }
    }
}

/** Slow-drifting color fields behind every slide. */
@Composable
private fun Backdrop(base: Color, c1: Color, c2: Color, c3: Color) {
    val t by rememberInfiniteTransition(label = "bg").animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "t")
    Canvas(Modifier.fillMaxSize()) {
        val a = t * 2 * PI.toFloat()
        val r = size.maxDimension * 0.62f
        fun blob(color: Color, cx: Float, cy: Float) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.75f), color.copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
        blob(c1, size.width * (0.2f + 0.25f * sin(a)), size.height * (0.18f + 0.12f * cos(a)))
        blob(c2, size.width * (0.85f + 0.15f * cos(a * 2)), size.height * (0.55f + 0.15f * sin(a)))
        blob(c3, size.width * (0.3f + 0.2f * cos(a)), size.height * (0.95f + 0.08f * sin(a * 2)))
        drawRect(Brush.verticalGradient(listOf(base.copy(alpha = 0.15f), base.copy(alpha = 0.55f))))
    }
}

// ---------------------------------------------------------------- Type and motion

/**
 * The app's font for the stories (#287, 1.8.1: "Stories in your font"), or null for Prism's poster type (condensed and
 * extended display faces, as since 1.7). Provided by [StoryContent] from the settings.
 */
internal val LocalStoryFont = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.text.font.FontFamily?> { null }

/**
 * A slide's big type. In the app's font (#287) it's bold and a touch tighter and smaller (an ordinary face runs wider
 * than the condensed poster one), with a little more line height than the poster faces need.
 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun display(size: TextUnit, type: ArtistType = ArtistTypography.condensed): TextStyle {
    val own = LocalStoryFont.current ?: return TextStyle(
        fontFamily = type.family, fontWeight = type.weight, fontSize = size, lineHeight = size * 0.94f,
        letterSpacing = type.tracking.em, color = Color.White,
    )
    return TextStyle(fontFamily = own, fontWeight = FontWeight.Bold, fontSize = size * 0.9f, lineHeight = size, letterSpacing = (-0.02).em, color = Color.White)
}

/**
 * A slide's type at a glance, for picking it in Settings (#287): the Minutes slide's words on Replay's colors, in
 * Prism's poster type or ([own]) the app's font.
 */
@Composable
internal fun StoryTypeSample(own: Boolean, modifier: Modifier = Modifier) {
    val family = if (own) (androidx.compose.material3.MaterialTheme.typography.displayLarge.fontFamily ?: androidx.compose.ui.text.font.FontFamily.Default) else null
    androidx.compose.runtime.CompositionLocalProvider(LocalStoryFont provides family) {
        Column(
            modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(replayGradient())).padding(12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(tr(R.string.you_listened_for), style = kicker(9.sp), maxLines = 1)
            Text("1,204", style = display(40.sp), maxLines = 1)
            Text(tr(R.string.of_music).uppercase(), style = display(16.sp, ArtistTypography.extended), maxLines = 1)
        }
    }
}

/** The small capitals over a slide's figure ("YOU LISTENED FOR"). */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun kicker(size: TextUnit = 14.sp): TextStyle {
    val own = LocalStoryFont.current
    return TextStyle(
        fontFamily = own ?: ArtistTypography.extended.family, fontWeight = if (own != null) FontWeight.SemiBold else FontWeight.Black,
        fontSize = size, letterSpacing = if (own != null) 0.1.em else 0.06.em, color = Color.White,
    )
}

private val body = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, color = Color.White)

/**
 * A slide's big type, at [style]'s size or smaller, so a word is never broken or cut off whatever the language, the
 * font or the phone's width (Reddit: the card's "TODAY" came out as "TODA"). [oneLine] keeps all of it on one line; else
 * it may wrap between words, and the longest word has to fit. [widest] is the text to fit when it changes as it's shown
 * (a figure counting up): the size stays put meanwhile.
 */
@Composable
private fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier, widest: String = text, oneLine: Boolean = true) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val size = remember(widest, style, room, oneLine) {
            fittedSize(style.fontSize.value, room) { s ->
                val sized = style.copy(fontSize = s.sp)
                (if (oneLine) listOf(widest) else widest.split(Regex("\\s+")).filter { it.isNotEmpty() })
                    .maxOfOrNull { measurer.measure(it, sized, maxLines = 1, softWrap = false).size.width } ?: 0
            }
        }
        val scale = size / style.fontSize.value
        Text(
            text, style = style.copy(fontSize = size.sp, lineHeight = style.lineHeight * scale),
            maxLines = if (oneLine) 1 else Int.MAX_VALUE, softWrap = !oneLine,
        )
    }
}

/** The biggest size up to [start] at which [width] fits in [room]. Type's width goes almost in step with its size. */
internal fun fittedSize(start: Float, room: Int, width: (Float) -> Int): Float {
    if (room == androidx.compose.ui.unit.Constraints.Infinity) return start
    var size = start
    repeat(4) {
        val w = width(size)
        if (w <= room) return size
        size = (size * room / w * 0.98f).coerceAtLeast(FIT_MIN_SP)
    }
    return size
}

/** Below this, a title stops being one. */
private const val FIT_MIN_SP = 12f

/** Rises and fades in after [delayMs]. */
@Composable
private fun Modifier.reveal(delayMs: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        a.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow))
    }
    return graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 90f }
}

private fun minutesOf(ms: Long) = ms / 60_000
private fun fmt(n: Long) = "%,d".format(n)

@Composable
private fun SlideView(slide: Slide, s: ReplayStory, onReplay: () -> Unit, onPlayAll: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        val pad = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 26.dp, end = 26.dp, top = 84.dp, bottom = 64.dp)
        when (slide.kind) {
            Kind.INTRO -> Intro(s, pad)
            Kind.MINUTES -> Minutes(s, pad)
            Kind.SEASONS -> Seasons(s, pad)
            Kind.GENRES -> Genres(s, pad)
            Kind.TOP_ARTIST -> TopArtist(s, pad)
            Kind.TOP_ARTISTS -> TopArtists(s, pad)
            Kind.TOP_SONG -> TopSong(s, pad)
            Kind.TOP_SONGS -> TopSongs(s, pad)
            Kind.TOP_ALBUM -> TopAlbum(s, pad)
            Kind.MONTHS -> Months(s, pad)
            Kind.FIRST_SONG -> FirstSong(s, pad)
            Kind.HABITS -> HabitsSlide(s.habits, pad)
            Kind.DISCOVERY -> Discovery(s, pad)
            Kind.PRISM_YEAR -> PrismYearSlide(s, pad)
            Kind.YEAR_COMPARE -> YearCompare(s, pad)
            Kind.PERSONALITY -> PersonalitySlide(s.personality, pad)
            Kind.SUMMARY -> Summary(s, pad, onReplay, onPlayAll)
        }
    }
}

@Composable
private fun Intro(s: ReplayStory, modifier: Modifier) {
    val spin by rememberInfiniteTransition(label = "fan").animateFloat(-4f, 4f, infiniteRepeatable(tween(3_000), RepeatMode.Reverse), label = "r")
    Column(modifier, verticalArrangement = Arrangement.Center) {
        // A fanned stack of your top three covers.
        Box(Modifier.fillMaxWidth().height(250.dp).reveal(100), contentAlignment = Alignment.Center) {
            s.songs.take(3).reversed().forEachIndexed { i, song ->
                val angle = (i - 1) * 12f + spin * (if (i == 2) 1f else 0.4f)
                AsyncImage(
                    hiRes(song.thumbnail, 544), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(190.dp).offset(x = ((i - 1) * 46).dp).rotate(angle)
                        .shadow(24.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)),
                )
            }
        }
        Spacer(Modifier.height(36.dp))
        Text(s.brand, style = kicker(), modifier = Modifier.reveal(350))
        FitText(s.title.uppercase(), display(if (s.title.length > 6) 76.sp else 120.sp), Modifier.reveal(550))
        Text(
            when (s.period.kind) {
                Period.Kind.YEAR -> tr(R.string.your_year_in_music_2)
                Period.Kind.MONTH, Period.Kind.MONTH_OF -> tr(R.string.your_month_in_music)
                Period.Kind.DAYS30 -> tr(R.string.your_last_30_days_in_music)
                Period.Kind.TODAY -> tr(R.string.your_day_in_music)
                Period.Kind.WEEK -> tr(R.string.your_week_in_music)
                Period.Kind.DAYS -> tr(R.string.those_days_in_music)
                Period.Kind.ALL -> tr(R.string.everything_you_ve_played_in_one)
            },
            style = body.copy(fontSize = 24.sp), modifier = Modifier.reveal(800).padding(top = 8.dp),
        )
        Text(tr(R.string.tap_to_go_on_hold_to), style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f)), modifier = Modifier.reveal(1_400).padding(top = 24.dp))
    }
}

@Composable
private fun Minutes(s: ReplayStory, modifier: Modifier) {
    val total = listenTotal(s.totalMs)
    val count = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(400); count.animateTo(total.value.toFloat(), tween(2_200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.you_listened_for), style = kicker(), modifier = Modifier.reveal(100))
        val text = total.text(count.value.toLong())
        val final = total.text(total.value)
        val longest = final.length
        FitText(text, display(if (longest > 8) 72.sp else if (longest > 6) 96.sp else 130.sp), Modifier.reveal(200), widest = final)
        FitText(total.unit.ifEmpty { tr(R.string.of_music) }.uppercase(), display(46.sp, ArtistTypography.extended), Modifier.reveal(400), oneLine = false)
        Spacer(Modifier.height(28.dp))
        val hours = s.totalMs / 3_600_000.0
        Text(
            if (hours >= 24) tr(R.string.that_s_1f_days_of_music).format(hours / 24) else tr(R.string.that_s_1f_hours_of_music).format(hours),
            style = body, modifier = Modifier.reveal(2_400),
        )
        if (s.previousMs != null && s.previousLabel != null) {
            val change = ((s.totalMs - s.previousMs) * 100.0 / s.previousMs).toInt()
            Row(Modifier.reveal(3_000).padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (change >= 0) PrismIcons.TrendingUp else PrismIcons.TrendingDown, null, tint = Color.White, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (change >= 0) tr(R.string.more_than, change, s.previousLabel) else tr(R.string.less_than, -change, s.previousLabel), style = body)
            }
        }
        Row(Modifier.reveal(3_600).padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Stat(fmt(s.songCount.toLong()), trp(R.plurals.songs_word, s.songCount))
            Stat(fmt(s.artistCount.toLong()), trp(R.plurals.artists_word, s.artistCount))
            Stat(fmt(s.activeDays.toLong()), trp(R.plurals.days_word, s.activeDays))
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = display(40.sp))
        Text(label, style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)))
    }
}

@Composable
private fun Genres(s: ReplayStory, modifier: Modifier) {
    val total = s.genres.sumOf { it.second }.coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_top_genre), style = kicker(), modifier = Modifier.reveal(100))
        FitText(com.prism.music.data.meta.Genres.shown(s.genres.first().first).uppercase(), display(72.sp), Modifier.reveal(300), oneLine = false)
        Spacer(Modifier.height(30.dp))
        s.genres.forEachIndexed { i, (g, ms) ->
            val share = ms.toFloat() / total
            val w = remember { Animatable(0f) }
            LaunchedEffect(Unit) { delay(700L + i * 160); w.animateTo(share, tween(900)) }
            Column(Modifier.padding(vertical = 6.dp).reveal(600 + i * 160)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(g, style = body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Text("${(share * 100).toInt()}%", style = body.copy(fontSize = 17.sp))
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(12.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(w.value).clip(CircleShape).background(Color.White.copy(alpha = if (i == 0) 1f else 0.7f)))
                }
            }
        }
    }
}

@Composable
private fun TopArtist(s: ReplayStory, modifier: Modifier) {
    val a = s.artists.first()
    val thumbs by LocalContainer.current.library.artistThumbs.collectAsState()
    val c = LocalContainer.current
    val image = s.topArtistImage ?: c.library.artistPhoto(thumbs, a.artistId, a.artistName)
    val zoom by rememberInfiniteTransition(label = "z").animateFloat(1.05f, 1.18f, infiniteRepeatable(tween(9_000), RepeatMode.Reverse), label = "z")
    Box(Modifier.fillMaxSize()) {
        AsyncImage(hiRes(image, 2880), null, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }, contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.35f to Color.Transparent, 0.6f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.9f))))
        Column(modifier, verticalArrangement = Arrangement.Bottom) {
            Text(tr(R.string.your_top_artist_2), style = kicker(), modifier = Modifier.reveal(300))
            val type = s.topArtistType
            // In your font (#287) the name is set in it too, at the artist's size, not in their signature's face.
            val own = LocalStoryFont.current
            Text(
                if (type.caps && own == null) a.artistName.uppercase() else a.artistName,
                style = TextStyle(
                    fontFamily = own ?: type.family, fontWeight = if (own != null) FontWeight.Bold else type.weight, fontSize = (type.size * 1.25f).coerceAtMost(72f).sp,
                    lineHeight = (type.size * 1.25f * type.lineHeight).coerceAtMost(72f).sp, letterSpacing = type.tracking.em, color = Color.White,
                    fontStyle = if (type.italic && own == null) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                ),
                maxLines = 3, modifier = Modifier.reveal(600),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.reveal(1_200), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                listenTotal(a.ms).let { t -> Stat(t.text(t.value), t.unit.ifEmpty { tr(R.string.listened).lowercase() }) }
                Stat(fmt(a.plays.toLong()), trp(R.plurals.plays_word, a.plays))
                val theirs = s.songs.count { it.mainArtist == a.artistName }
                Stat(fmt(theirs.toLong()), trp(R.plurals.songs_word, theirs))
            }
            val share = (a.ms * 100.0 / s.totalMs.coerceAtLeast(1)).toInt()
            Text(tr(R.string.of_everything_you_played_was_them, share), style = body, modifier = Modifier.reveal(1_800).padding(top = 16.dp))
        }
    }
}

@Composable
private fun TopArtists(s: ReplayStory, modifier: Modifier) {
    val lib = LocalContainer.current.library
    val thumbs by lib.artistThumbs.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_top_artists), style = kicker(), modifier = Modifier.reveal(100))
        Spacer(Modifier.height(20.dp))
        s.artists.take(5).forEachIndexed { i, a ->
            Row(Modifier.padding(vertical = 8.dp).reveal(300 + i * 220), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = display(52.sp), modifier = Modifier.width(44.dp))
                AsyncImage(
                    hiRes(lib.artistPhoto(thumbs, a.artistId, a.artistName), 300), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(if (i == 0) 76.dp else 62.dp).clip(CircleShape).border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.artistName, style = body.copy(fontSize = if (i == 0) 24.sp else 20.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listenTime(a.ms), style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)))
                }
            }
        }
    }
}

@Composable
private fun TopSong(s: ReplayStory, modifier: Modifier) {
    val song = s.songs.first()
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(1f, 1.035f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "p")
    Box(Modifier.fillMaxSize()) {
        AsyncImage(hiRes(song.thumbnail, 300), null, Modifier.fillMaxSize().blur(60.dp).graphicsLayer { alpha = 0.55f }, contentScale = ContentScale.Crop)
        // Keeps white text readable over light covers.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr(R.string.your_1_song_2), style = kicker(), modifier = Modifier.reveal(100))
            Spacer(Modifier.height(22.dp))
            AsyncImage(
                hiRes(song.thumbnail, 900), null, contentScale = ContentScale.Crop,
                modifier = Modifier.reveal(250).size(270.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .shadow(40.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(26.dp))
            Text(song.title.uppercase(), style = display(48.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.reveal(700))
            Text(song.artistName, style = body.copy(fontSize = 20.sp), textAlign = TextAlign.Center, modifier = Modifier.reveal(900).padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                trp(R.plurals.played_it_n_times, song.plays, song.plays, listenTime(song.ms)),
                style = body, textAlign = TextAlign.Center, modifier = Modifier.reveal(1_500),
            )
            // The line it's playing from (its best part).
            s.topLyric?.let { line ->
                Text(
                    "“$line”", style = body.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.reveal(2_400).padding(top = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun TopSongs(s: ReplayStory, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_top_songs), style = kicker(), modifier = Modifier.reveal(100))
        Spacer(Modifier.height(20.dp))
        s.songs.take(5).forEachIndexed { i, song ->
            Row(Modifier.padding(vertical = 7.dp).reveal(300 + i * 220), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = display(52.sp), modifier = Modifier.width(44.dp))
                AsyncImage(hiRes(song.thumbnail, 226), null, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = body.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${song.artistName} · ${listenTime(song.ms)}", style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HabitsSlide(h: Habits, modifier: Modifier) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(300); grow.animateTo(1f, tween(1_400)) }
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr(R.string.when_you_listen), style = kicker(), modifier = Modifier.reveal(100).fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        // A 24-hour clock: each spoke is an hour, its length how much you listened then.
        Box(Modifier.size(280.dp).reveal(200), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val max = h.byHour.max().coerceAtLeast(1)
                val inner = size.minDimension * 0.24f
                val outer = size.minDimension * 0.5f
                for (hr in 0 until 24) {
                    val ang = (hr / 24f) * 2 * PI.toFloat() - PI.toFloat() / 2
                    val len = (outer - inner) * (0.06f + 0.94f * h.byHour[hr] / max.toFloat()) * grow.value
                    val dir = Offset(cos(ang), sin(ang))
                    drawLine(
                        if (hr == h.peakHour) Color.White else Color.White.copy(alpha = 0.45f),
                        center + dir * inner, center + dir * (inner + len), strokeWidth = 11.dp.toPx() * 0.9f, cap = StrokeCap.Round,
                    )
                }
                drawCircle(Color.White.copy(alpha = 0.25f), inner - 8.dp.toPx(), style = Stroke(1.5f.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr(R.string.prime_time), style = kicker(10.sp))
                Text(ReplayStats.hourLabel(h.peakHour), style = display(34.sp))
            }
        }
        Spacer(Modifier.height(26.dp))
        val fmtDay = com.prism.music.localDates("MMMd")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(tr(R.string.favorite_day), ReplayStats.weekdays[h.topWeekday], 1_200)
            if (h.longestStreak > 1) Fact(tr(R.string.longest_streak), trp(R.plurals.n_days_in_a_row, h.longestStreak), 1_450)
            h.biggestDay?.let { Fact(tr(R.string.biggest_day), "${it.format(fmtDay)} · ${listenTime(h.biggestDayMs)}", 1_700) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, delayMs: Int) {
    Row(
        Modifier.fillMaxWidth().reveal(delayMs).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = kicker(11.sp).copy(color = Color.White.copy(alpha = 0.75f)), modifier = Modifier.weight(1f))
        Text(value, style = body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun Discovery(s: ReplayStory, modifier: Modifier) {
    val found = s.newArtists.orEmpty()
    val lib = LocalContainer.current.library
    val thumbs by lib.artistThumbs.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.new_to_you), style = kicker(), modifier = Modifier.reveal(100))
        FitText(fmt(found.size.toLong()), display(140.sp), Modifier.reveal(250))
        FitText(trp(R.plurals.new_artists_title, found.size), display(44.sp, ArtistTypography.extended), Modifier.reveal(450), oneLine = false)
        Text(tr(R.string.found_their_way_into_your_rotation), style = body, modifier = Modifier.reveal(700).padding(top = 8.dp))
        Spacer(Modifier.height(26.dp))
        Row(Modifier.reveal(1_000)) {
            found.take(6).forEachIndexed { i, a ->
                AsyncImage(
                    hiRes(lib.artistPhoto(thumbs, a.artistId, a.artistName), 226), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.offset(x = (-14 * i).dp).size(62.dp).clip(CircleShape).border(3.dp, Color(0xFF181818), CircleShape),
                )
            }
        }
        found.firstOrNull()?.let { a ->
            Text(
                tr(R.string.your_favorite_find_with, a.artistName, listenTime(a.ms)),
                style = body, modifier = Modifier.reveal(1_500).padding(top = 22.dp),
            )
        }
    }
}

@Composable
private fun PersonalitySlide(p: Personality, modifier: Modifier) {
    val turn by rememberInfiniteTransition(label = "prism").animateFloat(0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "a")
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_listening_personality), style = kicker(), modifier = Modifier.reveal(100))
        Spacer(Modifier.height(24.dp))
        // A slowly turning prism, Prism's emblem.
        Canvas(Modifier.size(170.dp).reveal(300).rotate(turn)) {
            val w = size.width
            val tri = Path().apply {
                moveTo(w / 2, w * 0.08f); lineTo(w * 0.94f, w * 0.84f); lineTo(w * 0.06f, w * 0.84f); close()
            }
            drawPath(tri, Brush.sweepGradient(listOf(Color(0xFFA6A6A6), Color(0xFFD4D4D4), Color(0xFFC2C2C2), Color(0xFFA4A4A4), Color(0xFFA6A6A6))))
            drawPath(tri, Color.White.copy(alpha = 0.6f), style = Stroke(3.dp.toPx()))
        }
        Spacer(Modifier.height(28.dp))
        FitText(p.name.uppercase(), display(64.sp), Modifier.reveal(700), oneLine = false)
        Text(p.line, style = body, modifier = Modifier.reveal(1_200).padding(top = 14.dp))
    }
}

/** The year's sound, season by season (by months, as hemispheres disagree on the names). */
@Composable
private fun Seasons(s: ReplayStory, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_year_season_by_season), style = kicker(), modifier = Modifier.reveal(100))
        Spacer(Modifier.height(22.dp))
        s.seasons.forEachIndexed { i, q ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).reveal(300 + i * 260)
                    .clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(q.label.uppercase(), style = kicker(11.sp).copy(color = Color.White.copy(alpha = 0.75f)))
                Text(q.genre?.let { com.prism.music.data.meta.Genres.shown(it) } ?: tr(R.string.a_bit_of_everything), style = display(34.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                q.topSong?.let { Text(tr(R.string.on_repeat, it), style = body.copy(fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun TopAlbum(s: ReplayStory, modifier: Modifier) {
    val a = s.topAlbum ?: return
    val turn by rememberInfiniteTransition(label = "disc").animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "d")
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr(R.string.your_top_album), style = kicker(), modifier = Modifier.reveal(100).fillMaxWidth())
        Spacer(Modifier.height(26.dp))
        // The cover, with its record sliding out from behind it.
        Box(Modifier.reveal(250).size(width = 300.dp, height = 240.dp)) {
            Box(Modifier.align(Alignment.CenterEnd).size(220.dp).rotate(turn).clip(CircleShape).background(Color(0xFF111111))) {
                Box(Modifier.align(Alignment.Center).size(80.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)))
                AsyncImage(hiRes(a.thumbnail, 300), null, Modifier.align(Alignment.Center).size(70.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            }
            AsyncImage(
                hiRes(a.thumbnail, 900), null, contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.CenterStart).size(230.dp).shadow(30.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)),
            )
        }
        Spacer(Modifier.height(26.dp))
        Text(a.albumTitle.uppercase(), style = display(44.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.reveal(700))
        Text(a.artistName, style = body.copy(fontSize = 20.sp), modifier = Modifier.reveal(900).padding(top = 6.dp))
        Text(
            tr(R.string.of_its_songs_plays, trp(R.plurals.n_of_its_songs, a.songs), trp(R.plurals.n_plays, a.plays), listenTime(a.ms)),
            style = body, textAlign = TextAlign.Center, modifier = Modifier.reveal(1_400).padding(top = 16.dp),
        )
    }
}

/** Month by month, the busiest one picked out. */
@Composable
private fun Months(s: ReplayStory, modifier: Modifier) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(300); grow.animateTo(1f, tween(1_400)) }
    val byMonth = LongArray(12)
    s.months.forEach { m -> m.month.substringAfter('-').toIntOrNull()?.let { if (it in 1..12) byMonth[it - 1] += m.ms } }
    val peak = byMonth.indices.maxByOrNull { byMonth[it] } ?: 0
    val names = java.text.DateFormatSymbols.getInstance().months
    val letters = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_biggest_month), style = kicker(), modifier = Modifier.reveal(100))
        FitText(names.getOrElse(peak) { "" }.uppercase(), display(72.sp), Modifier.reveal(300))
        Text(tr(R.string.that_month, listenTime(byMonth[peak])), style = body, modifier = Modifier.reveal(600).padding(top = 6.dp))
        Spacer(Modifier.height(30.dp))
        Canvas(Modifier.fillMaxWidth().height(200.dp).reveal(400)) {
            val max = byMonth.max().coerceAtLeast(1)
            val gap = 6.dp.toPx()
            val w = (size.width - gap * 11) / 12
            for (i in 0 until 12) {
                val h = (size.height * byMonth[i] / max.toFloat() * grow.value).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    if (i == peak) Color.White else Color.White.copy(alpha = 0.38f),
                    topLeft = Offset(i * (w + gap), size.height - h), size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            letters.forEach { Text(it, Modifier.weight(1f), style = body.copy(fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f)), textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun FirstSong(s: ReplayStory, modifier: Modifier) {
    val f = s.firstSong ?: return
    val at = java.time.Instant.ofEpochMilli(f.timestamp).atZone(java.time.ZoneId.systemDefault())
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr(R.string.where_it_started), style = kicker(), modifier = Modifier.reveal(100).fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        AsyncImage(
            hiRes(f.thumbnail, 900), null, contentScale = ContentScale.Crop,
            modifier = Modifier.reveal(250).size(240.dp).shadow(30.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.height(24.dp))
        Text(tr(R.string.your_first_song_of, s.title), style = body, modifier = Modifier.reveal(600))
        Text(f.title.uppercase(), style = display(44.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.reveal(800).padding(top = 6.dp))
        Text(f.artistName, style = body.copy(fontSize = 20.sp), modifier = Modifier.reveal(1_000).padding(top = 4.dp))
        Text(
            tr(R.string.date_at_time, at.format(com.prism.music.localDates("MMMMd")), at.format(com.prism.music.localDates("jmm"))),
            style = body.copy(fontSize = 15.sp, color = Color.White.copy(alpha = 0.75f)), modifier = Modifier.reveal(1_400).padding(top = 12.dp),
        )
    }
}

/** What Prism's own features did this year. */
@Composable
private fun PrismYearSlide(s: ReplayStory, modifier: Modifier) {
    val p = s.prism
    val rows = listOfNotNull(
        p.sung.takeIf { it > 0 }?.let { tr(R.string.songs_you_sang) to fmt(it.toLong()) },
        p.djSongs.takeIf { it > 0 }?.let { tr(R.string.songs_in_dj_sets) to fmt(it.toLong()) },
        p.smartPicks.takeIf { it > 0 }?.let { tr(R.string.smart_shuffle_finds_played) to fmt(it.toLong()) },
        p.recognized.takeIf { it > 0 }?.let { tr(R.string.songs_recognized) to fmt(it.toLong()) },
        p.blends.takeIf { it > 0 }?.let { trp(R.plurals.blends_made, p.blends) to fmt(it.toLong()) },
        p.radarFinds.takeIf { it > 0 }?.let { tr(R.string.release_radar_finds) to fmt(it.toLong()) },
    )
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(tr(R.string.your_year_in_prism), style = kicker(), modifier = Modifier.reveal(100))
        FitText(tr(R.string.more_than_listening), display(56.sp), Modifier.reveal(300).padding(top = 6.dp), oneLine = false)
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEachIndexed { i, (label, value) -> Fact(label, value, 700 + i * 220) }
        }
    }
}

/** This year against last. */
@Composable
private fun YearCompare(s: ReplayStory, modifier: Modifier) {
    val prev = s.previousMs ?: return
    val year = s.title.toIntOrNull()
    val change = ((s.totalMs - prev) * 100.0 / prev).toInt()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text("${year?.minus(1) ?: "LAST YEAR"} → ${s.title}", style = kicker(), modifier = Modifier.reveal(100))
        FitText(if (change >= 0) "+$change%" else "$change%", display(130.sp), Modifier.reveal(300))
        FitText(if (change >= 0) tr(R.string.more_music) else tr(R.string.less_music), display(44.sp, ArtistTypography.extended), Modifier.reveal(500), oneLine = false)
        Spacer(Modifier.height(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(tr(R.string.listening), "${listenTime(prev)} → ${listenTime(s.totalMs)}", 900)
            s.previousSongCount?.let { Fact(tr(R.string.different_songs), "${fmt(it.toLong())} → ${fmt(s.songCount.toLong())}", 1_150) }
            val now = s.artists.firstOrNull()?.artistName
            if (s.previousTopArtist != null && now != null) {
                Fact(tr(R.string.top_artist), if (s.previousTopArtist == now) tr(R.string.again_2, now) else "${s.previousTopArtist} → $now", 1_400)
            }
        }
    }
}

@Composable
private fun Summary(s: ReplayStory, modifier: Modifier, onReplay: () -> Unit, onPlayAll: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val card = rememberGraphicsLayer()
    val thumbs by c.library.artistThumbs.collectAsState()
    Column(modifier.padding(top = 0.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        // The card that gets shared, drawn into a layer so it can be saved as an image.
        val lead = s.artists.firstOrNull()
        SummaryCard(
            s, hiRes(s.topArtistImage ?: lead?.let { c.library.artistPhoto(thumbs, it.artistId, it.artistName) } ?: s.songs.firstOrNull()?.thumbnail, 600),
            Modifier.reveal(100).fillMaxWidth().recordInto(card),
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.reveal(500), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(tr(R.string.share), PrismIcons.Share, primary = true) {
                scope.launch {
                    val name = if (s.rewind) tr(R.string.rewind_3) else tr(R.string.replay_2)
                    if (!shareLayer(context, card, "prism-${name.lowercase()}.png", tr(R.string.my_prism, name, s.title), tr(R.string.share_your, name))) {
                        Toast.makeText(context, tr(R.string.couldn_t_share_your, name), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Pill(tr(R.string.widget_play), PrismIcons.Play, onClick = onPlayAll)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.reveal(700), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c.settings.current.isLoggedIn) Pill(if (s.rewind) tr(R.string.save_top_100) else tr(R.string.save_playlist), PrismIcons.PlaylistAdd) {
                scope.launch {
                    val name = if (s.rewind) tr(R.string.top_100_of, s.title) else tr(R.string.replay, s.title)
                    val id = runCatching { c.ytm.createPlaylist(name, s.songs.take(100).map { it.songId }, if (s.rewind) tr(R.string.made_with_prism_rewind) else tr(R.string.made_with_prism_replay)) }.getOrNull()
                    Toast.makeText(context, if (id != null) tr(R.string.saved_to_youtube_music, name) else tr(R.string.couldn_t_create_playlist), Toast.LENGTH_SHORT).show()
                    if (id != null) c.library.created(id, name, minOf(s.songs.size, 100))
                }
            }
            Pill(tr(R.string.watch_again), PrismIcons.Refresh, onClick = onReplay)
        }
    }
}

/** The summary slide's card: what gets shared. */
@Composable
internal fun SummaryCard(s: ReplayStory, image: Any?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF181818), Color(0xFF575757), Color(0xFF6B6B6B))))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(image, null, Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(s.brandName, style = kicker(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // What else it counts can run long ("Includes Spotify, Last.fm"): smaller, on its own lines, clear of the title.
                s.includes?.let {
                    Text(
                        it.uppercase(), style = kicker(9.sp).copy(lineHeight = 12.sp, color = Color.White.copy(alpha = 0.8f)),
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Shrinks to fit beside the cover (Reddit: "TODA").
                FitText(s.title.uppercase(), display(52.sp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row {
            SummaryList(tr(R.string.top_artists), s.artists.take(5).map { it.artistName }, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            SummaryList(tr(R.string.top_songs_3), s.songs.take(5).map { it.title }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row {
            Column(Modifier.weight(1f)) {
                val total = listenTotal(s.totalMs)
                Text(tr(R.string.listened_2, total.unit).trim().uppercase(), style = kicker(10.sp).copy(color = Color.White.copy(alpha = 0.75f)))
                FitText(total.text(total.value), display(36.sp))
            }
            s.genres.firstOrNull()?.let { (g, _) ->
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(tr(R.string.top_genre), style = kicker(10.sp).copy(color = Color.White.copy(alpha = 0.75f)))
                    Text(g, style = body.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(s.personality.name, style = body.copy(fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f)))
    }
}

@Composable
private fun SummaryList(title: String, items: List<String>, modifier: Modifier) {
    Column(modifier) {
        Text(title.uppercase(), style = kicker(10.sp).copy(color = Color.White.copy(alpha = 0.75f)))
        Spacer(Modifier.height(4.dp))
        items.forEachIndexed { i, t ->
            Text("${i + 1}  $t", style = body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Pill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (primary) Color.White else Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (primary) Color.Black else Color.White
        Icon(icon, null, Modifier.size(20.dp), tint = fg)
        Spacer(Modifier.width(8.dp))
        Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg))
    }
}
