package com.prism.music

import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #324: "Regular glass looks clear; Clear glass doesn't really let more through." The settings only said "Regular is
 * fuller, Clear lets more through". What each draws (ui/theme/Glass.kt): Regular is Haze's regular glass, which
 * blurs what's behind it; Clear is Haze's clear glass, which keeps it sharp under a light tint. The words now say so,
 * and this keeps them true if a newer Haze changes its styles.
 */
class GlassKindsTest {
    /** The blur radii (dp) a glass's optics name: one when fixed, one per size when it changes with the size. */
    private fun blurs(optics: Any): List<Float> {
        val radius = optics.javaClass.getMethod("getBlurRadius").invoke(optics).toString()
        return Regex("value=([0-9.]+)\\.dp").findAll(radius).map { it.groupValues[1].toFloat() }.toList()
            .also { assertTrue("read a blur from $radius", it.isNotEmpty()) }
    }

    @Test fun clearKeepsWhatsBehindSharpAndRegularBlursIt() {
        val regular = blurs(GlassDefaults.optics)
        val clear = blurs(GlassStyle.Companion.javaClass.getMethod("getClearOptics\$haze_root_haze_glass").invoke(GlassStyle.Companion)!!)
        assertTrue("Clear barely blurs ($clear dp)", clear.max() <= 2f)
        assertTrue("Regular blurs ($regular dp)", regular.min() >= 10f)
    }

    @Test fun theSettingSaysWhatEachDoes() {
        val info = tr(R.string.glass_kinds_what_differs)
        assertTrue(info, info.contains("Regular spreads the colors") && info.contains("Clear leaves the colors"))
    }
}
