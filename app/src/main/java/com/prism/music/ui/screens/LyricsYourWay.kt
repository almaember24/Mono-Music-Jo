package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.prefs.LyricsAhead
import com.prism.music.data.prefs.LyricsAlign
import com.prism.music.data.prefs.LyricsBackground
import com.prism.music.data.prefs.LyricsColor
import com.prism.music.data.prefs.LyricsExtraPlace
import com.prism.music.data.prefs.LyricsLookPrefs
import com.prism.music.data.prefs.LyricsMotion
import com.prism.music.data.prefs.LyricsPosition
import com.prism.music.data.prefs.LyricsStyle
import com.prism.music.data.prefs.LyricsWeight
import com.prism.music.ui.player.LyricsLook
import com.prism.music.ui.player.RestingLine
import com.prism.music.ui.player.drawNote
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import com.prism.music.ui.player.SungLine
import com.prism.music.ui.player.cardColor
import com.prism.music.ui.player.extraStyle
import com.prism.music.ui.player.lyricsFill
import com.prism.music.ui.player.lyricsLookOf
import com.prism.music.ui.player.rememberLyricsLook
import com.prism.music.ui.player.solid
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlin.math.roundToInt

/*
 * Customize Prism → Lyrics: how the lyrics look, in four parts (1.8.0, #253), each picked on its own: the Animation (how
 * the sung line moves), the Text, the Background and the Effects. The six Styles of 1.7.5 are where you "Start from": each
 * sets the parts it used to fix, and any part can be changed after. Every choice shows what it does as a small example,
 * drawn by the same code as the lyrics themselves (always dark, white type).
 */

private val SampleDark = Color(0xFF151515)

/** Prism's own words for the examples, timed word by word: a line sung over three seconds. */
// The string's own trailing space doesn't survive (resources trim it): "Everycolor" until 1.8.0.
private val SAMPLE_WORDS = listOf(tr(R.string.every).trimEnd() + " ", "color ", "finds ", "its ", "way")
private val SAMPLE_LINE = LyricLine(
    0, SAMPLE_WORDS.joinToString(""), 3000,
    SAMPLE_WORDS.mapIndexed { i, w -> LyricWord(i * 560L, i * 560L + 520L, w) },
)
private val SAMPLE_BEFORE = LyricLine(-1, tr(R.string.somewhere_a_light_is_turning_on))
private val SAMPLE_AFTER = LyricLine(-1, tr(R.string.we_follow_the_line_of_the))
/** Background vocals under the sung line, in Prism's own words too. */
private val SAMPLE_BACKING = "(" + SAMPLE_WORDS.take(2).joinToString("").trim().lowercase() + ")"

/** Where the examples rest: part-way through the line, a few words sung. */
private const val SAMPLE_REST_MS = 1500L
/** Times round the sample line when the page opens or a setting changes, before the examples rest. */
private const val SAMPLE_LOOPS = 3

/**
 * The examples' clock: [SAMPLE_LOOPS] times round the sample line whenever the page opens or a setting changes, then
 * held part-way through (at once with Reduce motion). Not round and round for as long as the page is open: Prism's
 * sweep lays its line out again every frame, and while any accessibility service is on, each layout is reported to it,
 * so the page never went quiet (1.7.9's Android 9 check couldn't read the screen there at all).
 */
@Composable
private fun rememberSampleClock(): State<Long> {
    val s = LocalAppSettings.current
    val clock = remember { mutableLongStateOf(SAMPLE_REST_MS) }
    LaunchedEffect(s) {
        // Still with Reduce motion, and with Android's animations turned off (as the infinite transition before it was).
        val moving = !s.reduceMotion && (coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f) > 0f
        if (moving) repeat(SAMPLE_LOOPS) {
            val start = withInfiniteAnimationFrameMillis { it }
            var now = start
            while (now - start < SAMPLE_LOOP_MS) {
                clock.longValue = SAMPLE_FROM_MS + (now - start)
                now = withInfiniteAnimationFrameMillis { it }
            }
        }
        clock.longValue = SAMPLE_REST_MS
    }
    return clock
}
private const val SAMPLE_FROM_MS = -400L
private const val SAMPLE_LOOP_MS = 4000L

/** The lyrics' typeface on its weight axis (as the lyrics page uses it), for Prism's sweep in the examples. */
@Composable
private fun sampleAxis(): androidx.compose.ui.text.font.FontFamily? {
    val s = LocalAppSettings.current
    val choice = if (s.lyricsFont.isEmpty()) s.fontChoice else com.prism.music.data.prefs.FontChoice.entries.firstOrNull { it.name == s.lyricsFont }
    return remember(choice) { choice?.let(com.prism.music.ui.theme.AppFonts::axis) }
}

/** The look as the settings have it now, for the song playing (its cover's colors). */
@Composable
private fun currentLook(): LyricsLook {
    val s = LocalAppSettings.current
    return rememberLyricsLook(s, LocalContainer.current.player.currentSong.value?.thumbnail)
}

/** The look [p] would draw, for the song playing: how each choice's example shows it, with the rest of your settings. */
@Composable
private fun lookFor(p: LyricsLookPrefs): LyricsLook {
    val cover = LocalContainer.current.player.currentSong.value?.thumbnail
    val sung = lyricsFill(p.sungColor, p.sungCustom, cover)
    val others = lyricsFill(p.otherColor, p.otherCustom, cover).solid()
    val extra = lyricsFill(p.extraColor, p.otherCustom, cover).solid()
    val palette = com.prism.music.ui.components.rememberArtPalette(cover)
    val card = if (p.onCard) cardColor(palette?.vibrant ?: palette?.dominant ?: Color(0xFF575757)) else null
    return remember(p, sung, others, extra, card) { lyricsLookOf(p, sung, others, extra, card) }
}

/**
 * Three lines as the lyrics page draws them in [look]: the one before, the sung one going through its words, the next.
 * [ground] stands in for the lyrics' background ([look]'s card color when it has one); [backing] adds background vocals
 * under the sung line and [progress] its progress line.
 */
@Composable
fun LyricsSample(
    look: LyricsLook, modifier: Modifier = Modifier, ground: Brush? = null, size: Float = 1f, lines: Int = 3, extra: String? = null,
    backing: Boolean = false, progress: Boolean = false,
) {
    val clock = rememberSampleClock()
    val axis = sampleAxis()
    val s = LocalAppSettings.current
    val centred = s.lyricsAlign == LyricsAlign.CENTER
    val font = com.prism.music.ui.theme.LocalLyricsFont.current ?: MaterialTheme.typography.bodyLarge.fontFamily
    val style = TextStyle(
        fontSize = (17 * size * look.sizeScale).sp, lineHeight = (21 * size * look.sizeScale).sp, fontFamily = font,
        fontWeight = FontWeight(look.weight), textAlign = if (centred) TextAlign.Center else TextAlign.Start,
    )
    val bg = ground ?: look.card?.let { SolidColor(it) } ?: Brush.verticalGradient(listOf(Color(0xFF272727), SampleDark))
    // A picture of the style, not text to read: its line changes every frame, and as text it kept accessibility busy
    // for as long as the page was open (Android 9's screen reader never settled there; the card's own title says it).
    Box(modifier.clearAndSetSemantics { }.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((5 * look.spacing).dp)) {
            if (lines >= 3) RestingLine(look.shown(SAMPLE_BEFORE), style, look, 0.8f * look.othersAlpha, Modifier.fillMaxWidth(), maxLines = 1)
            Column(Modifier.fillMaxWidth()) {
                if (extra != null && look.extraAbove) SampleExtra(extra, style, look)
                SungLine(look.shown(SAMPLE_LINE), clock, style, look, axis, Modifier.fillMaxWidth(), maxLines = 1, onCard = look.card != null)
                if (progress) SampleProgress(clock, centred)
                if (backing) Text(
                    if (look.uppercase) SAMPLE_BACKING.uppercase() else SAMPLE_BACKING,
                    style = style.copy(fontSize = style.fontSize * look.backingSize, lineHeight = style.lineHeight * look.backingSize * 1.07f, fontWeight = FontWeight.Normal, shadow = look.shadow),
                    color = (if (look.card != null) Color.Black else Color.White).copy(alpha = 0.6f), maxLines = 1, modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
                if (extra != null && !look.extraAbove) SampleExtra(extra, style, look)
            }
            if (lines >= 2 && look.ahead > 0) RestingLine(look.shown(SAMPLE_AFTER), style, look, 0.8f * look.othersAlpha, Modifier.fillMaxWidth(), maxLines = 1)
        }
    }
}

@Composable
private fun SampleExtra(text: String, style: TextStyle, look: LyricsLook) {
    Text(
        text, style = look.extraStyle(style, 0.6f, 0.66f), color = (if (look.card != null) Color.Black else look.extraColor).copy(alpha = 0.78f),
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** The progress line under the sample's sung line, as the lyrics page draws it (read in the draw phase). */
@Composable
private fun SampleProgress(clock: State<Long>, centred: Boolean) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(top = 5.dp).height(2.dp)) {
        val f = (clock.value.toFloat() / SAMPLE_LINE.endMs).coerceIn(0f, 1f)
        if (f <= 0f) return@Canvas
        val full = size.width * 0.7f
        val w = full * f
        val x = if (centred) (size.width - w) / 2 else 0f
        drawRoundRect(
            com.prism.music.ui.theme.PrismTokens.spectrum(true, x, x + full), Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height),
            androidx.compose.ui.geometry.CornerRadius(size.height / 2),
        )
    }
}

// ---------------------------------------------------------------------------- Start from

/**
 * "Start from" (1.7.5's Styles): each card is your look started again from it. A template, not a lock: the parts below
 * can each be changed after, and the card picked then says so.
 */
@Composable
fun LyricsStyleGroup() {
    val p = LocalAppSettings.current.lyricsLook
    val prefs = LocalContainer.current.settings
    Box(Modifier.testTag("lyricsStartFrom")) {
        Group(tr(R.string.start_from), info = tr(R.string.start_from_info)) {
            ChoiceCards(
                LyricsStyle.entries, p.style, title = { it.label },
                detail = { if (it == p.style && p.changedFromStart) tr(R.string.with_your_changes) else it.note },
                onSelect = { prefs.setLyricsStartFrom(it) },
            ) { style -> LyricsSample(lookFor(p.startFrom(style)), Modifier.fillMaxWidth(), size = 0.7f, lines = 2) }
        }
    }
}

// ---------------------------------------------------------------------------- Animation

/** The Animation part: how the line being sung moves, each one as it would draw with the rest of your look. */
@Composable
fun LyricsAnimationChoices() {
    val p = LocalAppSettings.current.lyricsLook
    val prefs = LocalContainer.current.settings
    ChoiceCards(
        LyricsMotion.entries, p.motionNow, title = { it.label }, detail = { it.note },
        onSelect = { prefs.setLyricsMotion(it) },
    ) { motion -> LyricsSample(lookFor(p.copy(motion = motion)), Modifier.fillMaxWidth(), size = 0.7f, lines = 2) }
    if (p.motionNow != LyricsMotion.NONE) Toggle(
        tr(R.string.word_by_word_on_every_line), tr(R.string.lines_timed_as_a_whole_move_too), p.wordByWordNow,
        info = tr(R.string.word_by_word_on_every_line_info),
    ) { prefs.setLyricsWordByWord(it) }
}

@Composable
fun LyricsAnimationGroup() {
    Box(Modifier.testTag("lyricsAnimation")) {
        Group(tr(R.string.animation), info = tr(R.string.animation_info)) { LyricsAnimationChoices() }
    }
}

// ---------------------------------------------------------------------------- Text

/** The Text part: alignment, size, weight, spacing and capitals, the colors, and the other lines (how dim, how many, where). */
@Composable
fun LyricsTextGroup() {
    val s = LocalAppSettings.current
    val p = s.lyricsLook
    val prefs = LocalContainer.current.settings
    var picking by remember { mutableStateOf<String?>(null) }
    Box(Modifier.testTag("lyricsText")) {
        Group(tr(R.string.text)) {
            LyricsSample(currentLook(), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
            Label(tr(R.string.alignment))
            Chips(LyricsAlign.entries, s.lyricsAlign, { it.label }) { prefs.setLyricsAlign(it) }
            SliderSetting(tr(R.string.text_size), s.lyricsScale, 0.75f..1.4f, { "${(it * 100).roundToInt()}%" }) { prefs.setLyricsScale(it) }
            Label(tr(R.string.weight))
            Chips(LyricsWeight.entries, p.weightNow, { it.label }) { prefs.setLyricsWeight(it) }
            SliderSetting(tr(R.string.line_spacing), p.spacing, 0.6f..1.8f, { worded(if (it < 0.85f) tr(R.string.tight) else if (it <= 1.15f) tr(R.string.as_usual) else tr(R.string.airy), it) }) { prefs.setLyricsSpacing(it) }
            Toggle(tr(R.string.capitals), tr(R.string.every_line_in_capitals), p.uppercase) { prefs.setLyricsUppercase(it) }
            Label(
                tr(R.string.the_line_being_sung),
                info = if (p.motionNow == LyricsMotion.FILL && p.sungColor == LyricsColor.WHITE) tr(R.string.with_color_fills_white_fills_with_your_light) else null,
            )
            LyricsColorSwatches(p.sungColor, p.sungCustom, { prefs.setLyricsSungColor(it) }) { picking = "sung" }
            Label(tr(R.string.the_other_lines), info = if (p.onCard) tr(R.string.on_bold_card_s_color_the) else null)
            LyricsColorSwatches(p.otherColor, p.otherCustom, { prefs.setLyricsOtherColor(it) }, spectrum = false) { picking = "other" }
            SliderSetting(tr(R.string.other_lines), p.othersAlpha, 0.2f..1f, { worded(if (it < 0.35f) tr(R.string.faint) else if (it < 0.75f) tr(R.string.dimmed) else tr(R.string.bright), it) }) { prefs.setLyricsOthersAlpha(it) }
            SliderSetting(tr(R.string.focus_blur), p.blurAmount, 0f..2f, { if (it < 0.05f) tr(R.string.off) else "${(it * 100).roundToInt()}%" }) { prefs.setLyricsBlurAmount(if (it < 0.05f) 0f else it) }
            if (android.os.Build.VERSION.SDK_INT < 31 && p.blurAmount > 0f) Note(tr(R.string.focus_blur_needs_android_12_or))
            Label(tr(R.string.lines_to_come), info = tr(R.string.how_many_of_the_next_lines))
            Chips(LyricsAhead.entries, p.ahead, { it.label }) { prefs.setLyricsAhead(it) }
            Label(tr(R.string.where_the_sung_line_sits))
            ChoiceCards(
                LyricsPosition.entries, p.position, title = { it.label }, detail = { it.note }, columns = 3,
                onSelect = { prefs.setLyricsPosition(it) },
            ) { PositionSample(it.fraction, p.ahead.lines) }
        }
    }
    picking?.let { which ->
        val sung = which == "sung"
        com.prism.music.ui.components.ColorPickerSheet(
            if (sung) tr(R.string.the_line_being_sung) else tr(R.string.the_other_lines),
            if (sung) p.sungCustom else p.otherCustom,
            onDismiss = { picking = null },
            preview = { col ->
                val look = currentLook()
                LyricsSample(
                    if (sung) look.copy(sung = SolidColor(com.prism.music.ui.player.readableOnDark(Color(col))), sungSolid = com.prism.music.ui.player.readableOnDark(Color(col)))
                    else look.copy(others = com.prism.music.ui.player.readableOnDark(Color(col))),
                    Modifier.fillMaxWidth(), lines = 2,
                )
            },
        ) { col -> if (sung) prefs.setLyricsSungCustom(col) else prefs.setLyricsOtherCustom(col) }
    }
}

/** Round swatches for a lyrics color, each showing what it is; Your color opens the picker ([onCustom]). */
@Composable
private fun LyricsColorSwatches(selected: LyricsColor, custom: Int, onSelect: (LyricsColor) -> Unit, spectrum: Boolean = true, onCustom: () -> Unit) {
    val cover = LocalContainer.current.player.currentSong.value?.thumbnail
    val options = LyricsColor.entries.filter { spectrum || it != LyricsColor.SPECTRUM }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        options.forEach { o ->
            val fill = lyricsFill(o, custom, cover) ?: SolidColor(Color.White)
            val on = o == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { if (o == LyricsColor.CUSTOM) onCustom() else onSelect(o) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(SampleDark).background(fill, CircleShape)
                        .then(
                            if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    o.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A tall dark screen of lyrics as bars: the sung one bright at [fraction] of the way down, then [ahead] lines to come. */
@Composable
private fun PositionSample(fraction: Float, ahead: Int) {
    val light = Brush.horizontalGradient(*com.prism.music.ui.theme.PrismTokens.spectrumColors(true).toTypedArray())
    Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF272727), SampleDark)))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
            val row = 9.dp.toPx()
            val h = 3.5.dp.toPx()
            val at = 4.dp.toPx() + (size.height - 8.dp.toPx()) * fraction * 1.4f
            val before = (at / row).toInt()
            for (k in -before..10) {
                val y = at + k * row
                if (y < 0 || y > size.height - h) continue
                if (k > 0 && k > ahead) continue
                val w = size.width * (if (k % 2 == 0) 0.82f else 0.64f)
                if (k == 0) drawRoundRect(light, androidx.compose.ui.geometry.Offset(0f, y - 1.dp.toPx()), androidx.compose.ui.geometry.Size(w, h + 2.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(h))
                else drawRoundRect(Color.White.copy(alpha = if (k < 0) 0.2f else 0.32f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h))
            }
        }
    }
}

// ---------------------------------------------------------------------------- Translation & romanization

@Composable
fun LyricsExtrasGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.translations_romanized_lines), info = tr(R.string.how_a_translation_or_a_line)) {
        LyricsSample(currentLook(), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), lines = 1, extra = tr(R.string.cada_color_encuentra_su_camino))
        Chips(LyricsExtraPlace.entries, s.lyricsExtraPlace, { it.label }) { prefs.setLyricsExtraPlace(it) }
        SliderSetting(tr(R.string.size), s.lyricsExtraSize, 0.7f..1.4f, { "${(it * 100).roundToInt()}%" }) { prefs.setLyricsExtraSize(it) }
        Label(tr(R.string.color_2), info = if (s.lyricsExtraColor == LyricsColor.CUSTOM) tr(R.string.your_color_here_is_the_one) else null)
        LyricsColorSwatches(s.lyricsExtraColor, s.lyricsOtherCustom, { prefs.setLyricsExtraColor(it) }) { prefs.setLyricsExtraColor(LyricsColor.CUSTOM) }
        Toggle(tr(R.string.italics), tr(R.string.sets_them_apart_from_the_lyrics), s.lyricsExtraItalic) { prefs.setLyricsExtraItalic(it) }
    }
}

// ---------------------------------------------------------------------------- Background

/** Behind the lyrics page: the six backgrounds, and Bold card (the cover's color, the lines still to come dark on it). */
private data class Behind(val ground: LyricsBackground, val card: Boolean)

private val BEHIND = listOf(Behind(LyricsBackground.PLAYER, false), Behind(LyricsBackground.PLAYER, true)) +
    LyricsBackground.entries.filter { it != LyricsBackground.PLAYER }.map { Behind(it, false) }

/** The Background part: behind the lyrics page (Bold card among them), your colors, and immersive mode's. */
@Composable
fun LyricsBackgroundGroup() {
    val s = LocalAppSettings.current
    val p = s.lyricsLook
    val prefs = LocalContainer.current.settings
    var picking by remember { mutableStateOf<Int?>(null) }
    Box(Modifier.testTag("lyricsBackground")) {
        Group(tr(R.string.background)) {
            Label(tr(R.string.behind_the_lyrics), info = if (p.background == LyricsBackground.CANVAS) tr(R.string.animated_cover_songs_without_a_moving) else null)
            ChoiceCards(
                BEHIND, if (p.onCard) Behind(LyricsBackground.PLAYER, true) else Behind(p.background, false),
                title = { if (it.card) tr(R.string.bold_card) else it.ground.label },
                detail = { when { it.card -> tr(R.string.big_bold_lines_on_the_cover); it.ground == LyricsBackground.PLAYER -> tr(R.string.the_player_s_own_background); else -> it.ground.note } },
                onSelect = { prefs.setLyricsBehind(it.ground, it.card) },
            ) { b ->
                val look = lookFor(p.copy(background = b.ground, card = b.card))
                LyricsSample(look, Modifier.fillMaxWidth(), ground = if (b.card) null else backgroundSample(b.ground), size = 0.7f, lines = 2)
            }
            if (s.lyricsBackground == LyricsBackground.COLOR || s.immersiveBackground == LyricsBackground.COLOR) {
                Label(tr(R.string.your_colors), info = tr(R.string.kept_dark_enough_for_white_words))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(tr(R.string.top), Color(s.lyricsBgColor)) { picking = 1 }
                    ColorDot(tr(R.string.bottom), if (s.lyricsBgColor2 == 0) null else Color(s.lyricsBgColor2)) { picking = 2 }
                    if (s.lyricsBgColor2 != 0) Text(
                        tr(R.string.one_color), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { prefs.setLyricsBgColors(s.lyricsBgColor, 0) }.padding(8.dp),
                    )
                }
            }
            Label(tr(R.string.immersive_mode))
            ChoiceCards(
                LyricsBackground.entries, s.immersiveBackground, title = { if (it == LyricsBackground.PLAYER) tr(R.string.the_cover) else it.label },
                detail = { if (it == LyricsBackground.PLAYER) tr(R.string.filling_the_screen_as_now) else it.note },
                onSelect = { prefs.setImmersiveBackground(it) },
            ) { LyricsSample(currentLook().withoutCard(s.lyricsOthersAlpha), Modifier.fillMaxWidth(), ground = backgroundSample(it, immersive = true), size = 0.7f, lines = 2) }
        }
    }
    picking?.let { which ->
        val prefsNow = LocalAppSettings.current
        com.prism.music.ui.components.ColorPickerSheet(
            if (which == 1) tr(R.string.top_color) else tr(R.string.bottom_color),
            if (which == 1) prefsNow.lyricsBgColor else prefsNow.lyricsBgColor2.takeIf { it != 0 } ?: prefsNow.lyricsBgColor,
            onDismiss = { picking = null },
            reset = if (which == 2 && prefsNow.lyricsBgColor2 != 0) tr(R.string.one_color) to { prefs.setLyricsBgColors(prefsNow.lyricsBgColor, 0) } else null,
            preview = { col ->
                val top = cardColor(Color(if (which == 1) col else prefsNow.lyricsBgColor))
                val bottom = if (which == 2) cardColor(Color(col)) else if (prefsNow.lyricsBgColor2 == 0) androidx.compose.ui.graphics.lerp(top, Color.Black, 0.3f) else cardColor(Color(prefsNow.lyricsBgColor2))
                LyricsSample(currentLook(), Modifier.fillMaxWidth(), ground = Brush.verticalGradient(listOf(top, bottom)), lines = 2)
            },
        ) { col -> if (which == 1) prefs.setLyricsBgColors(col, prefsNow.lyricsBgColor2) else prefs.setLyricsBgColors(prefsNow.lyricsBgColor, col) }
    }
}

/** A round swatch with its name; empty ([color] null) for a color not picked yet. */
@Composable
private fun ColorDot(label: String, color: Color?, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(color ?: Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (color == null) Text("+", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What each background looks like, drawn small (the cover's colors from the song playing, or Prism's own). */
@Composable
private fun backgroundSample(kind: LyricsBackground, immersive: Boolean = false): Brush {
    val s = LocalAppSettings.current
    val cover = LocalContainer.current.player.currentSong.value?.thumbnail
    val palette = com.prism.music.ui.components.rememberArtPalette(cover)
    val a = palette?.vibrant ?: Color(0xFF858585)
    val b = palette?.dominant ?: Color(0xFF9E9E9E)
    val dim = { c: Color -> androidx.compose.ui.graphics.lerp(c, Color.Black, 0.55f) }
    return when (kind) {
        LyricsBackground.PLAYER -> if (immersive) Brush.linearGradient(listOf(a, b)) else Brush.verticalGradient(listOf(dim(a), Color(0xFF151515)))
        LyricsBackground.DARK -> SolidColor(Color(0xFF0C0C0C))
        LyricsBackground.BLUR -> Brush.radialGradient(listOf(dim(a), dim(b), Color(0xFF101010)))
        LyricsBackground.CANVAS -> Brush.linearGradient(listOf(dim(b), dim(a), dim(b)))
        LyricsBackground.COVER_COLOR -> cardColor(a).let { Brush.verticalGradient(listOf(it, androidx.compose.ui.graphics.lerp(it, Color.Black, 0.35f))) }
        LyricsBackground.COLOR -> cardColor(Color(s.lyricsBgColor)).let { top ->
            Brush.verticalGradient(listOf(top, if (s.lyricsBgColor2 == 0) androidx.compose.ui.graphics.lerp(top, Color.Black, 0.3f) else cardColor(Color(s.lyricsBgColor2))))
        }
    }
}

// ---------------------------------------------------------------------------- Effects

/** The Effects part: glow, shadow and the progress line (cards that switch on and off), background vocals, breaks. */
@Composable
fun LyricsEffectsGroup() {
    val s = LocalAppSettings.current
    val p = s.lyricsLook
    val prefs = LocalContainer.current.settings
    Box(Modifier.testTag("lyricsEffects")) {
        Group(tr(R.string.effects)) {
            LyricsSample(currentLook(), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), lines = 1, backing = true, progress = s.lyricsProgressLine)
            // Each card shows its effect on, and switches it on or off.
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceCard(tr(R.string.glow), tr(R.string.glow_round_the_words), p.haloNow, Modifier.weight(1f), { prefs.setLyricsHalo(!p.haloNow) }) {
                        LyricsSample(lookFor(p.copy(halo = true)), Modifier.fillMaxWidth(), size = 0.7f, lines = 1)
                    }
                    ChoiceCard(tr(R.string.shadow), tr(R.string.helps_the_words_read_over_photos), p.shadow, Modifier.weight(1f), { prefs.setLyricsShadow(!p.shadow) }) {
                        LyricsSample(lookFor(p.copy(shadow = true)), Modifier.fillMaxWidth(), ground = backgroundSample(LyricsBackground.BLUR), size = 0.7f, lines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceCard(tr(R.string.progress_line), tr(R.string.split_light_under_the_line_being), s.lyricsProgressLine, Modifier.weight(1f), { prefs.setLyricsProgressLine(!s.lyricsProgressLine) }) {
                        LyricsSample(currentLook(), Modifier.fillMaxWidth(), size = 0.7f, lines = 1, progress = true)
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            if (android.os.Build.VERSION.SDK_INT < 31 && p.haloNow) Note(tr(R.string.glow_needs_android_12_or_later))
            SliderSetting(tr(R.string.background_vocals), p.backingSize, 0.4f..0.9f, { tr(R.string.of_the_line, (it * 100).roundToInt()) }) { prefs.setLyricsBackingSize(it) }
            Label(tr(R.string.instrumental_breaks), info = tr(R.string.what_shows_between_lines_while_the))
            ChoiceCards(
                com.prism.music.data.prefs.LyricsGapMark.entries, p.gapMark, title = { it.label }, detail = { null },
                onSelect = { prefs.setLyricsGapMark(it) },
            ) { GapSample(it == com.prism.music.data.prefs.LyricsGapMark.NOTE, s.lyricsAlign == LyricsAlign.CENTER) }
        }
    }
}

/** An instrumental break as the lyrics page draws it: dots or a note, half lit, where the lines sit. */
@Composable
private fun GapSample(note: Boolean, centered: Boolean) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
        androidx.compose.foundation.Canvas(Modifier.size(width = if (note) 24.dp else 42.dp, height = if (note) 24.dp else 10.dp)) {
            if (note) {
                drawNote(Color.White.copy(alpha = 0.35f))
                clipRect(top = size.height * 0.5f) { drawNote(Color.White) }
            } else {
                val r = size.height / 2
                val stride = (size.width - 2 * r) / 2
                repeat(3) { d -> drawCircle(Color.White.copy(alpha = if (d == 0) 1f else 0.35f), r, Offset(r + d * stride, size.height / 2)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------- On the player

/** The lyric line on the player itself (#121: centered, up to two lines). */
@Composable
fun LyricsOnThePlayerGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.on_the_player)) {
        Label(tr(R.string.the_line_on_the_player), info = tr(R.string.the_lyric_over_the_progress_bar))
        ChoiceCards(
            listOf(LyricsAlign.START, LyricsAlign.CENTER), s.lyricsStripAlign, title = { if (it == LyricsAlign.START) tr(R.string.left) else tr(R.string.centered) },
            detail = { null }, onSelect = { prefs.setLyricsStripAlign(it) },
        ) { StripSample(it == LyricsAlign.CENTER, if (s.lyricsStripTwoLines) 2 else 1) }
        Toggle(
            tr(R.string.up_to_two_lines), tr(R.string.a_long_line_wraps_instead_of), s.lyricsStripTwoLines,
            info = tr(R.string.the_line_keeps_room_for_two),
        ) { prefs.setLyricsStripTwoLines(it) }
    }
}

/** The player's foot as bars: the lyric line ([rows] lines, left or [centered]) over the progress bar. */
@Composable
private fun StripSample(centered: Boolean, rows: Int) {
    val light = Brush.horizontalGradient(*com.prism.music.ui.theme.PrismTokens.spectrumColors(true).toTypedArray())
    Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF272727), SampleDark)))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp)) {
            val h = 4.dp.toPx()
            val gap = 5.dp.toPx()
            val bar = size.height - 2.dp.toPx()
            // Progress bar: a third played.
            drawRoundRect(Color.White.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(0f, bar), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
            drawRoundRect(light, androidx.compose.ui.geometry.Offset(0f, bar), androidx.compose.ui.geometry.Size(size.width * 0.34f, 2.dp.toPx()))
            // The line, bottom up; a two-line one wraps its end onto the second.
            val widths = if (rows > 1) listOf(0.86f, 0.46f) else listOf(0.58f)
            widths.forEachIndexed { i, f ->
                val w = size.width * f
                val y = bar - 8.dp.toPx() - (widths.size - i) * (h + gap)
                val x = if (centered) (size.width - w) / 2 else 0f
                drawRoundRect(Color.White.copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h))
            }
        }
    }
}
