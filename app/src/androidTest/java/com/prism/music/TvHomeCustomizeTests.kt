package com.prism.music

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.TvFixture.awaitFocus
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.HomeSections
import com.prism.music.ui.tv.TAG_HOME
import com.prism.music.ui.tv.TAG_HOME_EDITOR
import com.prism.music.ui.tv.TAG_NAV
import com.prism.music.ui.tv.TAG_SETTINGS
import com.prism.music.ui.tv.TvHomeLayout
import com.prism.music.ui.tv.TvHomeShelves
import com.prism.music.ui.tv.TvSection
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prism TV's Customize Home (1.8.0, the owner: "You should be able to customize the homescreen"), with the remote
 * only: from the end of Home, a row hidden with OK (in the phone's Home layout too), one carried up with Move and one with OK held; Home then shows them
 * so; and from Settings, Back to default brings Home back as it came.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvHomeCustomizeTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()
    private val settings get() = Fixture.container.settings
    private val a = HomeSections.shelfKey("Prism test A")
    private val b = HomeSections.shelfKey("Prism test B")
    private val c = HomeSections.shelfKey("Prism test C")

    @Before fun setUp() {
        TvFixture.assumeTv()
        Fixture.ready()
        // A day outside the holidays: no holiday row (it comes in November and December, after Quick picks).
        val zone = java.time.ZoneId.systemDefault()
        Fixture.container.seasonal.clock = java.time.Clock.fixed(java.time.LocalDateTime.of(2026, 7, 10, 12, 0).atZone(zone).toInstant(), zone)
        // Home's shelves as YouTube would send them (fresh for this run, so nothing is asked of the network).
        val songs = Fixture.songs(6)
        TvHomeShelves.keep(
            null,
            listOf("A" to 0, "B" to 2, "C" to 4).map { (n, i) -> Shelf("Prism test $n", items = songs.subList(i, i + 2).map(::SongItem)) },
            System.currentTimeMillis(),
        )
    }

    @After fun backToToday() { Fixture.container.seasonal.clock = null }

    private val inNav = SemanticsMatcher("in the side navigation") { n ->
        n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith("$TAG_NAV/") == true
    }

    private fun goTo(section: TvSection) {
        compose.awaitFocus(15_000)
        for (i in 0 until 6) {
            if (compose.onAllNodes(inNav and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
            TvFixture.left(); Thread.sleep(300)
        }
        // The navigation opens on the place shown: up to its top (Home), then down to [section].
        repeat(TvSection.entries.size) { TvFixture.up(); Thread.sleep(150) }
        repeat(section.ordinal) { TvFixture.down(); Thread.sleep(200) }
        compose.awaitFocusOn(hasTestTag("$TAG_NAV/${section.name}"))
        TvFixture.ok()
    }

    private fun titleOf(r: Int): String? =
        compose.onAllNodes(hasTestTag("$TAG_HOME/title$r"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString { it.text }

    /**
     * Home's row titles, top to bottom. A TV screen holds about two rows and Home only draws what's on it, so the list
     * is scrolled to each further row in turn (no remote key, so focus stays where it is), then back to its top.
     */
    private fun homeTitles(): List<String> {
        val seen = sortedMapOf<Int, String>()
        val home = compose.onNode(hasTestTag(TAG_HOME))
        for (r in 0 until 8) {
            if (titleOf(r) == null) runCatching { home.performScrollToNode(hasTestTag("$TAG_HOME/title$r")) }
            titleOf(r)?.let { seen[r] = it } ?: break
        }
        runCatching { home.performScrollToIndex(0) }
        return seen.values.toList()
    }

    /** Waits until Home shows [titles] (polling: each look scrolls the list). */
    private fun awaitHomeTitles(titles: List<String>, ms: Long) {
        val end = System.currentTimeMillis() + ms
        var got = homeTitles()
        while (got != titles) {
            if (System.currentTimeMillis() > end) throw AssertionError("Home shows $got, not $titles")
            Thread.sleep(300)
            got = homeTitles()
        }
    }

    private fun keys() = settings.current.tv.homeLayout.map { it.key }

    private fun waitFor(ms: Long, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not after $ms ms: $what (layout ${settings.current.tv.homeLayout})")
            Thread.sleep(100)
        }
    }

    private fun row(key: String) = hasTestTag("$TAG_HOME_EDITOR/row/$key")

    /** Down through the editor's list until [key]'s row has focus (the list holds every row the TV can have). */
    private fun downTo(key: String) {
        for (i in 0 until 30) {
            if (compose.onAllNodes(row(key) and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
            TvFixture.down(); Thread.sleep(200)
        }
        compose.awaitFocusOn(row(key))
    }

    @Test fun aRowHiddenAndTwoMovedShowSoOnHome() {
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/row0/0"), 15_000)
        awaitHomeTitles(listOf("Prism test A", "Prism test B", "Prism test C"), 5_000)
        // Down past the rows: Customize Home, at Home's end.
        repeat(3) { TvFixture.down(); Thread.sleep(250) }
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/customize"))
        TvFixture.ok()

        // The editor opens on its first row (the hero); every row the TV can have follows, YouTube's shelves last.
        compose.awaitFocusOn(row(TvHomeLayout.HERO), 10_000)
        downTo(a)
        // OK hides A.
        TvFixture.ok()
        waitFor(5_000, "A hidden") { com.prism.music.ui.tv.hiddenIn(settings.current.homeLayout, a) }

        // C, carried up twice with Move beside it, put down with OK. Each press passes one row Home shows: B, then
        // (past A, hidden now, and the rows Home doesn't show) to the top.
        TvFixture.down(); Thread.sleep(250); TvFixture.down()
        compose.awaitFocusOn(row(c))
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME_EDITOR/move/$c"))
        TvFixture.ok(); Thread.sleep(300)
        TvFixture.up(); Thread.sleep(300); TvFixture.up()
        waitFor(5_000, "C above A") { keys().indexOf(c) in 0 until keys().indexOf(a) }
        // Carried, focus stayed on C.
        compose.awaitFocusOn(hasTestTag("$TAG_HOME_EDITOR/move/$c"))
        TvFixture.ok(); Thread.sleep(300)

        // B, picked up by holding OK, carried up once (past A, hidden, to just above C, the row above it on Home), put
        // down with OK.
        TvFixture.left()
        compose.awaitFocusOn(row(c))
        // C is at the top now: down the list to B.
        downTo(b)
        TvFixture.hold(KeyEvent.KEYCODE_DPAD_CENTER, 1_500); Thread.sleep(300)
        TvFixture.up()
        waitFor(5_000, "B above C") { keys().indexOf(b) in 0 until keys().indexOf(c) }
        compose.awaitFocusOn(row(b))
        TvFixture.ok(); Thread.sleep(300)
        // Put down: Down moves focus again (to C, under it), not B.
        TvFixture.down()
        compose.awaitFocusOn(row(c))
        assertEquals(listOf(b, c, a), keys().filter { it == a || it == b || it == c })

        // Back: Home, as laid out (A gone, B before C).
        TvFixture.back()
        awaitHomeTitles(listOf("Prism test B", "Prism test C"), 10_000)
        compose.awaitFocus()
    }

    @Test fun settingsOpensTheEditorAndBackToDefaultPutsHomeBack() {
        // C moved to the top on the TV; A hidden (in the phone's Home layout, which the TV shares).
        settings.setTvHomeLayout(listOf(
            com.prism.music.data.prefs.HomeSectionConfig(c, "Prism test C"),
            com.prism.music.data.prefs.HomeSectionConfig(a, "Prism test A"),
        ))
        settings.setHomeLayout(com.prism.music.ui.tv.layoutWithShown(settings.current.homeLayout, a, "Prism test A", shown = false))
        waitFor(5_000, "the layouts kept") { keys().isNotEmpty() && com.prism.music.ui.tv.hiddenIn(settings.current.homeLayout, a) }
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/row0/0"), 15_000)
        awaitHomeTitles(listOf("Prism test C", "Prism test B"), 5_000)

        // Settings → Home (after Account, Language, Sound, Lyrics, Sing and Ambient screen) → Customize Home.
        goTo(TvSection.SETTINGS)
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/0"), 10_000)
        for (g in 1..6) { TvFixture.down(); compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/$g")) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(com.prism.music.tr(R.string.customize_home)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/0"))
        TvFixture.ok()

        // The editor opens on its first row (the hero, then C as moved); Up: Back to default.
        compose.awaitFocusOn(row(TvHomeLayout.HERO), 10_000)
        TvFixture.up()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME_EDITOR/reset"))
        TvFixture.ok()
        waitFor(5_000, "no layout kept, A shown") { keys().isEmpty() && !com.prism.music.ui.tv.hiddenIn(settings.current.homeLayout, a) }
        // Back to Settings, then Home: as it came.
        TvFixture.back()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/0"), 10_000)
        TvFixture.back()
        awaitHomeTitles(listOf("Prism test A", "Prism test B", "Prism test C"), 10_000)
    }
    /**
     * The owner's second round (1.8.0): "I do not see things like Prism Music mixes … that match my phone app". A Prism
     * mix made on this TV shows in Made for you (on a fresh install's layout, as on the phone) and OK plays it.
     */
    @Test fun aPrismMixShowsInMadeForYouAndPlays() {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        val tones = listOf(Fixture.tone("Prism TV mix one", 90), Fixture.tone("Prism TV mix two", 90))
        try {
            val local = Fixture.container.localMusic
            kotlinx.coroutines.runBlocking { local.scan() }
            val songs = listOf("Prism TV mix one", "Prism TV mix two").map { t -> local.tracks.value.first { it.song.title == t }.song }
            kotlinx.coroutines.runBlocking { Fixture.container.forYou.saveMix("daily:1", "Prism test mix", "With Test Artist", listOf("Test Artist"), 0, songs) }
            assertEquals(true, HomeSections.initial.any { it.key == HomeSections.MADE_FOR_YOU })
            TvFixture.launch()
            // Made for you comes before YouTube's shelves, as the phone's layout has it.
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Prism test mix"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.awaitFocusOn(hasTestTag("$TAG_HOME/row0/0"), 15_000)
            assertEquals(com.prism.music.tr(R.string.made_for_you), titleOf(0))
            TvFixture.ok()
            compose.awaitFocusOn(hasTestTag("${com.prism.music.ui.tv.TAG_PLAYER}/playpause"), 15_000)
            val player = Fixture.container.player
            waitFor(15_000, "the mix playing") { player.currentSong.value?.id == songs[0].id }
            // The mix's two songs, then only what autoplay adds (it tops a queue this short up from your own music).
            assertEquals(songs.map { it.id }, player.queue.value.take(2).map { it.song.id })
            assertEquals(true, player.queue.value.size == 2 || Fixture.container.queue.autoplayStart.value == 2)
        } finally {
            runCatching { instrumentation.runOnMainSync { Fixture.container.player.stop() } }
            tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        }
    }
}