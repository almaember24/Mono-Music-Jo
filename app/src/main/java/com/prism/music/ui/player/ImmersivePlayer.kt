package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import com.prism.music.ui.theme.withLyricsFont
import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.prism.music.data.canvas.CanvasArtwork
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.ui.components.ActionGroup
import com.prism.music.ui.components.ActionHeader
import com.prism.music.ui.components.ActionItem
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.runAction
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.delay
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.prism.music.data.prefs.ImmersiveLyrics
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.prism.music.ui.components.FloatingDisc
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.unit.lerp
import com.prism.music.data.prefs.ImmersiveArt
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.rememberHaptics

/** The lines' look (Aura × Spectrum): big, light and centred, lifted off the cover by a soft glow rather than a hard shadow. */
private val lineShadow = Shadow(Color.Black.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(0f, 1f), 26f)

/** How immersive mode draws its lyrics: as the lyrics page does (1.8.0: always). */
@Composable
private fun immersiveLook(song: Song): LyricsLook {
    val s = com.prism.music.ui.theme.LocalAppSettings.current
    // There's no card under immersive lyrics: Bold card keeps its bold type, on the cover.
    return rememberLyricsLook(s, com.prism.music.data.PlaylistCovers.artOf(song)).withoutCard(s.lyricsOthersAlpha)
}

/** The lyrics' typeface on its weight axis, so the sung words thicken as on the lyrics page; null for System / imported fonts. */
@Composable
private fun lyricsAxis(): androidx.compose.ui.text.font.FontFamily? {
    val s = com.prism.music.ui.theme.LocalAppSettings.current
    val choice = if (s.lyricsFont.isEmpty()) s.fontChoice else com.prism.music.data.prefs.FontChoice.entries.firstOrNull { it.name == s.lyricsFont }
    return remember(choice) { choice?.let(com.prism.music.ui.theme.AppFonts::axis) }
}

/**
 * Immersive mode (the player's ⋯ menu): the cover fills the screen (its animated version when it has one) and the
 * lyrics play over its lower part, a line at a time, as in a lyric video. How much of the cover shows is up to the
 * listener ([ImmersiveArt]): zoomed out, it sits at the top and dissolves into a blurred copy of itself. Nothing else
 * shows; a tap brings up a settings button for a few seconds (back to the normal player, artwork size, lyrics timing
 * and source, the song's options). Swipe left or right for the next or previous song, as on the normal player; a drag
 * down closes the player.
 */
@Composable
fun ImmersivePlayer(
    song: Song,
    /** The song's animated cover, if it has one (and it's allowed here): it plays in place of the still. */
    canvas: CanvasArtwork?,
    canvasSaved: Boolean,
    isPlaying: Boolean,
    lyricsState: LyricsState,
    /** A sheet is over the player. The clip and the lyrics keep going under it (they used to freeze, which read as a bug). */
    covered: Boolean,
    /** The song's music video is playing: it fills the screen in the cover's place (#170). */
    video: Boolean = false,
    onExit: () -> Unit,
    onLyricsTiming: () -> Unit,
    onLyricsSource: () -> Unit,
    onSongOptions: () -> Unit,
    onCollapse: () -> Unit,
) {
    val view = LocalView.current
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val art = settings.immersiveArt
    val place = settings.immersiveLyrics
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var button by remember { mutableStateOf(false) }
    // A tap shows the button for a few seconds; another tap (or the time) hides it.
    // Held down, the button stays up however long that takes.
    var pressing by remember { mutableStateOf(false) }
    // A press on the controls keeps them up a while longer.
    var poke by remember { mutableStateOf(0) }
    LaunchedEffect(button, menu, pressing, poke) { if (button && !menu && !pressing) { delay(4000); button = false } }

    // Edge to edge for real: the system bars hide (a swipe from the edge shows them for a moment), and the screen stays on.
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            // Back to the bars as the settings have them (#115: the status bar may stay hidden).
            com.prism.music.ui.components.SystemBars.apply(ctl, com.prism.music.ui.components.SystemBars.hidden(c.settings.current))
            view.keepScreenOn = false
        }
    }

    var drag by remember { mutableFloatStateOf(0f) }
    // Sideways, the cover follows the finger a little; past the mark, letting go changes the song.
    var swipe by remember { mutableFloatStateOf(0f) }
    val blurred = rememberFullArtworkBlur(com.prism.music.data.PlaylistCovers.artOf(song))
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { translationY = drag.coerceAtLeast(0f) }
            // For TalkBack the gestures are actions: a double tap opens the settings (the button only shows for a few
            // seconds), and the swipes and the hold have their own entries in its actions menu.
            .semantics {
                contentDescription = tr(R.string.immersive_player_by, song.title, song.artistText)
                onClick(tr(R.string.open_immersive_mode_settings)) { menu = true; true }
                customActions = listOf(
                    CustomAccessibilityAction(tr(R.string.next_song)) { c.player.next(); true },
                    CustomAccessibilityAction(tr(R.string.previous_song)) { c.player.previous(); true },
                    CustomAccessibilityAction(tr(R.string.leave_immersive_mode)) { onExit(); true },
                    CustomAccessibilityAction(tr(R.string.close_the_player)) { onCollapse(); true },
                )
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { button = !button }) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (drag > 220f) onCollapse(); drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { change, amount -> change.consume(); drag += amount }
            }
            .pointerInput(Unit) {
                val mark = 72.dp.toPx()
                fun settle() { val from = swipe; scope.launch { animate(from, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> swipe = v } } }
                detectHorizontalDragGestures(
                    onDragEnd = {
                        // Left for the next song, right for the previous one (or the start of this one), as on the player.
                        if (swipe <= -mark) { haptics.gestureEnd(); c.player.next() }
                        else if (swipe >= mark) { haptics.gestureEnd(); c.player.previous() }
                        settle()
                    },
                    onDragCancel = { settle() },
                ) { change, d ->
                    change.consume()
                    if ((abs(swipe) < mark) != (abs(swipe + d) < mark)) haptics.threshold()
                    swipe += d
                }
            },
    ) {
        // ---- Settings → Lyrics → Immersive background (1.7.5): the cover (as before, moving when it can), or something
        // plainer behind the words.
        val ground = settings.immersiveBackground
        // A playing music video takes the cover's place, cropped to fill the screen (#170).
        if (video) MusicVideoSurface(Modifier.fillMaxSize().graphicsLayer { translationX = swipe * 0.35f }, fill = true)
        val coverShown = !video && (ground == com.prism.music.data.prefs.LyricsBackground.PLAYER || ground == com.prism.music.data.prefs.LyricsBackground.CANVAS)
        if (!coverShown && !video) LyricsBackdrop(ground, settings, com.prism.music.data.PlaylistCovers.artOf(song), blurred, null, canvasSaved, isPlaying, Modifier.fillMaxSize())

        // ---- Behind a zoomed-out cover, the cover blurred, so it still fills the screen.
        if (coverShown && art != ImmersiveArt.FILL) FullArtworkBlurBackdrop(blurred, scrim = 0.8f)

        // ---- The cover; the animated one over it once its first frame is in.
        if (coverShown) BoxWithConstraints(Modifier.fillMaxSize()) {
            val fill = art == ImmersiveArt.FILL
            // Zoomed out, a square from the top: as wide as the screen at most (the whole cover), as tall at least.
            val side = lerp(minOf(maxWidth, maxHeight), maxOf(maxWidth, maxHeight), art.zoom)
            val fade = if (fill) 0f else 0.3f
            var canvasCover by remember(canvas) { mutableFloatStateOf(0f) }
            Box(
                (if (fill) Modifier.fillMaxSize() else Modifier.align(Alignment.TopCenter).requiredSize(side))
                    .graphicsLayer { translationX = swipe * 0.35f },
            ) {
                AsyncImage(
                    hiRes(com.prism.music.data.PlaylistCovers.artOf(song), 1200), null, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 1f - canvasCover; compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            if (fade > 0f) drawRect(
                                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height * (1f - fade), endY = size.height),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
                // Filling a phone held upright, a tall version of the clip (when there is one) fits better than the square one.
                val clip = remember(canvas, fill) {
                    canvas?.let { cv -> if (fill) cv.fallbackUrl?.let { tall -> cv.copy(url = tall, fallbackUrl = cv.url) } ?: cv else cv }
                }
                clip?.let {
                    CanvasPlayer(it, canvasSaved, isPlaying, Modifier.fillMaxSize(), bottomFade = fade, onCoverChanged = { v -> canvasCover = v })
                }
            }
        }

        // ---- Shade where the lyrics are, so they (and the button, at the bottom) read over any cover.
        val black = { a: Float -> Color.Black.copy(alpha = a) }
        Crossfade(place, label = "immersiveShade") { p ->
            val stops = when (p) {
                ImmersiveLyrics.HIGH -> arrayOf(0f to black(0.62f), 0.42f to black(0.18f), 0.62f to Color.Transparent, 0.84f to black(0.2f), 1f to black(0.55f))
                ImmersiveLyrics.MIDDLE -> arrayOf(0f to black(0.25f), 0.28f to black(0.3f), 0.5f to black(0.5f), 0.72f to black(0.3f), 1f to black(0.55f))
                ImmersiveLyrics.LOW -> arrayOf(0f to black(0.28f), 0.16f to Color.Transparent, 0.5f to Color.Transparent, 0.78f to black(0.42f), 1f to black(0.78f))
                ImmersiveLyrics.FULL -> arrayOf(0f to black(0.5f), 0.5f to black(0.46f), 1f to black(0.66f))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(*stops)))
        }

        // ---- Lyrics: a line at a time high, in the middle or low on the screen, or all of them over it.
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        // The lyrics page's translation, when it's turned on (#358).
        val translation = rememberLyricTranslation(lyricsState)
        when (place) {
            ImmersiveLyrics.FULL -> LyricsOverAll(song, lyricsState, translation, isPlaying, Modifier.fillMaxSize().padding(horizontal = 28.dp))
            else -> {
                val align = when (place) { ImmersiveLyrics.HIGH -> Alignment.TopCenter; ImmersiveLyrics.MIDDLE -> Alignment.Center; else -> Alignment.BottomCenter }
                Box(
                    Modifier
                        .align(align)
                        .fillMaxWidth()
                        .then(if (place == ImmersiveLyrics.MIDDLE) Modifier else Modifier.fillMaxHeight(0.5f))
                        .padding(
                            start = 28.dp, end = 28.dp,
                            top = if (place == ImmersiveLyrics.HIGH) insets.calculateTopPadding() + 72.dp else 0.dp,
                            // Low, there's room underneath for the settings button.
                            bottom = if (place == ImmersiveLyrics.LOW) insets.calculateBottomPadding() + 132.dp else 0.dp,
                        ),
                    contentAlignment = align,
                ) {
                    LineAtATime(song, lyricsState, translation, isPlaying, align)
                }
            }
        }

        // ---- How far through the song: a hairline of split light along the very bottom, quiet enough to stay immersive.
        // Read in the drawing only: the playhead moving redraws the line, not immersive mode around it.
        val position = rememberPositionState(fast = false, hold = covered)
        val dark = true
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).drawBehind {
                val duration = c.player.duration.coerceAtLeast(1L)
                drawRect(Color.White.copy(alpha = 0.12f))
                drawRect(
                    com.prism.music.ui.theme.PrismTokens.spectrum(dark, 0f, size.width),
                    size = size.copy(width = size.width * (position.value.toFloat() / duration).coerceIn(0f, 1f)), alpha = 0.85f,
                )
            },
        )

        // ---- On a tap: the song at the top, and the controls low down under the thumb in a glass capsule (previous,
        // play / pause, next and the settings button, which held fills and leaves immersive mode).
        AnimatedVisibility(
            button || menu, Modifier.align(Alignment.TopCenter).padding(top = insets.calculateTopPadding() + 14.dp, start = 28.dp, end = 28.dp),
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 2 }, exit = fadeOut(tween(300)),
        ) {
            Column(
                Modifier.clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.22f)).background(Color.White.copy(alpha = 0.10f))
                    .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.02f))), RoundedCornerShape(22.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val shown = com.prism.music.data.SongVersions.shownOf(song)
                Text(shown.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shown.artistText, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // "Keep controls on screen" (Reddit 1.7): the capsule stays; a tap still brings up the song's name.
        AnimatedVisibility(
            button || menu || settings.immersiveControls, Modifier.align(Alignment.BottomCenter).padding(bottom = insets.calculateBottomPadding() + 26.dp),
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 }, exit = fadeOut(tween(300)),
        ) {
            Row(
                Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.22f)).background(Color.White.copy(alpha = 0.10f))
                    .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.02f))), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ImmersiveGlyph(PrismIcons.Previous, tr(R.string.widget_previous)) { haptics.tick(); poke++; c.player.previous() }
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color.White)
                        .border(2.dp, com.prism.music.ui.theme.PrismTokens.spectrumRing(), CircleShape)
                        .clickable(onClickLabel = if (isPlaying) tr(R.string.widget_pause) else tr(R.string.widget_play)) { haptics.click(); poke++; c.player.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (isPlaying) PrismIcons.Pause else PrismIcons.Play, if (isPlaying) tr(R.string.widget_pause) else tr(R.string.widget_play), Modifier.size(26.dp), tint = Color.Black) }
                ImmersiveGlyph(PrismIcons.Next, tr(R.string.widget_next)) { haptics.tick(); poke++; c.player.next() }
                HoldToLeaveButton(
                    onTap = { menu = true },
                    onPressing = { pressing = it },
                    onHeld = { haptics.gestureEnd(); onExit() },
                )
            }
        }
    }

    if (menu) ActionSheet(song.thumbnail, { menu = false; button = false }) { close ->
        ActionHeader(song.thumbnail, song.title, song.artistText)
        ActionGroup(
            tr(R.string.immersive_mode),
            listOf(
                ActionItem(PrismIcons.FullscreenExit, tr(R.string.show_the_player), onClick = onExit),
                // Fill the screen → zoomed out → the whole cover, round again.
                ActionItem(PrismIcons.AspectRatio, tr(R.string.artwork), value = art.label, keepOpen = true) {
                    c.settings.setImmersiveArt(ImmersiveArt.entries[(art.ordinal + 1) % ImmersiveArt.entries.size])
                },
                ActionItem(PrismIcons.Play, tr(R.string.keep_controls_on_screen), value = if (settings.immersiveControls) tr(R.string.on_2) else tr(R.string.off), active = settings.immersiveControls, keepOpen = true) {
                    c.settings.setImmersiveControls(!settings.immersiveControls)
                },
                ActionItem(PrismIcons.More, tr(R.string.song_options), onClick = onSongOptions),
            ),
        ) { runAction(it, close) }
        ActionGroup(
            tr(R.string.lyrics),
            listOf(
                // High → middle → low → over the whole screen, round again.
                ActionItem(PrismIcons.Lyrics, tr(R.string.lyrics_position), value = place.label, keepOpen = true) {
                    c.settings.setImmersiveLyrics(ImmersiveLyrics.entries[(place.ordinal + 1) % ImmersiveLyrics.entries.size])
                },
                ActionItem(PrismIcons.Timer, tr(R.string.lyrics_timing), onClick = onLyricsTiming),
                ActionItem(PrismIcons.Lyrics, tr(R.string.lyrics_source), onClick = onLyricsSource),
                // The same switch as the player's menu (#358).
                ActionItem(PrismIcons.Translate, tr(R.string.translate_lyrics), value = if (settings.lyricsTranslate) tr(R.string.into, translateTargetName(settings)) else tr(R.string.off), active = settings.lyricsTranslate, keepOpen = true) {
                    c.settings.setLyricsTranslate(!settings.lyricsTranslate)
                },
            ),
        ) { runAction(it, close) }
    }
}

/** What's sung now, big, with the next line small under it; the song's name before the first line and without lyrics. */
@Composable
private fun LineAtATime(song: Song, state: LyricsState, translation: com.prism.music.data.lyrics.LyricsTranslator.Translation?, active: Boolean, align: Alignment) {
    val roman = rememberRomanized(state)
    val instead = romanizeMode(song.id) == com.prism.music.data.prefs.RomanizeMode.INSTEAD
    val look = immersiveLook(song)
    val lines = remember(state.lines, roman, instead, look.uppercase) {
        (if (instead && roman != null) state.lines.mapIndexed { i, l -> roman.getOrNull(i) ?: l } else state.lines).map(look::shown)
    }
    val synced = lines.any { it.timeMs >= 0 }
    val lineStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 31.sp * look.sizeScale, lineHeight = 38.sp * look.sizeScale, fontWeight = FontWeight(look.weight),
        textAlign = TextAlign.Center, textDirection = TextDirection.Content, shadow = lineShadow,
    ).withLyricsFont()
    val axis = lyricsAxis()
    if (lines.isEmpty() || !synced) {
        // Plain lyrics can't be followed along: the song's name stays up, as at the start of a lyric video.
        TitleCard(song)
        return
    }
    val clock = rememberLyricClock(active)
    val index by remember(lines) { derivedStateOf { lines.indexAtOrBefore(clock.value) } }
    val firstSung = remember(lines) { lines.indexOfFirst { !it.isGap } }
    AnimatedContent(
        index,
        transitionSpec = {
            (fadeIn(tween(420, easing = FastOutSlowInEasing)) + slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it / 3 }) togetherWith
                (fadeOut(tween(260)) + slideOutVertically(tween(420, easing = FastOutSlowInEasing)) { -it / 3 }) using SizeTransform(clip = false)
        },
        contentAlignment = align,
        label = "immersiveLine",
    ) { i ->
        val line = lines.getOrNull(i)?.takeIf { !it.isGap }
        val next = remember(lines, i) { lines.drop(i + 1).firstOrNull { !it.isGap } }
        val arrange = when (align) { Alignment.TopCenter -> Arrangement.Top; Alignment.Center -> Arrangement.Center; else -> Arrangement.Bottom }
        // #358: the line in the listener's language, as the lyrics page has it (under it, or over it as picked), smaller.
        val translated = line?.let { translatedLine(translation, i, it.text) }
        val translationLine = @Composable { padTop: Boolean ->
            translated?.let { t ->
                Text(
                    t, style = look.extraStyle(lineStyle, 0.52f, 0.56f), color = look.extraColor.copy(alpha = 0.8f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = if (padTop) 6.dp else 0.dp, bottom = if (padTop) 0.dp else 6.dp),
                )
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = arrange) {
            if (look.extraAbove) translationLine(false)
            when {
                // Before the first line: the song's name, as a lyric video opens.
                line == null && i < firstSung -> TitleCard(song)
                line == null -> Text("♪", style = lineStyle, color = Color.White.copy(alpha = 0.7f))
                // Prism's line-timed lines stay as they were here (Normal, white); every animation else draws its own.
                !line.isWordSynced && look.plainWhenLineTimed ->
                    Text(line.text, style = lineStyle.copy(fontWeight = FontWeight(look.weight + 100)), color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                else -> SungLine(line, clock, lineStyle, look, axis, Modifier.fillMaxWidth(), maxLines = 4, dimAlpha = 0.45f)
            }
            // How far through the line the song is: the split light, growing from the middle.
            if (line != null && line.endMs > line.timeMs) LyricProgress(line.timeMs, line.endMs, clock, 0.5f, Modifier.fillMaxWidth().padding(top = 12.dp))
            // Romanized "under each line": the Latin spelling, smaller, under the big line.
            if (!instead && line != null) roman?.getOrNull(i)?.let { r ->
                val romanStyle = lineStyle.copy(fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal, textDirection = TextDirection.Ltr)
                Spacer(Modifier.height(6.dp))
                if (r.isWordSynced) SweptLine(r, clock, romanStyle, Modifier.fillMaxWidth(), maxLines = 3, dimAlpha = 0.45f)
                else Text(r.text, style = romanStyle, color = Color.White.copy(alpha = 0.85f), maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
            if (!look.extraAbove) translationLine(true)
            // The line to come, small and dim under it (one row of it beside a translation, to keep the screen clear).
            next?.let { n ->
                Spacer(Modifier.height(14.dp))
                Text(
                    n.text, color = look.others.copy(alpha = 0.55f), maxLines = if (translated != null) 1 else 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight(look.weight), textAlign = TextAlign.Center, textDirection = TextDirection.Content, shadow = lineShadow,
                    ).withLyricsFont(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TitleCard(song: Song) {
    // #328: as the version picked to play in its place, if any.
    @Suppress("NAME_SHADOWING") val song = com.prism.music.data.SongVersions.shownOf(song)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            song.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal, textAlign = TextAlign.Center, shadow = lineShadow),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            song.artistText, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal, textAlign = TextAlign.Center, shadow = lineShadow),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The line playing at [ms] (the last one started), or -1 before the first. */
private fun List<LyricLine>.indexAtOrBefore(ms: Long): Int {
    var idx = -1
    for (i in indices) if (this[i].timeMs in 0..ms) idx = i else if (this[i].timeMs > ms) break
    return idx
}

/** How long the settings button is held to leave immersive mode. */
private const val HOLD_TO_LEAVE_MS = 2500

/**
 * Immersive mode's settings button: a tap opens its settings; held down, a ring fills around it (with a hint above)
 * and, once it's full, [onHeld] takes you back to the normal player. Letting go early empties the ring again.
 */
@Composable
private fun HoldToLeaveButton(onTap: () -> Unit, onPressing: (Boolean) -> Unit, onHeld: () -> Unit) {
    val scope = rememberCoroutineScope()
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    var held by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(held, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Text(
                tr(R.string.keep_holding_to_leave_immersive_mode), color = Color.White,
                style = MaterialTheme.typography.labelLarge.copy(shadow = lineShadow),
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Box(
            Modifier
                .size(54.dp)
                // The hold can't be done with TalkBack, so it's an action of the button's own.
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = tr(R.string.immersive_mode_settings)
                    onClick { onTap(); true }
                    customActions = listOf(CustomAccessibilityAction(tr(R.string.leave_immersive_mode)) { onHeld(); true })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        held = true
                        onPressing(true)
                        val fill = scope.launch {
                            progress.animateTo(1f, tween((HOLD_TO_LEAVE_MS * (1f - progress.value)).toInt(), easing = LinearEasing))
                            onHeld()
                        }
                        val up = waitForUpOrCancellation()
                        up?.consume()
                        held = false
                        onPressing(false)
                        if (progress.value < 1f) {
                            // A quick press is a tap; a longer one let go early just empties the ring.
                            val tap = up != null && progress.value < 0.15f
                            fill.cancel()
                            scope.launch { progress.animateTo(0f, tween(250)) }
                            if (tap) onTap()
                        }
                    }
                }
                .drawBehind {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2
                    val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    val at = androidx.compose.ui.geometry.Offset(inset, inset)
                    if (progress.value > 0f || held) drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, at, arc, style = Stroke(stroke))
                    drawArc(Color.White, -90f, 360f * progress.value, false, at, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                },
            contentAlignment = Alignment.Center,
        ) {
            FloatingDisc { ink -> Icon(PrismIcons.SettingsFilled, tr(R.string.immersive_mode_settings_hold_to_leave), Modifier.size(22.dp), tint = ink) }
        }
    }
}

/**
 * Every line over the whole screen, as in the lyrics panel but freer: the one being sung large and bright a little
 * above the middle, the others smaller and dimmer the further away they are, and the list gliding along by itself
 * (a drag on the screen stays the player's: down to close, sideways for another song). The edges fade out.
 */
@Composable
private fun LyricsOverAll(song: Song, state: LyricsState, translation: com.prism.music.data.lyrics.LyricsTranslator.Translation?, active: Boolean, modifier: Modifier) {
    val roman = rememberRomanized(state)
    val instead = romanizeMode(song.id) == com.prism.music.data.prefs.RomanizeMode.INSTEAD
    val look = immersiveLook(song)
    val lines = remember(state.lines, roman, instead, look.uppercase) {
        (if (instead && roman != null) state.lines.mapIndexed { i, l -> roman.getOrNull(i) ?: l } else state.lines).map(look::shown)
    }
    if (lines.isEmpty() || lines.none { it.timeMs >= 0 }) {
        Box(modifier, contentAlignment = Alignment.Center) { TitleCard(song) }
        return
    }
    val clock = rememberLyricClock(active)
    val index by remember(lines) { derivedStateOf { lines.indexAtOrBefore(clock.value) } }
    val list = rememberLazyListState()
    val style = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 28.sp * look.sizeScale, lineHeight = 34.sp * look.sizeScale, fontWeight = FontWeight(look.weight),
        textAlign = TextAlign.Center, textDirection = TextDirection.Content, shadow = lineShadow,
    ).withLyricsFont()
    val axis = lyricsAxis()
    LaunchedEffect(lines, index) {
        val target = index.coerceAtLeast(0)
        // Glide to a line on screen; jump to one further off (a seek).
        val seen = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        if (seen != null) list.animateScrollBy(seen.offset.toFloat(), tween(700, easing = FastOutSlowInEasing)) else list.scrollToItem(target)
    }
    BoxWithConstraints(modifier) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.16f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
            state = list,
            // The line being sung sits just above the middle.
            contentPadding = PaddingValues(top = maxHeight * 0.36f, bottom = maxHeight * 0.6f),
            userScrollEnabled = false,
        ) {
            itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
                val current = i == index
                val away = abs(i - index).coerceAtMost(4)
                val alpha by animateFloatAsState(if (current) 1f else 0.5f - 0.07f * away, tween(500), label = "lineAlpha")
                val scale by animateFloatAsState(if (current) 1f else 0.86f, tween(600, easing = FastOutSlowInEasing), label = "lineScale")
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 9.dp).graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        // #358: as the lyrics page has it (under each line, or over it as picked), smaller.
                        val translated = if (line.isGap) null else translatedLine(translation, i, line.text)
                        val translationLine = @Composable { padTop: Boolean ->
                            translated?.let { t ->
                                Text(
                                    t, style = look.extraStyle(style, 0.52f, 0.56f), color = look.extraColor.copy(alpha = 0.8f),
                                    modifier = Modifier.fillMaxWidth().padding(top = if (padTop) 4.dp else 0.dp, bottom = if (padTop) 0.dp else 4.dp),
                                )
                            }
                        }
                        if (look.extraAbove) translationLine(false)
                        when {
                            line.isGap -> Text("♪", style = style, color = Color.White, modifier = Modifier.fillMaxWidth())
                            current && (line.isWordSynced || !look.plainWhenLineTimed) ->
                                SungLine(line, clock, style, look, axis, Modifier.fillMaxWidth(), dimAlpha = 0.45f)
                            // The lines around it are dimmed by the list itself; their color is the look's.
                            else -> Text(line.text, style = style.copy(shadow = look.shadow ?: style.shadow), color = if (current) Color.White else look.others, modifier = Modifier.fillMaxWidth())
                        }
                        if (current && !line.isGap && line.endMs > line.timeMs) LyricProgress(line.timeMs, line.endMs, clock, 0.5f, Modifier.fillMaxWidth().padding(top = 10.dp))
                        if (!instead && !line.isGap) roman?.getOrNull(i)?.let { r ->
                            val romanStyle = style.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, textDirection = TextDirection.Ltr)
                            if (current && r.isWordSynced) SweptLine(r, clock, romanStyle, Modifier.fillMaxWidth().padding(top = 3.dp), dimAlpha = 0.45f)
                            else Text(r.text, style = romanStyle, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
                        }
                        if (!look.extraAbove) translationLine(true)
                    }
                }
            }
        }
    }
}

/** Previous / next in immersive mode's capsule. */
@Composable
private fun ImmersiveGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(26.dp), tint = Color.White) }
}