package com.prism.music

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import com.prism.music.data.prefs.ArtworkSwatch
import com.prism.music.ui.theme.ColorMath
import com.prism.music.ui.theme.pickSwatch
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The accent "From the cover" is the cover's color, not a speck of it (Reddit, 2026-10: Jaze's "Desconectado", a deep
 * blue night with two pink tail lights, gave a pink accent from 7 of its 25,600 pixels).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CoverAccentTest {
    @Test
    fun aSpeckOfColorIsNotTheCoversAccent() {
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        for (y in 0 until 160) for (x in 0 until 160) {
            bmp.setPixel(x, y, when {
                y > 150 && x in 40..43 -> 0xFFD87080.toInt() // the tail lights
                y in 60..100 && x in 70..90 -> 0xFFD0D8C8.toInt() // the figure
                else -> if ((x + y) % 7 == 0) 0xFF0A3468.toInt() else 0xFF002858.toInt() // the night
            })
        }
        val p = Palette.from(bmp).generate()
        for (vivid in listOf(false, true)) {
            val picked = pickSwatch(p, ArtworkSwatch.VIBRANT, vivid) ?: error("no accent")
            val hue = ColorMath.toHsl(picked)[0]
            assertTrue("vivid=$vivid picked #%06X (hue $hue), not the night's blue".format(picked and 0xFFFFFF), hue in 190f..250f)
        }
    }

    /** A black-and-white photo (a little warm, as photos are) gives a grey accent, unless "Always colorful" is on. */
    @Test
    fun aBlackAndWhiteCoverGivesGrey() {
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        for (y in 0 until 160) for (x in 0 until 160) {
            val l = 0.15f + 0.7f * ((x + y) % 160) / 160f
            bmp.setPixel(x, y, ColorMath.fromHsl(30f, 0.1f, l))
        }
        val p = Palette.from(bmp).generate()
        for (sw in ArtworkSwatch.entries) {
            val grey = pickSwatch(p, sw, vivid = false) ?: continue
            assertTrue("$sw: #%06X is neutral".format(grey and 0xFFFFFF), com.prism.music.ui.theme.isNeutralSeed(grey))
            val colorful = pickSwatch(p, sw, vivid = true) ?: continue
            assertTrue("$sw with Always colorful: #%06X is a color".format(colorful and 0xFFFFFF), !com.prism.music.ui.theme.isNeutralSeed(colorful))
        }
    }
}
