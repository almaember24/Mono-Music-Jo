package com.prism.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Prism TV's banner (1.8.0, plan §3): the 320×180 picture TV launchers show for Prism, drawn from the app icon's Prism
 * mark and background with the name in Prism's typeface (Outfit). `PRISM_TV_BANNER=write` draws it into
 * res/drawable-xhdpi/tv_banner.png; otherwise the test checks the committed banner is that size and not blank.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w160dp-h90dp-xhdpi", application = android.app.Application::class)
class TvBannerTest {
    @get:Rule val compose = createComposeRule()

    private val file = File("src/main/res/drawable-xhdpi/tv_banner.png")

    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    @Test fun banner() {
        if (System.getenv("PRISM_TV_BANNER") == "write") {
            val outfit = FontFamily(Font(R.font.outfit, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))))
            compose.setContent {
                Box(
                    Modifier.size(160.dp, 90.dp).background(
                        // The classic icon's background, stretched to the banner: near-black, a little lighter up top.
                        Brush.radialGradient(listOf(Color(0xFF1A1A22), Color(0xFF060609)), center = Offset(110f, 90f), radius = 260f),
                    ),
                ) {
                    // The 108-unit icon grid: the mark spans 32-76 of it, so at 96 dp it is ~39 dp wide; its left edge at 16 dp.
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(96.dp).align(Alignment.CenterStart).offset(x = (-12).dp))
                    Text(
                        "Prism", Modifier.align(Alignment.CenterStart).offset(x = 62.dp, y = (-2).dp).wrapContentSize(unbounded = true),
                        style = TextStyle(fontFamily = outfit, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, color = Color.White, letterSpacing = (-0.3).sp),
                        maxLines = 1, softWrap = false,
                    )
                }
            }
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            file.parentFile!!.mkdirs()
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue("no banner at $file (PRISM_TV_BANNER=write draws it)", file.isFile)
        val bmp = BitmapFactory.decodeFile(file.path)
        assertEquals(320, bmp.width)
        assertEquals(180, bmp.height)
        // Not blank: the mark's white outline and the name are there.
        var bright = 0
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) if ((bmp.getPixel(x, y) and 0xFF) > 200) bright++
        assertTrue("banner looks blank ($bright bright pixels)", bright > 200)
    }
}
