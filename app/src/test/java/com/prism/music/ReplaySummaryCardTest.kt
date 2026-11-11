package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prism.music.data.Habits
import com.prism.music.data.Personality
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.SongPlays
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.screens.LocalStoryFont
import com.prism.music.ui.screens.Period
import com.prism.music.ui.screens.ReplayStory
import com.prism.music.ui.screens.SummaryCard
import com.prism.music.ui.screens.fittedSize
import com.prism.music.ui.theme.PrismTheme
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
 * Reddit: Replay's summary card showed "TODA": a 52 sp title beside the cover, on one line, had its end cut off. Each
 * period's title (in several languages) at a phone's width and a small one, in Prism's poster type and the app's own
 * font, with a long "Includes" line above it; the card is drawn to app/build/replay-cards/summary-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1400dp-h3000dp-xhdpi")
class ReplaySummaryCardTest {
    @get:Rule val compose = createComposeRule()

    private val titles = listOf(
        "Today", "This week", "This month", "Last 30 days", "All time", "2026", "September", "Hoy", "Heute", "Septiembre", "Сентябрь", "Февраль",
        "Szeptember", "Tháng 9", "9月", "Mar 3 – Mar 19", "Dieser Monat", "Letzte 30 Tage",
    )

    private fun story(title: String, includes: String?, rewind: Boolean = false) = ReplayStory(
        period = Period.ALL, title = title, totalMs = 72_240_000, previousMs = null, previousLabel = null, plays = 300, activeDays = 12, songCount = 80, artistCount = 40,
        songs = listOf(SongPlays("a", 9, 900_000, "A song with a rather long title for its column", "An artist", null, null, 200, null)),
        artists = listOf(ArtistPlays("An artist with a long name", 9, 900_000, null, null), ArtistPlays("Another artist", 5, 500_000, null, null)),
        genres = listOf("Dance & electronic" to 900_000L), genreSongs = emptyMap(), habits = Habits(LongArray(24), LongArray(7), 3, null, 0),
        newArtists = null, personality = Personality("The Explorer", ""), topArtistImage = null, topArtistType = com.prism.music.ui.theme.ArtistTypography.standard,
        rewind = rewind, includes = includes,
    )

    private fun shoot(name: String, width: Dp, ownFont: Boolean) {
        val long = "Includes Spotify, Last.fm, YouTube Music"
        val cases = titles.flatMap { listOf(it to null, it to long) }
        compose.setContent {
            PrismTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                CompositionLocalProvider(LocalStoryFont provides if (ownFont) FontFamily.SansSerif else null) {
                    Row(Modifier.background(Color.Black).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cases.chunked(cases.size / 4 + 1).forEach { col ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                col.forEach { (t, inc) -> SummaryCard(story(t, inc, rewind = t == "2026"), null, Modifier.width(width)) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val dir = File("build/replay-cards").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        for (t in titles) {
            val nodes = compose.onAllNodesWithText(t.uppercase()).fetchSemanticsNodes()
            assertTrue(t, nodes.isNotEmpty())
            for (n in nodes) {
                val results = ArrayList<TextLayoutResult>()
                n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                val layout = results.single()
                assertEquals("\"$t\" stays on one line", 1, layout.lineCount)
                assertTrue(
                    "\"$t\" is cut off at $width: ${layout.multiParagraph.getLineRight(0)} of ${n.size.width} at ${layout.layoutInput.style.fontSize}",
                    layout.multiParagraph.getLineRight(0) <= n.size.width + 1f,
                )
            }
        }
    }

    // A card's width at a 360 dp phone (24 dp each side) and at a small 320 dp one.
    @Test fun aPhonesWidth() = shoot("summary-phone", 312.dp, ownFont = false)
    @Test fun aSmallPhone() = shoot("summary-small", 272.dp, ownFont = false)
    @Test fun inTheAppsFont() = shoot("summary-own-font", 312.dp, ownFont = true)
    @Test fun aSmallPhoneInTheAppsFont() = shoot("summary-small-own-font", 272.dp, ownFont = true)

    /** The bug itself, as it was drawn: 52 sp beside the cover, one line, in the app's font. */
    @Test fun theOldTitleWasCutOff() {
        compose.setContent {
            Row(Modifier.width(272.dp - 40.dp)) {
                Spacer(Modifier.size(120.dp))
                Column {
                    Text(
                        "TODAY", maxLines = 1,
                        style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 52.sp * 0.9f, lineHeight = 52.sp, letterSpacing = (-0.02).em),
                    )
                }
            }
        }
        val results = ArrayList<TextLayoutResult>()
        compose.onAllNodesWithText("TODAY").fetchSemanticsNodes().single().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertTrue("the old drawing breaks the word and loses its end", results.single().hasVisualOverflow)
    }

    @Test fun theSizeComesDownToWhatFits() {
        // Width in step with the size, 10 px a point: 52 pt = 520 px into 300 px.
        val size = fittedSize(52f, 300) { (it * 10).toInt() }
        assertTrue("$size", (size * 10).toInt() <= 300)
        assertTrue("not much smaller than it has to be: $size", size > 28f)
        assertEquals("it fits: left alone", 52f, fittedSize(52f, 600) { (it * 10).toInt() })
    }
}
