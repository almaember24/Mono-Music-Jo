package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.player.lyricsPageBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.CoverSize
import com.prism.music.data.prefs.LyricsAlign
import com.prism.music.data.prefs.PlayerBackground
import com.prism.music.data.prefs.ScrubberStyle
import com.prism.music.data.prefs.TransportStyle
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.pageTitleStyle
import com.prism.music.ui.components.rememberArtPalette
import com.prism.music.ui.theme.AppFonts
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismUi
import com.prism.music.ui.theme.ThemeColors
import com.prism.music.ui.theme.isAppDark
import com.prism.music.ui.theme.prismTypography

/*
 * Looks (1.7.3): small phones showing Prism in a set of settings (the app's now, or a Look's before it's picked):
 * Home with its mini player and tab bar, the player, and the lyrics. Each is drawn at a phone's real size in a shrunken
 * density, so type, corners and spacing come out as they would, and its colors come from the same code as the app's
 * theme ([rememberColors]).
 */

/** How wide the phones draw what's on them, in dp (a phone's width), whatever size they're shown at. */
private const val PHONE_DP = 360f

/** [s]'s type, corners, spacing and colors for what's inside, without touching the app's own theme. */
@Composable
fun LookTheme(s: AppSettings, dark: Boolean, content: @Composable (ThemeColors) -> Unit) {
    val c = rememberColors(s, dark)
    val typography = remember(s.fontChoice, s.fontStamp, s.type) { com.prism.music.ui.theme.appTypography(s) }
    val ui = remember(s.corners, s.shapes, s.uiDensity, s.cardSize, s.titleSize, s.reduceMotion) { PrismUi.from(s) }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalAppSettings provides s, LocalIsDark provides dark, LocalUi provides ui,
        LocalDensity provides Density(density.density, density.fontScale * s.textScale),
    ) {
        MaterialTheme(colorScheme = c.scheme, typography = typography, shapes = ui.materialShapes) {
            CompositionLocalProvider(LocalContentColor provides c.scheme.onBackground) { content(c) }
        }
    }
}

/** A phone [width] wide (twice as tall) showing [screen] drawn at [PHONE_DP] across. */
@Composable
fun PreviewPhone(width: Dp, modifier: Modifier = Modifier, screen: @Composable () -> Unit) {
    val shape = RoundedCornerShape(width * 0.12f)
    Box(
        modifier.size(width, width * 2.05f)
            // Drawn once into a layer of its own and then only moved: the Looks page has twenty of these, and redrawing
            // each one's whole little screen every frame made scrolling it drop half its frames.
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), shape),
    ) {
        val outer = LocalDensity.current
        val shrink = width.value / PHONE_DP
        CompositionLocalProvider(LocalDensity provides Density(outer.density * shrink, outer.fontScale)) {
            Box(Modifier.fillMaxSize()) { screen() }
        }
    }
}

/** The three phones (Home, the player, the lyrics) for [s], each [width] wide. */
@Composable
fun LookPhones(s: AppSettings, width: Dp, modifier: Modifier = Modifier, gap: Dp = 10.dp) {
    val song by LocalContainer.current.player.currentSong.collectAsState()
    val dark = isAppDark(s)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        PreviewPhone(width) { LookTheme(s, dark) { c -> MiniHome(c, song) } }
        // The player is always white on dark, whatever the theme.
        PreviewPhone(width) { LookTheme(s, true) { c -> MiniPlayerScreen(c, song) } }
        PreviewPhone(width) { LookTheme(s, true) { c -> MiniLyrics(c, song) } }
    }
}

/** A cover: the song's own, or a stand-in in two colors. */
@Composable
internal fun StandIn(song: Song?, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, hues: List<Color>) {
    if (song?.thumbnail != null) Artwork(song.thumbnail, modifier, shape = shape, size = 226)
    else Box(modifier.clip(shape).background(Brush.linearGradient(hues)))
}

internal val standIns = listOf(
    listOf(Color(0xFF272727), Color(0xFF919191)), listOf(Color(0xFF303030), Color(0xFFA5A5A5)),
    listOf(Color(0xFF232323), Color(0xFFABABAB)), listOf(Color(0xFF2D2D2D), Color(0xFFC6C6C6)),
    listOf(Color(0xFF333333), Color(0xFFCCCCCC)),
)

/** The small player's veil over its background for a stored "Show background" [show], fully dark at its slider's start (#309). */
internal fun previewVeil(show: Float): Float {
    val veil = 1f - 0.55f * show.coerceIn(0f, 1f)
    val black = com.prism.music.ui.player.backdropBlackout(show)
    return (veil + (1f - veil) * black).coerceIn(0f, 1f)
}

/** The player as [c]'s settings draw it: its background, the cover's size and corners, the title, progress bar and controls. */
@Composable
fun MiniPlayerScreen(c: ThemeColors, song: Song?) {
    val s = LocalAppSettings.current
    val palette = rememberArtPalette(song?.thumbnail)
    val hues = palette?.let { listOf(it.dominant, it.vibrant) } ?: standIns[0]
    val light = Brush.horizontalGradient(*c.run.toTypedArray())
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (s.playerBackground) {
            PlayerBackground.MESH -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(hues[0].copy(alpha = 0.85f), hues[1].copy(alpha = 0.55f), Color.Black))))
            PlayerBackground.BLUR -> {
                StandIn(song, Modifier.fillMaxSize().blur(60.dp), RoundedCornerShape(0.dp), hues)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = previewVeil(s.playerBackdropShow))))
            }
            PlayerBackground.APPEARANCE -> {
                PageGround(c, Modifier.fillMaxSize(), player = true)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = previewVeil(s.playerBackdropShow))))
            }
            PlayerBackground.THEME -> Box(Modifier.fillMaxSize().background(c.scheme.background))
            PlayerBackground.BLACK -> {}
            PlayerBackground.WHITE -> Box(Modifier.fillMaxSize().background(com.prism.music.ui.player.PlayerWhiteBackdrop))
            PlayerBackground.GRADIENT -> com.prism.music.ui.player.CoverGradientBackdrop(
                com.prism.music.ui.player.rememberCoverGradient(song?.thumbnail)
                    ?: com.prism.music.ui.player.CoverGradient(hues[0].toArgb(), hues[1].toArgb()),
                s.view.gradientDepth,
            )
        }
        // Settings → Player (1.7.4): the layout, the title, the parts under it in their order, the buttons.
        val layout = s.playerLayout
        val vinyl = layout == com.prism.music.data.prefs.PlayerLayout.VINYL
        val fullBleed = s.fullBleedArtwork && !vinyl && layout != com.prism.music.data.prefs.PlayerLayout.CASSETTE
        val extra = if (layout == com.prism.music.data.prefs.PlayerLayout.COVER) maxOf(s.coverSize.extra, CoverSize.LARGE.extra) else s.coverSize.extra
        val side = if (fullBleed) 0.dp else 28.dp - extra.dp
        Column(Modifier.fillMaxSize().padding(top = if (fullBleed) 0.dp else 64.dp)) {
            val coverShape = if (fullBleed || s.coverSize == CoverSize.FULL) RoundedCornerShape(0.dp) else RoundedCornerShape(s.playerArtCorners.dp)
            val centred = s.playerTitleAlign == com.prism.music.data.prefs.PlayerTitleAlign.CENTER
            val title = @Composable {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start) {
                    val big = MaterialTheme.typography.headlineSmall.let { it.copy(fontSize = it.fontSize * s.playerTitleSize.scale) }
                    Text(song?.title ?: tr(R.string.northern_lights), style = big, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song?.artistText ?: tr(R.string.prism_sample), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                }
            }
            if (layout == com.prism.music.data.prefs.PlayerLayout.LYRICS) {
                // The lyrics layout: a small cover beside the title, the lyrics under them.
                Row(Modifier.padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    StandIn(song, Modifier.size(56.dp), RoundedCornerShape(8.dp), hues)
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.weight(1f)) { title() }
                }
                Column(Modifier.padding(horizontal = 28.dp).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    listOf(tr(R.string.somewhere_a_light_is_turning_on), tr(R.string.every_color_finds_its_way), tr(R.string.we_follow_the_line_of_the)).forEachIndexed { i, l ->
                        Text(l, style = MaterialTheme.typography.headlineSmall, color = Color.White.copy(alpha = if (i == 1) 1f else 0.35f))
                    }
                }
                return@Column
            }
            if (vinyl) {
                // A record: the cover as its label.
                Box(Modifier.padding(horizontal = 28.dp).fillMaxWidth().aspectRatio(1f)) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF0E0E0E)), contentAlignment = Alignment.Center) {
                        listOf(0.95f, 0.8f, 0.65f).forEach { f -> Box(Modifier.fillMaxSize(f).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape)) }
                        StandIn(song, Modifier.fillMaxSize(0.42f), CircleShape, hues)
                    }
                    if (s.vinylTonearm) Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 6.dp).size(6.dp, 200.dp).graphicsLayer { rotationZ = 24f; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f) }.background(Color(0xFFD0D0D0), CircleShape))
                }
            } else if (layout == com.prism.music.data.prefs.PlayerLayout.CASSETTE) {
                // A tape: the cover as its label, two reels in the window.
                Box(Modifier.padding(horizontal = 28.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.56f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1F1F1F))) {
                        StandIn(song, Modifier.padding(14.dp).fillMaxWidth().aspectRatio(2.3f), RoundedCornerShape(6.dp), hues)
                        Row(
                            Modifier.align(Alignment.Center).padding(bottom = 20.dp).fillMaxWidth(0.6f).clip(CircleShape).background(Color(0xFF0C0C0C)).padding(horizontal = 18.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) { repeat(2) { Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF3B3B3B)).border(8.dp, Color(0xFFE9E9E9), CircleShape)) } }
                    }
                }
            } else StandIn(song, Modifier.padding(horizontal = side.coerceAtLeast(0.dp)).fillMaxWidth().aspectRatio(1f), coverShape, hues)
            // #320: the title and controls dark over the White background, as the player draws them.
            val inked = with(com.prism.music.ui.player.PlayerInk) { Modifier.inked { s.playerBackground == PlayerBackground.WHITE } }
            Column(Modifier.padding(horizontal = 28.dp).padding(top = 28.dp).then(inked)) {
                title()
                when (layout) {
                    com.prism.music.data.prefs.PlayerLayout.COVER -> {}
                    com.prism.music.data.prefs.PlayerLayout.MINIMAL -> {
                        Spacer(Modifier.height(30.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PreviewPlay(s, c, 92.dp) }
                    }
                    else -> s.playerDeck.forEach { part ->
                        when (part) {
                            com.prism.music.data.prefs.DeckPart.STRIP -> if (s.lyricsOnPlayer) {
                                Spacer(Modifier.height(16.dp))
                                Text(tr(R.string.every_color_finds_its_way), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
                            }
                            com.prism.music.data.prefs.DeckPart.SCRUBBER -> { Spacer(Modifier.height(20.dp)); PreviewBar(s, light) }
                            com.prism.music.data.prefs.DeckPart.TRANSPORT -> {
                                Spacer(Modifier.height(20.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(PrismIcons.Previous, null, Modifier.size(34.dp), tint = Color.White)
                                    PreviewPlay(s, c, 76.dp)
                                    Icon(PrismIcons.Next, null, Modifier.size(34.dp), tint = Color.White)
                                }
                            }
                            com.prism.music.data.prefs.DeckPart.VOLUME -> if (!s.hideVolumeBar) {
                                Spacer(Modifier.height(18.dp))
                                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                                    Box(Modifier.fillMaxWidth(0.65f).height(5.dp).background(Color.White.copy(alpha = 0.85f)))
                                }
                            }
                            com.prism.music.data.prefs.DeckPart.BUTTONS -> {
                                Spacer(Modifier.height(18.dp))
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Row(
                                        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                                    ) { s.playerButtons.forEach { Icon(buttonIcon(it), null, Modifier.size(24.dp), tint = Color.White.copy(alpha = 0.8f)) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The preview's play button, in the Controls style picked. */
@Composable
private fun PreviewPlay(s: AppSettings, c: ThemeColors, size: Dp) {
    when (s.transportStyle) {
        TransportStyle.GLYPHS -> Icon(PrismIcons.Pause, null, Modifier.size(size * 0.68f), tint = Color.White)
        TransportStyle.BUTTON -> Box(
            Modifier.size(size).clip(CircleShape).background(Color.White).border(2.dp, Brush.sweepGradient(c.ring), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(PrismIcons.Pause, null, Modifier.size(size * 0.45f), tint = Color.Black) }
        TransportStyle.OUTLINE -> Box(
            Modifier.size(size).clip(CircleShape).border(1.5.dp, Brush.sweepGradient(c.ring), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(PrismIcons.Pause, null, Modifier.size(size * 0.45f), tint = Color.White) }
    }
}

/** The preview's progress bar: its style (a waveform's bars too), color and the times under it. */
@Composable
private fun PreviewBar(s: AppSettings, light: Brush) {
    val fill = com.prism.music.ui.player.scrubberFill(s.scrubberColor, null) ?: light
    if (s.scrubberStyle == ScrubberStyle.WAVE) {
        Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            PREVIEW_WAVE.forEachIndexed { i, v ->
                Box(Modifier.weight(1f).fillMaxSize(v).clip(CircleShape).background(if (i < PREVIEW_WAVE.size * 0.4f) fill else androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.22f))))
            }
        }
    } else {
        val bar = when (s.scrubberStyle) { ScrubberStyle.BOLD -> 6.dp; ScrubberStyle.HAIRLINE -> 2.dp; else -> 3.dp }
        Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(bar).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
            Box(Modifier.fillMaxWidth(0.4f).height(bar).clip(CircleShape).background(fill))
            if (s.scrubberStyle == ScrubberStyle.THUMB || s.scrubberStyle == ScrubberStyle.RIBBONS) Row {
                Spacer(Modifier.fillMaxWidth(0.38f))
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
    val times = s.playerTimes
    if (times != com.prism.music.data.prefs.PlayerTimes.NONE) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        val st = MaterialTheme.typography.labelMedium
        Text(if (times == com.prism.music.data.prefs.PlayerTimes.LEFT) "" else "1:12", style = st, color = Color.White.copy(alpha = 0.55f))
        Text(if (times == com.prism.music.data.prefs.PlayerTimes.ELAPSED) "" else "−2:31", style = st, color = Color.White.copy(alpha = 0.55f))
    }
}

/** A made-up song shape for the preview's waveform. */
private val PREVIEW_WAVE = listOf(0.3f, 0.45f, 0.6f, 0.5f, 0.7f, 0.85f, 0.75f, 0.9f, 0.6f, 0.5f, 0.8f, 0.95f, 0.85f, 0.7f, 0.9f, 1f, 0.8f, 0.6f, 0.75f, 0.9f, 0.7f, 0.5f, 0.4f, 0.3f)

/**
 * The lyrics as [c]'s settings draw them: alignment, size, the lyrics font, focus blur and the progress line. The lines are
 * Prism's own words, standing in for a song's.
 */
@Composable
fun MiniLyrics(c: ThemeColors, song: Song?) {
    val s = LocalAppSettings.current
    val lines = listOf(tr(R.string.somewhere_a_light_is_turning_on), tr(R.string.every_color_finds_its_way), tr(R.string.we_follow_the_line_of_the), tr(R.string.into_the_bright_of_the_day))
    val face = when (s.lyricsFont) {
        "" -> null
        com.prism.music.data.prefs.FontChoice.CUSTOM.name -> AppFonts.custom(AppFonts.FontSlot.LYRICS)
        else -> com.prism.music.data.prefs.FontChoice.entries.firstOrNull { it.name == s.lyricsFont }?.let(AppFonts::family)
    }
    val base = MaterialTheme.typography.headlineMedium.let { t -> t.copy(
            fontSize = t.fontSize * s.lyricsScale, fontFamily = face ?: t.fontFamily,
            lineHeight = if (t.lineHeight.isSpecified) t.lineHeight * s.lyricsScale else t.lineHeight,
        ) }
    val align = if (s.lyricsAlign == LyricsAlign.CENTER) TextAlign.Center else TextAlign.Start
    // Lyrics, your way (1.7.5): the sung line in its style (held part-way through), the others in their color.
    val look = com.prism.music.ui.player.rememberLyricsLook(s, song?.thumbnail)
    val clock = remember { androidx.compose.runtime.mutableLongStateOf(1300L) }
    val sung = remember(look.uppercase) {
        val words = listOf(tr(R.string.every), "color ", "finds ", "its ", "way")
        look.shown(com.prism.music.data.lyrics.LyricLine(0, words.joinToString(""), 2800, words.mapIndexed { k, w -> com.prism.music.data.lyrics.LyricWord(k * 520L, k * 520L + 480L, w) }))
    }
    val axis = remember(s.lyricsFont, s.fontChoice) {
        (if (s.lyricsFont.isEmpty()) s.fontChoice else com.prism.music.data.prefs.FontChoice.entries.firstOrNull { it.name == s.lyricsFont })?.let(AppFonts::axis)
    }
    val ground = when (s.lyricsPageBackground()) {
        com.prism.music.data.prefs.LyricsBackground.PLAYER -> Brush.verticalGradient(listOf(standIns[0][0], Color.Black))
        com.prism.music.data.prefs.LyricsBackground.DARK -> Brush.verticalGradient(listOf(Color(0xFF0C0C0C), Color(0xFF0C0C0C)))
        com.prism.music.data.prefs.LyricsBackground.COLOR -> com.prism.music.ui.player.cardColor(Color(s.lyricsBgColor)).let { top ->
            Brush.verticalGradient(listOf(top, if (s.lyricsBgColor2 == 0) androidx.compose.ui.graphics.lerp(top, Color.Black, 0.3f) else com.prism.music.ui.player.cardColor(Color(s.lyricsBgColor2))))
        }
        com.prism.music.data.prefs.LyricsBackground.COVER_COLOR -> (look.card ?: com.prism.music.ui.player.cardColor(standIns[0][0])).let { Brush.verticalGradient(listOf(it, androidx.compose.ui.graphics.lerp(it, Color.Black, 0.35f))) }
        else -> Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(standIns[0][0], Color.Black, 0.45f), Color(0xFF0E0E0E)))
    }
    Box(Modifier.fillMaxSize().background(ground)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp).padding(top = 120.dp), verticalArrangement = Arrangement.spacedBy((20 * look.spacing).dp)) {
            lines.forEachIndexed { i, line ->
                val now = i == 1
                Column(Modifier.fillMaxWidth()) {
                    if (now) com.prism.music.ui.player.SungLine(sung, clock, base.copy(textAlign = align, fontWeight = FontWeight(look.weight)), look, axis, Modifier.fillMaxWidth(), onCard = look.card != null)
                    else com.prism.music.ui.player.RestingLine(
                        look.shown(com.prism.music.data.lyrics.LyricLine(-1, line)), base.copy(textAlign = align),
                        look, 0.7f * look.othersAlpha, Modifier.fillMaxWidth().then(if (look.blur > 0f && look.card == null) Modifier.blur((2 * look.blur).dp) else Modifier),
                    )
                    if (now && s.lyricsProgressLine) Box(
                        Modifier.padding(top = 8.dp).align(if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start)
                            .width(120.dp).height(2.dp).clip(CircleShape).background(Brush.horizontalGradient(*c.run.toTypedArray())),
                    )
                }
            }
        }
    }
}
