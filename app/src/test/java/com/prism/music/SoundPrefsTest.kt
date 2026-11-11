package com.prism.music

import com.prism.music.data.prefs.CrossfadeCurve
import com.prism.music.data.prefs.EqPresetStore
import com.prism.music.data.prefs.EqSettings
import com.prism.music.data.prefs.SavedEq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.8's Sound update: crossfade curves (A1) and equalizer presets (A3). (Robolectric: codes use android.util.Base64.) */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class SoundPrefsTest {
    @Test
    fun everyCurveStartsOnTheEndingSongAndEndsOnTheNext() {
        for (curve in CrossfadeCurve.entries) {
            val (inStart, outStart) = curve.gains(0f)
            val (inEnd, outEnd) = curve.gains(1f)
            assertEquals("$curve in at the start", 0f, inStart, 1e-4f)
            assertEquals("$curve out at the start", 1f, outStart, 1e-4f)
            assertEquals("$curve in at the end", 1f, inEnd, 1e-4f)
            assertEquals("$curve out at the end", 0f, outEnd, 1e-4f)
            // The next song only ever comes up, the ending one only goes down.
            var lastIn = 0f; var lastOut = 1f
            for (i in 1..100) {
                val (a, b) = curve.gains(i / 100f)
                assertTrue("$curve in falls at $i", a >= lastIn - 1e-5f)
                assertTrue("$curve out rises at $i", b <= lastOut + 1e-5f)
                lastIn = a; lastOut = b
            }
        }
        // Smooth (as before 1.7.8) holds the power; the DJ cut keeps both at full until near the middle.
        for (i in 0..10) CrossfadeCurve.EQUAL_POWER.gains(i / 10f).let { (a, b) -> assertEquals(1f, a * a + b * b, 1e-4f) }
        assertEquals(0f to 1f, CrossfadeCurve.DJ_CUT.gains(0.3f))
        assertEquals(1f to 0f, CrossfadeCurve.DJ_CUT.gains(0.7f).let { (a, b) -> a to (if (b < 1e-6f) 0f else b) })
    }

    @Test
    fun aSharedPresetComesBackAsItWasAndInRange() {
        val mine = SavedEq("Car", listOf(4f, 3f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 2f), preampDb = -2f, bassBoost = 200, loudnessGainMb = 300)
        val code = EqPresetStore.code(mine)
        assertTrue(code.startsWith("PRISMEQ1:"))
        // Pasted with words around it, as a message would carry it.
        assertEquals(mine, EqPresetStore.parse("Car: an equalizer preset for Prism\n$code thanks"))
        assertNull(EqPresetStore.parse("PRISMLOOK1:abc"))
        assertNull(EqPresetStore.parse("PRISMEQ1:not-a-code"))
        // Out-of-range values someone typed in are brought back into the equalizer's range.
        val wild = EqPresetStore.code(SavedEq("\u0007Loud", List(10) { 40f }, preampDb = 30f, bassBoost = 9000))
        val back = EqPresetStore.parse(wild)
        assertNotNull(back)
        assertEquals("Loud", back!!.name)
        assertTrue(back.bands.all { it == 12f })
        assertEquals(6f, back.preampDb, 0f)
        assertEquals(1000, back.bassBoost)
    }

    @Test
    fun anOutputPlaysThroughItsOwnPreset() {
        val eq = EqSettings(enabled = false, preset = "Flat")
        val car = SavedEq("Car", List(10) { 2f })
        // Nothing picked for this output: the equalizer as set.
        assertEquals(eq, EqPresetStore.effective(eq, "bt:AA", emptyMap(), listOf(car)))
        // Your own preset, switched on there.
        EqPresetStore.effective(eq, "bt:AA", mapOf("bt:AA" to "Car"), listOf(car)).let {
            assertTrue(it.enabled); assertEquals("Car", it.preset); assertEquals(car.bands, it.bands)
        }
        // One of Prism's.
        assertEquals("Rock", EqPresetStore.effective(eq, "wired", mapOf("wired" to "Rock"), emptyList()).preset)
        // A preset since deleted: as set.
        assertEquals(eq, EqPresetStore.effective(eq, "bt:AA", mapOf("bt:AA" to "Gone"), emptyList()))
    }
}
