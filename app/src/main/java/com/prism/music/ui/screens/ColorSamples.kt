package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.frostFill
import com.prism.music.ui.theme.LocalArtworkColor
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import com.prism.music.ui.theme.ThemeColors
import com.prism.music.ui.theme.dynamicSchemes
import com.prism.music.ui.theme.seedFor
import com.prism.music.ui.theme.themeColors

/*
 * Appearance → Color's examples (1.7.2): small Prism pages drawn in the colors a choice would give, worked out by the
 * same code as the app's theme ([themeColors]), so what a card shows is what picking it does.
 */

/** What [s] looks like on a [dark] (or light) page, with the playing cover standing in for "Now playing". */
@Composable
fun rememberColors(s: AppSettings, dark: Boolean): ThemeColors {
    val context = LocalContext.current
    val seed = seedFor(s, dark, LocalArtworkColor.current)
    return remember(s, dark, seed) { themeColors(s, dark, seed, dynamicSchemes(s, context)) }
}

/** The three things Prism's colors are made of, numbered the same in every example. */
enum class ColorPart(val number: Int, val title: String, val detail: String) {
    LIGHT(1, tr(R.string.light), tr(R.string.progress_and_play_the_playing_song)),
    ACCENT(2, tr(R.string.accent), tr(R.string.artist_names_and_links_stars_and)),
    BACKGROUND(3, tr(R.string.background), tr(R.string.behind_the_main_screens_and_sheets)),
}

/**
 * What the app's own pages (Home, Search, Library, Replay, Settings) are drawn on with [c]'s settings, so an example
 * shows the page as it really comes out: Home's background as picked in Glass & backgrounds, else the Ambient frost
 * from what's playing (which takes Page background), else the plain page.
 */
@Composable
fun PageGround(c: ThemeColors, modifier: Modifier = Modifier, player: Boolean = false) {
    val s = c.settings ?: com.prism.music.ui.theme.LocalAppSettings.current
    // [player]: the player's own background (1.7.4), else Home's.
    val (style, photo) = com.prism.music.ui.theme.Backdrop.parse(
        (if (player) s.backdrops[com.prism.music.ui.theme.PLAYER_BACKDROP] else null) ?: s.backdrops[com.prism.music.ui.theme.BackdropScreen.HOME.name],
    )
    androidx.compose.runtime.CompositionLocalProvider(
        com.prism.music.ui.theme.LocalAppSettings provides s, com.prism.music.ui.theme.LocalIsDark provides c.dark,
    ) {
        MaterialTheme(colorScheme = c.scheme) {
            when {
                style != com.prism.music.ui.theme.Backdrop.DEFAULT -> com.prism.music.ui.theme.BackdropLayer(style, photo, s.backdropDim, false, modifier)
                com.prism.music.ui.theme.ambientFrostShown(s, c.dark) -> com.prism.music.ui.components.AmbientFrost(modifier)
                else -> Box(modifier.background(c.scheme.background))
            }
        }
    }
}

/** The bars beside the song that's playing, in the light. */
@Composable
private fun StageBars(c: ThemeColors, height: Dp = 12.dp) {
    Row(Modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(0.55f, 1f, 0.75f).forEach { f ->
            Box(Modifier.width(3.dp).fillMaxHeight(f).clip(RoundedCornerShape(1.dp)).background(Brush.verticalGradient(c.ring.take(3).let { if (it.size < 2) it + it else it })))
        }
    }
}

/** A small numbered glass bead marking a [ColorPart] in an example: a hairline ring, quiet enough to sit beside the part. */
@Composable
fun PartMark(part: ColorPart, c: ThemeColors, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(c.scheme.onBackground.copy(alpha = 0.08f))
            .border(1.dp, c.scheme.onBackground.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${part.number}", color = c.scheme.onBackground.copy(alpha = 0.85f),
            fontSize = (size.value * 0.58f).sp, lineHeight = (size.value * 0.58f).sp, fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The Color page's preview (1.7.2): a small album page as it comes out in [c], showing only what each part really
 * colors: on the page's real background (③, see [PageGround]), the artist in the accent (② — as album and artist pages
 * write it) and a star; the song that's playing written in the light (①) with its bars; the mini player's progress and
 * Play ring and a switch in the light. [song] is what's playing (a stand-in when nothing is). [compact]: one row, for
 * the copy pinned under the bar while the page scrolls.
 */
@Composable
fun ColorStage(c: ThemeColors, song: Song?, modifier: Modifier = Modifier, compact: Boolean = false, marks: Boolean = true) {
    val s = c.scheme
    // With Artist & album pages on "Picture's color", a page's accent is its cover's (none for a black-and-white cover,
    // which keeps yours): the example album page shows what its real page would.
    val pictureAccent = if (c.settings?.pageColors == true) com.prism.music.ui.components.rememberArtPalette(song?.thumbnail)
        ?.accent(c.dark)?.let { com.prism.music.ui.theme.readableOn(it, s.background) } else null
    val accent = pictureAccent ?: s.primary
    // The ② mark only where it really is your accent.
    val accentMarks = marks && pictureAccent == null
    val shape = RoundedCornerShape(if (compact) 18.dp else 24.dp)
    val title = song?.title ?: tr(R.string.blinding_lights)
    val album = song?.album?.title?.takeIf { it.isNotBlank() } ?: if (song == null) tr(R.string.after_hours) else title
    val artist = song?.artistText ?: tr(R.string.the_weeknd)
    val type = MaterialTheme.typography
    val light = Brush.horizontalGradient(*c.run.toTypedArray())
    val card = RoundedCornerShape(14.dp)
    val cardFill = s.onBackground.copy(alpha = if (c.dark) 0.07f else 0.05f)
    Box(modifier.fillMaxWidth().clip(shape).border(1.dp, c.line, shape)) {
        PageGround(c, Modifier.matchParentSize())
        if (compact) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StageCover(song, 44.dp, 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StageBars(c, 10.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(title, Modifier.weight(1f, fill = false), style = type.titleSmall.copy(brush = light), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(artist, Modifier.weight(1f, fill = false), color = accent, style = type.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (accentMarks) PartMark(ColorPart.ACCENT, c, Modifier.padding(start = 6.dp), 14.dp)
                    }
                    Spacer(Modifier.height(4.dp))
                    StageProgress(c, 0.42f)
                }
                Spacer(Modifier.width(12.dp))
                if (marks) PartMark(ColorPart.LIGHT, c, Modifier.padding(end = 6.dp), 14.dp)
                StagePlay(c, 38.dp)
            }
            if (marks) PartMark(ColorPart.BACKGROUND, c, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp), 14.dp)
            return@Box
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // An album page's header: the album, and its artist in the accent, with a star.
            Row(verticalAlignment = Alignment.CenterVertically) {
                StageCover(song, 60.dp, 12.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("ALBUM", color = c.ink3, style = type.labelSmall, letterSpacing = 1.4.sp, maxLines = 1)
                    Text(album, color = s.onBackground, style = type.titleLarge, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(artist, Modifier.weight(1f, fill = false), color = accent, style = type.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (accentMarks) PartMark(ColorPart.ACCENT, c, Modifier.padding(start = 6.dp))
                    }
                }
                Icon(PrismIcons.StarFilled, null, Modifier.padding(start = 8.dp, end = if (marks) 14.dp else 0.dp).size(18.dp), tint = accent)
            }
            // Its songs: the one playing is written in the light, beside its bars.
            Column(
                Modifier.fillMaxWidth().clip(card).background(cardFill).border(1.dp, c.line, card).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(20.dp)) { StageBars(c) }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f, fill = false), style = type.bodyMedium.copy(brush = light), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (marks) PartMark(ColorPart.LIGHT, c, Modifier.padding(start = 6.dp))
                    }
                    Text("3:20", color = c.ink3, style = type.labelSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("2", Modifier.width(20.dp), color = c.ink3, style = type.labelSmall)
                    Text(if (song == null) tr(R.string.in_your_eyes) else tr(R.string.the_next_song), Modifier.weight(1f), color = s.onBackground, style = type.bodyMedium, maxLines = 1)
                    Text("3:57", color = c.ink3, style = type.labelSmall)
                }
            }
            // The mini player: its progress and Play ring in the light.
            Row(
                Modifier.fillMaxWidth().clip(card).background(cardFill).border(1.dp, c.line, card).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageCover(song, 34.dp, 8.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = s.onBackground, style = type.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    StageProgress(c, 0.42f)
                }
                Spacer(Modifier.width(10.dp))
                StagePlay(c, 36.dp)
            }
            // A settings row: its switch in the light.
            Row(
                Modifier.fillMaxWidth().clip(card).background(cardFill).border(1.dp, c.line, card).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.smart_downloads), color = s.onBackground, style = type.bodyMedium, maxLines = 1)
                    Text(tr(R.string.ready_for_when_you_re_offline), color = c.ink2, style = type.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StageSwitch(c)
            }
        }
        // ③ in the page's own corner.
        if (marks) PartMark(ColorPart.BACKGROUND, c, Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = 8.dp))
    }
}

/** The playing cover, or a stand-in gradient. */
@Composable
private fun StageCover(song: Song?, size: Dp, corner: Dp) {
    val shape = RoundedCornerShape(corner)
    if (song?.thumbnail != null) Artwork(song.thumbnail, Modifier.size(size), shape = shape, size = 226)
    else Box(Modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(Color(0xFF272727), Color(0xFF626262), Color(0xFF919191)))))
}

/** Play, ringed in the light. */
@Composable
private fun StagePlay(c: ThemeColors, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(c.scheme.onBackground.copy(alpha = 0.06f))
            .border(if (size > 40.dp) 2.dp else 1.5.dp, Brush.sweepGradient(c.ring), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(PrismIcons.Play, null, Modifier.size(size * 0.42f), tint = c.scheme.onBackground) }
}

/** A progress line lit by the light up to [fraction]. */
@Composable
private fun StageProgress(c: ThemeColors, fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(c.line)) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(CircleShape).background(Brush.horizontalGradient(*c.run.toTypedArray())))
    }
}

/** A switch that's on, its track in the light. */
@Composable
private fun StageSwitch(c: ThemeColors) {
    Box(
        Modifier.width(38.dp).height(22.dp).clip(CircleShape).background(Brush.horizontalGradient(*c.run.toTypedArray())),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(Modifier.padding(end = 3.dp).size(16.dp).clip(CircleShape).background(if (c.white && c.whiteAccent == null) c.scheme.background else Color.White))
    }
}

/**
 * A tiny album page in [c]'s colors: the album and its artist (in the accent), a list with the playing song (in the
 * light, as lists write it), a progress line and play ring and a switch (the light) and a "More" link (accent), on the
 * page's real background ([PageGround]) and a card. [marks] numbers the three parts as in [ColorPart]. [pageAccent]
 * stands in for an artist or album page's own color.
 */
@Composable
fun ColorSample(c: ThemeColors, modifier: Modifier = Modifier, marks: Boolean = false, pageAccent: Color? = null, height: Dp = 150.dp) {
    val s = c.scheme
    val accent = pageAccent ?: s.primary
    val run = c.run
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.fillMaxWidth().height(height).clip(shape).border(1.dp, c.line, shape)) {
        PageGround(c, Modifier.matchParentSize())
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // The album, and its artist in the accent.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(Color(0xFF272727), Color(0xFF919191)))))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.after_hours), color = s.onBackground, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(R.string.the_weeknd), color = accent, fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        if (marks) PartMark(ColorPart.ACCENT, c, Modifier.padding(start = 5.dp))
                    }
                }
                Icon(PrismIcons.StarFilled, null, Modifier.padding(end = if (marks) 16.dp else 0.dp).size(13.dp), tint = accent)
            }
            // A card of songs: the playing one written in the light, as lists write it.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(s.onBackground.copy(alpha = if (c.dark) 0.07f else 0.05f)).padding(horizontal = 8.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr(R.string.blinding_lights), Modifier.weight(1f), style = androidx.compose.ui.text.TextStyle(brush = Brush.horizontalGradient(*run.toTypedArray())),
                        fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip,
                    )
                    Text("3:20", color = c.ink2, fontSize = 8.5.sp, lineHeight = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.in_your_eyes), Modifier.weight(1f), color = s.onBackground, fontSize = 9.5.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Clip)
                    Text("3:57", color = c.ink2, fontSize = 8.5.sp, lineHeight = 11.sp)
                }
            }
            // The light: progress, the play ring and a switch.
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(c.line)) {
                Box(Modifier.fillMaxWidth(0.6f).height(3.dp).clip(CircleShape).background(Brush.horizontalGradient(*run.toTypedArray())))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, Brush.sweepGradient(c.ring), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(PrismIcons.Play, null, Modifier.size(11.dp), tint = s.onBackground)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(26.dp).height(15.dp).clip(CircleShape).background(Brush.horizontalGradient(*run.toTypedArray())), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.padding(end = 2.dp).size(11.dp).clip(CircleShape).background(if (c.white && c.whiteAccent == null) s.background else Color.White))
                }
                if (marks) PartMark(ColorPart.LIGHT, c, Modifier.padding(start = 5.dp))
                Spacer(Modifier.weight(1f))
                Text("MORE", color = accent, fontSize = 8.5.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
        }
        // ③ in the page's corner (① and ② sit beside their parts above).
        if (marks) PartMark(ColorPart.BACKGROUND, c, Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 4.dp))
    }
}

/** Text at a contrast level: a title, secondary lines and a hairline, as a page would show them. */
@Composable
fun TextSample(c: ThemeColors, modifier: Modifier = Modifier) {
    val s = c.scheme
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().height(96.dp).clip(shape).background(s.background).border(1.dp, c.line, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(tr(R.string.blinding_lights), color = s.onBackground, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(tr(R.string.the_weeknd_2020), color = c.ink2, fontSize = 9.5.sp, lineHeight = 12.sp, maxLines = 1)
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Text(tr(R.string.in_your_eyes), color = s.onBackground, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(tr(R.string.t_3_min_explicit), color = c.ink3, fontSize = 9.5.sp, lineHeight = 12.sp, maxLines = 1)
    }
}

/** One choice shown as a card: its example on top, then its name and what it does; the picked one is ringed. */
@Composable
fun ChoiceCard(title: String, detail: String?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, example: @Composable () -> Unit) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.clip(ui.tile)
            .background(frostFill(if (selected) 2.6f else 1f))
            .then(if (selected) Modifier.border(2.dp, PrismTokens.spectrum, ui.tile) else Modifier.border(1.dp, scheme.onSurface.copy(alpha = 0.08f), ui.tile))
            .semantics { role = Role.RadioButton; this.selected = selected }
            .clickable(onClick = onClick).padding(8.dp),
    ) {
        example()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            if (selected) Icon(PrismIcons.CheckCircle, tr(R.string.picked), Modifier.size(18.dp), tint = scheme.onSurface)
        }
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, minLines = 2, maxLines = 3)
    }
}

/** Choices as cards, [columns] to a row. */
@Composable
fun <T> ChoiceCards(
    options: List<T>, selected: T, title: (T) -> String, detail: (T) -> String?, columns: Int = 2, onSelect: (T) -> Unit,
    example: @Composable (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { o -> ChoiceCard(title(o), detail(o), o == selected, Modifier.weight(1f), { onSelect(o) }) { example(o) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
