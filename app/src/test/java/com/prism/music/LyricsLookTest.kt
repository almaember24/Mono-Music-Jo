package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsBackground
import com.prism.music.data.prefs.LyricsLookPrefs
import com.prism.music.data.prefs.LyricsMotion
import com.prism.music.data.prefs.LyricsStyle
import com.prism.music.ui.player.lyricsLookOf
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.player.LyricsLook
import com.prism.music.ui.player.cardColor
import com.prism.music.ui.player.lyricsPageBackground
import com.prism.music.ui.player.upper
import com.prism.music.ui.player.withWords
import com.prism.music.ui.screens.LyricsSample
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismTheme
import com.prism.music.ui.theme.PrismTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Lyrics, your way (1.7.5): the defaults draw the lyrics as 1.7.4 did, lines timed as a whole get words of their own for
 * Karaoke and Typewriter, capitals keep words and line together, and every style renders (contact sheet in
 * app/build/lyrics-previews/styles.png: look at it after changing a style).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h3000dp-xxhdpi", application = android.app.Application::class)
class LyricsLookTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun defaultsAreTheLyricsAsBefore() {
        val s = AppSettings()
        assertEquals(LyricsStyle.PRISM, s.lyricsStyle)
        assertEquals(1f, s.lyricsBlurAmount)
        assertEquals(0.55f, s.lyricsOthersAlpha)
        assertEquals(300, s.lyricsWeight.weight)
        assertEquals(0.6f, s.lyricsBackingSize)
        assertEquals(LyricsBackground.PLAYER, s.lyricsPageBackground())
        assertEquals(LyricsBackground.PLAYER, s.immersiveBackground)
        // The classic look: white, Light growing to Medium, 1.7's dimming.
        val c = LyricsLook.Classic
        assertEquals(com.prism.music.data.prefs.LyricsMotion.GROW, c.motion)
        assertEquals(null, c.sung)
        assertEquals(300, c.weight)
        assertEquals(500, c.sungWeight)
        assertEquals(0f, c.position)
        assertEquals(Int.MAX_VALUE, c.ahead)
        // Bold card brings its color only when the background is left as it is.
        assertEquals(LyricsBackground.COVER_COLOR, s.copy(lyricsLook = s.lyricsLook.copy(style = LyricsStyle.CARD)).lyricsPageBackground())
        assertEquals(LyricsBackground.DARK, s.copy(lyricsLook = s.lyricsLook.copy(style = LyricsStyle.CARD, background = LyricsBackground.DARK)).lyricsPageBackground())
    }

    @Test
    fun lineTimedLinesGetWordsAcrossTheirTime() {
        val line = LyricLine(10_000, "Every color finds its way", 16_000)
        val w = line.withWords()
        assertTrue(w.isWordSynced)
        assertEquals("Every color finds its way", w.text)
        assertEquals(w.text, w.words.joinToString("") { it.text })
        assertEquals(10_000, w.words.first().startMs)
        assertTrue("words run in order", w.words.zipWithNext().all { (a, b) -> a.endMs <= b.startMs && a.startMs < a.endMs })
        assertTrue("within the line", w.words.last().endMs <= 16_000)
        // Longer words take longer.
        assertTrue(w.words[1].endMs - w.words[1].startMs > w.words[3].endMs - w.words[3].startMs)
        // A short line isn't stretched over a long pause.
        assertTrue(LyricLine(0, "Oh", 30_000).withWords().words.last().endMs < 2_000)
        // Word-timed lines, breaks and lines without an end are left as they are.
        val timed = LyricLine(0, "Hi there", 1000, listOf(LyricWord(0, 400, "Hi "), LyricWord(400, 1000, "there")))
        assertSame(timed, timed.withWords())
        assertTrue(!LyricLine(0, "", 5000, isGap = true).withWords().isWordSynced)
        assertTrue(!LyricLine(0, "No end").withWords().isWordSynced)
    }

    @Test
    fun capitalsKeepWordsAndLineTogether() {
        val line = LyricLine(0, "Every color", 1000, listOf(LyricWord(0, 500, "Every "), LyricWord(500, 1000, "color")))
        val up = line.upper()
        assertEquals("EVERY COLOR", up.text)
        assertEquals(up.text, up.words.joinToString("") { it.text })
        // A letter whose capital is longer (ß → SS) would push the sweep out of line: that word stays as it is.
        val street = LyricLine(0, "Straße", 1000, listOf(LyricWord(0, 1000, "Straße")))
        assertEquals("Straße", street.upper().words.single().text)
    }

    @Test
    fun cardColorReadsUnderWhite() {
        for (c in listOf(Color.White, Color(0xFFFFE066), Color(0xFF00C2FF), Color.Black, Color(0xFF8A6BFF))) {
            val g = cardColor(c)
            assertTrue("$c → $g too light", g.luminance() <= 0.21f)
        }
    }

    /** The look [p] draws (white, or Accent for the sung line), Bold card on [card]. */
    private fun lookOf(p: LyricsLookPrefs): LyricsLook {
        val sung = if (p.sungColor == com.prism.music.data.prefs.LyricsColor.ACCENT) androidx.compose.ui.graphics.SolidColor(Color(0xFFFF7A59)) else null
        return lyricsLookOf(p, sung, Color.White, Color.White, if (p.onCard) cardColor(Color(0xFF8A6BFF)) else null)
    }

    /** Each "Start from" template as 1.8.0 draws it from the stored Style (styles.png: the same as 1.7.9's). */
    @Test
    fun everyStyleRenders() {
        val looks = buildList {
            LyricsStyle.entries.forEach { st -> add(st.label to lookOf(LyricsLookPrefs().startFrom(st))) }
            add("Accent sung, bold, capitals" to LyricsLook(sung = androidx.compose.ui.graphics.SolidColor(Color(0xFFFF7A59)), weight = 700, sungWeight = 900, uppercase = true))
            add("Glow in your light, shadow" to lookOf(LyricsLookPrefs(style = LyricsStyle.GLOW, shadow = true)).copy(sung = PrismTokens.spectrum(true)))
            add("Thin, airy, faint others" to LyricsLook(weight = 200, sungWeight = 400, spacing = 1.6f, othersAlpha = 0.25f))
        }
        sheet("styles.png", looks, extraFor = "Calm")
    }

    /**
     * The four parts (parts.png, for the owner): each Animation on Prism's look, then templates with one part changed
     * (only that part looks different from the template above it in styles.png).
     */
    @Test
    fun everyPartRenders() {
        val prism = LyricsLookPrefs()
        val looks = buildList {
            LyricsMotion.entries.forEach { m -> add("Animation: ${m.label}" to lookOf(prism.copy(motion = m))) }
            add("Bold card + Typewriter" to lookOf(prism.startFrom(LyricsStyle.CARD).copy(motion = LyricsMotion.TYPEWRITER)))
            add("Bold card, weight Light" to lookOf(prism.startFrom(LyricsStyle.CARD).copy(weight = com.prism.music.data.prefs.LyricsWeight.LIGHT, boldLines = false)))
            add("Glow + Color fills" to lookOf(prism.startFrom(LyricsStyle.GLOW).copy(motion = LyricsMotion.FILL)))
            add("Glow + Grows bolder" to lookOf(prism.startFrom(LyricsStyle.GLOW).copy(motion = LyricsMotion.GROW)))
            add("Karaoke + Glow, accent" to lookOf(prism.startFrom(LyricsStyle.KARAOKE).copy(halo = true, sungColor = com.prism.music.data.prefs.LyricsColor.ACCENT)))
            add("Prism on Bold card" to lookOf(prism.copy(card = true)))
            add("Calm, centered, capitals, shadow" to lookOf(prism.startFrom(LyricsStyle.CALM).copy(uppercase = true, shadow = true)))
        }
        sheet("parts.png", looks, centered = "Calm, centered, capitals, shadow")
    }

    private fun sheet(file: String, looks: List<Pair<String, LyricsLook>>, extraFor: String? = null, centered: String? = null) {
        val dark = AppSettings(themeMode = ThemeMode.DARK, reduceMotion = true)
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides dark) {
                PrismTheme(dark, artworkColor = null) {
                    Column(Modifier.width(412.dp).background(Color(0xFF0A0A0D)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        looks.forEach { (name, look) ->
                            Text(name, color = Color.White.copy(alpha = 0.7f))
                            val s = if (name == centered) dark.copy(lyricsAlign = com.prism.music.data.prefs.LyricsAlign.CENTER) else dark
                            CompositionLocalProvider(LocalAppSettings provides s) {
                                LyricsSample(look, Modifier.fillMaxWidth(), extra = if (name == extraFor) "Cada color encuentra su camino" else null, backing = name == centered, progress = name == centered)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/lyrics-previews").apply { mkdirs() }
        File(dir, file).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
