package com.prism.music.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay

/*
 * Prism TV's focus rules (plan-1.8.0 §3), one helper each, so every TV screen follows them the same way:
 * - on entering a screen focus lands on a sensible item, never nothing ([entryFocus]);
 * - leaving a row or list and coming back returns to the item that had focus ([rememberedFocus]).
 * Back (overlay → up a level → leave Prism on Home) is the shell's ([TvRoot]); media keys reach the media session.
 */

/** How long [entryFocus] keeps asking: a screen's first item can take a few frames to be placed (lists, pictures). */
private const val ENTRY_TRIES = 40
private const val ENTRY_STEP_MS = 50L

/**
 * Takes focus when it first appears (the item a screen opens on). Asks again for a moment if it isn't placed yet;
 * once it has had focus it never takes it back (the listener has moved on).
 */
/**
 * Focus coming into a group by the D-pad (not given to one of its items: a page coming back puts it on the item left
 * there, and a group's own way in mustn't take it elsewhere, the release check).
 */
fun androidx.compose.ui.focus.FocusEnterExitScope.byDpad(): Boolean = requestedFocusDirection.let {
    it == androidx.compose.ui.focus.FocusDirection.Left || it == androidx.compose.ui.focus.FocusDirection.Right ||
        it == androidx.compose.ui.focus.FocusDirection.Up || it == androidx.compose.ui.focus.FocusDirection.Down
}

/**
 * A panel or card over a page: the D-pad keeps focus inside it (the release check: Left, Up or Down could leave it for a
 * control hidden behind it, which OK then pressed). It's closed with Back, or its own way.
 */
fun Modifier.tvHoldsFocus(): Modifier = this
    .then(Modifier.focusProperties { onExit = { cancelFocusChange() } })
    .then(Modifier.focusGroup())

@Composable
fun Modifier.entryFocus(key: Any? = Unit): Modifier {
    val requester = remember { FocusRequester() }
    var had by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        var i = 0
        while (!had && i++ < ENTRY_TRIES) {
            runCatching { requester.requestFocus() }
            delay(ENTRY_STEP_MS)
        }
    }
    return this.focusRequester(requester).onFocusChanged { if (it.hasFocus) had = true }
}

/**
 * A row or list that remembers which of its items had focus: coming back into it (D-pad from above or below, or
 * back from a page) lands there, not on its first item.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rememberedFocus(): Modifier = this.focusRestorer()

/**
 * Which item had focus on each screen, so coming back to a screen (Back from the player, from an album) lands on the
 * item the listener left, not on the screen's first one. Lives as long as the TV app's screen.
 */
class TvFocusMemory {
    internal val lastFocused = HashMap<String, String>()
    /** Screens where something has taken focus since they were last opened. */
    internal val landed = HashSet<String>()

    /** [screen] is opening (again): its items may take focus anew. */
    fun opening(screen: String) { landed -= screen }
}

/** How long a screen waits for the item the listener left to take focus before its first item does instead. */
private const val RESTORE_WAIT_MS = 700L

val LocalTvFocusMemory = staticCompositionLocalOf { TvFocusMemory() }
/** The screen being drawn, as the key [TvFocusMemory] files its focus under ("section:HOME", "player", "album:…"). */
val LocalTvScreen = staticCompositionLocalOf { "" }

/**
 * A focusable item on a TV screen, named [tag] (its test tag too). It takes focus when the screen opens if it's the
 * screen's [entry] item, or, coming back to the screen, if it's the item that had focus when the listener left.
 */
@Composable
fun Modifier.tvItem(tag: String, entry: Boolean = false): Modifier {
    val memory = LocalTvFocusMemory.current
    val screen = LocalTvScreen.current
    val left = remember(screen) { memory.lastFocused[screen] }
    val takes = if (left != null) left == tag else entry
    // The item left isn't there any more (the row changed): the screen's first item, so focus is never nowhere.
    val fallback = entry && left != null && left != tag
    val requester = remember { FocusRequester() }
    if (fallback) LaunchedEffect(screen) {
        delay(RESTORE_WAIT_MS)
        if (screen !in memory.landed) runCatching { requester.requestFocus() }
    }
    return (if (takes) this.entryFocus(screen) else this)
        .focusRequester(requester)
        .onFocusChanged {
            if (it.isFocused) { memory.lastFocused[screen] = tag; memory.landed += screen }
        }
        .testTag(tag)
}
