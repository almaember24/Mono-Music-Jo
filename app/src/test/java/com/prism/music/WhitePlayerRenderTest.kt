package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.player.PlayerInk
import com.prism.music.ui.player.PlayerInk.inked
import com.prism.music.ui.player.PlayerWhiteBackdrop
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #320 (1.8.1): the White player background. The player's type and controls are drawn white for its dark backgrounds
 * and turned dark over the white one by [PlayerInk]; the colors keep their hue. A contact sheet of the same controls
 * over the mesh-dark ground and over White goes to app/build/player-previews/white-player.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w560dp-h900dp-xhdpi")
class WhitePlayerRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun lum(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    @Test fun whiteReadsDarkAndColorsKeepTheirHue() {
        assertTrue(lum(PlayerInk.over(Color.White)) < 0.05f)
        assertTrue(lum(PlayerInk.over(Color.Black)) > 0.95f)
        // Partly see-through white (the pills' fills) stays as see-through, dark.
        val pill = PlayerInk.over(Color.White.copy(alpha = 0.10f))
        assertTrue(pill.alpha in 0.09f..0.11f && lum(pill) < 0.05f)
        // The split light's red stays the reddest of its channels, its green the greenest.
        PlayerInk.over(Color(0xFFFF4D5E)).let { assertTrue(it.red > it.green && it.red > it.blue) }
        PlayerInk.over(Color(0xFF3DDC97)).let { assertTrue(it.green > it.red && it.green > it.blue) }
    }

    /** The player's parts as they're drawn (white type, a filled play button, pills, the split light). */
    @androidx.compose.runtime.Composable
    private fun Controls() {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Northern Lights", color = Color.White, fontSize = 22.sp)
            Text("Ava Lune", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxWidth(0.4f).height(4.dp).background(Brush.horizontalGradient(listOf(Color(0xFFFF4D5E), Color(0xFF3DDC97), Color(0xFF7C5CFF)))))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Icon(PrismIcons.Previous, null, Modifier.size(30.dp), tint = Color.White)
                Box(Modifier.size(58.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(PrismIcons.Play, null, Modifier.size(28.dp), tint = Color.Black)
                }
                Icon(PrismIcons.Next, null, Modifier.size(30.dp), tint = Color.White)
            }
            Row(
                Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) { listOf(PrismIcons.Lyrics, PrismIcons.Queue, PrismIcons.Heart).forEach { Icon(it, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = 0.8f)) } }
        }
    }

    @Test fun theControlsReadOverWhite() {
        val settings = AppSettings(themeMode = ThemeMode.LIGHT, reduceMotion = true)
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides settings) {
                PrismTheme(settings, artworkColor = null) {
                    Row(Modifier.testTag("sheet")) {
                        Box(Modifier.width(260.dp).background(Color(0xFF241B33))) { Controls() }
                        Box(Modifier.width(260.dp).background(PlayerWhiteBackdrop)) { Box(Modifier.testTag("white").inked { true }) { Controls() } }
                    }
                }
            }
        }
        val sheet = compose.onNodeWithTag("sheet").captureToImage().asAndroidBitmap()
        File("build/player-previews").apply { mkdirs() }.resolve("white-player.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Over White, the title's pixels are dark and none of the controls is left white.
        val white = compose.onNodeWithTag("white").captureToImage().asAndroidBitmap()
        var dark = 0
        var bright = 0
        for (y in 0 until white.height step 2) for (x in 0 until white.width step 2) {
            val p = white.getPixel(x, y)
            val l = (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
            if (android.graphics.Color.alpha(p) > 200 && l < 0.2f) dark++
            if (android.graphics.Color.alpha(p) > 200 && l > 0.99f) bright++
        }
        assertTrue("the type and controls are drawn dark ($dark dark pixels)", dark > 400)
        // The filled play button's glyph is the one white thing left (black on white turns white on black).
        assertTrue("little is left white ($bright bright pixels)", bright < white.width * white.height / 8)
    }
}
