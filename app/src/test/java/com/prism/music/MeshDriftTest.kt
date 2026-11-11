package com.prism.music

import android.provider.Settings
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.player.meshDrift
import com.prism.music.ui.screens.meshHeldBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #284: the color mesh at Motion 100% read as still (a 48 s loop, 4% across), and nothing said what was holding it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MeshDriftTest {
    @Test fun fullMotionVisiblyMoves() {
        val full = meshDrift(1f)!!
        assertTrue("a loop at 100% is quick enough to see (${full.loopMs} ms)", full.loopMs <= 20_000)
        assertTrue("and goes far enough to see (${full.reachX})", full.reachX >= 0.08f)
        // The layer is scaled up enough that its edges never come into view, breathing included.
        for (v in listOf(0.1f, 0.5f, 1f)) {
            val d = meshDrift(v)!!
            val leastScale = d.scale - 0.04f
            assertTrue("edges stay out of view at $v", (leastScale - 1f) / 2f >= maxOf(d.reachX, d.reachY))
        }
        assertTrue("livelier is quicker", meshDrift(0.3f)!!.loopMs > full.loopMs)
        assertNull("0 is still", meshDrift(0f))
    }

    @Test fun thePageSaysWhatHoldsItStill() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertNull(meshHeldBy(AppSettings(meshSpeed = 1f), context))
        assertEquals(tr(R.string.reduce_motion_is_on_so_it), meshHeldBy(AppSettings(meshSpeed = 1f, reduceMotion = true), context))
        // The settings page shows them as set, where Performance mode leaves Reduce motion off.
        assertEquals(tr(R.string.performance_mode_is_on_so_it_stays_still), meshHeldBy(AppSettings(meshSpeed = 1f, performanceMode = true), context))
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertNotNull("the phone's animations off", meshHeldBy(AppSettings(meshSpeed = 1f), context))
    }
}
