package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsAlign
import com.prism.music.data.prefs.LyricsBackground
import com.prism.music.data.prefs.LyricsColor
import com.prism.music.data.prefs.LyricsLookPrefs
import com.prism.music.data.prefs.LyricsStyle
import com.prism.music.data.prefs.LyricsWeight
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.player.rememberLyricsLook
import com.prism.music.ui.screens.LyricsSample
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Saved lyrics looks drawn from their settings alone (build/lyrics-previews/saved-looks.png): every Style with the
 * details that change how it draws, on the lyrics page and in immersive mode. Only the settings go in, so the same file
 * renders on 1.7.9's code and 1.8.0's: put side by side, nobody's look changed on the update.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h4000dp-xxhdpi", application = android.app.Application::class)
class LyricsBeforeAfterTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun savedLooksRender() {
        val base = AppSettings(themeMode = ThemeMode.DARK, reduceMotion = true)
        val details = listOf<Pair<String, (LyricsLookPrefs) -> LyricsLookPrefs>>(
            "as saved" to { it },
            "accent, bold" to { it.copy(sungColor = LyricsColor.ACCENT, weight = LyricsWeight.BOLD) },
            "plain dark" to { it.copy(background = LyricsBackground.DARK) },
            "cover's color, thin" to { it.copy(background = LyricsBackground.COVER_COLOR, weight = LyricsWeight.THIN) },
            "caps, shadow, faint" to { it.copy(uppercase = true, shadow = true, othersAlpha = 0.3f) },
        )
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides base) {
                PrismTheme(base, artworkColor = null) {
                    Column(Modifier.width(412.dp).background(Color(0xFF0A0A0D)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (style in LyricsStyle.entries) for ((name, change) in details) {
                            val s = base.copy(lyricsLook = change(LyricsLookPrefs(style = style)), lyricsAlign = if (name.startsWith("caps")) LyricsAlign.CENTER else LyricsAlign.START)
                            CompositionLocalProvider(LocalAppSettings provides s) {
                                Text("${style.name} · $name", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                                Row(Modifier.fillMaxWidth()) {
                                    val look = rememberLyricsLook(s, null)
                                    LyricsSample(look, Modifier.weight(1f).padding(end = 4.dp), size = 0.8f, lines = 2)
                                    LyricsSample(look.withoutCard(s.lyricsOthersAlpha), Modifier.weight(1f), size = 0.8f, lines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/lyrics-previews").apply { mkdirs() }
        File(dir, "saved-looks.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
