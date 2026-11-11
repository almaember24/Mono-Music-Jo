package com.prism.music

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reddit, 1.8.1, Prism TV: "The first song plays, but the next shows 'Video Not Available'", the player at 0:00, paused,
 * YouTube's own words in a toast. A song YouTube refuses on every client is passed over: the music moves on to the next
 * one and says so, however it was reached; with nothing after it, it says so and stops, not looking as if it were
 * loading. Every /player answer here is YouTube's refusal; the songs around it are files on the TV.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvUnplayableTests {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val made = ArrayList<android.net.Uri>()
    private val c get() = Fixture.container
    private val player get() = c.player
    private val said = java.util.Collections.synchronizedList(ArrayList<String>())
    private val watching = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)

    private val refusal = """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Video unavailable"}}"""
    private val bare = Song("unplayable1", "A refused song", listOf(ArtistRef("Test Artist", null)), durationSec = 200)

    @Before fun setUp() {
        TvFixture.assumeTv()
        Fixture.ready()
        TvStress.mute()
        TvStress.grantAudio()
        for (host in listOf("https://www.youtube.com", "https://music.youtube.com", "https://youtubei.googleapis.com", "https://m.youtube.com")) {
            RecordedNetwork.answers["$host/youtubei/v1/player"] = refusal
        }
        watching.launch { player.errors.collect { said += it } }
    }

    @After fun cleanUp() {
        RecordedNetwork.answers.clear()
        watching.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        runCatching { instrumentation.runOnMainSync { player.stop() } }
        runBlocking { c.settings.editNow { it.remove(stringPreferencesKey("cookie")) } }
        made.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    private fun tones(vararg titles: String, seconds: Int = 40): List<Song> {
        titles.forEach { made += Fixture.tone(it, seconds) }
        return TvStress.songs(titles.toList())
    }

    private fun signIn() = runBlocking {
        c.settings.editNow { it[stringPreferencesKey("cookie")] = "SAPISID=test; __Secure-3PAPISID=test; SID=test" }
        kotlinx.coroutines.withTimeout(5_000) { while (!c.settings.current.isLoggedIn) kotlinx.coroutines.delay(20) }
    }

    private fun play(songs: List<Song>, from: Int = 0) {
        instrumentation.runOnMainSync { player.playQueue(songs, from, QueueSource(QueueKind.OTHER, "TV refused test")) }
    }

    private fun onNow() = player.currentSong.value?.title

    /** Pressed Next onto the refused song: the one after it plays, and the refusal is said. */
    @Test fun nextOntoARefusedSongMovesOnToTheOneAfter() {
        val (one, two) = tones("Prism TV refused before", "Prism TV refused after")
        play(listOf(one, bare, two))
        TvStress.waitFor(20_000, "the first song playing") { player.isPlaying.value && onNow() == one.title }
        instrumentation.runOnMainSync { player.next() }
        TvStress.waitFor(60_000, { "the song after the refused one, on ${onNow()}, said $said" }) { onNow() == two.title && player.isPlaying.value }
        assertTrue("it said so ($said)", said.isNotEmpty())
    }

    /** The report: nothing pressed, the first song ends, the next is refused. */
    @Test fun theFirstSongEndingIntoARefusedOneMovesOnToTheOneAfter() {
        val one = tones("Prism TV refused ends", seconds = 24).single()
        val two = tones("Prism TV refused then").single()
        play(listOf(one, bare, two))
        TvStress.waitFor(90_000, { "the song after the refused one, on ${onNow()}, said $said" }) { onNow() == two.title && player.isPlaying.value }
        // Reading ahead, the failure comes to the player as the ending song's: said once, of the song it's about.
        assertEquals("said once ($said)", 1, said.size)
        assertTrue("of the refused song ($said)", said[0].contains(bare.title))
    }

    /**
     * The same with a music video on (Keep playing videos): the next song is lined up as its video, and that's refused
     * too. It falls back to the song; refused as well, it's passed over; the song that ended isn't touched.
     */
    @Test fun aRefusedNextVideoIsPassedOverToo() {
        val one = tones("Prism TV refused video ends", seconds = 24).single()
        val two = tones("Prism TV refused video then").single()
        play(listOf(one, bare, two))
        TvStress.waitFor(20_000, "the first song playing") { player.isPlaying.value && onNow() == one.title }
        instrumentation.runOnMainSync { player.player!!.replaceMediaItem(1, bare.toMediaItem("unplayablevideo")) }
        TvStress.waitFor(120_000, { "the song after the refused one, on ${onNow()}, said $said" }) { onNow() == two.title && player.isPlaying.value }
        assertTrue("of the refused song ($said)", said.any { it.contains(bare.title) })
        assertTrue("nothing said of the song that ended ($said)", said.none { it.contains(one.title) })
    }

    /** Back onto a song refused a moment ago (the refusal's remembered: [PlaybackErrorsTest]): said again, on again. */
    @Test fun aRefusedSongGoneBackToMovesOnAgain() {
        signIn()
        val (one, two) = tones("Prism TV refused again before", "Prism TV refused again after")
        play(listOf(one, bare, two))
        TvStress.waitFor(20_000, "the first song playing") { player.isPlaying.value && onNow() == one.title }
        instrumentation.runOnMainSync { player.next() }
        TvStress.waitFor(120_000, { "past it, on ${onNow()}, said $said" }) { onNow() == two.title && player.isPlaying.value }
        val first = said.size
        // Back onto it: it's turned down at once (well under the first go's many asks), and on to the next again.
        val started = System.currentTimeMillis()
        instrumentation.runOnMainSync { player.skipTo(1) }
        TvStress.waitFor(30_000, { "past it again, on ${onNow()}, said $said" }) { said.size > first && onNow() == two.title && player.isPlaying.value }
        android.util.Log.i("PrismTest", "second go took ${System.currentTimeMillis() - started} ms")
    }

    /** Nothing queued after the refused song, Autoplay still finding songs: they're waited for, and the music goes on. */
    @Test fun aRefusedLastSongWaitsForAutoplaysSongs() {
        val (one, two) = tones("Prism TV refused autoplay", "Prism TV refused autoplay came")
        play(listOf(one, bare))
        TvStress.waitFor(20_000, "the first song playing") { player.isPlaying.value && onNow() == one.title }
        // (What Autoplay looked for as the first song started has found nothing by now.)
        TvStress.waitFor(20_000, "Autoplay done") { !c.queue.autoplayLoading }
        instrumentation.runOnMainSync { c.queue.autoplayLoading = true; player.next() }
        TvStress.waitFor(60_000, { "the refusal said, on ${onNow()}" }) { said.isNotEmpty() }
        Thread.sleep(3_000)
        assertEquals("it waits", bare.title, onNow())
        instrumentation.runOnMainSync {
            player.player!!.addMediaItem(two.toMediaItem())
            c.queue.autoplayLoading = false
        }
        TvStress.waitFor(20_000, { "Autoplay's song, on ${onNow()}, said $said" }) { onNow() == two.title && player.isPlaying.value }
    }

    /** As the photo: signed in, every account client's refusal is "Video unavailable". */
    @Test fun signedInEveryClientRefusesAndTheMusicMovesOn() {
        signIn()
        val one = tones("Prism TV refused signed in", seconds = 24).single()
        val two = tones("Prism TV refused signed in after").single()
        play(listOf(one, bare, two))
        TvStress.waitFor(120_000, { "the song after the refused one, on ${onNow()}, said $said" }) { onNow() == two.title && player.isPlaying.value }
        assertTrue("YouTube's words or Prism's, said ($said)", said.isNotEmpty())
    }

    /** Several refused in a row: passed over one after another to the next that plays. */
    @Test fun severalRefusedInARowAllMoveOn() {
        val (one, two) = tones("Prism TV refused row before", "Prism TV refused row after")
        val refused = (2..4).map { bare.copy(id = "unplayable$it", title = "Refused $it") }
        play(listOf(one) + refused + two)
        TvStress.waitFor(20_000, "the first song playing") { player.isPlaying.value && onNow() == one.title }
        instrumentation.runOnMainSync { player.next() }
        TvStress.waitFor(120_000, { "the song after them, on ${onNow()}, said $said" }) { onNow() == two.title && player.isPlaying.value }
    }

    /** Nothing after the refused song: it says so and stops; not stuck loading, and Previous still works. */
    @Test fun aRefusedLastSongSaysSoAndStops() {
        val one = tones("Prism TV refused only").single()
        play(listOf(one, bare))
        TvStress.waitFor(20_000, "the first song playing") { player.isPlaying.value && onNow() == one.title }
        instrumentation.runOnMainSync { player.next() }
        TvStress.waitFor(60_000, { "the refusal said, on ${onNow()}" }) { said.any { it.contains(bare.title) } }
        Thread.sleep(2_000)
        assertEquals("it stays on the last song", bare.title, onNow())
        assertTrue("it doesn't look as if it's loading", !player.buffering.value)
        assertTrue("nothing's playing", !player.isPlaying.value)
        instrumentation.runOnMainSync { player.previous() }
        TvStress.waitFor(20_000, { "Previous brings the first song back, on ${onNow()}" }) { onNow() == one.title && player.isPlaying.value }
    }

    /** A song that was skipped past: the failure already in hand doesn't then jump the song the listener picked. */
    @Test fun aPickMadeWhileARefusalIsInHandIsKept() {
        val (one, two, three) = tones("Prism TV refused pick a", "Prism TV refused pick b", "Prism TV refused pick c")
        play(listOf(one, bare, two, three))
        TvStress.waitFor(20_000, "the first song playing") { player.isPlaying.value && onNow() == one.title }
        instrumentation.runOnMainSync { player.next() }
        TvStress.waitFor(60_000, { "the refusal said, on ${onNow()}" }) { said.isNotEmpty() }
        // Straight after it, the listener picks the song right after it themselves: the move still waiting would then
        // have gone one further.
        instrumentation.runOnMainSync { player.skipTo(2) }
        Thread.sleep(4_000)
        assertEquals("the pick was kept", two.title, onNow())
        assertTrue(player.isPlaying.value)
    }

    /** Next pressed while it stands stopped on a refused song (nothing was next then; a song's been added since). */
    @Test fun nextFromAStoppedRefusalPlays() {
        val (one, two) = tones("Prism TV refused stopped", "Prism TV refused stopped next")
        play(listOf(one, bare))
        TvStress.waitFor(20_000, "the first song playing") { player.isPlaying.value && onNow() == one.title }
        instrumentation.runOnMainSync { player.next() }
        TvStress.waitFor(60_000, { "stopped on it, on ${onNow()}, said $said" }) { said.any { it.contains(bare.title) } }
        Thread.sleep(1_000)
        instrumentation.runOnMainSync {
            player.player!!.addMediaItem(two.toMediaItem())
            player.next()
        }
        TvStress.waitFor(20_000, { "the song Next went to, on ${onNow()}" }) { onNow() == two.title && player.isPlaying.value }
    }
}
