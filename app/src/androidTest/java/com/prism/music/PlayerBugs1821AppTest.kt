package com.prism.music

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.DeckPart
import com.prism.music.data.prefs.ImmersiveLyrics
import com.prism.music.data.prefs.LyricsAlign
import com.prism.music.data.prefs.PlayerPart
import com.prism.music.playback.HistoryShuffleOrder
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * 1.8.2.1's player round, on the real player and service with silent tones as songs (no network): Play next and the queue
 * (#368), the current lyric's place in the deck (#361), and translations in Immersive mode (#358).
 */
@RunWith(AndroidJUnit4::class)
class PlayerBugs1821AppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val tones = ArrayList<android.net.Uri>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val player get() = Fixture.container.player
    private val settings get() = Fixture.container.settings
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @After fun cleanUp() {
        runCatching { main { player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        runBlocking { settings.setMix(false) }
    }

    /** Prism open, with a tone per title as a song on the phone. */
    private fun songs(vararg titles: String, seconds: Int = 60): List<Song> {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        titles.forEach { tones += Fixture.tone(it, seconds) }
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        return titles.map { t -> local.tracks.value.first { it.song.title == t }.song }
    }

    private fun start(list: List<Song>, shuffle: Boolean = false, at: Int = 0) {
        main { player.playQueue(list, at, QueueSource(QueueKind.OTHER, "Queue test"), shuffle = shuffle) }
        compose.waitUntil(20_000) { player.currentSong.value != null && player.isPlaying.value }
    }

    /** What's coming, in the order it will really play. */
    private fun upcoming(): List<String> {
        var out = emptyList<String>()
        main {
            val p = player.player!!
            val t = p.currentTimeline
            val list = ArrayList<String>()
            var i = t.getNextWindowIndex(p.currentMediaItemIndex, androidx.media3.common.Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            while (i != androidx.media3.common.C.INDEX_UNSET) {
                list += p.getMediaItemAt(i).mediaMetadata.title.toString()
                i = t.getNextWindowIndex(i, androidx.media3.common.Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            }
            out = list
        }
        return out
    }

    private fun position(): Long { var p = 0L; main { p = player.position }; return p }


    // ------------------------------------------------------------------ #368 Play next

    /** A song new to the queue, in order and shuffled: straight after the one playing, and Next plays it. */
    @Test fun playNextOfANewSongIsWhatNextPlays() {
        val s = songs("Prism next tone A", "Prism next tone B", "Prism next tone C", "Prism next tone X")
        start(s.take(3))
        main { player.playNext(s[3]) }
        Thread.sleep(300)
        assertEquals(listOf("Prism next tone X", "Prism next tone B", "Prism next tone C"), upcoming())
        main { player.next() }
        compose.waitUntil(10_000) { player.currentSong.value?.id == s[3].id }
        assertEquals(listOf("Prism next tone B", "Prism next tone C"), upcoming())
    }

    /** #368: tapped on a song already coming up, it moves up: one copy of it, and Next plays it. */
    @Test fun playNextOfASongAlreadyComingUpMovesIt() {
        val s = songs("Prism move tone A", "Prism move tone B", "Prism move tone C", "Prism move tone D")
        start(s)
        main { player.playNext(s[2]) }
        Thread.sleep(300)
        assertEquals(listOf("Prism move tone C", "Prism move tone B", "Prism move tone D"), upcoming())
        main { player.next() }
        compose.waitUntil(10_000) { player.currentSong.value?.id == s[2].id }
        assertEquals(listOf("Prism move tone B", "Prism move tone D"), upcoming())
        assertEquals("Previous goes back to the song before", s[0].id, run { main { player.previous(); player.previous() }; Thread.sleep(800); player.currentSong.value?.id })
    }

    /** #368, shuffled: the same, in the shuffled order. */
    @Test fun playNextWhileShuffledComesNextWithNoCopy() {
        val s = songs("Prism shuffle tone A", "Prism shuffle tone B", "Prism shuffle tone C", "Prism shuffle tone D", "Prism shuffle tone E")
        start(s, shuffle = true)
        val coming = upcoming()
        assertEquals(4, coming.size)
        val pick = coming.last()
        main { player.playNext(s.first { it.title == pick }) }
        Thread.sleep(300)
        assertEquals(listOf(pick) + coming.dropLast(1), upcoming())
        // Through the session too (the notification, a car, headphones): it lists the queue in play order.
        val token = SessionToken(Fixture.context, android.content.ComponentName(Fixture.context, com.prism.music.playback.PlaybackServices.target(Fixture.context)))
        val controller = MediaController.Builder(Fixture.context, token).setApplicationLooper(android.os.Looper.getMainLooper()).buildAsync().get(10, java.util.concurrent.TimeUnit.SECONDS)
        try {
            Thread.sleep(500)
            main { controller.seekToNext() }
            compose.waitUntil(10_000) { player.currentSong.value?.title == pick }
        } finally {
            main { controller.release() }
        }
    }

    /** Crossfade on at 8 s over 60 s tones: the blend comes at 0:52, the tail playing along from 0:47. */
    private fun comingUpToABlend(s: List<Song>, shuffle: Boolean, at: Int = 0) {
        runBlocking {
            settings.setMix(true); settings.setMixAuto(false); settings.setMixSeconds(8)
            settings.setMixBeatMatch(false)
        }
        start(s, shuffle = shuffle, at = at)
        if (shuffle) main {
            // The next song's place in the list isn't straight after the playing one's, as after any real shuffle.
            val p = player.player!!
            val cur = p.currentMediaItemIndex
            val rest = (0 until p.mediaItemCount).filter { it != cur }.sortedDescending()
            p.setShuffleOrder(HistoryShuffleOrder((listOf(cur) + rest).toIntArray()) { p.currentMediaItemIndex })
        }
        main { player.seekTo(40_000) }
        val until = System.currentTimeMillis() + 25_000
        while (position() < 48_000 && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue("the tail's playing along by now (${position()} ms)", position() in 48_000..51_500)
    }

    /** #368: Play next as a blend is coming: the blend goes into that song, not the one it was planned for. */
    @Test fun playNextJustBeforeABlendBlendsIntoIt() {
        val s = songs("Prism blend tone A", "Prism blend tone B", "Prism blend tone C", "Prism blend tone X")
        comingUpToABlend(s.take(3), shuffle = false)
        main { player.playNext(s[3]) }
        compose.waitUntil(15_000) { player.currentSong.value?.id != s[0].id }
        assertEquals(s[3].id, player.currentSong.value?.id)
        Thread.sleep(300)
        assertEquals("it comes in as a blend (it was cut off, the next song jumping to full volume)", s[3].id, Fixture.container.mixState.into.value?.songId)
    }

    /** #368, shuffled: the planned next song's place in the list had moved, so the blend went to another song altogether. */
    @Test fun playNextJustBeforeABlendWhileShuffledBlendsIntoIt() {
        val s = songs("Prism sblend tone A", "Prism sblend tone B", "Prism sblend tone C", "Prism sblend tone D", "Prism sblend tone X")
        comingUpToABlend(s.take(4), shuffle = true)
        val before = player.currentSong.value?.id
        main { player.playNext(s[4]) }
        compose.waitUntil(15_000) { player.currentSong.value?.id != before }
        assertEquals("the song lined up, not whichever took the old next's place", s[4].title, player.currentSong.value?.title)
    }

    /** A played song taken out just before a blend: every place moves down one, and the blend must still find its song. */
    @Test fun aQueueEditJustBeforeABlendDoesntChangeWhatComesNext() {
        val s = songs("Prism edit tone A", "Prism edit tone B", "Prism edit tone C", "Prism edit tone D")
        comingUpToABlend(s, shuffle = false, at = 1)
        main { player.removeAt(0) }
        compose.waitUntil(15_000) { player.currentSong.value?.id != s[1].id }
        assertEquals("the song next in the queue, not the one after it", s[2].id, player.currentSong.value?.id)
        Thread.sleep(300)
        assertEquals(s[2].id, Fixture.container.mixState.into.value?.songId)
    }

    /** The queue's rows, top to bottom, under its title, without the playing song. */
    private fun rows(prefix: String, playing: String): List<String> {
        compose.waitForIdle()
        val queueTop = compose.onAllNodes(hasText(tr(R.string.queue))).fetchSemanticsNodes().first().boundsInRoot.top
        return compose.onAllNodes(hasText(prefix, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top > queueTop }
            .sortedBy { it.boundsInRoot.top }
            .mapNotNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.firstOrNull()?.text }
            .filter { it != playing }
    }

    private fun openPlayer() {
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        Thread.sleep(2_000)
        main { player.setPlaying(false) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("playerDeck")).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * #368: the queue shows songs in the order they'll play. A song added to the queue used to be listed first while it
     * plays after the list's own songs (so Next seemed to skip it), and a song with a second copy further down had that
     * copy listed beside the first.
     */
    @Test fun theQueueShowsTheOrderTheSongsPlayIn() {
        val s = songs("Prism row tone A", "Prism row tone B", "Prism row tone C", "Prism row tone D", "Prism row tone X", "Prism row tone Y")
        start(s.take(4))
        main { player.addToQueue(s[4]) }
        openPlayer()
        compose.clickWhenFound(hasContentDescription(tr(R.string.up_next)) and hasClickAction())
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(tr(R.string.queue))).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(600)
        // (As many as fit on the screen: the list only draws those.)
        fun shownInPlayOrder(): List<String> {
            val shown = rows("Prism row tone", "Prism row tone A")
            // (A shorter phone only has room for one row under the headings, with the controls showing.)
            assertTrue("rows to look at: $shown", shown.isNotEmpty())
            assertEquals(upcoming().take(shown.size), shown)
            return shown
        }
        shownInPlayOrder()
        // Play next on a song added to the queue, on one of the list's own, and on a new one: the first row is what Next plays.
        main { player.playNext(s[2]) }
        Thread.sleep(600)
        shownInPlayOrder()
        main { player.playNext(s[5]) }
        Thread.sleep(600)
        val shown = shownInPlayOrder()
        assertEquals("no song twice", shown.distinct(), shown)
        assertEquals("Prism row tone Y", shown.first())
    }

    // ------------------------------------------------------------------ #361 the current lyric in the deck

    private fun bounds(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().first().boundsInRoot

    /**
     * #361: with the current lyric moved between the controls and the buttons, and room kept for two lines, the line sits
     * in the middle of the gap (it stayed at the bottom of its room, against the buttons).
     */
    @Test fun theCurrentLyricMovedInTheDeckSitsMidwayBetweenItsNeighbours() {
        val lrc = (0 until 12).joinToString("\n") { i -> "[00:%02d.00]Sung line number ${i + 1}".format(i * 8) }
        val s = songs("Prism deck tone", seconds = 120)
        runBlocking {
            Fixture.container.db.lyrics().put(LyricsEntity(s[0].id, "LRCLIB", true, lrc, System.currentTimeMillis()))
            settings.setPlayerDeck(listOf(DeckPart.SCRUBBER, DeckPart.TRANSPORT, DeckPart.STRIP, DeckPart.VOLUME, DeckPart.BUTTONS))
            settings.setPartShown(PlayerPart.VOLUME, false)
            settings.setLyricsStripTwoLines(true)
            settings.setLyricsStripAlign(LyricsAlign.CENTER)
        }
        start(s)
        openPlayer()
        val line = hasText("Sung line number", substring = true)
        compose.waitUntil(15_000) { compose.onAllNodes(line, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500)
        val text = compose.onAllNodes(line, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        val play = compose.onAllNodes(hasTestTag("playDisc")).fetchSemanticsNodes().first().boundsInRoot
        val buttons = compose.onAllNodes(hasContentDescription(tr(R.string.up_next)) and hasClickAction()).fetchSemanticsNodes().first().boundsInRoot
        val density = Fixture.context.resources.displayMetrics.density
        val above = (text.top - play.bottom) / density
        val below = (buttons.top - text.bottom) / density
        assertTrue("the line sits between them: $above dp above it, $below dp below it", kotlin.math.abs(above - below) < 22f && below > 14f)
    }

    // ------------------------------------------------------------------ #358 translations in Immersive mode

    /** What the translator keeps on the phone for [lines], so no request is made. */
    private fun translationKept(lines: List<String>, to: String, from: String, translated: List<String?>) {
        val digest = MessageDigest.getInstance("SHA-1").digest((to + "\n" + lines.joinToString("\n")).toByteArray())
        val key = digest.joinToString("") { "%02x".format(it) }
        val dir = File(Fixture.context.filesDir, "translations").apply { mkdirs() }
        val json = com.prism.music.data.innertube.InnerTube.json.encodeToString(
            com.prism.music.data.lyrics.LyricsTranslator.Translation.serializer(),
            com.prism.music.data.lyrics.LyricsTranslator.Translation(from, to, translated),
        )
        File(dir, "$key.json").writeText(json)
    }

    private fun immersiveSong(title: String, sung: List<String>, to: String = "es", from: String = "en", translated: List<String?>): Song {
        val lrc = sung.mapIndexed { i, l -> "[00:%02d.00]%s".format(i * 20, l) }.joinToString("\n")
        val s = songs(title, seconds = 120)[0]
        runBlocking {
            Fixture.container.db.lyrics().put(LyricsEntity(s.id, "LRCLIB", true, lrc, System.currentTimeMillis()))
            settings.setLyricsTranslate(true)
            settings.setLyricsTranslateTo(to)
            settings.setImmersivePlayer(true)
        }
        translationKept(sung, to, from, translated)
        return s
    }

    /** #358: the listener's translation under the sung line, each way Immersive mode shows its lyrics; none when off. */
    @Test fun immersiveModeShowsTheTranslation() {
        val sung = listOf("The first made up line", "The second made up line", "The third made up line")
        val s = immersiveSong("Prism immersive tone", sung, translated = listOf("La primera linea inventada", "La segunda linea inventada", "La tercera linea inventada"))
        start(listOf(s))
        openPlayerImmersive()
        val translated = hasText("La primera linea inventada")
        compose.waitUntil(15_000) { compose.onAllNodes(translated, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        Fixture.screenshot("immersive-translation-low")
        runBlocking { settings.setImmersiveLyrics(ImmersiveLyrics.FULL) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("La segunda linea inventada"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        Fixture.screenshot("immersive-translation-full")
        runBlocking { settings.setImmersiveLyrics(ImmersiveLyrics.MIDDLE); settings.setLyricsTranslate(false) }
        compose.waitUntil(10_000) { compose.onAllNodes(translated, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    /** #358: long lines and right-to-left ones stay on screen and the translation with them; a song in the same language adds nothing. */
    @Test fun immersiveTranslationCopesWithLongAndRightToLeftLines() {
        val long = "A very long made up line that goes on and on past the edge of any phone and then a good deal further still, so that it wraps a few times over"
        val arabic = "هذا سطر مختلق طويل بعض الشيء للتجربة"
        val sung = listOf(arabic, long, "A short line after it")
        val s = immersiveSong(
            "Prism rtl tone", sung, from = "ar",
            translated = listOf("Esta es una linea inventada para probar", "$long (traducida, igual de larga y con unas cuantas palabras de mas)", "Una linea corta"),
        )
        start(listOf(s))
        openPlayerImmersive()
        val first = hasText("Esta es una linea inventada para probar")
        compose.waitUntil(15_000) { compose.onAllNodes(first, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val height = Fixture.context.resources.displayMetrics.heightPixels.toFloat()
        fun onScreen(m: androidx.compose.ui.test.SemanticsMatcher) = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.let { it.top >= 0f && it.bottom <= height }
        assertTrue("the translation of a right-to-left line is on screen", onScreen(first))
        Fixture.screenshot("immersive-translation-rtl")
        // The long line, Low on the screen, over the controls' room: all of it on screen.
        main { player.seekTo(21_000) }
        val second = hasText("(traducida", substring = true)
        compose.waitUntil(10_000) { compose.onAllNodes(second, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(800)
        assertTrue("the long line's translation is on screen", onScreen(second))
        Fixture.screenshot("immersive-translation-long")
    }

    /** #358: a song already in the listener's language shows nothing extra. */
    @Test fun immersiveShowsNoTranslationForASongInThatLanguage() {
        val sung = listOf("Una linea en espanol", "Otra linea en espanol")
        val s = immersiveSong("Prism same tone", sung, to = "es", from = "es", translated = listOf("Linea traducida de mas", "Otra traducida de mas"))
        start(listOf(s))
        openPlayerImmersive()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Una linea en espanol"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1_000)
        assertTrue(compose.onAllNodes(hasText("Linea traducida de mas"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    private fun openPlayerImmersive() {
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        Thread.sleep(2_000)
        main { player.setPlaying(false) }
        Thread.sleep(500)
    }
}
