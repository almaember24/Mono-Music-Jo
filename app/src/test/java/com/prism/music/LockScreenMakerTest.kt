package com.prism.music

import androidx.test.core.app.ApplicationProvider
import com.prism.music.ui.lockscreen.LockScreenMaker
import com.prism.music.ui.lockscreen.LockScreenRefusals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D43 (1.8.3): the lock screen player didn't come up on vivo and HyperOS phones, whose makers add permissions Android
 * can't read. Those phones get one more step (their own permission page), and the page says when a start was refused.
 * Not tried on a real vivo or Xiaomi here: the reporters confirm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LockScreenMakerTest {
    @Test fun makersAreFoundByManufacturerOrBrand() {
        assertEquals(LockScreenMaker.VIVO, LockScreenMaker.of("vivo", "vivo"))
        assertEquals(LockScreenMaker.VIVO, LockScreenMaker.of("vivo", "iQOO"))
        assertEquals(LockScreenMaker.XIAOMI, LockScreenMaker.of("Xiaomi", "Redmi"))
        assertEquals(LockScreenMaker.XIAOMI, LockScreenMaker.of("Xiaomi", "POCO"))
        assertNull(LockScreenMaker.of("samsung", "samsung"))
        assertNull(LockScreenMaker.of("Google", "google"))
        assertNull(LockScreenMaker.of("HONOR", "HONOR"))
    }

    @Test fun eachMakersStepOpensItsOwnPermissionPageForPrism() {
        val xiaomi = LockScreenMaker.XIAOMI.pages("com.prism.music")
        assertEquals("com.miui.securitycenter", xiaomi.first().component?.packageName)
        assertEquals("com.prism.music", xiaomi.first().getStringExtra("extra_pkgname"))
        val vivo = LockScreenMaker.VIVO.pages("com.prism.music")
        assertEquals("com.vivo.permissionmanager", vivo.first().component?.packageName)
        assertEquals("com.prism.music", vivo.first().getStringExtra("packagename"))
    }

    @Test fun aRefusedStartIsSaidUntilOneComesUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = 1_800_000_000_000L
        assertFalse(LockScreenRefusals.lately(ctx, now))
        LockScreenRefusals.note(ctx, now)
        assertTrue(LockScreenRefusals.lately(ctx, now + 60_000))
        assertFalse("a week later it's not said any more", LockScreenRefusals.lately(ctx, now + LockScreenRefusals.SHOWN_FOR_MS))
        LockScreenRefusals.clear(ctx)
        assertFalse(LockScreenRefusals.lately(ctx, now + 60_000))
    }
}
