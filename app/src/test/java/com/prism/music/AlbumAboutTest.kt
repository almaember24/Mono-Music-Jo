package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.screens.AboutBox
import com.prism.music.ui.screens.CreditPart
import com.prism.music.ui.screens.splitCredit
import com.prism.music.ui.theme.PrismTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Reddit: an opened album description was a wall of centered text ending in YouTube's raw "From Wikipedia (https://…)
 * under Creative Commons…". It's now a quiet left-aligned box, closed to a few lines, with that credit on its own small
 * line of links. The sheet goes to app/build/design-previews/album-about-*.png. The texts are made up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h1400dp-xxhdpi")
class AlbumAboutTest {
    @get:Rule val compose = createComposeRule()

    private val long = "An imagined record, made over two winters in a small town by the sea. ".repeat(8).trim()
    private val en = "$long\n\nFrom Wikipedia (https://en.wikipedia.org/wiki/Imagined_(album)) under Creative Commons Attribution CC-BY-SA 3.0 (https://creativecommons.org/licenses/by-sa/3.0/legalcode)"
    private val es = "Un disco imaginado, grabado en dos inviernos junto al mar. ".repeat(8).trim() +
        "\n\nDe Wikipedia (https://es.wikipedia.org/wiki/Imaginado) con la licencia Creative Commons Attribution CC-BY-SA 3.0 (https://creativecommons.org/licenses/by-sa/3.0/legalcode)"
    private val de = "Ein erdachtes Album. ".repeat(20).trim() + " Aus Wikipedia (https://de.wikipedia.org/wiki/Erdacht) unter Creative Commons Attribution CC-BY-SA 3.0 (https://creativecommons.org/licenses/by-sa/3.0/legalcode)"
    private val ja = "海辺の町で二度の冬をかけて作られた、架空のアルバムです。".repeat(10) +
        "\n\nウィキペディア（https://ja.wikipedia.org/wiki/架空）の記事は Creative Commons Attribution CC-BY-SA 3.0 (https://creativecommons.org/licenses/by-sa/3.0/legalcode)"
    private val short = "Songs for a slow Sunday."
    private val playlist = "Everything I like right now.\nSent to a friend, see https://example.com/notes for the story."

    @Test fun theCreditComesOffInEachLanguage() {
        val a = splitCredit(en)
        assertEquals(long, a.body)
        assertEquals(
            listOf(
                CreditPart("From Wikipedia", "https://en.wikipedia.org/wiki/Imagined_(album)"), CreditPart(" under ", null),
                CreditPart("Creative Commons Attribution CC-BY-SA 3.0", "https://creativecommons.org/licenses/by-sa/3.0/legalcode"),
            ),
            a.credit,
        )
        val b = splitCredit(es)
        assertFalse(b.body, b.body.contains("http"))
        assertEquals("De Wikipedia", b.credit!![0].text)
        assertEquals(" con la licencia ", b.credit!![1].text)
        // On the body's own last line, it starts after the last sentence.
        val c = splitCredit(de)
        assertTrue(c.body, c.body.endsWith("Album."))
        assertEquals("Aus Wikipedia", c.credit!![0].text)
        // Full-width brackets: the words after the link aren't taken for its address.
        val d = splitCredit(ja)
        assertEquals("ウィキペディア", d.credit!![0].text)
        assertEquals("https://ja.wikipedia.org/wiki/架空", d.credit!![0].url)
        assertEquals("の記事は ", d.credit!![1].text)
    }

    @Test fun onlyTheCreditComesOff() {
        assertEquals(short, splitCredit(short).body)
        assertNull(splitCredit(short).credit)
        // A playlist's own link is its text, not a credit.
        assertEquals(playlist, splitCredit(playlist).body)
        assertNull(splitCredit(playlist).credit)
        // A description that's only the credit has an empty body.
        val only = splitCredit("From Wikipedia (https://en.wikipedia.org/wiki/X)")
        assertEquals("", only.body)
        assertEquals("https://en.wikipedia.org/wiki/X", only.credit!!.single().url)
    }

    @Test fun theBoxOpensAndCloses() {
        compose.setContent {
            PrismTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) { Column(Modifier.width(320.dp)) { AboutBox(en) } }
        }
        compose.onNodeWithText("Show more").performClick()
        compose.onNodeWithText("Show less").performClick()
        compose.onNodeWithText("Show more").assertExists()
        // The address itself is never read out, and the credit's still there, closed or open.
        compose.onAllNodesWithText("https", substring = true).assertCountEquals(0)
        compose.onNodeWithText("From Wikipedia", substring = true).assertExists()
    }

    @Test fun aShortOneHasNothingToOpen() {
        compose.setContent {
            PrismTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) { Column(Modifier.width(320.dp)) { AboutBox(short) } }
        }
        compose.onAllNodesWithText("Show more").assertCountEquals(0)
    }

    private fun shoot(name: String, settings: AppSettings) {
        compose.setContent {
            PrismTheme(settings, artworkColor = null) {
                val scheme = MaterialTheme.colorScheme
                Row(Modifier.background(scheme.background).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for ((label, text) in listOf("English" to en, "Spanish" to es, "German" to de)) { Text(label, color = scheme.onSurface); AboutBox(text) }
                    }
                    Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for ((label, text) in listOf("Japanese" to ja, "Short" to short, "A playlist" to playlist)) { Text(label, color = scheme.onSurface); AboutBox(text) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val dir = File("build/design-previews").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun dark() = shoot("album-about-dark", AppSettings(themeMode = ThemeMode.DARK))
    @Test fun light() = shoot("album-about-light", AppSettings(themeMode = ThemeMode.LIGHT))
    /** Performance mode: frosted pages off, so its own soft fill. */
    @Test fun performanceMode() = shoot("album-about-performance", AppSettings(themeMode = ThemeMode.LIGHT, performanceMode = true).effective())
}
