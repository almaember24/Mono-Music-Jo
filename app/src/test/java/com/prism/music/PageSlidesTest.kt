package com.prism.music

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** #208 (1.7.9.2): "Slide between screens" off leaves the fades alone; on, every way between pages slides. */
class PageSlidesTest {
    @Test fun offLeavesTheFades() {
        val off = PageSlides(on = false, rtl = false)
        listOf(off.enter, off.exit, off.popEnter, off.popExit).forEach { assertNull(it) }
    }

    @Test fun onSlidesEveryWayInBothDirections() {
        for (rtl in listOf(false, true)) {
            val on = PageSlides(on = true, rtl = rtl)
            listOf(on.enter, on.exit, on.popEnter, on.popExit).forEach { assertNotNull(it) }
        }
    }
}
