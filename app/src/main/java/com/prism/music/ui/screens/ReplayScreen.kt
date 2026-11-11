package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.data.db.mergeUploads
import com.prism.music.ui.theme.lit
import androidx.compose.foundation.border
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismFilledTonalButton
import com.prism.music.ui.theme.PrismIcons
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.MonthTotal
import com.prism.music.data.db.SongPlays
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * A stretch of Replay: today, this week, this month, the last 30 days, a calendar year (its Rewind), everything, or
 * (1.7.8, #149) any month ([year] + [month], 0–11) or any days ([fromDay]..[toDay], days since 1970 on the phone's clock).
 */
internal data class Period(val kind: Kind, val year: Int = 0, val month: Int = 0, val fromDay: Long = 0, val toDay: Long = 0) {
    enum class Kind { TODAY, WEEK, MONTH, DAYS30, YEAR, ALL, MONTH_OF, DAYS }

    val isYear: Boolean get() = kind == Kind.YEAR
    val label: String get() = when (kind) {
        Kind.TODAY -> tr(R.string.today)
        Kind.WEEK -> tr(R.string.this_week)
        Kind.MONTH -> tr(R.string.this_month)
        Kind.DAYS30 -> tr(R.string.last_30_days)
        Kind.YEAR -> "$year"
        Kind.ALL -> tr(R.string.all_time)
        Kind.MONTH_OF -> java.time.YearMonth.of(year, month + 1).let { ym ->
            ym.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " ${ym.year}"
        }
        Kind.DAYS -> {
            val fmt = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            val a = java.time.LocalDate.ofEpochDay(fromDay)
            val b = java.time.LocalDate.ofEpochDay(toDay)
            if (a == b) a.format(fmt) else "${a.format(fmt)} – ${b.format(fmt)}"
        }
    }
    /** For saving the pick ("y2025", "MONTH", "m2026-9", "d20380-20390"…). */
    val key: String get() = when (kind) {
        Kind.YEAR -> "y$year"
        Kind.MONTH_OF -> "m$year-$month"
        Kind.DAYS -> "d$fromDay-$toDay"
        else -> kind.name
    }

    companion object {
        val TODAY = Period(Kind.TODAY)
        val WEEK = Period(Kind.WEEK)
        val MONTH = Period(Kind.MONTH)
        val DAYS30 = Period(Kind.DAYS30)
        val ALL = Period(Kind.ALL)
        fun year(y: Int) = Period(Kind.YEAR, y)
        fun monthOf(y: Int, m: Int) = Period(Kind.MONTH_OF, y, m)
        fun days(from: Long, to: Long) = Period(Kind.DAYS, fromDay = minOf(from, to), toDay = maxOf(from, to))
        fun thisYear() = year(Calendar.getInstance().get(Calendar.YEAR))
        fun of(key: String): Period {
            if (key.startsWith("y")) key.drop(1).toIntOrNull()?.let { return year(it) }
            if (key.startsWith("m")) key.drop(1).split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { (y, m) -> return monthOf(y, m) }
            if (key.startsWith("d")) key.drop(1).split("-").mapNotNull { it.toLongOrNull() }.takeIf { it.size == 2 }?.let { (a, b) -> return days(a, b) }
            return Kind.entries.firstOrNull { it.name == key && it != Kind.YEAR && it != Kind.MONTH_OF && it != Kind.DAYS }?.let { Period(it) } ?: thisYear()
        }
    }
}

private data class ReplayData(
    val totalMs: Long,
    val plays: Int,
    val activeDays: Int,
    /** Every different song played in the period (the lists below are capped). */
    val songCount: Int,
    val songs: List<SongPlays>,
    val artists: List<ArtistPlays>,
    val months: List<MonthTotal>,
    val genres: List<Pair<String, Long>>,
    /** Up to 250 top songs: what Play and Shuffle play. Not shown. */
    val top250: List<SongPlays> = songs,
    /** #286/#287: the graph by hours, days or weeks for a shorter stretch (null: by month, [months]). */
    val timeline: ReplayTimeline? = null,
)

internal fun SongPlays.toSong() = Song(
    id = songId, title = title,
    artists = artistName.split(", ").mapIndexed { i, n -> ArtistRef(n, if (i == 0) artistId else null) },
    album = albumTitle?.let { AlbumRef(it) }, durationSec = durationSec, thumbnail = thumbnail,
)

internal fun range(p: Period): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val zone = java.time.ZoneId.systemDefault()
    // Midnight of a day on the phone's clock (whatever daylight saving does).
    fun startOf(d: java.time.LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    val today = java.time.LocalDate.now(zone)
    return when (p.kind) {
        Period.Kind.TODAY -> startOf(today) to now
        // The week as the phone's region starts it (Monday or Sunday).
        Period.Kind.WEEK -> startOf(today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek))) to now
        Period.Kind.MONTH -> startOf(today.withDayOfMonth(1)) to now
        Period.Kind.DAYS30 -> now - 30L * 86_400_000 to now
        // Midnight to midnight on the phone's clock (whatever daylight saving does), up to now for this year.
        Period.Kind.YEAR -> com.prism.music.data.RewindCalendar.bounds(p.year).let { (a, b) -> a to minOf(b, now) }
        Period.Kind.ALL -> 0L to now
        Period.Kind.MONTH_OF -> java.time.YearMonth.of(p.year, p.month + 1).let { ym -> startOf(ym.atDay(1)) to minOf(startOf(ym.plusMonths(1).atDay(1)) - 1, now) }
        Period.Kind.DAYS -> startOf(java.time.LocalDate.ofEpochDay(p.fromDay)) to minOf(startOf(java.time.LocalDate.ofEpochDay(p.toDay + 1)) - 1, now)
    }
}

/** The "Other dates…" chip (it opens [ReplayDatesSheet]; never a period itself). */
private val PICK_DATES = Period(Period.Kind.DAYS, fromDay = -1, toDay = -1)

/** Replay for any month you listened in, or any days (#149): a list of the months, and a calendar for days. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReplayDatesSheet(onPick: (Period) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val months by produceState(emptyList<Period>()) {
        value = runCatching { c.imports.replay().monthly(0, System.currentTimeMillis()) }.getOrDefault(emptyList())
            .mapNotNull { m -> m.month.split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { (y, mo) -> Period.monthOf(y, mo - 1) } }
            .reversed()
    }
    var calendar by remember { mutableStateOf(false) }
    if (calendar) {
        val now = System.currentTimeMillis()
        val state = androidx.compose.material3.rememberDateRangePickerState(
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= now
            },
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { calendar = false },
            confirmButton = {
                com.prism.music.ui.components.PrismTextButton(
                    onClick = {
                        // The picker's days are UTC midnights: their day numbers are the calendar days picked.
                        val a = state.selectedStartDateMillis ?: return@PrismTextButton
                        val b = state.selectedEndDateMillis ?: a
                        calendar = false
                        onPick(Period.days(a / 86_400_000, b / 86_400_000))
                    },
                    enabled = state.selectedStartDateMillis != null,
                ) { Text(tr(R.string.show)) }
            },
            dismissButton = { com.prism.music.ui.components.PrismTextButton(onClick = { calendar = false }) { Text(tr(R.string.cancel)) } },
        ) {
            androidx.compose.material3.DateRangePicker(state, Modifier.weight(1f), title = { Text(tr(R.string.your_replay_for), Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
        return
    }
    com.prism.music.ui.components.PrismSheet(onDismiss) { close ->
        Column(Modifier.padding(bottom = 20.dp)) {
            Text(tr(R.string.other_dates), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            com.prism.music.ui.components.SheetAction(PrismIcons.Calendar, tr(R.string.pick_days_on_a_calendar)) { calendar = true }
            if (months.isNotEmpty()) Text(
                tr(R.string.a_month), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(months.size) { i ->
                    val m = months[i]
                    Text(
                        m.label, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { close { onPick(m) } }.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** Replay / Rewind: a private, on-device recap of your listening. */
@Composable
fun ReplayScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var periodKey by rememberSaveable { mutableStateOf(Period.thisYear().key) }
    val period = remember(periodKey) { Period.of(periodKey) }
    // Every year with listening (Rewind), newest first; this year is always there.
    val years by produceState(listOf(Period.thisYear().year)) {
        value = runCatching { c.rewind.years() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: value
    }
    // The imported histories Replay counts besides Prism's own plays (1.8.1): the page says so, and reads again on a change.
    val counted by c.imports.counted.collectAsState()
    val historyPaused = com.prism.music.data.HistoryPause.paused(LocalAppSettings.current.behavior)
    val data by produceState<ReplayData?>(null, period, counted) {
        value = null
        val (from, to) = range(period)
        val dao = c.imports.replay()
        // The top 250 are kept for Play / Shuffle; the screen itself shows the top 100 at most.
        val top = dao.topSongs(from, to, 300).mergeUploads().take(250)
        val songs = top.take(100)
        val genreMap = c.meta.cachedGenres(songs.map { it.toSong() })
        val genres = songs.filter { genreMap[it.songId] != null }.groupBy { genreMap.getValue(it.songId) }
            .mapValues { (_, v) -> v.sumOf { it.ms } }
            .toList().sortedByDescending { it.second }.take(6)
        value = ReplayData(
            totalMs = dao.totalMs(from, to), plays = dao.count(from, to), activeDays = dao.activeDays(from, to),
            songCount = dao.songCount(from, to), songs = songs, artists = dao.topArtists(from, to, 12), months = dao.monthly(from, to), genres = genres,
            top250 = top, timeline = runCatching { loadTimeline(dao, period, from, to) }.getOrNull(),
        )
        // Fill in genres for the top tracks in the background so the next visit is richer.
        songs.take(40).filter { it.songId !in genreMap }.forEach { runCatching { c.meta.genreFor(it.toSong()) } }
    }
    var story by remember { mutableStateOf<ReplayStory?>(null) }
    var storyLoading by remember { mutableStateOf(false) }
    val open: (Period) -> Unit = { p ->
        if (!storyLoading) scope.launch {
            storyLoading = true
            story = runCatching { loadReplayStory(c, p) }.onFailure { android.util.Log.w("PrismRewind", "story failed", it) }.getOrNull()
            if (story == null) Toast.makeText(context, if (p.isYear) tr(R.string.couldn_t_build_your_rewind) else tr(R.string.couldn_t_build_your_replay), Toast.LENGTH_SHORT).show()
            storyLoading = false
        }
    }
    val openStory: () -> Unit = { open(period) }
    // Home's Rewind card or the 1 December notification: straight to that year's story.
    val request by c.rewindRequest.collectAsState()
    LaunchedEffect(request) {
        val y = request ?: return@LaunchedEffect
        c.rewindRequest.value = null
        periodKey = Period.year(y).key
        if (c.rewind.isOpen(y)) open(Period.year(y))
    }
    story?.let { ReplayStoryDialog(it) { story = null } }
    var pickingDates by remember { mutableStateOf(false) }
    if (pickingDates) ReplayDatesSheet(onPick = { periodKey = it.key; pickingDates = false }) { pickingDates = false }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 24 } }
    val edge by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 1f else 0f, tween(220), label = "edge")
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
        item {
            com.prism.music.ui.components.ScreenHeader(
                tr(R.string.replay_2),
                subtitle = replayIncludes(counted) ?: tr(R.string.your_listening_recapped_on_device),
            )
        }
        // #296: while history is paused, Replay says so (and how to turn it back on).
        if (historyPaused) item { HistoryPausedNote() }
        item {
            // Any dates (#149): today, this week, any month, or the days you pick, beside the usual ones.
            val presets = listOf(Period.TODAY, Period.WEEK, Period.MONTH, Period.DAYS30) + years.map { Period.year(it) } + Period.ALL
            val chips = presets + (if (period !in presets) listOf(period) else emptyList()) + PICK_DATES
            com.prism.music.ui.components.ChipRow(
                chips, { it == period }, { if (it == PICK_DATES) tr(R.string.other_dates_2) else it.label },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) { if (it == PICK_DATES) pickingDates = true else periodKey = it.key }
        }
        val d = data
        if (d == null) { item { LoadingState() }; return@LazyColumn }
        if (d.plays == 0) {
            item { EmptyState(PrismIcons.ReplayFilled, tr(R.string.nothing_to_replay_yet), tr(R.string.listen_for_a_while_songs_you)) }
            return@LazyColumn
        }
        item { HeroCard(d, period, period.isYear && !c.rewind.isOpen(period.year), storyLoading, openStory) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val songs = remember(d) { d.top250.map { it.toSong() } }
                val replaySource = QueueSource(QueueKind.REPLAY, tr(R.string.replay_3, period.label))
                PrismButton(onClick = { c.player.playQueue(songs, 0, replaySource) }, Modifier.weight(1f).height(48.dp)) {
                    Icon(PrismIcons.Play, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.widget_play))
                }
                PrismFilledTonalButton(onClick = { c.player.playQueue(songs, 0, replaySource, shuffle = true) }, Modifier.weight(1f).height(48.dp)) {
                    Icon(PrismIcons.Shuffle, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.shuffle))
                }
                if (c.settings.current.isLoggedIn) androidx.compose.material3.FilledTonalIconButton(
                    onClick = {
                        scope.launch {
                            val name = if (period.isYear) tr(R.string.top_songs_of, period.year) else tr(R.string.replay_3, period.label)
                            val id = runCatching { c.ytm.createPlaylist(name, songs.take(100).map { it.id }, tr(R.string.made_with_prism_replay)) }.getOrNull()
                            Toast.makeText(context, if (id != null) tr(R.string.saved_to_youtube_music, name) else tr(R.string.couldn_t_create_playlist), Toast.LENGTH_SHORT).show()
                            if (id != null) {
                                c.library.created(id, name, minOf(songs.size, 100))
                                nav.go(Routes.playlist(id))
                            }
                        }
                    },
                    modifier = Modifier.size(48.dp),
                ) { Icon(PrismIcons.PlaylistAdd, tr(R.string.save_as_a_playlist)) }
            }
        }
        // A day by its hours, a week or a month by its days (#286, #287); longer stretches by month, as before.
        val timeline = d.timeline
        if (timeline != null) item {
            SectionHeader(timeline.heading())
            TimelineChart(timeline, Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(196.dp))
        } else if (d.months.size > 1) item {
            SectionHeader(if (LocalAppSettings.current.replayHours) tr(R.string.hours_by_month) else tr(R.string.minutes_by_month))
            MonthChart(d.months, Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(160.dp))
            // The busiest month, as the shorter graphs say theirs.
            d.months.maxByOrNull { it.ms }?.takeIf { it.ms > 0 }?.let { m ->
                val (y, mo) = m.month.split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return@let
                val at = java.time.LocalDate.of(y, mo, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                Text(
                    tr(R.string.replay_busiest_month, formatAt(at, com.prism.music.localDates("MMMMy")), listenTime(m.ms)),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (d.artists.isNotEmpty()) item {
            // Artist photos, not the cover of whichever of their songs came first.
            val thumbs by c.library.artistThumbs.collectAsState()
            LaunchedEffect(d.artists) { c.library.fetchArtistPhotos(d.artists.map { it.artistId to it.artistName }) }
            SectionHeader(tr(R.string.top_artists))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(d.artists) { i, a ->
                    Column(
                        Modifier.width(112.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = a.artistId != null) { a.artistId?.let { nav.go(Routes.artist(it)) } },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            Artwork(
                                c.library.artistPhoto(thumbs, a.artistId, a.artistName), Modifier.size(104.dp), CircleShape, size = 300,
                                placeholderIcon = PrismIcons.Person,
                            )
                            Text(
                                "${i + 1}", color = Color.White, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.align(Alignment.BottomStart).clip(CircleShape).background(com.prism.music.ui.theme.PrismTokens.spectrumRing()).padding(horizontal = 9.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(a.artistName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listenTime(a.ms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (d.genres.isNotEmpty()) item {
            SectionHeader(tr(R.string.top_genres))
            val max = d.genres.maxOf { it.second }.coerceAtLeast(1)
            // The bar runs primary → tertiary; with a pale accent (white) its labels go dark.
            val barInk = inkOn(MaterialTheme.colorScheme.primary)
            val endInk = inkOn(MaterialTheme.colorScheme.tertiary)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.genres.forEachIndexed { i, (g, ms) ->
                    val anim = remember(g, period) { Animatable(0f) }
                    LaunchedEffect(g, period) { anim.animateTo(ms / max.toFloat(), tween(900, delayMillis = i * 90)) }
                    Box(Modifier.fillMaxWidth().height(40.dp).clip(com.prism.music.ui.theme.LocalUi.current.shape(12.dp)).background(com.prism.music.ui.components.quietFill())) {
                        Box(Modifier.fillMaxWidth(anim.value).height(40.dp).background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))))
                        // The labels twice: in the page's ink, then in the bar's ink clipped to the bar, so a name
                        // that runs past the end of a short bar changes color where the bar stops.
                        GenreLabels(com.prism.music.data.meta.Genres.shown(g), listenTime(ms), Color.Unspecified, Color.Unspecified, Modifier)
                        GenreLabels(
                            com.prism.music.data.meta.Genres.shown(g), listenTime(ms), barInk, endInk,
                            Modifier.drawWithContent { clipRect(right = size.width * anim.value) { this@drawWithContent.drawContent() } },
                        )
                    }
                }
            }
        }
        item { SectionHeader(tr(R.string.top_songs_3)) }
        itemsIndexed(d.songs, key = { _, s -> s.songId }) { i, s ->
            val songs = remember(d) { d.songs.map { it.toSong() } }
            val ui = com.prism.music.ui.theme.LocalUi.current
            Row(
                Modifier.fillMaxWidth().clickable { c.player.playQueue(songs, i, QueueSource(QueueKind.REPLAY, tr(R.string.replay_2))) }.padding(horizontal = 16.dp, vertical = ui.gap(6.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}", Modifier.width(36.dp), style = MaterialTheme.typography.titleLarge,
                    color = if (i < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium,
                )
                Artwork(s.thumbnail, Modifier.size(52.dp), ui.smallArt, size = 226)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(s.artistName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(minutes(s.ms), style = MaterialTheme.typography.labelMedium)
                    Text(trp(R.plurals.n_plays, s.plays), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    com.prism.music.ui.components.TopScrollEdge({ edge })
    }
}

@Composable
private fun HeroCard(d: ReplayData, period: Period, notYet: Boolean, loading: Boolean, onOpen: () -> Unit) {
    val total = listenTotal(d.totalMs)
    val anim = remember(period) { Animatable(0f) }
    LaunchedEffect(period, total.value) { anim.animateTo(total.value.toFloat(), tween(1400)) }
    val top = d.songs.firstOrNull()
    val themed = LocalAppSettings.current.replayThemeColors
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.padding(16.dp).fillMaxWidth().clip(com.prism.music.ui.theme.LocalUi.current.shape(30.dp))
            .background(
                Brush.linearGradient(
                    if (themed) listOf(scheme.primaryContainer.copy(alpha = 1f).compositeDark(), scheme.primary.compositeDark(0.55f), scheme.tertiary.compositeDark(0.55f))
                    else listOf(Color(0xFF181818).lit(), Color(0xFF787878).lit(), Color(0xFF797979).lit())
                )
            )
            .clickable(enabled = !notYet, onClick = onOpen)
            .padding(24.dp),
    ) {
        Column {
            Text(
                (if (period.isYear) tr(R.string.rewind_x, period.year) else tr(R.string.replay_x, period.label)).uppercase(),
                color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Column(Modifier.toggleListenUnit()) {
                Text(total.text(anim.value.toLong()), color = Color.White, style = MaterialTheme.typography.displayLarge, maxLines = 1)
                Text(tr(R.string.listened_2, total.unit).trim(),color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                // In the listener's own number format ("1,358", "1.358"), as the rest of Prism writes counts.
                val n = java.text.NumberFormat.getIntegerInstance()
                Stat(n.format(d.plays), trp(R.plurals.plays_word, d.plays))
                Stat(n.format(d.songCount), trp(R.plurals.songs_word, d.songCount))
                Stat(n.format(d.activeDays), trp(R.plurals.days_word, d.activeDays))
            }
            if (top != null) {
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.14f)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(top.thumbnail, Modifier.size(52.dp), RoundedCornerShape(10.dp), size = 226)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(tr(R.string.your_1_song), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                        Text(top.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${minutes(top.ms)} · ${top.artistName}", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            // The story: Prism's take on Wrapped / Replay. A year's is its Rewind, which opens on 1 December.
            if (notYet) Text(
                tr(R.string.your_rewind_opens_on_december_1, period.year),
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
            ) else Row(
                Modifier.clip(CircleShape).background(Color.White).padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                else Icon(PrismIcons.Play, null, Modifier.size(20.dp), tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text(if (period.isYear) tr(R.string.watch_your_rewind) else tr(R.string.watch_your_replay), color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Listening time the way Replay is set to show it: "1,204 min", or "20h 4m" with hours on.
 * Anything under a minute shows "<1 min".
 */
@Composable
@ReadOnlyComposable
fun listenTime(ms: Long): String = listenTime(ms, LocalAppSettings.current.replayHours)

fun listenTime(ms: Long, hours: Boolean): String {
    val m = ms / 60_000
    return when {
        m == 0L && ms > 0 -> tr(R.string.t_1_min)
        !hours || m < 60 -> tr(R.string.d_min).format(m)
        else -> hoursAndMinutes(m)
    }
}

/** "8h 20m", or "8h" on the hour. */
fun hoursAndMinutes(totalMinutes: Long): String =
    if (totalMinutes % 60 == 0L) "%,dh".format(totalMinutes / 60) else tr(R.string.dh_dm).format(totalMinutes / 60, totalMinutes % 60)

@Composable
@ReadOnlyComposable
private fun minutes(ms: Long): String = listenTime(ms)

/** Tapping the headline listening time flips Replay between minutes and hours. */
@Composable
fun Modifier.toggleListenUnit(): Modifier {
    val c = LocalContainer.current
    val hours = LocalAppSettings.current.replayHours
    return clickable(interactionSource = null, indication = null, onClickLabel = if (hours) tr(R.string.show_minutes) else tr(R.string.show_hours)) {
        c.settings.setReplayHours(!hours)
    }
}

/**
 * The big headline time: [value] whole minutes, printed by [text] as "1,204" (with [unit] "minutes")
 * or, with hours on, as "20h 4m" (no unit). Animate [value] and print it with [text].
 */
class ListenTotal(val value: Long, val unit: String, val text: (Long) -> String) {
    /** The whole thing on one line: "1,204 minutes" / "20h 4m". */
    val full: String get() = (text(value) + " " + if (value == 1L && unit == "minutes") "minute" else unit).trim()
}

@Composable
@ReadOnlyComposable
fun listenTotal(ms: Long): ListenTotal =
    // Under an hour stays in minutes, so the hours view never shows "0h".
    if (LocalAppSettings.current.replayHours && ms >= 3_600_000) ListenTotal(ms / 60_000, "", ::hoursAndMinutes)
    else ListenTotal(ms / 60_000, "minutes") { "%,d".format(it) }

/** Replay's own colors, or the theme's when "Use theme colors" is on — always dark enough for white text. */
@Composable
fun replayGradient(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return if (LocalAppSettings.current.replayThemeColors) listOf(scheme.primary.compositeDark(0.6f), scheme.tertiary.compositeDark(0.5f), scheme.secondary.compositeDark(0.6f))
    else listOf(Color(0xFF797979).lit(), Color(0xFF787878).lit(), Color(0xFF828282).lit())
}

/**
 * White text on [bg], or near-black when that reads better. 0.18 is where the two contrasts cross; the
 * old 0.5 cut put white on mid greys (the white accent's tertiary, g 0.70), which was hard to read.
 */
private fun inkOn(bg: Color): Color = if (bg.luminance() > 0.18f) Color(0xFF111111) else Color.White

/** Darkens a color towards black by [amount] (0 = as is), so white sits on it comfortably. */
private fun Color.compositeDark(amount: Float = 0.7f): Color = Color(red * (1 - amount), green * (1 - amount), blue * (1 - amount), 1f)

@Composable
private fun GenreLabels(name: String, time: String, nameInk: Color, timeInk: Color, modifier: Modifier) {
    Box(modifier.fillMaxSize()) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = nameInk, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
        Text(time, style = MaterialTheme.typography.labelMedium, color = timeInk, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(label, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MonthChart(months: List<MonthTotal>, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val anim = remember(months) { Animatable(0f) }
    LaunchedEffect(months) { anim.animateTo(1f, tween(900)) }
    val names = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val max = months.maxOf { it.ms }.coerceAtLeast(1)
            val gap = 8.dp.toPx()
            val w = (size.width - gap * (months.size - 1)) / months.size
            months.forEachIndexed { i, m ->
                val h = size.height * (m.ms / max.toFloat()) * anim.value
                drawRoundRect(
                    Brush.verticalGradient(listOf(tertiary, primary)),
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            months.forEach { m ->
                val idx = m.month.substringAfter("-").toIntOrNull()?.minus(1) ?: 0
                Text(names[idx.coerceIn(0, 11)], Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = label, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
