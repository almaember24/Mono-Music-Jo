package com.prism.music

import com.prism.music.playback.AudioDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playback diagnostics' processing summary: share of real time, worst buffer, slow buffers; and quiet when off. */
class AudioDiagnosticsTest {
    @Test
    fun summarisesProcessingAndResets() {
        AudioDiagnostics.setEnabled(true) { "test" }
        try {
            AudioDiagnostics.summary() // anything left from before
            // A 10 ms buffer (480 frames at 48 kHz) that took ~4 ms: 40% of real time, and slow (over a fifth).
            AudioDiagnostics.processed("eq", System.nanoTime() - 4_000_000, 480, 48_000)
            val s = AudioDiagnostics.summary()!!
            assertTrue(s, s.startsWith("eq "))
            assertTrue(s, s.contains("1 slow"))
            val share = Regex("""eq ([\d.]+)%""").find(s)!!.groupValues[1].toDouble()
            assertTrue(s, share in 39.0..60.0)
            assertNull("the summary starts again after each one", AudioDiagnostics.summary())
        } finally {
            AudioDiagnostics.setEnabled(false) { "" }
        }
    }

    @Test
    fun recordsNothingWhenOff() {
        AudioDiagnostics.setEnabled(false) { "" }
        AudioDiagnostics.processed("eq", System.nanoTime() - 4_000_000, 480, 48_000)
        assertNull(AudioDiagnostics.summary())
        assertEquals(false, AudioDiagnostics.enabled)
    }
}
