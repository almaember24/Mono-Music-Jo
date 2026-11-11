package com.prism.music

import com.prism.music.data.foryou.RadioSignals
import com.prism.music.data.foryou.RadioTopUp
import com.prism.music.data.foryou.RadioTune
import com.prism.music.data.foryou.RadioTuner
import com.prism.music.data.foryou.Variety
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.SessionHeard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "No repeats this session" (1.8.2, #159.4): what counts as heard, and how a top-up looks further when a radio runs out. */
class NoRepeatsTest {
    private fun song(id: String, artist: String = "A$id", title: String = "Song $id") = Song(id, title, listOf(ArtistRef(artist)), durationSec = 200)
    private fun songs(prefix: String, n: Int, artists: Int = n) = (1..n).map { song("$prefix$it", "$prefix-artist-${it % artists}") }

    private val noRules = RadioTopUp.Rules(emptySet(), emptySet(), { false }, { true })

    private fun noRepeats(
        heard: Set<String> = emptySet(),
        seeds: List<String> = emptyList(),
        ask: (suspend (String, String?) -> RadioTopUp.Page?)? = null,
        own: List<Song> = emptyList(),
        again: List<Song> = emptyList(),
        heardAt: Map<String, Long> = emptyMap(),
    ) = RadioTopUp.NoRepeats({ it.id in heard }, seeds, ask, { own }, { again }, { heardAt[it.id] })

    // --- What's heard

    @Test fun aSongStartedCountsHoweverBriefly() {
        val s = SessionHeard(now = { 1000L })
        val a = song("a")
        assertFalse(s.has(a))
        s.note(a)
        assertTrue(s.has(a))
        assertEquals(1000L, s.heardAt(a))
        assertNull(s.heardAt(song("b")))
    }

    @Test fun anotherUploadOfTheSongOrItsVideoCounts() {
        val s = SessionHeard(now = { 1000L })
        s.note(song("audio", "Queen", "Bohemian Rhapsody"), video = "videoId")
        assertTrue("another upload of it", s.has(song("other", "Queen", "Bohemian Rhapsody (Remastered 2011)")))
        assertTrue("its music video", s.has(song("videoId", "Queen Official", "Bohemian Rhapsody (Official Video)")))
        assertFalse("another song by them", s.has(song("x", "Queen", "Radio Ga Ga")))
        assertFalse("the same title by someone else", s.has(song("y", "Panic!", "Bohemian Rhapsody")))
    }

    @Test fun aTitleWithNothingToGoByOnlyCountsByItsId() {
        val s = SessionHeard(now = { 1000L })
        s.note(song("a", "", ""))
        assertFalse("two songs with no title and artist aren't the same song", s.has(song("b", "", "")))
        assertTrue(s.has(song("a", "", "")))
    }

    @Test fun nonLatinTitlesAreToldApart() {
        val s = SessionHeard(now = { 1000L })
        s.note(song("a", "아이유", "밤편지"))
        assertTrue(s.has(song("b", "아이유", "밤편지")))
        assertFalse(s.has(song("c", "아이유", "좋은 날")))
    }

    @Test fun aSessionEndsAfterSixHoursWithNothingStarted() {
        var t = 0L
        val s = SessionHeard(now = { t })
        val a = song("a")
        s.note(a)
        t = SessionHeard.IDLE_MS - 1
        assertTrue("just inside", s.has(a))
        s.note(song("b"))
        t += SessionHeard.IDLE_MS - 1
        assertTrue("each song started keeps the session going", s.has(a))
        t += 1
        assertFalse("six hours of nothing", s.has(a))
        assertTrue(s.oldestFirst().isEmpty())
    }

    @Test fun whatsHeardComesBackAfterARestartOnlyAsSaved() {
        var saved: Map<String, Long> = emptyMap()
        val first = SessionHeard(load = { saved }, save = { saved = it }, now = { 5L })
        first.note(song("a"))
        val after = SessionHeard(load = { saved }, now = { 6L })
        assertTrue(after.has(song("a")))
        assertTrue("the songs themselves aren't kept", after.oldestFirst().isEmpty())
        val tooLate = SessionHeard(load = { saved }, now = { SessionHeard.IDLE_MS + 5 })
        assertFalse(tooLate.has(song("a")))
    }

    @Test fun heardAgainMovesToTheBackOfTheLine() {
        var t = 1L
        val s = SessionHeard(now = { t })
        val a = song("a"); val b = song("b")
        s.note(a); t = 2; s.note(b); t = 3; s.note(a)
        assertEquals(listOf("b", "a"), s.oldestFirst().map { it.id })
    }

    @Test fun aRadioStartsOnItsSongAndLeavesOutTheHeard() {
        val s = SessionHeard(now = { 1L })
        val list = songs("r", 10)
        s.note(list[0]); s.note(list[3])
        val (kept, at) = s.radioStart(list, 0)
        assertEquals("the song it starts on stays, even heard", "r1", kept[at].id)
        assertEquals(9, kept.size)
        assertFalse(kept.any { it.id == "r4" })
        val (kept2, at2) = s.radioStart(list, 5)
        assertEquals("r6", kept2[at2].id)
    }

    @Test fun aRadioWithHardlyAnythingLeftStartsAsItIs() {
        val s = SessionHeard(now = { 1L })
        val list = songs("r", 10)
        list.drop(1).forEach(s::note)
        assertEquals(list to 0, s.radioStart(list, 0))
    }

    // --- Off: exactly 1.8.1

    /** 1.8.1's lines from [com.prism.music.playback.PlaybackService.maybeAutoplay], kept here as the baseline. */
    private fun oneEightOne(mine: List<Song>, r: List<Song>?, radio: Boolean, existing: Set<String>, heardLately: Set<String>, turnedDown: Set<String>, allowed: (Song) -> Boolean): List<Song> {
        val theirs = r.orEmpty().filter { it.id !in heardLately && it.id !in turnedDown }
        val freshShare = when {
            radio || mine.isEmpty() -> 1.0
            theirs.isEmpty() -> 0.0
            else -> 0.6
        }
        val songs = Variety.arrange(mine, theirs, 25, freshShare, perArtist = 3) { it.primaryArtist.lowercase() }
        return songs.distinctBy { it.id }.filter { it.id !in existing && allowed(it) }
    }

    @Test fun offAndUntunedItIsExactlyWhat181Did() = runBlocking {
        val rnd = java.util.Random(7)
        repeat(300) { round ->
            val pool = (1..200).map { song("s$it", "artist-${rnd.nextInt(12)}") }
            fun some(n: Int) = (0 until n).map { pool[rnd.nextInt(pool.size)] }.distinctBy { it.id }
            val mine = if (rnd.nextBoolean()) emptyList() else some(rnd.nextInt(16))
            val yt = if (rnd.nextInt(5) == 0) null else some(rnd.nextInt(60))
            val radio = rnd.nextBoolean()
            val existing = some(rnd.nextInt(30)).map { it.id }.toSet()
            val lately = some(rnd.nextInt(30)).map { it.id }.toSet()
            val down = some(rnd.nextInt(10)).map { it.id }.toSet()
            val refused = some(rnd.nextInt(10)).map { it.id }.toSet()
            val allowed: (Song) -> Boolean = { it.id !in refused }
            val rules = RadioTopUp.Rules(existing, lately, { it.id in down }, allowed)
            val page = yt?.let { RadioTopUp.Page(it, "next") }
            val answer = RadioTopUp.next(page, "seed", "before", mine, radio, rules)
            assertEquals("round $round", oneEightOne(mine, yt, radio, existing, lately, down, allowed), answer.add)
            assertEquals("the place in the radio, as 1.8.1 kept it", if (yt != null) "next" else "before", answer.continuation)
            assertEquals("seed", answer.seed)
            assertFalse(answer.cameRound)
        }
    }

    // --- On

    @Test fun nothingHeardGoesIn() = runBlocking {
        val page = RadioTopUp.Page(songs("y", 30), "more")
        val heard = (1..10).map { "y$it" }.toSet()
        val answer = RadioTopUp.next(page, "seed", null, songs("m", 10).take(5), true, noRules, noRepeats = noRepeats(heard = heard + "m1"))
        assertTrue(answer.add.isNotEmpty())
        assertTrue(answer.add.none { it.id in heard || it.id == "m1" })
    }

    @Test fun itReadsOnWhileThereAreTooFewNewSongs() = runBlocking {
        val pages = mutableListOf<String?>()
        val page1 = RadioTopUp.Page(songs("a", 25), "t2")
        val heard = page1.songs.map { it.id }.toSet()
        val answer = RadioTopUp.next(page1, "seed", null, emptyList(), true, noRules, noRepeats = noRepeats(heard = heard, ask = { s, t ->
            pages += t
            assertEquals("the same radio", "seed", s)
            RadioTopUp.Page(songs("b", 25), "t3")
        }))
        assertEquals("one more page was enough", listOf<String?>("t2"), pages)
        assertTrue(answer.add.all { it.id.startsWith("b") })
        assertEquals("t3", answer.continuation)
        assertEquals("seed", answer.seed)
    }

    @Test fun aDryRadioMovesOnToAnotherSongsRadio() = runBlocking {
        val asked = mutableListOf<Pair<String, String?>>()
        val dry = RadioTopUp.Page(songs("a", 25), null)
        val answer = RadioTopUp.next(dry, "seed", "old", emptyList(), true, noRules, noRepeats = noRepeats(
            heard = dry.songs.map { it.id }.toSet(), seeds = listOf("seed", "other", "third"),
            ask = { s, t -> asked += s to t; RadioTopUp.Page(songs("o", 20), "o2") },
        ))
        assertEquals("not itself again, and from its first page", listOf<Pair<String, String?>>("other" to null), asked)
        assertTrue(answer.add.isNotEmpty())
        assertEquals("the queue goes on with that radio", "other", answer.seed)
        assertEquals("o2", answer.continuation)
        assertFalse(answer.cameRound)
    }

    @Test fun whenYouTubeHasNothingNewPrismsOwnRadioFollows() = runBlocking {
        val dry = RadioTopUp.Page(songs("a", 25), null)
        val heard = dry.songs.map { it.id }.toSet() + "m1"
        val answer = RadioTopUp.next(dry, "seed", null, emptyList(), true, noRules, noRepeats = noRepeats(
            heard = heard, ask = { _, _ -> error("no other song to ask") }, own = songs("m", 6),
        ))
        assertEquals((2..6).map { "m$it" }.toSet(), answer.add.map { it.id }.toSet())
        assertFalse(answer.cameRound)
    }

    @Test fun onlyWithNothingAnywhereDoSongsComeRoundOldestHalfOnly() = runBlocking {
        val dry = RadioTopUp.Page(songs("a", 4), null)
        val heard = dry.songs.map { it.id }.toSet() + (1..10).map { "h$it" }
        val past = (1..10).map { song("h$it", "h$it") }
        val at = past.withIndex().associate { (i, s) -> s.id to i.toLong() + 100 }
        val answer = RadioTopUp.next(dry, "seed", null, emptyList(), true, noRules, noRepeats = noRepeats(heard = heard, ask = { _, _ -> null }, again = past.reversed(), heardAt = at))
        assertTrue(answer.cameRound)
        assertEquals((1..5).map { "h$it" }, answer.add.map { it.id })
    }

    @Test fun whatComesRoundStillObeysDislikes() {
        val past = (1..4).map { song("h$it") }
        val rules = RadioTopUp.Rules(emptySet(), emptySet(), { false }, { it.id != "h1" })
        val out = RadioTopUp.comingRound(past, { it.id.drop(1).toLong() }, rules)
        assertEquals(listOf("h2", "h3"), out.map { it.id })
    }

    @Test fun noConnectionIsNoReasonToStartRepeating() = runBlocking {
        var asks = 0
        val answer = RadioTopUp.next(null, "seed", "place", emptyList(), true, noRules, noRepeats = noRepeats(
            seeds = listOf("other", "third", "fourth"), ask = { _, _ -> asks++; null }, again = songs("h", 10),
        ))
        assertTrue(answer.add.isEmpty())
        assertFalse(answer.cameRound)
        assertEquals("not every seed in turn on a dead connection", 1, asks)
        assertEquals("keeps its place for next time", "place", answer.continuation)
        assertEquals("seed", answer.seed)
    }

    @Test fun aPageThatWontComeDoesntLoseThePlace() = runBlocking {
        val heard = songs("a", 25).map { it.id }.toSet()
        val answer = RadioTopUp.next(RadioTopUp.Page(songs("a", 25), "t2"), "seed", null, songs("m", 8), true, noRules, noRepeats = noRepeats(heard = heard, ask = { _, _ -> null }))
        assertTrue("its own songs carry on", answer.add.isNotEmpty())
        assertEquals("t2", answer.continuation)
        assertEquals("seed", answer.seed)
    }

    @Test fun offlineItsOwnMusicThenWhatComesRound() = runBlocking {
        // Offline nothing is asked (no [ask]), and with nothing new it isn't "YouTube couldn't be reached" that stops it.
        val past = songs("h", 6)
        val answer = RadioTopUp.next(null, "seed", null, emptyList(), false, noRules, noRepeats = noRepeats(again = past))
        assertTrue(answer.cameRound)
        assertEquals(3, answer.add.size)
    }

    @Test fun itNeverAsksWithoutLimit() = runBlocking {
        var asks = 0
        val heard = (1..1000).map { "z$it" }.toSet()
        RadioTopUp.next(RadioTopUp.Page(emptyList(), "t"), "seed", null, emptyList(), true, noRules, noRepeats = noRepeats(heard = heard, ask = { _, _ ->
            asks++
            RadioTopUp.Page((1..25).map { song("z${asks * 25 + it}") }, "t")
        }))
        assertEquals(RadioTopUp.MAX_ASKS, asks)
    }

    @Test fun otherSeedsAreOtherArtistsNewestFirstAndRealSongs() {
        val session = listOf(
            song("old", "Old"), song("cur2", "Current"), song("twice1", "Twice"), song("twice2", "Twice"),
            Song("local:1", "File", listOf(ArtistRef("Files")), durationSec = 200),
            Song("ep", "Episode", listOf(ArtistRef("Show")), durationSec = 200, isEpisode = true),
            Song("live", "Stream", listOf(ArtistRef("Station")), durationSec = 0, isLive = true),
            song("cur", "Current"),
        )
        assertEquals(listOf("twice2", "old", "cur2"), RadioTopUp.otherSeeds(session, song("cur", "Current")))
        assertEquals(listOf("twice2", "old"), RadioTopUp.otherSeeds(session, song("cur", "Current"), count = 2))
    }

    // --- Tuning

    @Test fun neutralLeavesTheListAlone() {
        val list = songs("s", 20)
        assertTrue(RadioTuner.apply(list, RadioTune(), RadioSignals(heard = { true }), 1) === list)
        assertEquals(0.6, RadioTuner.freshShare(0.6, RadioTune()), 0.0)
        assertTrue(RadioTune().neutral)
    }

    @Test fun whatThePhoneDoesntKnowDoesntMoveASong() {
        val list = songs("s", 40)
        val tune = RadioTune(1f, 1f, 1f)
        assertEquals("nothing known: every song stays, in YouTube's order", list, RadioTuner.apply(list, tune, RadioSignals(), 3))
        assertEquals(1.0, RadioTuner.chance(list[0], tune, RadioSignals()), 0.0)
    }

    @Test fun eachSliderGoesByItsOwnSignal() {
        val s = song("s")
        assertEquals("all the way to new, heard goes", 0.0, RadioTuner.chance(s, RadioTune(newness = 1f), RadioSignals(heard = { true })), 1e-9)
        assertEquals("new leads", 2.0, RadioTuner.chance(s, RadioTune(newness = 1f), RadioSignals(heard = { false })), 1e-9)
        assertEquals("all the way to familiar, unheard goes", 0.0, RadioTuner.chance(s, RadioTune(newness = -1f), RadioSignals(heard = { false })), 1e-9)
        assertEquals("deep: a hit goes", 0.0, RadioTuner.chance(s, RadioTune(depth = 1f), RadioSignals(onTopList = { true })), 1e-9)
        assertEquals("hits: a hit leads", 2.0, RadioTuner.chance(s, RadioTune(depth = -1f), RadioSignals(onTopList = { true })), 1e-9)
        assertEquals("energetic: a lively scanned song leads", 2.0, RadioTuner.chance(s, RadioTune(energy = 1f), RadioSignals(energy = { 1.0 })), 1e-9)
        assertEquals("energetic: a calm scanned song goes", 0.0, RadioTuner.chance(s, RadioTune(energy = 1f), RadioSignals(energy = { 0.0 })), 1e-9)
        assertEquals("in between it says nothing", 1.0, RadioTuner.chance(s, RadioTune(energy = 1f), RadioSignals(energy = { 0.5 })), 1e-9)
        assertEquals("a slider left alone ignores its signal", 1.0, RadioTuner.chance(s, RadioTune(newness = 1f), RadioSignals(onTopList = { true }, energy = { 0.0 })), 1e-9)
    }

    @Test fun aRadioTunedAllTheWayStillHasSongsLeft() {
        val list = songs("s", 20)
        val out = RadioTuner.apply(list, RadioTune(newness = 1f), RadioSignals(heard = { true }), 5)
        assertEquals(RadioTuner.MIN_LEFT, out.size)
    }

    @Test fun energyComesFromTheScanOnly() {
        assertNull("not scanned", RadioTuner.energyOf(null, null))
        assertEquals(0.0, RadioTuner.energyOf(60.0, -30f)!!, 1e-9)
        assertEquals(1.0, RadioTuner.energyOf(180.0, -2f)!!, 1e-9)
        assertEquals("tempo alone", 0.5, RadioTuner.energyOf(120.0, null)!!, 1e-9)
        assertEquals("loudness alone", 0.5, RadioTuner.energyOf(null, -13f)!!, 1e-9)
    }

    @Test fun theFamiliarEndTakesMoreOfYourOwn() {
        assertEquals(1.0, RadioTuner.freshShare(0.6, RadioTune(newness = 1f)), 1e-9)
        assertEquals(0.12, RadioTuner.freshShare(0.6, RadioTune(newness = -1f)), 1e-6)
    }
}
