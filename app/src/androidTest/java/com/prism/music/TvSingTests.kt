package com.prism.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.TvFixture.awaitFocus
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.ui.tv.TAG_ACTIONS
import com.prism.music.ui.tv.TAG_HOME
import com.prism.music.ui.tv.TAG_LYRICS
import com.prism.music.ui.tv.TAG_NAV
import com.prism.music.ui.tv.TAG_PLAYER
import com.prism.music.ui.tv.TAG_SETTINGS
import com.prism.music.ui.tv.TAG_SING
import com.prism.music.ui.tv.TvSection
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sing on more TVs (1.8.x): a TV whose check found it too slow for Sing's separation, or whose processor Sing's engine
 * doesn't run on, is offered the quick way, said plainly in Settings → Sing, rather than told it can't sing.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvSingTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Before fun setUp() { TvFixture.assumeTv() }

    private val inNav = androidx.compose.ui.test.SemanticsMatcher("in the side navigation") { n ->
        n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith("$TAG_NAV/") == true
    }

    @Test fun aTvTooSlowForTheSeparationIsOfferedTheQuickWay() {
        Fixture.ready()
        val noEngine = com.prism.music.data.ModelStore.Model.singRuntime() == null
        // An earlier check's answer: too slow (on a TV the engine doesn't run on, that's the answer without a check).
        if (!noEngine) Fixture.context.getSharedPreferences("tv_sing", android.content.Context.MODE_PRIVATE).edit().putString("state", "SLOW").commit()
        TvFixture.launch()
        compose.awaitFocus(15_000)
        for (i in 0 until 6) {
            if (compose.onAllNodes(inNav and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
            TvFixture.left(); Thread.sleep(300)
        }
        repeat(TvSection.SETTINGS.ordinal) { TvFixture.down(); Thread.sleep(200) }
        compose.awaitFocusOn(hasTestTag("$TAG_NAV/${TvSection.SETTINGS.name}"))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/0"), 10_000)
        // Down to Sing: Account, Language, Sound, Lyrics, Sing.
        repeat(4) { TvFixture.down(); Thread.sleep(250) }
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/4"))
        val answer = tr(if (noEngine) R.string.tv_sing_no_cpu else R.string.tv_sing_too_slow)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(answer, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // And Sing is offered: it's in the player's ⋯ for both answers.
        org.junit.Assert.assertTrue(com.prism.music.ui.tv.TvSing.state.value.sings)
    }

    /**
     * The owner: Sing on the TV "just doesn't do anything". From the player's ⋯, Sing's page: Sing on, a line saying
     * how the song's got ready (or why not), and Right from the lyrics reaches the key's − and +, which move the key.
     */
    @Test fun singsPageSaysWhatItsDoingAndItsKeyIsReached() {
        Fixture.ready()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val c = Fixture.container
        val noEngine = com.prism.music.data.ModelStore.Model.singRuntime() == null
        if (!noEngine) Fixture.context.getSharedPreferences("tv_sing", android.content.Context.MODE_PRIVATE).edit().putString("state", "SLOW").commit()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        val tone = Fixture.tone("Prism TV sing tone", 90)
        try {
            runBlocking { c.localMusic.scan() }
            val song = c.localMusic.tracks.value.first { it.song.title == "Prism TV sing tone" }.song
            // Lyrics saved for it (as if found earlier): no network needed.
            val lrc = (0 until 10).joinToString("\n") { i -> "[00:%02d.00]Line number ${i + 1} of the tone".format(i * 8) }
            runBlocking { c.db.lyrics().put(com.prism.music.data.db.LyricsEntity(song.id, "LRCLIB", true, lrc, System.currentTimeMillis())) }
            instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.OTHER, "TV sing test")) }
            val end0 = System.currentTimeMillis() + 20_000
            while (!c.player.isPlaying.value && System.currentTimeMillis() < end0) Thread.sleep(100)
            // Paused: the screen is still while it's looked at.
            instrumentation.runOnMainSync { c.player.setPlaying(false) }
            instrumentation.runOnMainSync { c.sing.setSemitones(0) }
            TvFixture.launch()
            compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 20_000)
            TvFixture.ok()
            compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
            TvFixture.down()
            // Along the row to ⋯.
            for (i in 0 until 8) {
                if (compose.onAllNodes(hasTestTag("$TAG_PLAYER/more") and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
                TvFixture.right(); Thread.sleep(250)
            }
            compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/more"))
            TvFixture.ok()
            // A tone has no album or artist page: Sing is the ⋯'s first.
            compose.awaitFocusOn(hasTestTag("$TAG_ACTIONS/0"), 10_000)
            // (The label is a child of the button in the unmerged tree.)
            compose.onNode(hasText(tr(R.string.sing)) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("$TAG_ACTIONS/0")), useUnmergedTree = true).assertExists()
            TvFixture.ok()
            compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"), 20_000)
            org.junit.Assert.assertTrue("Sing is on", c.sing.on.value)
            // It says what it's doing.
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("$TAG_LYRICS/status"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            // Right: the key's −; OK lowers the key; Right, Right: +, raising it again.
            TvFixture.right()
            compose.awaitFocusOn(hasTestTag("$TAG_SING/keydown"))
            TvFixture.ok()
            compose.waitUntil(5_000) { c.sing.semitones.value == -1 }
            TvFixture.right()
            compose.awaitFocusOn(hasTestTag("$TAG_SING/keyup"))
            TvFixture.ok(); TvFixture.ok()
            compose.waitUntil(5_000) { c.sing.semitones.value == 1 }
            // Back: Sing off again, the song as it is.
            TvFixture.back()
            compose.waitUntil(10_000) { !c.sing.on.value }
        } finally {
            runCatching { instrumentation.runOnMainSync { c.sing.setSemitones(0); c.player.stop() } }
            runCatching { Fixture.context.contentResolver.delete(tone, null, null) }
        }
    }

    // ---------------------------------------------------------------- 1.8.1, Reddit: Sing on a Google TV Streamer
    // "Whenever I try to remove the vocals, the music stops playing and the player gets stuck loading. Even switching
    // back to normal playback doesn't help. I have to uninstall and reinstall the app to restore playback."

    private val instrumentation get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()

    private fun <T> main(block: () -> T): T { var r: T? = null; instrumentation.runOnMainSync { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    private fun waitFor(ms: Long, what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not after $ms ms: $what")
            Thread.sleep(100)
        }
    }

    /** Silent test tones on this TV, scanned in: the songs, in [titles]' order (removed again by [tones]' caller). */
    private fun tones(titles: List<String>, tones: MutableList<android.net.Uri>): List<com.prism.music.data.model.Song> {
        instrumentation.uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        titles.forEach { tones += Fixture.tone(it, 120) }
        runBlocking { Fixture.container.localMusic.scan() }
        return titles.map { t -> Fixture.container.localMusic.tracks.value.first { it.song.title == t }.song }
    }

    /**
     * Sing at work on [song] and holding on (holding what the player needs), then [song] played: it sits loading. Sing's
     * engine counts as there ([TestHooks.singEngineFails]: as on a TV it was downloaded to; its work holds before it's
     * needed).
     */
    private fun holdThenPlay(song: com.prism.music.data.model.Song, stuckMs: Long) {
        val c = Fixture.container
        TestHooks.singEngineFails = true
        TestHooks.singPassHangs = true
        instrumentation.runOnMainSync {
            c.sing.stuckMs = stuckMs
            c.sing.setOn(true)
            c.sing.prepare(listOf(song))
        }
        waitFor(10_000, "Sing holding the player") { TestHooks.singHolding }
        instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.OTHER, "TV sing test")) }
        waitFor(10_000, "the song loading") { c.player.buffering.value && c.player.currentSong.value?.id == song.id }
    }

    private fun cleanUp(tones: List<android.net.Uri>) {
        TestHooks.singEngineFails = false
        TestHooks.singPassHangs = false
        TestHooks.singHolding = false
        val c = Fixture.container
        runCatching { instrumentation.runOnMainSync { c.sing.setOn(false); c.sing.stuckMs = com.prism.music.playback.sing.SingWatch.STUCK_MS; c.sing.engineAgain(); c.player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    /**
     * Sing's engine failing as it loads (forced here; on a TV it can't run on, or with its files cut short): the song
     * plays on as it is, Sing's page says so, the quick way stands in from then on, and Back leaves the song playing.
     */
    @Test fun anEngineThatFailsLeavesTheSongPlayingAndSaysSo() {
        Fixture.ready()
        val c = Fixture.container
        val made = ArrayList<android.net.Uri>()
        try {
            TestHooks.singEngineFails = true
            val song = tones(listOf("Prism TV sing engine"), made).single()
            instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.OTHER, "TV sing test")) }
            waitFor(20_000, "playing") { c.player.isPlaying.value }
            // Paused while the screen is looked at (playing, the lyrics redraw all the time and the screen never settles
            // for the test); played again below to see it plays on.
            instrumentation.runOnMainSync { c.player.setPlaying(false) }
            TvFixture.launch()
            compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 20_000)
            TvFixture.ok()
            compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
            TvFixture.down()
            for (i in 0 until 8) {
                if (compose.onAllNodes(hasTestTag("$TAG_PLAYER/more") and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
                TvFixture.right(); Thread.sleep(250)
            }
            compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/more"))
            TvFixture.ok()
            compose.awaitFocusOn(hasTestTag("$TAG_ACTIONS/0"), 10_000)
            TvFixture.ok()
            compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"), 20_000)
            org.junit.Assert.assertTrue("Sing is on", c.sing.on.value)
            // The quick stand-in first, then the song's own separation, whose engine fails.
            val engine = tr(R.string.sing_engine_didn_t_start)
            waitFor(60_000, "the engine's failure said") { c.sing.problem.value?.contains(engine) == true }
            org.junit.Assert.assertTrue(c.sing.problem.value!!.contains(tr(R.string.sing_song_plays_as_it_is)))
            // Said on the page.
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(engine, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            // The quick way from now on, and the song plays (waited on without the screen, which moves while it plays).
            org.junit.Assert.assertEquals(com.prism.music.playback.sing.Separation.QUICK, c.sing.method.value)
            instrumentation.runOnMainSync { c.player.setPlaying(true) }
            waitFor(10_000, "playing again") { c.player.isPlaying.value }
            val at = main { c.player.position }
            waitFor(5_000, "the song moving on") { main { c.player.position } > at + 500 }
            // Back (paused again: the screen is waited on through the rule): Sing off, and the song plays as it is.
            instrumentation.runOnMainSync { c.player.setPlaying(false) }
            TvFixture.back()
            compose.waitUntil(10_000) { !c.sing.on.value }
            instrumentation.runOnMainSync { c.player.setPlaying(true) }
            waitFor(10_000, "playing after Sing") { c.player.isPlaying.value }
            val after = main { c.player.position }
            waitFor(5_000, "the song moving on after Sing") { main { c.player.position } > after + 500 }
        } finally {
            cleanUp(made)
        }
    }

    /**
     * Sing's work on a song holding what the player needs (as Sing held the TV's only audio decoder): the next song sits
     * loading. The watchdog lets Sing go after a while (3 s here, 12 in use): Sing switches off and says why, its work is
     * dropped, and the song loads again and plays.
     */
    @Test fun aSongSittingLoadingWhileSingWorksHasSingLetGo() {
        Fixture.ready()
        val c = Fixture.container
        val made = ArrayList<android.net.Uri>()
        try {
            val song = tones(listOf("Prism TV sing held"), made).single()
            holdThenPlay(song, stuckMs = 3_000)
            // The watchdog: Sing lets go, switches off and says why; the song plays.
            waitFor(10_000, "Sing let go") { !TestHooks.singHolding && !c.sing.on.value }
            org.junit.Assert.assertEquals(tr(R.string.sing_stopped_for_the_song), c.sing.problem.value)
            waitFor(10_000, "the song playing") { c.player.isPlaying.value && c.player.currentSong.value?.id == song.id }
        } finally {
            cleanUp(made)
        }
    }

    /** "Even switching back to normal playback doesn't help": switched off, Sing lets go at once and the song plays. */
    @Test fun switchingSingOffWhileTheSongSitsLoadingPlaysIt() {
        Fixture.ready()
        val c = Fixture.container
        val made = ArrayList<android.net.Uri>()
        try {
            val song = tones(listOf("Prism TV sing off"), made).single()
            // The watchdog far off: it's switching Sing off that does it here.
            holdThenPlay(song, stuckMs = 120_000)
            // Loading a few seconds (as the listener sees it, then switches Sing off).
            Thread.sleep(3_000)
            org.junit.Assert.assertTrue("still loading", c.player.buffering.value)
            instrumentation.runOnMainSync { c.sing.setOn(false) }
            waitFor(10_000, "the song playing") { c.player.isPlaying.value && c.player.currentSong.value?.id == song.id }
            org.junit.Assert.assertTrue("Sing let go", !TestHooks.singHolding)
        } finally {
            cleanUp(made)
        }
    }
}
