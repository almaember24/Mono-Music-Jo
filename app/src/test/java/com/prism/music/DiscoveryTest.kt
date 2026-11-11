package com.prism.music

import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.foryou.Discovery
import com.prism.music.data.foryou.Heard
import com.prism.music.data.foryou.LocalRadio
import com.prism.music.data.foryou.MixMaker
import com.prism.music.data.foryou.OnThisDay
import com.prism.music.data.foryou.RadioSignals
import com.prism.music.data.foryou.RadioTopUp
import com.prism.music.data.foryou.RadioTune
import com.prism.music.data.foryou.Variety
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.CollectionKind
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.Related
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.HomeSections
import com.prism.music.playback.SessionHeard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * New to you, Deep cuts and radios (1.8.2), measured before and after on a made-up listener and a made-up YouTube.
 *
 * The world: 60 artists of three albums each, whose radios lean hard on each artist's few hits, as YouTube's do, and overlap
 * each other. The listener: a year of plays of 12 artists, most of it older than the 90 days a Daily Mix looks back.
 * These numbers describe the rules on this made-up world, not how good the picks would be on real music:
 * [DiscoverLiveTest] looks at real answers.
 */
class DiscoveryTest {
    private fun artistName(a: Int) = "Artist $a"

    private inner class Track(val song: Song, val hit: Boolean)

    /** Track 1 of each album and a couple more are the hits. A guest track, a live take and a remix on each last album. */
    private val world: Map<Int, List<List<Track>>> = (1..60).associateWith { a ->
        (1..3).map { al ->
            (1..12).map { t ->
                val title = when {
                    al == 3 && t == 11 -> "Song $a-$al-$t (Live at Wembley)"
                    al == 3 && t == 12 -> "Song $a-$al-$t (Club Remix)"
                    else -> "Song $a-$al-$t"
                }
                val credit = if (al == 2 && t == 12) listOf(ArtistRef("Guest of $a")) else listOf(ArtistRef(artistName(a), "UC$a"))
                Track(Song("v$a-$al-$t", title, credit, durationSec = 200), hit = t == 1 || (al == 1 && t <= 3))
            }
        }
    }
    private val allTracks = world.values.flatten().flatten()
    private val hits = world.mapValues { (_, albums) -> albums.flatten().filter { it.hit }.map { it.song } }
    private fun near(a: Int) = (0..4).map { (a + it * 3 - 1) % 60 + 1 }

    /**
     * A radio, the same each time it's asked: [perPage] songs a page, starting among the seed's artist's neighbours, mostly
     * their hits, drifting further from them page by page. Radios of neighbouring artists overlap, most of all at the start.
     */
    private inner class Radio(seed: String, val perPage: Int = 25, val pages: Int = 8) {
        private val a = seed.removePrefix("v").substringBefore('-').toIntOrNull() ?: 1
        private val rnd = java.util.Random(seed.hashCode().toLong())
        val all: List<Song> = (0 until perPage * pages * 2).map { n ->
            val artist = near((a + (n / perPage) * 2 - 1) % 60 + 1)[rnd.nextInt(5)]
            if (rnd.nextInt(5) < 3) hits.getValue(artist).let { h -> h[rnd.nextInt(h.size)] }
            else world.getValue(artist).flatten().let { t -> t[rnd.nextInt(t.size)].song }
        }.distinctBy { it.id }
        fun page(i: Int) = if (i < pages) all.drop(i * perPage).take(perPage) else emptyList()
    }

    private inner class Sources : Discovery.Sources {
        override suspend fun radio(seed: Song) = Radio(seed.id).page(0)
        override suspend fun related(seed: Song): Related {
            val a = seed.id.removePrefix("v").substringBefore('-').toInt()
            return Related(Radio(seed.id + "r").page(1), near(a).drop(1).map { ArtistItem("UC$it", artistName(it), "", null) })
        }
        override suspend fun artistTop(artist: ArtistItem) = hits.getValue(artist.id.removePrefix("UC").toInt()).take(6)
        override suspend fun catalog(artist: Discovery.LovedArtist): Discovery.Catalog {
            val a = artist.name.removePrefix("Artist ").toInt()
            return Discovery.Catalog(hits.getValue(a).take(5), (1..3).map { AlbumItem("album$a-$it", "Album $it", "Album", null) })
        }
        override suspend fun album(item: AlbumItem): CollectionPage {
            val (a, al) = item.id.removePrefix("album").split('-').map { it.toInt() }
            val tracks = world.getValue(a)[al - 1]
            return CollectionPage(
                item.id, CollectionKind.ALBUM, item.title, "", thumbnail = null, songs = tracks.map { it.song },
                // Each album's two most played stand out ([CollectionPage.popularIds]).
                trackPlays = tracks.mapIndexed { i, t -> t.song.id to if (i < 2) 90_000_000L else 2_000_000L }.toMap(),
            )
        }
    }

    private val day = 86_400_000L
    private val now = LocalDate.of(2026, 11, 2).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    private val loved = (1..12).toList()

    /** The listener's year: mostly hits of 12 artists, plus a few album tracks, more than half of it over 90 days old. */
    private val history: List<PlayEventEntity> = run {
        val rnd = java.util.Random(11)
        (0 until 1500).map { i ->
            val a = loved[(rnd.nextDouble().let { it * it } * loved.size).toInt()]
            val s = if (rnd.nextInt(10) < 7) hits.getValue(a).let { it[rnd.nextInt(it.size)] } else world.getValue(a).flatten().let { it[rnd.nextInt(it.size)].song }
            PlayEventEntity(i + 1L, s.id, now - (rnd.nextDouble() * 360 * day).toLong(), 180_000L)
        }
    }
    private val byId = allTracks.associate { it.song.id to it.song }
    private val everHeard = Heard.of(history.map { it.songId }, history.mapNotNull { byId[it.songId] }.map { it.title to it.primaryArtist })
    private val inLast90 = history.filter { it.timestamp > now - 90 * day }.mapNotNull { byId[it.songId] }
    private val favorites = Discovery.favorites(history, byId, emptyList(), now)

    private fun listener(lastWeeks: Set<String> = emptySet(), refused: (Song) -> Boolean = { false }, served: (String) -> Int? = { null }) = Discovery.Listener(
        favorites, Discovery.lovedArtists(favorites, emptyList()), everHeard, refused, servedDaysAgo = served, lastWeeks = lastWeeks,
        albumsHeard = setOf("album1-2"),
    )

    /** What's measured of a list of songs. */
    private inner class Numbers(name: String, val songs: List<Song>, val again: Double? = null) {
        val artists = songs.map { it.primaryArtist.lowercase() }.distinct().size
        val repeats = songs.size - songs.distinctBy { LocalRadio.sameSong(it) }.size
        val neverPlayed = if (songs.isEmpty()) 0.0 else songs.count { !everHeard.has(it) }.toDouble() / songs.size
        val longestRun = runs(songs)
        val busiest = songs.groupingBy { it.primaryArtist.lowercase() }.eachCount().values.maxOrNull() ?: 0
        val line = "%-46s %3d songs, %2d artists, busiest %2d, %2d repeats, %3.0f%% never played, longest run %d%s".format(
            name, songs.size, artists, busiest, repeats, neverPlayed * 100, longestRun, again?.let { ", %.0f%% same as the week before".format(it * 100) }.orEmpty(),
        ).also { println("[discover] $it") }
    }

    private fun runs(songs: List<Song>): Int {
        var best = 0; var run = 0; var last: String? = null
        for (s in songs) {
            val a = s.primaryArtist.lowercase()
            run = if (a == last) run + 1 else 1
            last = a
            best = maxOf(best, run)
        }
        return best
    }

    /** Four Mondays of [make], each knowing what the last four offered, as [com.prism.music.data.foryou.Discover] has it. */
    private fun fourWeeks(make: suspend Discovery.(Discovery.Listener, String) -> Discovery.Made): List<List<Song>> = runBlocking {
        val offered = ArrayList<Set<String>>()
        (0 until 4).map { w ->
            val week = Discovery.weekOf(LocalDate.of(2026, 11, 2).plusWeeks(w.toLong()))
            val made = Discovery(Sources()).make(listener(lastWeeks = offered.flatten().toSet()), week)
            offered += made.songs.map { LocalRadio.sameSong(it) }.toSet()
            made.songs
        }
    }

    private fun overlap(weeks: List<List<Song>>) = weeks.zipWithNext { a, b -> Variety.overlap(a.map { it.id }, b.map { it.id }) }.average()

    // ------------ New to you

    @Test fun newToYouIsAllNewWhereTheDailyMixRuleIsNot() = runBlocking {
        // Before: new songs by the rule Daily Mixes have, "not played in the last 90 days", from the same seeds' radios.
        val played = inLast90.flatMap { listOf(it.id, LocalRadio.sameSong(it)) }.toSet()
        val seeds = Variety.roundRobin(favorites.map { it.song }) { it.primaryArtist }.take(Discovery.SEEDS)
        val rawRadios = seeds.flatMap { Sources().radio(it) }.take(30)
        val taste = MixMaker.Taste(emptyList(), played, { _, _ -> false })
        val before = (0 until 4).map { w ->
            val d = Discovery.weekOf(LocalDate.of(2026, 11, 2).plusWeeks(w.toLong()))
            val seed = Variety.daySeed(d, "before")
            val pool = MixMaker(Sources()).discover(seeds, MixMaker.Recipe("before", 30, 1.0, seeds = 6, perArtist = 4), taste, emptySet(), seed, emptySet(), want = 30)
            Variety.arrange(emptyList(), pool, 30, 1.0, 4) { it.primaryArtist.lowercase() }
        }
        val after = fourWeeks { l, w -> newToYou(l, w) }

        Numbers("New to you, before: radios of favorites", rawRadios)
        val b = Numbers("New to you, before: Daily Mix's 90-day rule", before.first(), overlap(before))
        val a = Numbers("New to you, after", after.first(), overlap(after))

        for (week in after) {
            assertTrue("about 30 songs (${week.size})", week.size in 25..30)
            assertTrue("never played means never", week.none { everHeard.has(it) })
            assertEquals("no song twice", week.size, week.distinctBy { LocalRadio.sameSong(it) }.size)
            assertEquals("never the same artist twice running", 1, runs(week))
        }
        assertEquals(1.0, a.neverPlayed, 0.0)
        assertTrue("before really did bring back old plays (${b.neverPlayed})", b.neverPlayed < 1.0)
        assertTrue("next Monday's are other songs (${overlap(after)})", overlap(after) < overlap(before))
        assertTrue("a good spread of artists (${a.artists})", a.artists >= 8)
    }

    @Test fun newToYouLeavesOutWhatsRefused() {
        val first = runBlocking { Discovery(Sources()).newToYou(listener(), "2026-11-02") }.songs
        val no = first.take(5).map { it.id }.toSet()
        val again = runBlocking { Discovery(Sources()).newToYou(listener(refused = { it.id in no }), "2026-11-02") }.songs
        assertTrue(again.none { it.id in no })
    }

    @Test fun aWeekIsTheSameAllWeekAndTheNextDiffers() = runBlocking {
        val one = Discovery(Sources()).newToYou(listener(), Discovery.weekOf(LocalDate.of(2026, 11, 2))).songs
        val two = Discovery(Sources()).newToYou(listener(), Discovery.weekOf(LocalDate.of(2026, 11, 8))).songs
        assertEquals("Monday to Sunday is one week", one, two)
        val next = Discovery(Sources()).newToYou(listener(), Discovery.weekOf(LocalDate.of(2026, 11, 9))).songs
        assertTrue(one != next)
    }

    @Test fun nobodyToGoOnMeansNoMix() = runBlocking {
        val none = Discovery.Listener(emptyList(), emptyList(), Heard.NOTHING)
        assertTrue(Discovery(Sources()).newToYou(none, "2026-11-02").songs.isEmpty())
        assertTrue(Discovery(Sources()).deepCuts(none, "2026-11-02").songs.isEmpty())
    }

    @Test fun onlyStarredArtistsIsEnoughToStart() = runBlocking {
        // Signed out with no plays yet: a starred artist's top songs seed it.
        val l = Discovery.Listener(emptyList(), listOf(Discovery.LovedArtist(artistName(3), "UC3", 1.0)), Heard.NOTHING)
        assertTrue(Discovery(Sources()).newToYou(l, "2026-11-02").songs.isNotEmpty())
        assertTrue(Discovery(Sources()).deepCuts(l, "2026-11-02").songs.isNotEmpty())
    }

    // ------------ Deep cuts

    @Test fun deepCutsAreAlbumTracksNeverPlayedByLovedArtists() = runBlocking {
        // Before: what an artist-based list had: their top songs not played in the last 90 days.
        val recent = inLast90.map { it.id }.toSet()
        val tops = Discovery.lovedArtists(favorites, emptyList()).take(8).flatMap { a -> Sources().artistTop(ArtistItem(a.id!!, a.name, "", null)) }.filter { it.id !in recent }.take(30)
        val after = fourWeeks { l, w -> deepCuts(l, w) }

        val b = Numbers("Deep cuts, before: top songs minus 90 days", tops)
        val a = Numbers("Deep cuts, after", after.first(), overlap(after))

        val lovedNames = loved.map { artistName(it).lowercase() }.toSet()
        val hitIds = hits.values.flatten().map { it.id }.toSet()
        for (week in after) {
            assertTrue("about 30 songs (${week.size})", week.size in 25..30)
            assertTrue("by artists they love", week.all { it.primaryArtist.lowercase() in lovedNames })
            assertTrue("never played", week.none { everHeard.has(it) })
            assertTrue("no top song", week.none { it.id in hitIds })
            assertTrue("no standout of the album", week.none { it.id.endsWith("-2") })
            assertTrue("no live take or remix", week.none { "Live" in it.title || "Remix" in it.title })
            assertTrue("the artist is on the track", week.none { it.primaryArtist.startsWith("Guest") })
            assertEquals("no song twice", week.size, week.distinctBy { LocalRadio.sameSong(it) }.size)
            assertEquals("never the same artist twice running", 1, runs(week))
        }
        assertEquals(1.0, a.neverPlayed, 0.0)
        assertTrue("before had plays in it (${b.neverPlayed})", b.neverPlayed < 0.5)
        assertTrue("a week's mix isn't one artist (${a.busiest})", a.busiest <= 6)
    }

    @Test fun anotherUploadOfASongTheyKnowIsntADeepCut() {
        val heard = Heard.of(emptyList(), listOf("Song 1-2-5" to "Artist 1"))
        assertTrue(heard.has(Song("elsewhere", "Song 1-2-5 (Remastered 2011)", listOf(ArtistRef("Artist 1")))))
        assertFalse(heard.has(Song("other", "Song 1-2-6", listOf(ArtistRef("Artist 1")))))
    }

    // ------------ Heard

    @Test fun heardGoesByIdAndByNameInAnyScript() {
        val heard = Heard.of(listOf("id1"), listOf("밤편지" to "아이유", "Imported Song" to "Someone feat. Someone Else"))
        assertTrue(heard.has(Song("id1", "Whatever", listOf(ArtistRef("x")))))
        assertTrue("Korean title, another id", heard.has(Song("other", "밤편지", listOf(ArtistRef("아이유")))))
        assertFalse(heard.has(Song("other2", "좋은 날", listOf(ArtistRef("아이유")))))
        assertTrue("an import's main artist", heard.has(Song("other3", "Imported Song", listOf(ArtistRef("Someone")))))
        // Only punctuation in the title: nothing to go by.
        assertFalse(Heard.of(emptyList(), listOf("..." to "A")).has(Song("x", "...", listOf(ArtistRef("A")))))
    }

    // ------------ Dates

    @Test fun weeksStartOnMonday() {
        assertEquals("2026-11-02", Discovery.weekOf(LocalDate.of(2026, 11, 2)))
        assertEquals("2026-11-02", Discovery.weekOf(LocalDate.of(2026, 11, 8)))
        assertEquals("2026-11-09", Discovery.weekOf(LocalDate.of(2026, 11, 9)))
        assertEquals("over a new year", "2026-12-28", Discovery.weekOf(LocalDate.of(2027, 1, 1)))
    }

    @Test fun onThisDayIsThatWholeLocalDay() {
        val zone = ZoneId.of("America/New_York")
        val w = OnThisDay.window(LocalDate.of(2026, 11, 5), zone)
        assertEquals(LocalDate.of(2025, 11, 5).atStartOfDay(zone).toInstant().toEpochMilli(), w.first)
        assertEquals(LocalDate.of(2025, 11, 6).atStartOfDay(zone).toInstant().toEpochMilli() - 1, w.last)
        assertEquals(24 * 3_600_000L - 1, w.last - w.first)
    }

    @Test fun onThisDayCopesWithClockChangesAndLeapDays() {
        val zone = ZoneId.of("America/New_York")
        // A year before Nov 2, 2026 is the day the clocks went back: 25 hours.
        val long = OnThisDay.window(LocalDate.of(2026, 11, 2), zone)
        assertEquals(25 * 3_600_000L - 1, long.last - long.first)
        // March 8, 2026 lost an hour.
        val short = OnThisDay.window(LocalDate.of(2027, 3, 8), zone)
        assertEquals(23 * 3_600_000L - 1, short.last - short.first)
        val leap = OnThisDay.window(LocalDate.of(2029, 2, 28), zone)
        assertEquals(LocalDate.of(2028, 2, 28).atStartOfDay(zone).toInstant().toEpochMilli(), leap.first)
        val afterLeap = OnThisDay.window(LocalDate.of(2028, 2, 29), zone)
        assertEquals("a leap day's year-ago is Feb 28", LocalDate.of(2027, 2, 28).atStartOfDay(zone).toInstant().toEpochMilli(), afterLeap.first)
    }

    // ------------ Home

    @Test fun aHomeFrom181LooksTheSame() {
        val mine = listOf(HomeSections.NEW_TO_YOU, HomeSections.DEEP_CUTS, HomeSections.ON_THIS_DAY)
        val fresh = HomeSections.initial.map { it.key }
        assertTrue("not on a new install's Home", fresh.none { it in mine })
        assertTrue("offered in Customize Home", HomeSections.defaults.map { it.key }.containsAll(mine))
    }

    // ------------ Favorites

    @Test fun favoritesFadeSlowlyAndLikesCount() {
        val a = Song("a", "A", listOf(ArtistRef("X")), durationSec = 100)
        val b = Song("b", "B", listOf(ArtistRef("Y")), durationSec = 100)
        val c = Song("c", "C", listOf(ArtistRef("Z")), durationSec = 100)
        val plays = listOf(
            PlayEventEntity(1, "a", now - 300 * day, 100_000), PlayEventEntity(2, "b", now - day, 100_000),
        )
        val f = Discovery.favorites(plays, mapOf("a" to a, "b" to b), listOf(c), now)
        assertEquals("a like counts for more than a couple of plays", listOf("c", "b", "a"), f.map { it.song.id })
        assertTrue("a song played most of a year ago still counts", f.last().weight > 0)
        val artists = Discovery.lovedArtists(f, listOf(Discovery.LovedArtist("Starred", "UCs", 0.0)))
        assertEquals(setOf("Z", "Y", "X", "Starred"), artists.map { it.name }.toSet())
        assertTrue("a starred artist ranks, however little played", artists.first { it.name == "Starred" }.weight > artists.first { it.name == "X" }.weight)
    }

    // ------------ Radios: a whole session, played through

    /** What's asked of a session: songs played, by whom, repeated or not, and whether the music stopped. */
    private inner class Session(
        val noRepeats: Boolean, val want: Int = 170, val pages: Int = 8,
        /** After this many songs, the listener starts another song's radio (the queue is replaced). */
        val newRadioEvery: Int = 45,
        val tune: RadioTune = RadioTune(), val signals: RadioSignals = RadioSignals(),
        /** A YouTube that only knows these songs, whatever the seed. */
        val only: List<Song>? = null,
    ) {
        var clock = now
        val session = SessionHeard(now = { clock })
        val played = ArrayList<Song>()
        /** Which of [played] the listener started themselves: those may repeat, it's what they asked for. */
        val tapped = HashSet<Int>()
        val events = ArrayList<Pair<String, Long>>()
        val turnedDown = HashSet<String>()
        var stoppedAt: Int? = null
        var cameRound = 0
        private val rnd = java.util.Random(23)

        private fun radio(seed: String, token: String?): RadioTopUp.Page? {
            only?.let { return RadioTopUp.Page(it, null) }
            val r = Radio(seed, pages = pages)
            val i = token?.toInt() ?: 0
            return RadioTopUp.Page(r.page(i), if (i + 1 < pages && r.page(i + 1).isNotEmpty()) "${i + 1}" else null)
        }

        fun run() = runBlocking {
            var seed = favorites[0].song.id
            var token: String?
            var current = 0
            while (played.size < want) {
                // A new radio: its first page, the song it starts on first. The queue before it is gone.
                val start = byId.getValue(seed)
                val first = radio(seed, null)!!
                token = first.continuation
                var queue: MutableList<Song> = (listOf(start) + first.songs.filter { it.id != start.id }).toMutableList()
                if (noRepeats) queue = session.radioStart(queue, 0).first.toMutableList()
                var index = 0
                var from = seed
                current++
                var sinceStart = 0
                while (played.size < want && sinceStart < newRadioEvery) {
                    if (index >= queue.size) { stoppedAt = played.size; return@runBlocking }
                    val s = queue[index]
                    if (index == 0) tapped += played.size
                    session.note(s)
                    played += s
                    sinceStart++
                    // One in five skipped in the first seconds: not a play, but a turned down suggestion.
                    if (rnd.nextInt(5) == 0) { clock += 5_000; turnedDown += s.id } else {
                        clock += 210_000
                        events += s.id to clock
                    }
                    index++
                    if (queue.size - index <= 3) {
                        val rules = RadioTopUp.Rules(
                            queue.map { it.id }.toSet(),
                            events.filter { it.second > clock - 4 * 3_600_000L }.map { it.first }.toSet(),
                            { it.id in turnedDown }, { true },
                        )
                        val answer = RadioTopUp.next(
                            radio(from, token), from, token, emptyList(), true, rules, tune, signals, from.hashCode().toLong() + queue.size,
                            noRepeats = if (!noRepeats) null else RadioTopUp.NoRepeats(
                                session::has, RadioTopUp.otherSeeds(session.oldestFirst(), s),
                                { f, t -> radio(f, t) }, { emptyList() }, { session.oldestFirst() }, session::heardAt,
                            ),
                        )
                        from = answer.seed
                        token = answer.continuation
                        if (answer.cameRound && answer.add.isNotEmpty()) cameRound++
                        queue.addAll(answer.add)
                    }
                }
                // The next radio: a song of the next loved artist.
                seed = favorites.map { it.song }.distinctBy { it.primaryArtist }[current % loved.size].id
            }
        }
    }

    private fun Session.summary(name: String): Numbers {
        run()
        val n = Numbers(name, played)
        println("[discover]   stopped dead: ${stoppedAt?.let { "after $it songs" } ?: "no"}, came round: $cameRound time(s), repeats the listener didn't start: ${repeated()}")
        return n
    }

    /** Songs played again that the listener didn't start by hand. */
    private fun Session.repeated(): Int {
        val seen = HashSet<String>()
        return played.withIndex().count { (i, s) -> !seen.add(LocalRadio.sameSong(s)) && i !in tapped }
    }

    @Test fun aLongSessionOfRadiosDoesntRepeat() {
        val before = Session(noRepeats = false)
        val after = Session(noRepeats = true)
        val b = before.summary("Radios, 12 hours, before (1.8.1)")
        val a = after.summary("Radios, 12 hours, after (No repeats on)")
        assertTrue("1.8.1 repeats in this session, or it shows nothing (${before.repeated()})", before.repeated() > 5)
        assertEquals("nothing played twice", 0, after.repeated())
        assertEquals("the session ran its whole length", after.want, after.played.size)
        assertEquals(0, after.cameRound)
        assertTrue("no worse a spread of artists (${a.artists} vs ${b.artists})", a.artists >= b.artists - 1)
        assertTrue("no longer run of one artist (${a.longestRun} vs ${b.longestRun})", a.longestRun <= b.longestRun + 1)
    }

    @Test fun aRadioThatRunsDryCarriesOnWithNoRepeats() {
        // One page each and no new radio started by hand. 1.8.1 stops dead once the page is used up.
        val before = Session(noRepeats = false, pages = 1, newRadioEvery = Int.MAX_VALUE)
        val after = Session(noRepeats = true, pages = 1, newRadioEvery = Int.MAX_VALUE)
        before.summary("Dry radio, before (1.8.1)")
        after.summary("Dry radio, after (No repeats on)")
        assertTrue("1.8.1 stops (${before.stoppedAt})", before.stoppedAt != null && before.stoppedAt!! < 40)
        assertTrue("it keeps going (${after.played.size})", after.stoppedAt == null && after.played.size == after.want)
        assertEquals(0, after.repeated())
    }

    @Test fun whenTheWholeWorldIsHeardSongsComeRoundRatherThanSilence() {
        // A YouTube of 40 songs and a session of 200: it must come round, not stop.
        val songs = (1..40).map { i -> hits.getValue(i % 60 + 1).first() }
        val long = Session(noRepeats = true, want = 200, newRadioEvery = Int.MAX_VALUE, only = songs)
        long.summary("Radios past the end of the world (No repeats on)")
        assertEquals("the music didn't stop", long.want, long.played.size)
        assertTrue("it came round (${long.cameRound})", long.cameRound > 0)
        val firstAgain = long.played.withIndex().first { (i, s) -> long.played.take(i).any { it.id == s.id } }.index
        assertTrue("not before everything had played once ($firstAgain)", firstAgain >= songs.size - 1)
    }

    @Test fun tuningMovesARadioByWhatItKnows() {
        val plain = Session(noRepeats = false)
        val p = plain.summary("Radios, untuned")
        val toNew = Session(noRepeats = false, tune = RadioTune(newness = 1f), signals = RadioSignals(heard = { everHeard.has(it) }))
        val n = toNew.summary("Radios, all the way to new")
        val toFamiliar = Session(noRepeats = false, tune = RadioTune(newness = -1f), signals = RadioSignals(heard = { everHeard.has(it) }))
        val f = toFamiliar.summary("Radios, all the way to familiar")
        assertTrue("towards new (${n.neverPlayed} vs ${p.neverPlayed})", n.neverPlayed > p.neverPlayed)
        assertTrue("towards familiar (${f.neverPlayed} vs ${p.neverPlayed})", f.neverPlayed < p.neverPlayed)

        val top = hits.values.flatten().map { it.id }.toSet()
        fun hitShare(s: Session) = s.played.count { it.id in top }.toDouble() / s.played.size
        val deep = Session(noRepeats = false, tune = RadioTune(depth = 1f), signals = RadioSignals(onTopList = { it.id in top }))
        deep.summary("Radios, all the way to deep cuts")
        val hitsOnly = Session(noRepeats = false, tune = RadioTune(depth = -1f), signals = RadioSignals(onTopList = { it.id in top }))
        hitsOnly.summary("Radios, all the way to hits")
        println("[discover]   share of hits: untuned %.0f%%, deep %.0f%%, hits %.0f%%".format(hitShare(plain) * 100, hitShare(deep) * 100, hitShare(hitsOnly) * 100))
        assertTrue(hitShare(deep) < hitShare(plain))
        assertTrue(hitShare(hitsOnly) > hitShare(plain))

        // Untouched with nothing to go by: a calm-energetic slider on songs not scanned changes nothing at all.
        val unscanned = Session(noRepeats = false, tune = RadioTune(energy = 1f))
        unscanned.run()
        assertEquals("unscanned songs aren't moved", plain.played.map { it.id }, unscanned.played.map { it.id })
    }
}
