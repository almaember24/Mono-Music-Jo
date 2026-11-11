package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.data.looks.UndoNote
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What "Customize this" (1.7.3) can open settings for, and where those settings live. */
enum class CustomizeTarget(val title: String) {
    MINI_PLAYER(tr(R.string.mini_player)), TAB_BAR(tr(R.string.tab_bar)), PLAYER(tr(R.string.player)), HOME_SECTION(tr(R.string.home_section)),
}

/** Opens a target's settings sheet (provided at the app's root); null where there's no sheet to open. */
val LocalCustomize = staticCompositionLocalOf<((CustomizeTarget, String?) -> Unit)?> { null }

/**
 * With "Customize this" on, this element is outlined in split light and a tap (or a hold) on it opens its own settings
 * ([target]; [key] says which Home section) instead of doing what it usually does. Scrolling past it still scrolls.
 */
@Composable
fun Modifier.customizable(target: CustomizeTarget, key: String? = null): Modifier {
    val open = LocalCustomize.current ?: return this
    val on by LocalContainer.current.customizing.collectAsState()
    if (!on) return this
    val dark = LocalIsDark.current
    return this
        .drawWithContent {
            drawContent()
            val inset = 1.5.dp.toPx()
            drawRoundRect(
                PrismTokens.spectrum(dark, 0f, size.width), Offset(inset, inset),
                androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(14.dp.toPx()),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))),
            )
        }
        .pointerInput(target, key) {
            // Taken before the element's own buttons see it (the Initial pass); a move past the touch slop lets it go.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                val slop = viewConfiguration.touchSlop
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > slop) break
                    if (!change.pressed) {
                        change.consume()
                        open(target, key)
                        break
                    }
                }
            }
        }
}

/**
 * While "Customize this" is on: a pill at the top saying so, with Done. Then: after each change to the look, "Undo" with
 * what changed, for five seconds ([LookStore][com.prism.music.data.looks.LookStore]'s notes).
 */
@Composable
fun CustomizingPill(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val on by c.customizing.collectAsState()
    AnimatedVisibility(on, modifier, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier.clip(CircleShape).background(scheme.inverseSurface).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PrismIcons.Customize, null, Modifier.size(18.dp), tint = scheme.inverseOnSurface)
            Spacer(Modifier.width(10.dp))
            Text(tr(R.string.tap_anything_outlined_to_change_it), style = MaterialTheme.typography.bodyMedium, color = scheme.inverseOnSurface)
            PrismTextButton({ c.customizing.value = false }) { Text(tr(R.string.done), color = scheme.inversePrimary) }
        }
    }
}

/**
 * After Day and night switched Looks while the settings were the listener's own (GitHub #174: "my customizations reset
 * after a few hours"): says they're kept as "Before <Look>", with a way straight back. Stays until answered.
 */
@Composable
fun ScheduleKeptBar(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val kept by c.looks.keptBySchedule.collectAsState()
    val scope = rememberCoroutineScope()
    AnimatedVisibility(kept != null, modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
        val name = kept ?: return@AnimatedVisibility
        // Never over the player (MainActivity leaves it out there).
        val glass = LocalAppSettings.current.glassEverywhere
        val (ink, action) = barInk(glass)
        BarSurface(glass, RoundedCornerShape(20.dp), Modifier.widthIn(max = 520.dp)) {
            Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Text(
                    tr(R.string.day_and_night_switched_to_your, name, name),
                    style = MaterialTheme.typography.bodyMedium, color = ink,
                )
                Row(Modifier.align(Alignment.End)) {
                    PrismTextButton({ c.looks.dismissKept() }) { Text("OK", color = action) }
                    PrismTextButton({ scope.launch { c.looks.bringBackKept() } }) { Text(tr(R.string.bring_them_back), color = action) }
                }
            }
        }
    }
}

/**
 * The surface of a short-lived bar over the page: as ever in the inverse colors, or with "Glass on more of the screen" (#326)
 * real glass, which only costs while it's up, a few seconds at a time.
 */
@Composable
internal fun BarSurface(glass: Boolean, shape: RoundedCornerShape, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (glass) com.prism.music.ui.theme.GlassSurface(modifier, shape = shape, elevation = 6.dp) { content() }
    else Box(modifier.clip(shape).background(MaterialTheme.colorScheme.inverseSurface)) { content() }
}

/** What's written on a [BarSurface]: its text, and its buttons. */
@Composable
internal fun barInk(glass: Boolean): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val scheme = MaterialTheme.colorScheme
    return if (glass) scheme.onSurface to scheme.primary else scheme.inverseOnSurface to scheme.inversePrimary
}

/** "Undo" after a change to the look, saying what changed ("Corners → Sharp"); taps back the last change. */
@Composable
fun LookUndoBar(modifier: Modifier = Modifier, overPlayer: Boolean = false) {
    val c = LocalContainer.current
    var note by remember { mutableStateOf<UndoNote?>(null) }
    var shown by remember { mutableStateOf<UndoNote?>(null) }
    LaunchedEffect(Unit) {
        c.looks.notes.collectLatest { n ->
            note = n; shown = n
            delay(5_000)
            note = null
        }
    }
    AnimatedVisibility(note != null, modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
        val n = shown ?: return@AnimatedVisibility
        // Glass samples the page, which the player covers: over the player it would show the wrong picture.
        val glass = LocalAppSettings.current.glassEverywhere && !overPlayer
        val (ink, action) = barInk(glass)
        BarSurface(glass, RoundedCornerShape(50), Modifier.widthIn(max = 520.dp)) {
            Row(
                // The bar takes every touch on it, so none reaches what's under it (a Look card below once got the tap too).
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                    }
                }
                    .padding(start = 18.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(n.label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                PrismTextButton({ note = null; n.undo?.invoke() ?: c.looks.undo() }) {
                    Icon(PrismIcons.Undo, null, Modifier.size(16.dp), tint = action)
                    Spacer(Modifier.width(6.dp))
                    Text(tr(R.string.undo), color = action)
                }
            }
        }
    }
}
