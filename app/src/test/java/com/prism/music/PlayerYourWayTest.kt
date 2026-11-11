package com.prism.music

import com.prism.music.data.SongWaves
import com.prism.music.data.looks.BuiltInLooks
import com.prism.music.data.prefs.DeckPart
import com.prism.music.data.prefs.PlayerButton
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.data.prefs.PlayerLayout
import com.prism.music.data.prefs.SessionButton
import com.prism.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** 1.7.4's player settings: their defaults are 1.7's player, lists come back whole, the waveform's outline. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class PlayerYourWayTest {
    private fun settings(): SettingsRepository =
        SettingsRepository(org.robolectric.RuntimeEnvironment.getApplication(), CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun defaultsAreThePlayerAs17HadIt() {
        val d = com.prism.music.data.prefs.AppSettings()
        assertEquals(PlayerLayout.STANDARD, d.playerLayout)
        assertEquals(listOf(PlayerButton.LYRICS, PlayerButton.VIDEO, PlayerButton.QUEUE), d.playerButtons)
        assertEquals(DeckPart.entries, d.playerDeck)
        assertEquals(listOf(SessionButton.LIKE, SessionButton.SHUFFLE, SessionButton.REPEAT, SessionButton.CAR_LYRICS, SessionButton.RADIO, SessionButton.DISLIKE), d.sessionButtons)
        assertEquals(PlayerGesture.NEXT, d.coverSwipeLeft)
        assertEquals(PlayerGesture.PREVIOUS, d.coverSwipeRight)
        assertEquals(PlayerGesture.QUEUE, d.coverSwipeUp)
        assertEquals(PlayerGesture.SEEK_SIDES, d.coverDoubleTap)
        assertEquals(PlayerGesture.STOP, d.miniSwipeDown)
        // The mesh stays still unless asked (motion costs frames).
        assertEquals(0f, d.meshSpeed)
    }

    @Test
    fun listsComeBackInTheirOrderAndTheDeckWhole(): Unit = runBlocking {
        val s = settings()
        s.setPlayerButtons(listOf(PlayerButton.LIKE, PlayerButton.SLEEP, PlayerButton.QUEUE, PlayerButton.DJ))
        s.setSessionButtons(listOf(SessionButton.BACK_10, SessionButton.AHEAD_30))
        // A deck saved without some parts (an older order, say) still has every part, the missing ones at the end.
        s.setPlayerDeck(listOf(DeckPart.TRANSPORT, DeckPart.SCRUBBER))
        val now = withTimeout(5_000) { s.flow.first { it.playerButtons.first() == PlayerButton.LIKE && it.sessionButtons.size == 2 && it.playerDeck.first() == DeckPart.TRANSPORT } }
        assertEquals(listOf(PlayerButton.LIKE, PlayerButton.SLEEP, PlayerButton.QUEUE, PlayerButton.DJ), now.playerButtons)
        assertEquals(listOf(SessionButton.BACK_10, SessionButton.AHEAD_30), now.sessionButtons)
        assertEquals(listOf(DeckPart.TRANSPORT, DeckPart.SCRUBBER, DeckPart.STRIP, DeckPart.VOLUME, DeckPart.BUTTONS), now.playerDeck)
        s.setGesture("cover_long_press", PlayerGesture.MENU)
        assertEquals(PlayerGesture.MENU, withTimeout(5_000) { s.flow.first { it.coverLongPress == PlayerGesture.MENU } }.coverLongPress)
    }

    @Test
    fun gestureChoicesFitWhereTheyAre() {
        assertTrue(PlayerGesture.SEEK_SIDES in PlayerGesture.choices(double = true))
        assertFalse(PlayerGesture.SEEK_SIDES in PlayerGesture.choices())
        // Stop only on the mini player; the full player's own things (lyrics, video, Sing) not on it.
        assertTrue(PlayerGesture.STOP in PlayerGesture.choices(mini = true))
        assertFalse(PlayerGesture.STOP in PlayerGesture.choices())
        assertFalse(PlayerGesture.LYRICS in PlayerGesture.choices(mini = true))
    }

    @Test
    fun outlineFollowsTheSongsLoudness() {
        // Quiet, then loud, then quiet: a sine whose level steps up in the middle third.
        val rate = 11_025
        val pcm = FloatArray(rate * 30) { i ->
            val level = if (i in rate * 10 until rate * 20) 0.8f else 0.1f
            level * sin(2 * PI * 220 * i / rate).toFloat()
        }
        val w = SongWaves.outline(pcm)
        assertEquals(SongWaves.POINTS, w.size)
        assertEquals(1f, w.max(), 1e-6f)
        val quiet = w.slice(5 until 30).average()
        val loud = w.slice(38 until 62).average()
        assertTrue("loud part stands out: quiet $quiet, loud $loud", loud > quiet * 1.8)
        assertTrue(w.all { it in 0f..1f })
    }

    @Test
    fun outlinesAreKeptAndReadBack() {
        val waves = SongWaves(org.robolectric.RuntimeEnvironment.getApplication())
        val wave = FloatArray(SongWaves.POINTS) { it / (SongWaves.POINTS - 1f) }
        assertFalse(waves.has("abc"))
        waves.put("abc", wave)
        assertTrue(waves.has("abc"))
        val back = SongWaves(org.robolectric.RuntimeEnvironment.getApplication()).get("abc")!!
        // Kept as a byte a point.
        for (i in wave.indices) assertEquals(wave[i], back[i], 1 / 255f)
    }

    @Test
    fun theVinylLookPutsTheRecordOn() {
        val vinyl = BuiltInLooks.of("vinyl")!!
        assertEquals("s:VINYL", vinyl.values["player_layout"])
        // Every value it sets is a look setting, of the right kind.
        val kinds = settings().lookKinds
        assertTrue(vinyl.values.all { (k, v) -> kinds[k] == v[0] })
    }
}
