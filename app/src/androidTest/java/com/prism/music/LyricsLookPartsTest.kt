package com.prism.music

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsBackground
import com.prism.music.data.prefs.LyricsMotion
import com.prism.music.data.prefs.LyricsStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #253 (1.8.0): the lyrics look in four parts. On Customize Prism → Lyrics, "Start from" a template, then change one part
 * (the Animation, then the Background): each time only that part of the look changes, and the template's card says the
 * look has your changes; starting from another template sets its parts and keeps the background picked.
 */
@RunWith(AndroidJUnit4::class)
class LyricsLookPartsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val settings get() = Fixture.container.settings

    @After fun putBack() {
        settings.setLyricsStartFrom(LyricsStyle.PRISM)
        settings.setLyricsBehind(LyricsBackground.PLAYER, false)
    }

    /** Everything about the look as it's drawn: the four parts and the details around them. */
    private data class Look(
        val motion: LyricsMotion, val wordByWord: Boolean, val weight: Int, val size: Float, val align: String, val spacing: Float,
        val sung: String, val others: Float, val page: LyricsBackground, val card: Boolean, val immersive: LyricsBackground,
        val halo: Boolean, val shadow: Boolean, val progress: Boolean, val backing: Float, val breaks: String,
    )
    private fun AppSettings.look() = lyricsLook.let { p ->
        Look(
            p.motionNow, p.wordByWordNow, p.weightNow.weight, p.sizeScale * lyricsScale, lyricsAlign.name, p.spacing, p.sungColor.name,
            p.othersAlpha, p.pageBackground, p.onCard, p.immersiveBackground, p.haloNow, p.shadow, lyricsProgressLine, p.backingSize, p.gapMark.name,
        )
    }
    private fun now() = settings.stored.value

    private fun tap(card: String, part: String) {
        compose.clickWhenFound(hasText(card) and hasClickAction() and hasAnyAncestor(hasTestTag(part)), 15_000)
        compose.waitForIdle()
    }

    @Test fun aTemplateThenOnePartChangesOnlyThatPart() {
        Fixture.ready()
        Fixture.launch()
        compose.openSettings()
        compose.clickWhenFound(hasText("Customize Prism") and hasClickAction(), 15_000)
        compose.clickWhenFound(hasText("Lyrics") and hasClickAction(), 15_000)

        // Start from Bold card: its sweep, its bold type, its card.
        tap("Bold card", "lyricsStartFrom")
        compose.waitUntil(10_000) { now().lyricsStyle == LyricsStyle.CARD }
        val card = now().look()
        assertEquals(LyricsMotion.SWEEP, card.motion)
        assertEquals(true, card.card)
        assertEquals(700, card.weight)

        // The Animation part: Typewriter. Only the animation changes.
        tap("Typewriter", "lyricsAnimation")
        compose.waitUntil(10_000) { now().lyricsLook.motionNow == LyricsMotion.TYPEWRITER }
        assertEquals(card.copy(motion = LyricsMotion.TYPEWRITER), now().look())
        assertEquals(LyricsStyle.CARD, now().lyricsStyle)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("With your changes") and hasAnyAncestor(hasTestTag("lyricsStartFrom"))).fetchSemanticsNodes().isNotEmpty()
        }

        // The Background part: Plain dark. Only the background changes (no card on it), the typewriter stays.
        tap("Plain dark", "lyricsBackground")
        compose.waitUntil(10_000) { now().lyricsLook.pageBackground == LyricsBackground.DARK }
        assertEquals(card.copy(motion = LyricsMotion.TYPEWRITER, page = LyricsBackground.DARK, card = false), now().look())

        // Start from Glow: its animation and glow, the type back to your own; the background you picked stays. Glow
        // lights a line timed only as a whole all at once (as 1.7.9 did), so word by word is off there.
        tap("Glow", "lyricsStartFrom")
        compose.waitUntil(10_000) { now().lyricsStyle == LyricsStyle.GLOW }
        assertEquals(
            card.copy(motion = LyricsMotion.SWEEP, wordByWord = false, weight = 300, size = card.size / 1.1f, page = LyricsBackground.DARK, card = false, halo = true),
            now().look(),
        )
    }
}
