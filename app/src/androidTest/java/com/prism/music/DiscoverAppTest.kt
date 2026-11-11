package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.toEntity
import com.prism.music.data.foryou.Discover
import com.prism.music.data.foryou.Heard
import com.prism.music.data.foryou.RadioTune
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Finding music you haven't heard (1.8.2) in the app itself: "No repeats this session" (#159) with the real player and a
 * fake radio, also with the switch off (where the songs do come back, which is the report), and New to you and On this
 * day on the real Home. Deep cuts needs real artist and album pages: DiscoverLiveTest reads those. These songs have no
 * stream, so the player moves on through them.
 */
@RunWith(AndroidJUnit4::class)
class DiscoverAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = Fixture.container

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> onMain(read: () -> T): T { var out: T? = null; main { out = read() }; @Suppress("UNCHECKED_CAST") return out as T }
    private fun queue(): List<String> = onMain { val p = c.player.player!!; (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId } }

    @After fun cleanUp() {
        RecordedNetwork.answering = null
        runCatching { main { c.player.stop() } }
    }

    private fun song(id: String) = Song(id, "Song $id", listOf(ArtistRef("Artist $id")), durationSec = 190)
    private fun songs(prefix: String, n: Int) = (1..n).map { song("$prefix${it.toString().padStart(4, '0')}") }

    // ---- A radio as YouTube answers it.

    private fun row(s: Song) = """{"playlistPanelVideoRenderer":{"videoId":"${s.id}","title":{"runs":[{"text":"${s.title}"}]},
        "longBylineText":{"runs":[{"text":"${s.primaryArtist}"}]},"lengthText":{"runs":[{"text":"${s.durationSec / 60}:${(s.durationSec % 60).toString().padStart(2, '0')}"}]}}}"""

    private fun page(list: List<Song>) =
        """{"contents":{"playlistPanelRenderer":{"contents":[${list.joinToString(",", transform = ::row)}]}}}"""

    /** Answers every radio with what [radio] says of its seed. Anything else stays offline. */
    private fun radios(radio: (String) -> List<Song>?) {
        RecordedNetwork.answering = { url, body ->
            val seed = Regex("\"playlistId\":\"RDAMVM([^\"]+)\"").find(body)?.groupValues?.get(1)
            if ("/next" in url && seed != null) radio(seed)?.let(::page) else null
        }
    }

    private fun start(noRepeats: Boolean) {
        Fixture.ready()
        Fixture.launch()
        compose.waitUntil(20_000) { onMain { c.player.player != null } }
        if (noRepeats) {
            c.settings.setAutoplayNoRepeats(true)
            compose.waitUntil(5_000) { c.settings.current.behavior.autoplayNoRepeats }
        }
    }

    /** [list] played and skipped through, as a listener going through a playlist. */
    private fun heard(list: List<Song>) {
        main { c.player.playQueue(list, 0, QueueSource(QueueKind.PLAYLIST, "Earlier")) }
        compose.waitUntil(30_000) {
            val all = list.all { c.sessionHeard.has(it) }
            if (!all) main { c.player.next() }
            all
        }
        compose.waitUntil(10_000) { !c.queue.autoplayLoading }
    }

    /** A new radio on [seed]: what's before it is gone from the queue, so the queue can't say what was heard. */
    private fun radioOn(seed: Song) = main { c.player.playQueue(listOf(seed), 0, QueueSource(QueueKind.RADIO, "Radio", seedId = seed.id)) }

    @Test fun aSongSkippedAtOnceCountsAsHeardEvenWithTheSwitchOff() {
        start(noRepeats = false)
        val list = songs("skip", 3)
        heard(list)
        assertTrue(list.all { c.sessionHeard.has(it) })
        assertTrue("and it's no play in the history", runBlocking { c.db.plays().eventsSince(0) }.none { e -> list.any { it.id == e.songId } })
    }

    @Test fun withTheSwitchOnARadioLeavesOutWhatsBeenPlayed() {
        start(noRepeats = true)
        val before = songs("hear", 4)
        val fresh = songs("frsh", 12)
        val seed = song("seed0001")
        radios { before + fresh }
        heard(before)
        radioOn(seed)
        compose.waitUntil(20_000) { queue().any { it.startsWith("frsh") } }
        assertTrue("heard songs came back: ${queue()}", queue().none { it.startsWith("hear") })
    }

    /** The report in #159: as 1.8.1 had it, and as it stays with the switch off. */
    @Test fun withTheSwitchOffTheyComeBackAsBefore() {
        start(noRepeats = false)
        val before = songs("hear", 4)
        val fresh = songs("frsh", 12)
        radios { before + fresh }
        heard(before)
        radioOn(song("seed0001"))
        compose.waitUntil(20_000) { queue().any { it.startsWith("frsh") } }
        assertTrue("the old way shows the songs again: ${queue()}", queue().any { it.startsWith("hear") })
    }

    @Test fun aRadioStartedByHandLeavesThemOutToo() {
        start(noRepeats = true)
        val before = songs("hear", 2)
        val fresh = songs("frsh", 8)
        heard(before)
        val list = listOf(fresh[0], before[0]) + fresh.drop(1) + before[1]
        main { c.player.playQueue(list, 0, QueueSource(QueueKind.RADIO, "Radio", seedId = fresh[0].id)) }
        compose.waitUntil(5_000) { queue().firstOrNull() == fresh[0].id }
        assertTrue(queue().none { it.startsWith("hear") })
    }

    @Test fun aRadioThatRunsDryMovesOnToAnotherSong() {
        start(noRepeats = true)
        val before = songs("hear", 4)
        val fresh = songs("frsh", 12)
        val seed = song("seed0001")
        radios { from -> if (from == seed.id) before else fresh }
        heard(before)
        radioOn(seed)
        compose.waitUntil(20_000) { queue().any { it.startsWith("frsh") } }
        assertTrue(queue().none { it.startsWith("hear") })
        val moved = onMain { c.queue.reseededFor(c.queue.source.value) }
        assertTrue("the queue goes on with the other song's radio ($moved)", moved != null && moved != seed.id)
    }

    /** Nothing new anywhere: the music goes on, with the same song twice in the queue and the player none the worse. */
    @Test fun withNothingNewLeftSongsComeRound() {
        start(noRepeats = true)
        val before = songs("hear", 6)
        radios { before }
        heard(before)
        radioOn(song("seed0001"))
        compose.waitUntil(45_000) {
            val q = queue()
            if (q.size == q.toSet().size) main { c.player.next() }
            q.size > q.toSet().size
        }
        assertTrue("still has a song", onMain { c.player.currentSong.value != null })
        val count = queue().size
        repeat(3) { main { c.player.next() }; Thread.sleep(300) }
        assertTrue("and it can still move through them", queue().size >= count)
    }

    @Test fun noConnectionIsNoReasonToRepeat() {
        start(noRepeats = true)
        val before = songs("hear", 4)
        heard(before)
        // No answers at all (every radio: 503).
        radioOn(song("seed0001"))
        Thread.sleep(4_000)
        assertTrue("nothing came round: ${queue()}", queue().none { it.startsWith("hear") })
    }

    /** #299's limit lives in the top-up's rules now: Autoplay's own songs only, switch on or off. */
    private fun lengthLimit(noRepeats: Boolean) {
        start(noRepeats)
        c.settings.setAutoplayMaxMinutes(10)
        compose.waitUntil(5_000) { c.settings.current.behavior.autoplayMaxMinutes == 10 }
        val long = songs("long", 4).map { it.copy(durationSec = 3_000) }
        val fresh = songs("frsh", 12)
        radios { long + fresh }
        // Autoplay after a playlist: the long songs stay out.
        main { c.player.playQueue(songs("list", 2), 0, QueueSource(QueueKind.PLAYLIST, "Mine")) }
        compose.waitUntil(20_000) { queue().any { it.startsWith("frsh") } }
        assertTrue("Autoplay added a long song: ${queue()}", queue().none { it.startsWith("long") })
        compose.waitUntil(10_000) { !c.queue.autoplayLoading }
        // A radio the listener started: left as it is.
        radioOn(song("seed0001"))
        compose.waitUntil(20_000) { queue().any { it.startsWith("long") } }
    }

    @Test fun autoplaysLengthLimitHoldsAndARadioIsLeftAsItIs() = lengthLimit(noRepeats = false)

    @Test fun autoplaysLengthLimitHoldsWithNoRepeatsToo() = lengthLimit(noRepeats = true)

    // ---- The switch and the sliders.

    @Test fun theSwitchIsFoundAndWorks() {
        Fixture.ready()
        Fixture.launch()
        compose.openSettings()
        // Only part of its name: the search box holding the whole of it would match below.
        compose.onNode(hasSetTextAction()).performTextInput(tr(R.string.no_repeats_title).dropLast(3))
        compose.clickWhenFound(hasText(tr(R.string.no_repeats_title)) and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        assertFalse("off until it's turned on", c.settings.current.behavior.autoplayNoRepeats)
        compose.clickWhenFound(hasText(tr(R.string.no_repeats_title)) and hasClickAction())
        compose.waitUntil(5_000) { c.settings.current.behavior.autoplayNoRepeats }
    }

    @Test fun theSlidersStartInTheMiddleAndSayWhatTheyGoBy() {
        Fixture.ready()
        Fixture.launch()
        assertTrue(c.settings.current.behavior.radioTune.neutral)
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput(tr(R.string.tune_calm_energetic).dropLast(3))
        compose.clickWhenFound(hasText(tr(R.string.tune_calm_energetic)) and hasClickAction())
        compose.waitForText(tr(R.string.tune_as_it_comes))
        compose.waitForText(tr(R.string.tune_hits_deep))
        c.settings.setRadioTune(RadioTune(energy = 1f))
        compose.waitForText(tr(R.string.tune_measured_songs, c.mixTempo.measuredCount()))
        Fixture.screenshot("discover-sliders")
    }

    // ---- Home.

    private fun onHome(key: String, title: String) {
        val s = c.settings
        s.setHomeLayout(listOf(HomeSectionConfig(key, title)) + s.current.homeLayout)
        compose.waitUntil(5_000) { s.current.homeLayout.first().key == key }
    }

    private fun played(s: Song, day: LocalDate) = runBlocking {
        c.db.songs().upsert(s.toEntity(null))
        val at = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        c.db.plays().insert(PlayEventEntity(songId = s.id, timestamp = at, playedMs = 180_000))
    }

    private fun gone(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()

    @Test fun aHomeFromBeforeHasNoneOfThem() {
        Fixture.ready()
        val keys = c.settings.current.homeLayout.map { it.key }
        assertTrue(keys.none { it in listOf(HomeSections.NEW_TO_YOU, HomeSections.DEEP_CUTS, HomeSections.ON_THIS_DAY) })
    }

    @Test fun customizeHomeOffersThem() {
        Fixture.ready()
        Fixture.launch()
        compose.clickWhenFound(hasContentDescription(tr(R.string.customize_home_2)), 20_000)
        compose.clickWhenFound(hasText(tr(R.string.add_a_section)) and hasClickAction())
        // The list is longer than a small phone's screen (the Android 9 emulator): each is scrolled to.
        fun scrolledTo(title: String) {
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).onLast().performScrollToNode(hasText(title))
        }
        scrolledTo(tr(R.string.section_new_to_you))
        compose.waitForText(tr(R.string.section_new_to_you))
        scrolledTo(tr(R.string.section_deep_cuts))
        compose.waitForText(tr(R.string.section_deep_cuts))
        scrolledTo(tr(R.string.section_on_this_day))
        compose.clickWhenFound(hasText(tr(R.string.section_on_this_day)) and hasClickAction())
        compose.waitUntil(5_000) { c.settings.current.homeLayout.any { it.key == HomeSections.ON_THIS_DAY && it.visible } }
    }

    @Test fun onThisDayShowsAYearAgoToday() {
        Fixture.ready()
        val song = Fixture.songs(1, "A year ago").first()
        played(song, LocalDate.now().minusYears(1))
        onHome(HomeSections.ON_THIS_DAY, tr(R.string.section_on_this_day))
        Fixture.launch()
        compose.waitForText(tr(R.string.section_on_this_day), 20_000)
        compose.waitForText(song.title)
        Fixture.screenshot("discover-on-this-day")
        // It plays.
        compose.clickWhenFound(hasText(song.title) and hasClickAction())
        compose.waitUntil(10_000) { onMain { c.player.currentSong.value?.id } == song.id }
    }

    @Test fun withNothingThatDayOnThisDayStaysAway() {
        Fixture.ready()
        val song = Fixture.songs(1, "Another day").first()
        played(song, LocalDate.now().minusYears(1).minusDays(2))
        onHome(HomeSections.ON_THIS_DAY, tr(R.string.section_on_this_day))
        Fixture.launch()
        compose.waitForText("Quick picks", 20_000)
        Thread.sleep(3_000)
        assertTrue(gone(tr(R.string.section_on_this_day)))
        assertTrue(c.discover.onThisDaySongs(LocalDate.now()).isEmpty())
    }

    @Test fun withNothingToGoOnTheMixesStayAway() {
        Fixture.ready()
        onHome(HomeSections.NEW_TO_YOU, tr(R.string.section_new_to_you))
        onHome(HomeSections.DEEP_CUTS, tr(R.string.section_deep_cuts))
        Fixture.launch()
        compose.waitForText("Quick picks", 20_000)
        Thread.sleep(3_000)
        assertTrue(gone(tr(R.string.section_new_to_you)))
        assertTrue(gone(tr(R.string.section_deep_cuts)))
    }

    /** The whole way: a history on the phone, radios from YouTube, the mix on Home and its page, and none of it heard. */
    @Test fun newToYouIsMadeFromTheRadiosOfWhatYouPlay() {
        Fixture.ready()
        val known = songs("know", 8)
        known.forEach { played(it, LocalDate.now().minusDays(200)) }
        val fresh = songs("frsh", 30)
        radios { known + fresh }
        onHome(HomeSections.NEW_TO_YOU, tr(R.string.section_new_to_you))
        Fixture.launch()
        compose.waitForText(tr(R.string.section_new_to_you), 30_000)
        val mix = runBlocking { c.forYou.mix(Discover.NEW) }!!
        val heard = Heard.of(known.map { it.id }, known.map { it.title to it.primaryArtist })
        assertTrue("enough for a mix (${mix.songs.size})", mix.songs.size >= 20)
        assertTrue("even a song played 200 days ago isn't new: ${mix.songs.map { it.id }}", mix.songs.none { heard.has(it) })
        assertEquals("not in Made for you", null, c.forYou.mixes.value?.firstOrNull { Discover.isDiscover(it.id) })
        compose.waitForText(mix.songs.first().title)
        Fixture.screenshot("discover-new-to-you")
        // Its heading opens the mix's page.
        compose.clickWhenFound(hasText(tr(R.string.section_new_to_you)) and hasClickAction())
        compose.waitForText(tr(R.string.new_to_you_line))
        Thread.sleep(800)
        Fixture.screenshot("discover-new-to-you-page")
    }

    @Test fun aMixMadeOnceStaysForTheWeek() {
        Fixture.ready()
        val known = songs("know", 6)
        known.forEach { played(it, LocalDate.now().minusDays(30)) }
        var asks = 0
        radios { asks++; known + songs("frsh", 30) }
        runBlocking { c.discover.refresh(Discover.NEW) }
        val first = runBlocking { c.forYou.mix(Discover.NEW) }!!.songs.map { it.id }
        assertTrue(first.isNotEmpty() && asks > 0)
        val after = asks
        runBlocking { c.discover.refresh(Discover.NEW) }
        assertEquals("asked nothing more", after, asks)
        assertEquals(first, runBlocking { c.forYou.mix(Discover.NEW) }!!.songs.map { it.id })
    }
}
