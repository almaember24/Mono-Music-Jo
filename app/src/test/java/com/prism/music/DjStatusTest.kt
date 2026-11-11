package com.prism.music

import com.prism.music.playback.DjProgress
import com.prism.music.playback.DjStatus
import com.prism.music.playback.QuickScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DJ mode's status says only what's so; the quick scan hears three stretches of a song. */
// Robolectric: the status line's words come from strings.xml (1.7.9).
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class DjStatusTest {
    @Test
    fun readingThenInOrder() {
        assertEquals("DJ mode: reading keys and tempos… 4 of 24", DjStatus.of(DjProgress(4, 24, 4)).first)
        assertEquals("DJ mode: in key order, beat-matched", DjStatus.of(DjProgress(24, 24, 24)).first)
    }

    @Test
    fun songsWaitingForWifiAreNotCountedAsRead() {
        // Seen on the emulator (mobile data): 41 songs skipped at once, the playing one still being read.
        val (line, chip) = DjStatus.of(DjProgress(41, 42, 0, needNetwork = 41))
        assertEquals("DJ mode: reading keys and tempos… 0 of 1, 41 wait for Wi-Fi", line)
        assertEquals(true, chip)
    }

    @Test
    fun onMobileDataItSaysSoAndOffersData() {
        // The bug: every song skipped for lack of Wi-Fi, and it still said "in key order".
        val (line, offer) = DjStatus.of(DjProgress(24, 24, keyed = 1, needNetwork = 23))
        assertEquals("DJ mode: keys are read on Wi-Fi (23 songs waiting)", line)
        assertTrue(offer)
        assertFalse(line.contains("in key order"))
    }

    @Test
    fun partlyOrderedSaysHowMany() {
        assertEquals("DJ mode: 10 of 24 songs in key order, the rest wait for Wi-Fi", DjStatus.of(DjProgress(24, 24, 10, 14)).first)
        assertEquals("DJ mode: 22 of 24 songs in key order, the rest stay put", DjStatus.of(DjProgress(24, 24, 22, 0)).first)
        assertEquals("DJ mode: no clear keys to order these by", DjStatus.of(DjProgress(5, 5, 2, 0)).first)
    }

    @Test
    fun quickScanWindows() {
        // A 4-minute song: 25 s around a quarter, half and three quarters in.
        assertEquals(listOf(47_500L to 25_000L, 107_500L to 25_000L, 167_500L to 25_000L), QuickScan.windows(240_000))
        // A short one is heard whole.
        assertEquals(listOf(0L to 80_000L), QuickScan.windows(80_000))
    }
}
