package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.db.mergeUploads
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.AppContainer
import com.prism.music.BuildConfig
import com.prism.music.container
import com.prism.music.data.db.SongPlays
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Rewind: the year in music, for every year Prism has history for. The current year's opens on 1 December; a finished
 * year is worked out once and kept ([RewindStore]), so it opens at once and stays the way it was.
 */
class Rewinds(private val c: AppContainer) {
    val store = RewindStore(File(c.app.filesDir, "rewind"))

    /** Debug builds open the current year's Rewind early, for trying it out. */
    val early: Boolean get() = BuildConfig.DEBUG

    fun isOpen(year: Int, now: Long = System.currentTimeMillis()) = RewindCalendar.isOpen(year, now, early = early)

    /** Years with listening, newest first. */
    suspend fun years(): List<Int> = RewindCalendar.years(c.imports.replay().firstEvent(), System.currentTimeMillis())

    private fun finished(year: Int, now: Long = System.currentTimeMillis()) = now > RewindCalendar.bounds(year).second + FINISHED_AFTER_MS

    /** The year still under way, worked out lately (watching it twice in a row needn't work it out twice). */
    @Volatile private var recent: RewindSnapshot? = null
    @Volatile private var recentSources: List<com.prism.music.data.imports.ImportSource> = emptyList()

    /** [year]'s Rewind: as kept, or worked out now (and kept, once the year's over). */
    suspend fun snapshot(year: Int): RewindSnapshot = withContext(Dispatchers.IO) {
        // Kept Rewinds are Prism's own plays: with an imported history counted (1.8.1) the year is worked out afresh.
        val stats = c.imports.replay()
        val prismOnly = stats.sources.isEmpty()
        if (prismOnly) store.load(year)?.let { return@withContext it }
        recent?.takeIf { it.year == year && System.currentTimeMillis() - it.frozenAt < RECENT_MS && recentSources == stats.sources }?.let { return@withContext it }
        val s = build(year, stats)
        if (prismOnly && finished(year) && s.plays > 0) runCatching { store.save(s) }.onFailure { Log.w(TAG, "couldn't keep Rewind $year", it) }
        else { recent = s; recentSources = stats.sources }
        s
    }

    /** The year that's just ended, kept on the first launch after it (in the background, after start-up settles). */
    suspend fun freezeLastYear() {
        val last = RewindCalendar.yearOf(System.currentTimeMillis()) - 1
        if (store.load(last) != null) return
        val (from, to) = RewindCalendar.bounds(last)
        if (c.db.plays().count(from, to) == 0) return
        runCatching { snapshot(last) }.onFailure { Log.w(TAG, "couldn't freeze Rewind $last", it) }
    }

    private fun SongPlays.song() = Song(
        id = songId, title = title,
        artists = artistName.split(", ").mapIndexed { i, n -> ArtistRef(n, if (i == 0) artistId else null) },
        album = albumTitle?.let { AlbumRef(it) }, durationSec = durationSec, thumbnail = thumbnail,
    )

    suspend fun build(year: Int, dao: com.prism.music.data.imports.PlayStats = c.imports.replay()): RewindSnapshot = withContext(Dispatchers.IO) {
        // The slow, network-bound pieces (a lyric line, the top artist's photo) go first, alongside the counting.
        val (from0, end0) = RewindCalendar.bounds(year)
        val topNow = dao.topSongs(from0, minOf(end0, System.currentTimeMillis()), 20).mergeUploads().firstOrNull()
        val lyricJob = async { topNow?.let { lyricLine(it) } }
        val imageJob = async {
            dao.topArtists(from0, minOf(end0, System.currentTimeMillis()), 1).firstOrNull()?.artistId?.let { id ->
                withTimeoutOrNull(5_000) { runCatching { c.ytm.artist(id).thumbnail }.getOrNull() }
            }
        }
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val (from, end) = RewindCalendar.bounds(year, zone)
        val to = minOf(end, now)
        val songs = dao.topSongs(from, to, 150).mergeUploads().take(100)
        val artists = dao.topArtists(from, to, 20)
        val totalMs = dao.totalMs(from, to)
        val genreMap = c.meta.cachedGenres(songs.map { it.song() })
        val genres = songs.groupBy { genreMap[it.songId] ?: "" }.filterKeys { it.isNotEmpty() }
            .mapValues { (_, v) -> v.sumOf { it.ms } }.toList().sortedByDescending { it.second }.take(5)
        val genreSongs = genres.associate { (g, _) -> g to songs.first { genreMap[it.songId] == g }.songId }

        // Last year over the same stretch (all of it, once this one's over).
        val prevFrom = RewindCalendar.bounds(year - 1, zone).first
        val prevTo = minOf(RewindCalendar.bounds(year - 1, zone).second, Instant.ofEpochMilli(to).atZone(zone).minusYears(1).toInstant().toEpochMilli())
        val previousMs = dao.totalMs(prevFrom, prevTo).takeIf { it > 60 * 60_000L }

        val events = dao.events(from, to)
        val habits = ReplayStats.habits(events, zone)
        val artistCount = dao.artistCount(from, to)
        val newArtists = if (dao.firstEvent()?.let { it >= from } == true) null else dao.newArtists(from, to)
        val activeDays = dao.activeDays(from, to)
        val plays = dao.count(from, to)
        val songCount = dao.songCount(from, to)
        val top = songs.firstOrNull()
        val genreTotal = genres.sumOf { it.second }.coerceAtLeast(1)

        val seasons = RewindCalendar.quarters.indices.mapNotNull { q ->
            val (qa, qb) = RewindCalendar.quarterBounds(year, q, zone)
            if (qa > to) return@mapNotNull null
            val qs = dao.topSongs(qa, minOf(qb, to), 60).mergeUploads().take(40)
            if (qs.isEmpty()) return@mapNotNull null
            val g = c.meta.cachedGenres(qs.map { it.song() })
            val genre = qs.groupBy { g[it.songId] ?: "" }.filterKeys { it.isNotEmpty() }.mapValues { (_, v) -> v.sumOf { it.ms } }.maxByOrNull { it.value }?.key
            SeasonGenre(RewindCalendar.quarters[q].first, genre, qs.sumOf { it.ms }, qs.first().title)
        }

        val bySource = dao.bySource(from, to).associate { (it.source ?: "") to it.plays }
        val prism = PrismYear(
            sung = bySource["sing"] ?: 0,
            djSongs = bySource["dj"] ?: 0,
            smartPicks = bySource["smart"] ?: 0,
            recognized = dao.recognizedIn(from, to),
            blends = dao.blendsIn(from, to),
            radarFinds = dao.releasesFoundIn(from, to),
        )

        val topLyric = if (top?.songId == topNow?.songId) lyricJob.await() else top?.let { lyricLine(it) }
        val leadImage = imageJob.await()

        RewindSnapshot(
            year = year, frozenAt = now, totalMs = totalMs, previousMs = previousMs,
            previousTopArtist = if (previousMs != null) dao.topArtists(prevFrom, prevTo, 1).firstOrNull()?.artistName else null,
            previousSongCount = if (previousMs != null) dao.songCount(prevFrom, prevTo) else null,
            plays = plays, activeDays = activeDays, songCount = songCount, artistCount = artistCount,
            songs = songs, artists = artists, genres = genres.map { GenreShare(it.first, it.second) }, genreSongs = genreSongs,
            byHour = habits.byHour.toList(), byWeekday = habits.byWeekday.toList(), longestStreak = habits.longestStreak,
            biggestDay = habits.biggestDay?.toString(), biggestDayMs = habits.biggestDayMs,
            newArtists = newArtists,
            personality = ReplayStats.personality(
                totalMs = totalMs, topFiveArtistsMs = artists.take(5).sumOf { it.ms }, artistCount = artistCount,
                newArtistCount = newArtists?.size, plays = plays, songCount = songCount,
                topSongPlays = top?.plays ?: 0, topSongTitle = top?.title, activeDays = activeDays,
                topGenreShare = genres.firstOrNull()?.let { it.second.toDouble() / genreTotal }, habits = habits,
            ),
            topArtistImage = leadImage,
            months = dao.monthly(from, to),
            topAlbum = dao.topAlbums(from, to, 1).firstOrNull(),
            firstSong = dao.firstPlay(from, to),
            seasons = seasons,
            prism = prism,
            topLyric = topLyric,
        )
    }

    /** A line from [t], where its best part starts (its chorus, most likely); null without both. */
    private suspend fun lyricLine(t: SongPlays): String? = coroutineScope {
        val song = t.song()
        val start = async { withTimeoutOrNull(5_000) { runCatching { c.bestParts.startMs(song) }.getOrNull() } }
        val lyrics = async { withTimeoutOrNull(8_000) { runCatching { c.lyrics.auto(song) }.getOrNull() }?.takeIf { it.synced } }
        val s = start.await() ?: return@coroutineScope null
        val l = lyrics.await() ?: return@coroutineScope null
        l.lines.filter { !it.isGap && it.text.isNotBlank() }.firstOrNull { it.timeMs >= s - 1_500 }?.text?.trim()?.take(120)
    }

    companion object {
        private const val TAG = "PrismRewind"
        private const val RECENT_MS = 10 * 60_000L
        /** A little past midnight on 31 December, so the year's last plays have been counted before it's kept. */
        private const val FINISHED_AFTER_MS = 6 * 60 * 60_000L

        /** The "Your Rewind is ready" notification, 1 December at 10:00 this year (or next, once that's passed). */
        fun schedule(context: Context) {
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val year = RewindCalendar.yearOf(now, zone)
            val at = { y: Int -> RewindCalendar.opensAt(y, zone) + 10 * 60 * 60_000L }
            val target = if (now < at(year)) year else year + 1
            WorkManager.getInstance(context).enqueueUniqueWork(
                "rewind_$target", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RewindWorker>().setInitialDelay(at(target) - now, TimeUnit.MILLISECONDS).build(),
            )
        }
    }
}

/** 1 December: tells the listener their Rewind is ready (if they listened this year), then lines up next year's. */
class RewindWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val year = RewindCalendar.yearOf(System.currentTimeMillis())
        val (from, to) = RewindCalendar.bounds(year)
        if (c.settings.current.rewindNotification && c.db.plays().totalMs(from, to) > StoryParts.LIGHT_MS) {
            Notifications.post(
                applicationContext, Notifications.REWIND, 4800 + year % 100, tr(R.string.your_rewind_is_ready_2, year),
                tr(R.string.your_year_in_music_your_top), "prism://rewind/$year",
            )
        }
        Rewinds.schedule(applicationContext)
        return Result.success()
    }
}
