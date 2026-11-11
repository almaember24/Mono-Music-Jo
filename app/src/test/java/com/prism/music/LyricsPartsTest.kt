package com.prism.music

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.prism.music.data.prefs.LyricsBackground
import com.prism.music.data.prefs.LyricsColor
import com.prism.music.data.prefs.LyricsLookPrefs
import com.prism.music.data.prefs.LyricsMotion
import com.prism.music.data.prefs.LyricsStyle
import com.prism.music.data.prefs.LyricsWeight
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.ui.player.LyricsLook
import com.prism.music.ui.player.LyricsShadow
import com.prism.music.ui.player.SungKind
import com.prism.music.ui.player.SungPlan
import com.prism.music.ui.player.lyricsLookOf
import com.prism.music.ui.player.solid
import com.prism.music.ui.player.sungPlan
import com.prism.music.ui.player.timesWords
import com.prism.music.ui.theme.PrismTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyrics look in four parts (1.8.0, #253). 1.7.9 stored a Style plus its details; 1.8.0 reads the same keys, and the
 * parts a Style fixed (animation, word timings for lines timed as a whole, Bold card, Glow's halo, Bold card's type) are
 * keys of their own that, unset, follow the Style. So nobody's look changes on the update, and old Look codes and backups
 * still draw as they did. Checked here against 1.7.9's own rules ([OldLook], copied from its LyricStyles.kt), for every
 * Style with every saved detail that changed how a Style drew.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class LyricsPartsTest {
    /** Stand-ins for each lyrics color's fill (the real ones need a composition: the accent, the cover). */
    private fun fill(c: LyricsColor): Brush? = when (c) {
        LyricsColor.WHITE -> null
        LyricsColor.SPECTRUM -> PrismTokens.spectrum(true)
        LyricsColor.ACCENT -> SolidColor(Color(0xFFFF7A59))
        LyricsColor.COVER -> SolidColor(Color(0xFF59C3FF))
        LyricsColor.CUSTOM -> SolidColor(Color(0xFFFFD166))
    }
    private val ground = Color(0xFF6B3FA0)

    /** 1.7.9's rememberLyricsLook, with [fill] for the colors. */
    private object OldLook {
        fun pageBackground(p: LyricsLookPrefs) =
            if (p.style == LyricsStyle.CARD && p.background == LyricsBackground.PLAYER) LyricsBackground.COVER_COLOR else p.background

        fun look(p: LyricsLookPrefs, fill: (LyricsColor) -> Brush?, ground: Color): Old {
            val sung = fill(p.sungColor)
            val onGround = p.style == LyricsStyle.CARD && pageBackground(p) == LyricsBackground.COVER_COLOR
            val card = if (onGround) ground else null
            val weight = if (p.style == LyricsStyle.CARD) maxOf(p.weight.weight, 700) else p.weight.weight
            val lit = sung ?: if (p.style == LyricsStyle.KARAOKE) PrismTokens.spectrum(true) else null
            return Old(
                p.style, LyricsLook(
                    sung = lit, sungSolid = lit.solid(),
                    others = if (card != null) Color.Black else fill(p.otherColor).solid(),
                    othersAlpha = if (card != null) 0.8f else p.othersAlpha,
                    weight = weight, sungWeight = (weight + 200).coerceAtMost(900),
                    blur = if (card != null) 0f else p.blurAmount, spacing = p.spacing, backingSize = p.backingSize,
                    shadow = if (p.shadow) LyricsShadow else null, uppercase = p.uppercase, extraSize = p.extraSize,
                    extraColor = fill(p.extraColor).solid(), extraItalic = p.extraItalic,
                    extraAbove = p.extraPlace == com.prism.music.data.prefs.LyricsExtraPlace.ABOVE,
                    ahead = p.ahead.lines, position = p.position.fraction, card = card,
                    sizeScale = if (p.style == LyricsStyle.CARD) 1.1f else 1f,
                ),
            )
        }
    }

    /** 1.7.9's look and the Style SungLine drew it in. */
    private data class Old(val style: LyricsStyle, val look: LyricsLook) {
        /** Whether SungLine gave a line timed as a whole words of its own first. */
        val timesWords get() = style == LyricsStyle.KARAOKE || style == LyricsStyle.CARD || style == LyricsStyle.TYPEWRITER

        /** 1.7.9's SungLine, branch by branch. */
        fun plan(wordSynced: Boolean, onCard: Boolean, strip: Boolean, axis: Boolean, dimAlpha: Float): SungPlan {
            val l = look
            val dim = if (onCard) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = dimAlpha)
            return when (style) {
                LyricsStyle.PRISM -> when {
                    !wordSynced -> SungPlan(SungKind.PLAIN, l.sungWeight)
                    axis && !strip -> SungPlan(SungKind.GROW, null)
                    else -> SungPlan(SungKind.SWEEP, if (strip) null else l.sungWeight, Color.White.copy(alpha = dimAlpha))
                }
                LyricsStyle.KARAOKE -> if (!wordSynced) SungPlan(SungKind.PLAIN, null)
                    else SungPlan(SungKind.SWEEP, l.weight + 100, if (onCard) dim else Color.White.copy(alpha = 0.92f))
                LyricsStyle.CARD -> if (!wordSynced) SungPlan(SungKind.PLAIN, l.weight) else SungPlan(SungKind.SWEEP, l.weight, dim)
                LyricsStyle.GLOW -> if (!wordSynced) SungPlan(SungKind.PLAIN, l.weight + 100, halo = true)
                    else SungPlan(SungKind.SWEEP, l.weight + 100, Color.White.copy(alpha = dimAlpha), halo = true)
                LyricsStyle.TYPEWRITER -> if (!wordSynced) SungPlan(SungKind.PLAIN, null) else SungPlan(SungKind.TYPEWRITER, l.weight + 100)
                LyricsStyle.CALM -> SungPlan(SungKind.PLAIN, l.weight)
            }
        }
    }

    private fun newLook(p: LyricsLookPrefs) = lyricsLookOf(p, fill(p.sungColor), fill(p.otherColor).solid(), fill(p.extraColor).solid(), if (p.onCard) ground else null)

    /** The look's fields 1.7.9 had, for comparing (the parts' own fields are compared through the plans). */
    private fun LyricsLook.drawn() = copy(motion = LyricsMotion.GROW, wordByWord = false, halo = false)

    /** Every Style with every saved detail that changed how a Style drew: the same look and the same sung line. */
    @Test
    fun everyStyleWithEveryDetailDrawsAsIn179() {
        var checked = 0
        for (style in LyricsStyle.entries) for (weight in LyricsWeight.entries) for (bg in LyricsBackground.entries)
            for (sung in LyricsColor.entries) for (other in listOf(LyricsColor.WHITE, LyricsColor.ACCENT)) for (shadow in listOf(false, true))
                for (blur in listOf(0f, 1f, 2f)) for (upper in listOf(false, true)) {
                    val p = LyricsLookPrefs(style = style, weight = weight, background = bg, sungColor = sung, otherColor = other, shadow = shadow, blurAmount = blur, uppercase = upper, othersAlpha = 0.4f)
                    val old = OldLook.look(p, ::fill, ground)
                    val now = newLook(p)
                    val what = "$style $weight $bg sung $sung other $other shadow $shadow blur $blur caps $upper"
                    assertEquals(what, OldLook.pageBackground(p), p.pageBackground)
                    assertEquals(what, old.look, now.drawn())
                    // Immersive mode: no card, the other lines dim white.
                    assertEquals(what, old.look.withoutCard(0.4f), now.withoutCard(0.4f).drawn())
                    assertEquals(what, old.timesWords, now.timesWords)
                    for (ws in listOf(false, true)) for (strip in listOf(false, true)) for (axis in listOf(false, true)) for (dimAlpha in listOf(0.38f, 0.45f)) {
                        val onCard = now.card != null
                        assertEquals("$what words $ws strip $strip axis $axis", old.plan(ws, onCard, strip, axis, dimAlpha), now.sungPlan(ws, onCard, strip, axis, dimAlpha))
                    }
                    // Where a line timed as a whole was drawn plain (Prism's own), it still is.
                    assertEquals(what, style == LyricsStyle.PRISM, now.plainWhenLineTimed)
                    checked++
                }
        assertEquals(6 * 5 * 6 * 5 * 2 * 2 * 3 * 2, checked)
    }

    /** "Start from" a template, then change one part: only that part changes, nothing else in the look. */
    @Test
    fun aTemplateThenOnePartChangesOnlyThatPart() {
        val mine = LyricsLookPrefs(sungColor = LyricsColor.ACCENT, spacing = 1.3f, othersAlpha = 0.4f, shadow = true, background = LyricsBackground.DARK)
        for (template in LyricsStyle.entries) {
            val start = mine.startFrom(template)
            // Starting from a template keeps your own choices: colors, spacing, shadow…
            assertEquals(mine.sungColor, start.sungColor)
            assertEquals(mine.spacing, start.spacing)
            assertEquals(mine.shadow, start.shadow)
            assertEquals(template.name, template.motionDefault(), start.motionNow)
            // Word by word on lines timed as a whole: only where 1.7.9's Style did it (not Prism, Glow or Calm), also
            // after another template set it (Bold card's on, then Glow's off).
            val words = template == LyricsStyle.KARAOKE || template == LyricsStyle.CARD || template == LyricsStyle.TYPEWRITER
            assertEquals(template.name, words, start.wordByWordNow)
            assertEquals(template.name, words, mine.startFrom(LyricsStyle.CARD).copy(wordByWord = true, motion = LyricsMotion.TYPEWRITER).startFrom(template).wordByWordNow)
            // Bold card brings its color back; the others leave the background as it was.
            assertEquals(template == LyricsStyle.CARD, start.onCard)
            if (template != LyricsStyle.CARD) assertEquals(LyricsBackground.DARK, start.background)
            assertFalse(start.changedFromStart)
            val base = parts(start)
            for (m in LyricsMotion.entries) {
                val changed = start.copy(motion = m)
                assertEquals(base.copy(motion = m), parts(changed))
                assertEquals(m != start.motionNow, changed.changedFromStart)
            }
            assertEquals(base.copy(halo = !base.halo), parts(start.copy(halo = !start.haloNow)))
            assertEquals(base.copy(wordByWord = !base.wordByWord), parts(start.copy(wordByWord = !start.wordByWordNow)))
            // Picking a weight (setLyricsWeight also lets Bold card's own Bold go).
            assertEquals(base.copy(weight = LyricsWeight.THIN), parts(start.copy(weight = LyricsWeight.THIN, boldLines = false)))
            // Another background: Bold card off, the rest as it was.
            val dark = parts(start.copy(background = LyricsBackground.BLUR, card = false))
            assertEquals(base.copy(page = LyricsBackground.BLUR, card = false), dark)
        }
    }

    private fun LyricsStyle.motionDefault() = when (this) {
        LyricsStyle.PRISM -> LyricsMotion.GROW
        LyricsStyle.KARAOKE -> LyricsMotion.FILL
        LyricsStyle.CARD, LyricsStyle.GLOW -> LyricsMotion.SWEEP
        LyricsStyle.TYPEWRITER -> LyricsMotion.TYPEWRITER
        LyricsStyle.CALM -> LyricsMotion.NONE
    }

    /** The four parts as drawn. */
    private data class Parts(
        val motion: LyricsMotion, val wordByWord: Boolean, val weight: LyricsWeight, val size: Float, val sung: LyricsColor,
        val spacing: Float, val page: LyricsBackground, val card: Boolean, val halo: Boolean, val shadow: Boolean, val backing: Float,
    )
    private fun parts(p: LyricsLookPrefs) = Parts(
        p.motionNow, p.wordByWordNow, p.weightNow, p.sizeScale, p.sungColor, p.spacing, p.pageBackground, p.onCard, p.haloNow, p.shadow, p.backingSize,
    )

    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private fun repo() = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** The stored keys: Start from writes what [LyricsLookPrefs.startFrom] says; old Look codes and backups still apply. */
    @Test
    fun storedPartsStartFromAndOldCodes(): Unit = runBlocking {
        val r = repo()
        // Every part is in a Look (so Looks save it, and a Look without it puts it back to its Style's).
        for (k in listOf("lyrics_motion", "lyrics_word_by_word", "lyrics_card", "lyrics_halo", "lyrics_bold_lines")) assertTrue(k, k in r.lookKinds)
        r.setLyricsBehind(LyricsBackground.DARK, false)
        r.setLyricsMotion(LyricsMotion.TYPEWRITER)
        r.setLyricsHalo(true)
        withTimeout(5_000) { r.flow.first { it.lyricsLook.motion == LyricsMotion.TYPEWRITER && it.lyricsLook.halo == true && it.lyricsLook.card == false } }
        val before = r.current.lyricsLook
        r.setLyricsStartFrom(LyricsStyle.CARD)
        val after = withTimeout(5_000) { r.flow.first { it.lyricsLook.style == LyricsStyle.CARD } }.lyricsLook
        assertEquals(before.startFrom(LyricsStyle.CARD), after)
        assertNull(after.motion)
        assertTrue(after.onCard)
        // A weight picked on Bold card is the weight drawn.
        r.setLyricsWeight(LyricsWeight.LIGHT)
        assertEquals(LyricsWeight.LIGHT, withTimeout(5_000) { r.flow.first { it.lyricsLook.boldLines == false } }.lyricsLook.weightNow)

        // A Look code from 1.7.9 (only the Style and its details): the parts follow its Style again.
        r.applyLook(mapOf("lyrics_style" to "s:GLOW", "lyrics_weight" to "s:MEDIUM"))
        val glow = withTimeout(5_000) { r.flow.first { it.lyricsLook.style == LyricsStyle.GLOW } }.lyricsLook
        assertEquals(LyricsMotion.SWEEP, glow.motionNow)
        assertTrue(glow.haloNow)
        assertFalse(glow.onCard)
        assertEquals(LyricsWeight.MEDIUM, glow.weightNow)
        assertEquals(LyricsLookPrefs(style = LyricsStyle.GLOW, weight = LyricsWeight.MEDIUM), glow)

        // A backup from 1.7.9 likewise: Bold card on the player's background, its bold type.
        r.setLyricsMotion(LyricsMotion.NONE)
        r.fromBackup(mapOf("lyrics_style" to "s:CARD", "lyrics_weight" to "s:LIGHT"), settings = true, accounts = false)
        val card = withTimeout(5_000) { r.flow.first { it.lyricsLook.style == LyricsStyle.CARD && it.lyricsLook.motion == null } }.lyricsLook
        assertTrue(card.onCard)
        assertEquals(LyricsWeight.BOLD, card.weightNow)
        assertEquals(LyricsMotion.SWEEP, card.motionNow)
    }

    /** 1.7.9's defaults are 1.8.0's: Prism, growing words, no card, no halo. */
    @Test
    fun theDefaultLookIsAsBefore() {
        val p = LyricsLookPrefs()
        assertEquals(LyricsMotion.GROW, p.motionNow)
        assertFalse(p.wordByWordNow || p.cardNow || p.haloNow || p.boldLinesNow)
        assertEquals(LyricsWeight.LIGHT, p.weightNow)
        assertEquals(1f, p.sizeScale)
        assertEquals(LyricsBackground.PLAYER, p.pageBackground)
        assertEquals(LyricsLook(), newLook(p))
    }
}
