package com.prism.music

import com.prism.music.data.foryou.RecFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the listener made of Prism's suggestions (1.7.9): skips keep songs out a while, a full listen lets them back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecFeedbackTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test fun skipsAndListens() {
        val fb = RecFeedback(app).apply { clear() }
        fb.skipped("new", "Artist")
        assertTrue("a new song skipped once is out", fb.turnedDown("new", known = false))
        fb.skipped("fav", "Artist")
        assertFalse("a song you know needs two skips", fb.turnedDown("fav", known = true))
        fb.skipped("fav", "Artist")
        assertTrue(fb.turnedDown("fav", known = true))
        fb.heard("fav", "Artist")
        assertFalse("heard through, it's welcome again", fb.turnedDown("fav", known = true))
        // Kept across a restart.
        assertTrue(RecFeedback(app).turnedDown("new", known = false))
    }

    @Test fun servedAndDoubt() {
        val fb = RecFeedback(app).apply { clear() }
        fb.served(listOf("a", "b"))
        assertEquals(0, fb.servedDaysAgo("a"))
        assertEquals(null, fb.servedDaysAgo("z"))
        assertEquals(setOf("a", "b"), fb.servedOn(0))
        assertEquals(0.0, fb.artistDoubt("Someone"), 1e-9)
        repeat(6) { fb.skipped("s$it", "Someone") }
        assertTrue("an artist whose suggestions get skipped counts for less", fb.artistDoubt("someone") > 0.4)
        fb.clear()
        assertFalse(fb.turnedDown("s1", known = false))
        assertEquals(null, fb.servedDaysAgo("a"))
    }
}
