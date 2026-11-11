package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import com.prism.music.ui.player.drawRibbonStrands
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.prism.music.data.prefs.DeckPart
import com.prism.music.data.prefs.PlayerButton
import com.prism.music.data.prefs.PlayerPart
import com.prism.music.data.prefs.canHide
import com.prism.music.data.prefs.shows
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.data.prefs.PlayerLayout
import com.prism.music.data.prefs.PlayerTimes
import com.prism.music.data.prefs.PlayerTitleAlign
import com.prism.music.data.prefs.ScrubberColor
import com.prism.music.data.prefs.SessionButton
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import kotlin.math.roundToInt

/*
 * Settings → Player, the parts that came with 1.7.4 ("the player, your way"): its layout, its buttons, the order of what's
 * under the title, the title and times, and gestures. Every choice shows what it does as a small example, drawn the way
 * the player is (always dark, white type), and the preview pinned at the top of the page shows the whole player as it'll be.
 */

private val PlayerDark = Color(0xFF181818)
private val Ink = Color.White.copy(alpha = 0.85f)
private val Faint = Color.White.copy(alpha = 0.28f)

// ---------------------------------------------------------------------------- Layout

@Composable
fun PlayerLayoutGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val layoutInfo = when (s.playerLayout) {
        PlayerLayout.MINIMAL -> tr(R.string.minimal_swipe_the_cover_for_the)
        PlayerLayout.COVER -> tr(R.string.cover_tap_the_cover_for_the)
        else -> ""
    } + tr(R.string.tablets_and_phones_turned_sideways_keep)
    Group(tr(R.string.layout), info = layoutInfo) {
        ChoiceCards(
            PlayerLayout.entries, s.playerLayout,
            title = { it.label }, detail = { it.note },
            onSelect = { prefs.setPlayerLayout(it) },
        ) { LayoutSample(it) }
        if (s.playerLayout == PlayerLayout.VINYL) Toggle(tr(R.string.tonearm), tr(R.string.swings_onto_the_record_while_it), s.vinylTonearm) { prefs.setVinylTonearm(it) }
    }
}

/** A tiny dark phone showing where things sit in [layout]. */
@Composable
private fun LayoutSample(layout: PlayerLayout) {
    val light = Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    SampleScreen(Modifier.fillMaxWidth().height(150.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (layout) {
                PlayerLayout.LYRICS -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(Brush.linearGradient(listOf(Color(0xFF858585), Color(0xFF9E9E9E)))))
                        Spacer(Modifier.width(5.dp))
                        Column { Bar(0.6f, 4.dp, Ink); Spacer(Modifier.height(2.dp)); Bar(0.4f, 3.dp, Faint) }
                    }
                    Spacer(Modifier.height(10.dp))
                    listOf(0.7f to Faint, 0.85f to Ink, 0.6f to Faint, 0.75f to Faint).forEach { (w, c) -> Bar(w, 4.dp, c, Alignment.Start); Spacer(Modifier.height(6.dp)) }
                }
                PlayerLayout.VINYL -> {
                    Box(Modifier.fillMaxWidth(0.52f).aspectRatio(1f)) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF0B0B0B)).border(0.6.dp, Color.White.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxSize(0.66f).clip(CircleShape).border(0.5.dp, Color.White.copy(alpha = 0.07f), CircleShape))
                            Box(Modifier.fillMaxSize(0.42f).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF858585), Color(0xFF9E9E9E)))))
                        }
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp).size(2.dp, 34.dp).rotate(22f).background(Color(0xFFD0D0D0)))
                    }
                    Spacer(Modifier.height(6.dp)); Bar(0.6f, 4.dp, Ink); Spacer(Modifier.height(6.dp)); Transport()
                }
                PlayerLayout.CASSETTE -> {
                    Spacer(Modifier.height(10.dp))
                    // The tape: the cover as its label, two reels in the window.
                    Box(Modifier.fillMaxWidth(0.8f).aspectRatio(1.56f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1F1F1F))) {
                        Box(
                            Modifier.padding(4.dp).fillMaxWidth().fillMaxHeight(0.62f).clip(RoundedCornerShape(2.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF858585), Color(0xFF9E9E9E)))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                Modifier.fillMaxWidth(0.62f).clip(CircleShape).background(Color(0xFF0C0C0C)).padding(horizontal = 6.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) { repeat(2) { Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF3B3B3B)).border(2.dp, Color(0xFFE9E9E9), CircleShape)) } }
                        }
                    }
                    Spacer(Modifier.height(8.dp)); Bar(0.6f, 4.dp, Ink); Spacer(Modifier.height(6.dp)); Transport()
                }
                else -> {
                    val cover = when (layout) { PlayerLayout.COVER -> 0.7f; PlayerLayout.MINIMAL -> 0.52f; else -> 0.46f }
                    Box(Modifier.fillMaxWidth(cover).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(listOf(Color(0xFF858585), Color(0xFF9E9E9E)))))
                    Spacer(Modifier.height(6.dp))
                    Bar(0.6f, 4.dp, Ink, Alignment.Start); Spacer(Modifier.height(3.dp)); Bar(0.4f, 3.dp, Faint, Alignment.Start)
                    when (layout) {
                        PlayerLayout.STANDARD -> {
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(Faint)) { Box(Modifier.fillMaxWidth(0.4f).height(2.dp).background(light)) }
                            Spacer(Modifier.height(5.dp)); Transport()
                        }
                        PlayerLayout.MINIMAL -> { Spacer(Modifier.height(6.dp)); Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White)) }
                        else -> {}
                    }
                }
            }
        }
    }
}

/** Previous, play and next, small. */
@Composable
private fun Transport() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Ink))
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
        Box(Modifier.size(6.dp).clip(CircleShape).background(Ink))
    }
}

/** A rounded bar [fraction] of the width: a line of text in a sample. */
@Composable
private fun Bar(fraction: Float, height: Dp, color: Color, align: Alignment.Horizontal = Alignment.CenterHorizontally) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (align == Alignment.Start) Alignment.CenterStart else Alignment.Center) {
        Box(Modifier.fillMaxWidth(fraction).height(height).clip(CircleShape).background(color))
    }
}

/** The player's dark, for an example to sit on. */
@Composable
private fun SampleScreen(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF272727), PlayerDark))), content = content)
}

// ---------------------------------------------------------------------------- What's on the player (1.8.0)

/** 1.8.0: the buttons row is edited in [PlayerPartsGroup] now (the Customize sheet over the player still calls this). */
@Composable
fun PlayerButtonsGroup() = PlayerPartsGroup()

/**
 * What's on the player (1.8.0, replacing "Show on the player", "Under the title" and the buttons editor): the player
 * drawn as it is now, every part a tap to hide or show it (a hidden one stays, faint, to tap back); under it the order
 * of what's under the title and the row's buttons, to drag. Only play / pause always stays; what a hidden part did is in
 * the ⋯ menu, which is edited here too.
 */
@Composable
fun PlayerPartsGroup() {
    val s = LocalAppSettings.current
    val c = LocalContainer.current
    val prefs = c.settings
    var editMenu by remember { mutableStateOf(false) }
    Group(tr(R.string.what_s_on_the_player), info = tr(R.string.tap_a_part_of_the_player_to_hide_or_show_it)) {
        PlayerPartsPreview(s, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { part -> prefs.setPartShown(part, !s.shows(part)) }
        val hidden = PlayerPart.entries.filter { !s.shows(it) && it != PlayerPart.NAV_BAND && (s.mix || it !in PlayerPart.CROSSFADE) }
        if (hidden.isNotEmpty()) Note(tr(R.string.hidden_s, hidden.joinToString(", ") { it.label }))
        // The parts under the title, top to bottom, as they sit on the player.
        Label(tr(R.string.under_the_title), info = tr(R.string.top_to_bottom_as_they_sit))
        val order = s.playerDeck
        com.prism.music.ui.components.ReorderList(order, { it }, { prefs.setPlayerDeck(it) }) { deck, i, held, mod ->
            val part = deckPart(deck)
            OrderRow(
                deck.label, deckIcon(deck), part == null || s.shows(part), canCheck = part != null && s.canHide(part),
                onCheck = { v -> if (part != null) prefs.setPartShown(part, v) },
                onUp = if (i > 0) ({ prefs.setPlayerDeck(order.toMutableList().apply { add(i - 1, removeAt(i)) }) }) else null,
                onDown = if (i < order.lastIndex) ({ prefs.setPlayerDeck(order.toMutableList().apply { add(i + 1, removeAt(i)) }) }) else null,
                modifier = mod, grip = Modifier.dragHandle(), held = held,
            )
        }
        if (order != DeckPart.entries) com.prism.music.ui.components.PrismTextButton({ prefs.setPlayerDeck(DeckPart.entries) }, Modifier.padding(start = 12.dp)) {
            Text(tr(R.string.back_to_prism_s_order))
        }
        if (s.shows(PlayerPart.BUTTONS)) RowButtons()
        // GitHub #116: a volume just for Prism, for phones whose volume buttons don't work (or to keep other apps' as they are).
        if (s.shows(PlayerPart.VOLUME)) {
            Label(
                tr(R.string.the_volume_slider_changes),
                info = s.volumeControl.label + ": " + if (s.volumeControl == com.prism.music.data.prefs.VolumeControl.PRISM)
                    tr(R.string.only_prism_gets_quieter_or_louder)
                else tr(R.string.like_the_phone_s_volume_buttons),
            )
            Chips(com.prism.music.data.prefs.VolumeControl.entries, s.volumeControl, { it.label }) { v -> prefs.setVolumeControl(v) }
        }
        Toggle(
            tr(R.string.hide_controls_over_videos), tr(R.string.they_come_back_with_a_tap), s.view.videoControlsHide,
            info = tr(R.string.while_a_music_video_plays_the),
        ) { prefs.setVideoControlsHide(it) }
        // #293: the small buttons on the video itself.
        Toggle(
            tr(R.string.buttons_on_videos_wait_for_a_tap), null, s.view.videoButtonsHide,
            info = tr(R.string.buttons_on_videos_wait_for_a_tap_info),
        ) { prefs.setVideoButtonsHide(it) }
        // The ⋯ menu: its tiles and what it shows (also from the menu itself).
        Item(tr(R.string.edit_this_menu), tr(R.string.the_player_s_menu_its_tiles_and_actions), onClick = { editMenu = true }, icon = PrismIcons.More) { Chevron() }
    }
    if (editMenu) com.prism.music.ui.player.MenuEditorSheet { editMenu = false }
}

/** The row's buttons (three to six), in their order, to drag; the others to add. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RowButtons() {
    val s = LocalAppSettings.current
    val c = LocalContainer.current
    val prefs = c.settings
    val chosen = s.playerButtons
    // The Video button also needs the music video on (the old "Song / Video" switch: off, audio only).
    fun on(b: PlayerButton) = b != PlayerButton.VIDEO || s.showVideoSwitch
    Label(tr(R.string.buttons_in_the_row), info = tr(R.string.pick_to_for_the_row_under, PlayerButton.MIN, PlayerButton.MAX))
    com.prism.music.ui.components.ReorderList(chosen, { it }, { prefs.setPlayerButtons(it) }) { b, i, held, mod ->
        OrderRow(
            b.label, buttonIcon(b), on(b),
            canCheck = chosen.size > PlayerButton.MIN || !on(b) || b == PlayerButton.VIDEO,
            onCheck = { v ->
                when {
                    b == PlayerButton.VIDEO && v -> prefs.setShowVideoSwitch(true)
                    v -> {}
                    chosen.size > PlayerButton.MIN -> prefs.setPlayerButtons(chosen - b)
                    // The last three: the music video can still go (audio only, as the old Song / Video switch did).
                    b == PlayerButton.VIDEO -> { prefs.setShowVideoSwitch(false); c.player.setVideoMode(false) }
                }
            },
            onUp = if (i > 0) ({ prefs.setPlayerButtons(chosen.toMutableList().apply { add(i - 1, removeAt(i)) }) }) else null,
            onDown = if (i < chosen.lastIndex) ({ prefs.setPlayerButtons(chosen.toMutableList().apply { add(i + 1, removeAt(i)) }) }) else null,
            modifier = mod, grip = Modifier.dragHandle(), held = held,
        )
    }
    val rest = PlayerButton.entries.filter { it !in chosen }
    if (rest.isNotEmpty() && chosen.size < PlayerButton.MAX) {
        Label(tr(R.string.add_a_button))
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rest.forEach { b ->
                com.prism.music.ui.components.PrismChip(false, {
                    prefs.setPlayerButtons(chosen + b)
                    if (b == PlayerButton.VIDEO) prefs.setShowVideoSwitch(true)
                }, b.label, icon = buttonIcon(b))
            }
        }
    }
    if (chosen != PlayerButton.DEFAULT) com.prism.music.ui.components.PrismTextButton({ prefs.setPlayerButtons(PlayerButton.DEFAULT) }, Modifier.padding(start = 12.dp)) {
        Text(tr(R.string.back_to_lyrics_video_and_up))
    }
}

/** The row in a capsule or apart (1.7.8, #155), each as its own card: a Look choice (Settings → Player → Look). */
@Composable
fun ButtonsPillCards() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val chosen = s.playerButtons
    Label(tr(R.string.buttons))
    ChoiceCards(listOf(true, false), s.view.buttonsPill, { if (it) tr(R.string.in_a_capsule) else tr(R.string.apart) }, { if (it) tr(R.string.one_glass_capsule_as_now) else tr(R.string.spread_across_the_row_as_in) }, onSelect = { prefs.setButtonsPill(it) }) { pill ->
        SampleScreen(Modifier.padding(6.dp).fillMaxWidth().height(52.dp)) {
            Row(
                if (pill) Modifier.align(Alignment.Center).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 8.dp, vertical = 6.dp)
                else Modifier.align(Alignment.Center).fillMaxWidth(),
                horizontalArrangement = if (pill) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) { chosen.forEach { Icon(buttonIcon(it), it.label, Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.85f)) } }
        }
    }
}

private fun deckPart(d: DeckPart): PlayerPart? = when (d) {
    DeckPart.STRIP -> PlayerPart.LYRIC_LINE
    DeckPart.SCRUBBER -> PlayerPart.PROGRESS
    DeckPart.VOLUME -> PlayerPart.VOLUME
    DeckPart.BUTTONS -> PlayerPart.BUTTONS
    // Play stays; previous / next are hidden in the preview.
    DeckPart.TRANSPORT -> null
}

/**
 * The player as it is now, drawn small and dark the way the player is: every part ([PlayerPart]) a switch, faint with an
 * eye struck through while hidden. The parts under the title follow the listener's order; play / pause never hides.
 */
@Composable
fun PlayerPartsPreview(s: com.prism.music.data.prefs.AppSettings, modifier: Modifier = Modifier, onToggle: (PlayerPart) -> Unit) {
    val light = Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    @Composable
    fun Spot(part: PlayerPart, m: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
        val shown = s.shows(part)
        val state = if (shown) tr(R.string.shown) else tr(R.string.hidden)
        Box(
            m.clip(RoundedCornerShape(6.dp))
                .toggleable(shown, enabled = s.canHide(part), role = androidx.compose.ui.semantics.Role.Switch) { onToggle(part) }
                .semantics(mergeDescendants = true) { contentDescription = part.label; stateDescription = state }
                .then(if (shown) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(6.dp)))
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.graphicsLayer { alpha = if (shown) 1f else 0.22f }, contentAlignment = Alignment.Center, content = content)
            if (!shown) Icon(PrismIcons.Hidden, null, Modifier.align(Alignment.TopEnd).size(12.dp), tint = Color.White.copy(alpha = 0.7f))
        }
    }
    val tiny = 11.sp
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SampleScreen(Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("playerPartsPreview")) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spot(PlayerPart.HANDLE) { Box(Modifier.padding(horizontal = 14.dp, vertical = 3.dp).size(30.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.6f))) }
                Spot(PlayerPart.FROM_ALBUM) { Text(tr(R.string.from_the_album_2, tr(R.string.northern_lights)), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1) }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.62f)) {
                    Spot(PlayerPart.COVER, Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Color(0xFF858585), Color(0xFF9E9E9E)))))
                    }
                    // With Crossfade on: the cover dissolving into the next song's, drawn as the next cover over a corner.
                    if (s.mix) Spot(PlayerPart.BLEND_COVERS, Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(Color(0xFF999999).copy(alpha = 0.9f), Color(0xFF858585).copy(alpha = 0.2f)))))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Spot(PlayerPart.TITLE) { Text(tr(R.string.northern_lights), color = Color.White, fontSize = 16.sp, maxLines = 1) }
                        Spot(PlayerPart.ARTIST) { Text(tr(R.string.prism_sample), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1) }
                    }
                    Spot(PlayerPart.LIKE) { Icon(PrismIcons.Heart, null, Modifier.padding(4.dp).size(18.dp), tint = Color.White) }
                    Spot(PlayerPart.MENU) { Icon(PrismIcons.More, null, Modifier.padding(4.dp).size(18.dp), tint = Color.White) }
                }
                s.playerDeck.forEach { deck ->
                    Spacer(Modifier.height(6.dp))
                    when (deck) {
                        DeckPart.STRIP -> Spot(PlayerPart.LYRIC_LINE, Modifier.fillMaxWidth()) {
                            Text(tr(R.string.and_the_sky_lit_up), Modifier.fillMaxWidth(), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1)
                        }
                        DeckPart.SCRUBBER -> Column(Modifier.fillMaxWidth()) {
                            Spot(PlayerPart.PROGRESS, Modifier.fillMaxWidth()) {
                                Box(Modifier.padding(vertical = 5.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Faint)) {
                                    Box(Modifier.fillMaxWidth(0.4f).height(3.dp).background(light))
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Spot(PlayerPart.TIMES) { Text("1:12", color = Color.White.copy(alpha = 0.6f), fontSize = tiny) }
                                Spacer(Modifier.weight(1f))
                                Spot(PlayerPart.BEST_PART) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(PrismIcons.Flame, null, Modifier.size(11.dp), tint = Color.White.copy(alpha = 0.8f))
                                        Text(" " + tr(R.string.best_part), color = Color.White.copy(alpha = 0.8f), fontSize = tiny, maxLines = 1)
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Spot(PlayerPart.QUALITY) { Text(tr(R.string.sample_stream_quality), color = Color.White.copy(alpha = 0.6f), fontSize = tiny, maxLines = 1) }
                                // With Crossfade on: the mark where the next song comes in.
                                if (s.mix) {
                                    Spacer(Modifier.width(6.dp))
                                    Spot(PlayerPart.CROSSFADE_MARK) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(2.dp, 10.dp).background(Color.White.copy(alpha = 0.85f)))
                                            Text(" " + tr(R.string.crossfade_mark), color = Color.White.copy(alpha = 0.6f), fontSize = tiny, maxLines = 1)
                                        }
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                Spot(PlayerPart.TIMES) { Text("−2:31", color = Color.White.copy(alpha = 0.6f), fontSize = tiny) }
                            }
                        }
                        DeckPart.TRANSPORT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            Spot(PlayerPart.SHUFFLE_REPEAT) { Icon(PrismIcons.Shuffle, null, Modifier.size(16.dp), tint = Ink) }
                            Spot(PlayerPart.PREV_NEXT) { Icon(PrismIcons.Previous, null, Modifier.size(22.dp), tint = Color.White) }
                            // Play / pause always stays.
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Color.White).semantics { contentDescription = tr(R.string.play_pause_always_stays) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(PrismIcons.Pause, null, Modifier.size(20.dp), tint = Color.Black) }
                            Spot(PlayerPart.PREV_NEXT) { Icon(PrismIcons.Next, null, Modifier.size(22.dp), tint = Color.White) }
                            Spot(PlayerPart.SHUFFLE_REPEAT) { Icon(PrismIcons.Repeat, null, Modifier.size(16.dp), tint = Ink) }
                        }
                        DeckPart.VOLUME -> Spot(PlayerPart.VOLUME, Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(PrismIcons.VolumeDown, null, Modifier.size(12.dp), tint = Faint)
                                Box(Modifier.padding(horizontal = 6.dp).weight(1f).height(3.dp).clip(CircleShape).background(Faint)) {
                                    Box(Modifier.fillMaxWidth(0.7f).height(3.dp).background(Color.White.copy(alpha = 0.7f)))
                                }
                                Icon(PrismIcons.VolumeUp, null, Modifier.size(12.dp), tint = Faint)
                            }
                        }
                        DeckPart.BUTTONS -> Spot(PlayerPart.BUTTONS) {
                            val pill = s.view.buttonsPill
                            Row(
                                if (pill) Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp)
                                else Modifier.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (pill) 10.dp else 18.dp), verticalAlignment = Alignment.CenterVertically,
                            ) { s.playerButtons.filter { it != PlayerButton.VIDEO || s.showVideoSwitch }.forEach { Icon(buttonIcon(it), null, Modifier.size(16.dp), tint = Ink) } }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Spot(PlayerPart.OUTPUT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(light))
                        Text("  " + tr(R.string.this_phone) + "  ", color = Color.White.copy(alpha = 0.6f), fontSize = tiny, maxLines = 1)
                        Icon(PrismIcons.Cast, null, Modifier.size(10.dp), tint = Color.White.copy(alpha = 0.6f))
                    }
                }
                // Three-button navigation only: the band behind its buttons (#250).
                if (threeButtonNav()) {
                    Spacer(Modifier.height(4.dp))
                    Spot(PlayerPart.NAV_BAND, Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            repeat(3) { Box(Modifier.size(6.dp).clip(CircleShape).background(Faint)) }
                        }
                    }
                }
            }
        }
    }
}

/** A navigation bar tall enough to be three buttons (gesture navigation's is a thin strip). */
@Composable
private fun threeButtonNav(): Boolean {
    val density = androidx.compose.ui.platform.LocalDensity.current
    return with(density) { WindowInsets.navigationBars.getBottom(density).toDp() } >= 40.dp
}

/** The buttons beside play outside Prism: the notification, the lock screen, Android Auto (Controls & gestures). */
@Composable
fun SessionButtonsGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val chosen = s.sessionButtons
    Group(tr(R.string.notification_and_car), info = tr(R.string.your_phone_s_notification_and_lock)) {
        // The notification as it'll look: previous, play, next and the first two beside them.
        SampleScreen(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(56.dp)) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                chosen.getOrNull(0)?.let { Icon(sessionIcon(it), it.label, Modifier.size(20.dp), tint = Ink) }
                Icon(PrismIcons.Previous, null, Modifier.size(22.dp), tint = Color.White)
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Icon(PrismIcons.Pause, null, Modifier.size(16.dp), tint = Color.Black) }
                Icon(PrismIcons.Next, null, Modifier.size(22.dp), tint = Color.White)
                chosen.getOrNull(1)?.let { Icon(sessionIcon(it), it.label, Modifier.size(20.dp), tint = Ink) }
            }
        }
        com.prism.music.ui.components.ReorderList(chosen, { it }, { prefs.setSessionButtons(it) }) { b, i, held, mod ->
            OrderRow(
                b.label, sessionIcon(b), true, canCheck = true,
                onCheck = { v -> if (!v) prefs.setSessionButtons(chosen - b) },
                onUp = if (i > 0) ({ prefs.setSessionButtons(chosen.toMutableList().apply { add(i - 1, removeAt(i)) }) }) else null,
                onDown = if (i < chosen.lastIndex) ({ prefs.setSessionButtons(chosen.toMutableList().apply { add(i + 1, removeAt(i)) }) }) else null,
                modifier = mod, grip = Modifier.dragHandle(), held = held,
            )
        }
        SessionButton.entries.filter { it !in chosen }.forEach { b ->
            OrderRow(
                b.label, sessionIcon(b), false, canCheck = true,
                onCheck = { v -> if (v) prefs.setSessionButtons(chosen + b) }, onUp = null, onDown = null,
            )
        }
        if (chosen != SessionButton.DEFAULT) com.prism.music.ui.components.PrismTextButton({ prefs.setSessionButtons(SessionButton.DEFAULT) }, Modifier.padding(start = 12.dp)) {
            Text(tr(R.string.back_to_prism_s_buttons))
        }
    }
}

/**
 * A tickable row with its icon, and up / down to move it while it's ticked; in a [com.prism.music.ui.components.ReorderList]
 * it also takes the list's [modifier] (hold to carry it) and shows a [grip] to drag it at once.
 */
@Composable
private fun OrderRow(
    label: String, icon: ImageVector, on: Boolean, canCheck: Boolean,
    onCheck: (Boolean) -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?,
    modifier: Modifier = Modifier, grip: Modifier? = null, held: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PrismCheck(on, enabled = canCheck, label = label, onChange = onCheck)
        Icon(icon, null, Modifier.size(20.dp), tint = if (on) scheme.onSurface else scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
        if (on && !held) {
            IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Icon(PrismIcons.ChevronUp, tr(R.string.move_up_2, label)) }
            IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Icon(PrismIcons.ChevronDown, tr(R.string.move_down_2, label)) }
        } else if (held) Spacer(Modifier.width(96.dp))
        if (grip != null) com.prism.music.ui.components.DragGrip(grip.padding(start = 4.dp, end = 8.dp), tr(R.string.drag, label))
    }
}

fun buttonIcon(b: PlayerButton): ImageVector = when (b) {
    PlayerButton.LYRICS -> PrismIcons.Lyrics
    PlayerButton.VIDEO -> PrismIcons.Video
    PlayerButton.QUEUE -> PrismIcons.Queue
    PlayerButton.OUTPUT -> PrismIcons.Cast
    PlayerButton.SLEEP -> PrismIcons.Moon
    PlayerButton.LIKE -> PrismIcons.Heart
    PlayerButton.DISLIKE -> PrismIcons.ThumbDown
    PlayerButton.ADD_TO_PLAYLIST -> PrismIcons.PlaylistAdd
    PlayerButton.SHARE -> PrismIcons.Share
    PlayerButton.SING -> PrismIcons.Mic
    PlayerButton.DJ -> PrismIcons.Turntable
    PlayerButton.EQUALIZER -> PrismIcons.Equalizer
    PlayerButton.DOWNLOAD -> PrismIcons.Download
    PlayerButton.RADIO -> PrismIcons.Radio
    PlayerButton.SPEED -> PrismIcons.Speed
}

private fun sessionIcon(b: SessionButton): ImageVector = when (b) {
    SessionButton.LIKE -> PrismIcons.Heart
    SessionButton.SHUFFLE -> PrismIcons.Shuffle
    SessionButton.REPEAT -> PrismIcons.Repeat
    SessionButton.CAR_LYRICS -> PrismIcons.Lyrics
    SessionButton.RADIO -> PrismIcons.Radio
    SessionButton.DISLIKE -> PrismIcons.ThumbDown
    SessionButton.BACK_10 -> PrismIcons.Backward
    SessionButton.AHEAD_30 -> PrismIcons.Forward
}

private fun deckIcon(p: DeckPart): ImageVector = when (p) {
    DeckPart.STRIP -> PrismIcons.Lyrics
    DeckPart.SCRUBBER -> PrismIcons.Timer
    DeckPart.TRANSPORT -> PrismIcons.PlayCircle
    DeckPart.VOLUME -> PrismIcons.VolumeUp
    DeckPart.BUTTONS -> PrismIcons.More
}

// ---------------------------------------------------------------------------- Title, times and the progress bar's color

@Composable
fun PlayerTitleGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.title_and_times)) {
        ChoiceCards(
            PlayerTitleAlign.entries, s.playerTitleAlign, title = { it.label }, detail = { if (it == PlayerTitleAlign.START) tr(R.string.under_the_cover_s_left_edge) else tr(R.string.in_the_middle) },
            onSelect = { prefs.setPlayerTitleAlign(it) },
        ) { TitleSample(it) }
        Label(tr(R.string.title_size))
        Chips(com.prism.music.data.prefs.PlayerTitleSize.entries, s.playerTitleSize, { it.label }) { prefs.setPlayerTitleSize(it) }
        Label(tr(R.string.title_weight), info = tr(R.string.light_is_prism_s_own_and))
        ChoiceCards(
            com.prism.music.data.prefs.PlayerTitleWeight.entries, s.type.playerTitleWeight, title = { it.label }, detail = { null },
            onSelect = { prefs.setPlayerTitleWeight(it) },
        ) { TitleSample(s.playerTitleAlign, it) }
        Toggle(tr(R.string.scrolling_titles), tr(R.string.a_long_title_scrolls_across_off), s.playerTitleScroll) { prefs.setPlayerTitleScroll(it) }
        // #289 (1.8.1): with several artists, each name a link of its own; off, the line asks which (as before).
        Label(tr(R.string.each_artist_its_own_link), info = tr(R.string.each_artist_its_own_link_info))
        ChoiceCards(
            listOf(false, true), s.view.artistLinks,
            title = { if (it) tr(R.string.each_name_opens_its_artist) else tr(R.string.one_tap_asks_which) },
            detail = { if (it) tr(R.string.a_tap_between_names_still_asks) else tr(R.string.as_now) },
            onSelect = { prefs.setArtistLinks(it) },
        ) { ArtistLinksSample(it) }
        Label(tr(R.string.times_under_the_progress_bar))
        ChoiceCards(
            PlayerTimes.entries, s.playerTimes, title = { it.label }, detail = { null }, onSelect = { prefs.setPlayerTimes(it) },
        ) { TimesSample(it) }
    }
}

/** The progress bar (1.8.0, one group): its style first, then its color, thickness and knob, and the volume bar like it. */
@Composable
fun PlayerProgressGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(
        tr(R.string.progress_bar),
        info = when (s.scrubberStyle) {
            com.prism.music.data.prefs.ScrubberStyle.WAVE -> tr(R.string.waveform_each_song_s_own_shape)
            com.prism.music.data.prefs.ScrubberStyle.RIBBONS -> tr(R.string.ribbons_flow_only_while_a_song)
            else -> null
        },
    ) {
        ScrubberStyleCards()
        Label(tr(R.string.progress_bar_color))
        ChoiceCards(
            ScrubberColor.entries, s.scrubberColor, title = { it.label },
            detail = {
                when (it) {
                    ScrubberColor.SPECTRUM -> tr(R.string.the_split_light)
                    ScrubberColor.ACCENT -> tr(R.string.your_accent_color)
                    ScrubberColor.WHITE -> tr(R.string.plain_white)
                    ScrubberColor.COVER -> tr(R.string.the_song_s_cover_s_color)
                }
            },
            onSelect = { prefs.setScrubberColor(it) },
        ) { BarSample(it) }
        // Thickness and the knob (1.7.4): the waveform keeps its own height.
        SliderSetting(
            tr(R.string.progress_bar_thickness), s.scrubberThickness, 0.6f..2f, { worded(if (it < 0.85f) tr(R.string.thinner) else if (it <= 1.15f) tr(R.string.as_the_style) else tr(R.string.thicker), it) },
            info = if (s.scrubberStyle == com.prism.music.data.prefs.ScrubberStyle.WAVE) tr(R.string.the_waveform_keeps_its_own_height) else null,
        ) {
            prefs.setScrubberThickness(if (it in 0.93f..1.07f) 1f else it)
        }
        Label(tr(R.string.knob_at_the_playhead))
        ChoiceCards(
            com.prism.music.data.prefs.ScrubberThumb.entries, s.scrubberThumb, title = { it.label },
            detail = { if (it == com.prism.music.data.prefs.ScrubberThumb.AUTO) tr(R.string.a_dot_with_the_thumb_style) else null },
            onSelect = { prefs.setScrubberThumb(it) },
        ) { KnobSample(it, s.scrubberThickness) }
        // 1.8.1 (the owner's): the bar shaped by the crossfade, an option; off it's the bar as before.
        Label(tr(R.string.crossfade_on_the_progress_bar), info = tr(R.string.crossfade_shape_info))
        ChoiceCards(
            listOf(false, true), s.view.crossfadeShape,
            title = { if (it) tr(R.string.shaped_by_the_crossfade) else tr(R.string.plain) },
            detail = { if (it) tr(R.string.the_end_tapers_as_the_song_fades) else tr(R.string.as_now) },
            onSelect = { prefs.setCrossfadeShape(it) },
        ) { CrossfadeShapeSample(it, s.sound.crossfadeCurve, s.scrubberColor) }
        // Reddit, 1.7.9.1: the volume bar as thick as the progress bar (its style's height and this thickness).
        Toggle(tr(R.string.volume_bar_like_the_progress_bar), tr(R.string.same_style_and_thickness), s.volumeMatchesProgress) {
            prefs.setVolumeMatchesProgress(it)
        }
    }
}

@Composable
private fun KnobSample(knob: com.prism.music.data.prefs.ScrubberThumb, thickness: Float) {
    val light = Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    SampleScreen(Modifier.fillMaxWidth().height(40.dp)) {
        androidx.compose.foundation.Canvas(Modifier.align(Alignment.Center).padding(horizontal = 14.dp).fillMaxWidth().height(16.dp)) {
            val h = 4.dp.toPx() * thickness
            val y = size.height / 2
            val x = size.width * 0.55f
            drawRoundRect(Faint, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            drawRoundRect(light, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(x, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            val r = 6.dp.toPx()
            when (knob) {
                com.prism.music.data.prefs.ScrubberThumb.DOT, com.prism.music.data.prefs.ScrubberThumb.AUTO ->
                    if (knob == com.prism.music.data.prefs.ScrubberThumb.DOT) drawCircle(Color.White, r, androidx.compose.ui.geometry.Offset(x, y))
                com.prism.music.data.prefs.ScrubberThumb.LINE -> drawLine(Color.White, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                com.prism.music.data.prefs.ScrubberThumb.RING -> {
                    drawCircle(Color.Black.copy(alpha = 0.6f), r, androidx.compose.ui.geometry.Offset(x, y))
                    drawCircle(Color.White, r, androidx.compose.ui.geometry.Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
                com.prism.music.data.prefs.ScrubberThumb.NONE -> {}
            }
        }
    }
}

/**
 * A song's bar near its end, as the mockup draws it: the last quarter its blend, the playhead in it ([shaped]: tapering
 * along [curve], the knob riding it; else the plain bar).
 */
@Composable
private fun CrossfadeShapeSample(shaped: Boolean, curve: com.prism.music.data.prefs.CrossfadeCurve, color: ScrubberColor) {
    val fill = com.prism.music.ui.player.scrubberFill(color, LocalContainer.current.player.currentSong.value?.thumbnail)
        ?: Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    val outline = remember { com.prism.music.ui.player.BlendBarPath() }
    val shape = remember(curve) { com.prism.music.ui.player.BlendShape(curve, outFrom = 0.75f, outTo = 1f) }
    SampleScreen(Modifier.fillMaxWidth().height(40.dp)) {
        androidx.compose.foundation.Canvas(Modifier.align(Alignment.Center).padding(horizontal = 14.dp).fillMaxWidth().height(16.dp)) {
            val h = 7.dp.toPx()
            val y = size.height / 2
            val played = size.width * 0.86f
            translate(top = y - h / 2) {
                val bar = androidx.compose.ui.geometry.Size(size.width, h)
                if (shaped) {
                    val path = outline.outline(shape, bar.width, h)
                    drawPath(path, Faint)
                    clipRect(right = played) { drawPath(path, fill) }
                } else {
                    val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
                    drawRoundRect(Faint, size = bar, cornerRadius = r)
                    drawRoundRect(fill, size = androidx.compose.ui.geometry.Size(played, h), cornerRadius = r)
                }
            }
            val knob = 6.dp.toPx() * (if (shaped) com.prism.music.ui.player.BlendShape.knobScale(shape.height(0.86f)) else 1f)
            drawCircle(Color.White, knob, androidx.compose.ui.geometry.Offset(played, y))
        }
    }
}

// ---------------------------------------------------------------------------- Visualizer

/**
 * The visualizer (1.7.4): off unless picked, since it's the one part of the player that works on every frame. It moves
 * with Prism's own sound (no microphone), only while the player is open and playing.
 */
@Composable
fun PlayerVisualizerGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.visualizer), info = tr(R.string.it_listens_to_prism_s_own)) {
        ChoiceCards(
            com.prism.music.data.prefs.VisualizerStyle.entries, s.visualizer, title = { it.label }, detail = { it.note },
            onSelect = { prefs.setVisualizer(it) },
        ) { VisualizerSample(it, s.visualizerColor) }
        if (s.visualizer != com.prism.music.data.prefs.VisualizerStyle.OFF) {
            Label(tr(R.string.visualizer_color))
            Chips(ScrubberColor.entries, s.visualizerColor, { it.label }) { prefs.setVisualizerColor(it) }
            // Why it doesn't move: a state, so it stays in view.
            if (s.reduceMotion) Note(tr(R.string.reduce_motion_is_on_so_it))
        }
    }
}

@Composable
private fun VisualizerSample(style: com.prism.music.data.prefs.VisualizerStyle, color: ScrubberColor) {
    val fill = com.prism.music.ui.player.scrubberFill(color, LocalContainer.current.player.currentSong.value?.thumbnail)
        ?: Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    // A still frame of each, from a made-up spectrum.
    val bands = remember { FloatArray(20) { i -> (0.85f - i * 0.025f) * (0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(i * 1.7f))) } }
    SampleScreen(Modifier.fillMaxWidth().height(96.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val cover = size.height * 0.5f
            val cx = size.width / 2
            val coverTop = size.height * 0.06f
            fun coverRect() = drawRoundRect(
                Brush.linearGradient(listOf(Color(0xFF858585), Color(0xFF9E9E9E))),
                androidx.compose.ui.geometry.Offset(cx - cover / 2, coverTop), androidx.compose.ui.geometry.Size(cover, cover), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
            when (style) {
                com.prism.music.data.prefs.VisualizerStyle.GLOW -> drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), androidx.compose.ui.geometry.Offset(cx, coverTop + cover / 2), cover * 1.3f),
                    cover * 1.3f, androidx.compose.ui.geometry.Offset(cx, coverTop + cover / 2),
                )
                com.prism.music.data.prefs.VisualizerStyle.RING -> for (k in 3 downTo 1) {
                    val g = 3.dp.toPx() * k
                    drawRoundRect(
                        fill, androidx.compose.ui.geometry.Offset(cx - cover / 2 - g, coverTop - g), androidx.compose.ui.geometry.Size(cover + 2 * g, cover + 2 * g),
                        androidx.compose.ui.geometry.CornerRadius(4.dp.toPx() + g), alpha = 0.35f / k, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                    )
                }
                else -> {}
            }
            coverRect()
            val barY = size.height * 0.78f
            when (style) {
                com.prism.music.data.prefs.VisualizerStyle.BARS -> {
                    val w = size.width / bands.size
                    bands.forEachIndexed { i, v ->
                        val h = size.height * 0.4f * v
                        drawRoundRect(fill, androidx.compose.ui.geometry.Offset(i * w + w * 0.15f, size.height - h), androidx.compose.ui.geometry.Size(w * 0.7f, h), androidx.compose.ui.geometry.CornerRadius(w * 0.35f), alpha = 0.5f)
                    }
                }
                com.prism.music.data.prefs.VisualizerStyle.WAVE -> {
                    val path = androidx.compose.ui.graphics.Path()
                    val mid = size.height * 0.68f
                    val step = size.width / (bands.size - 1)
                    bands.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, mid - v * 10.dp.toPx()) else path.lineTo(i * step, mid - v * 10.dp.toPx()) }
                    bands.indices.reversed().forEach { i -> path.lineTo(i * step, mid + bands[i] * 10.dp.toPx()) }
                    path.close()
                    drawPath(path, fill, alpha = 0.35f)
                }
                else -> {}
            }
            // The progress bar (thicker and glowing for Pulse).
            val pulse = style == com.prism.music.data.prefs.VisualizerStyle.PULSE
            val h = if (pulse) 4.dp.toPx() else 2.dp.toPx()
            if (pulse) drawRoundRect(fill, androidx.compose.ui.geometry.Offset(0f, barY - h * 1.5f), androidx.compose.ui.geometry.Size(size.width * 0.45f, h * 4), androidx.compose.ui.geometry.CornerRadius(h * 2), alpha = 0.25f)
            drawRoundRect(Faint, androidx.compose.ui.geometry.Offset(0f, barY), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            drawRoundRect(fill, androidx.compose.ui.geometry.Offset(0f, barY), androidx.compose.ui.geometry.Size(size.width * 0.45f, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
        }
    }
}

@Composable
private fun TitleSample(align: PlayerTitleAlign, weight: com.prism.music.data.prefs.PlayerTitleWeight = LocalAppSettings.current.type.playerTitleWeight) {
    SampleScreen(Modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (align == PlayerTitleAlign.CENTER) Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f), horizontalAlignment = if (align == PlayerTitleAlign.CENTER) Alignment.CenterHorizontally else Alignment.Start) {
                Text(
                    tr(R.string.northern_lights), color = Color.White, fontSize = 12.sp, maxLines = 1, textAlign = if (align == PlayerTitleAlign.CENTER) TextAlign.Center else TextAlign.Start,
                    // Prism's own big titles are light; at this size their own weight wouldn't show it.
                    fontWeight = weight.title ?: androidx.compose.ui.text.font.FontWeight.Light,
                )
                Text(tr(R.string.prism_sample), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, fontWeight = weight.artist)
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(Faint))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(9.dp).clip(CircleShape).background(Faint))
        }
    }
}

/**
 * The artists under a title (#289): [separate], each name underlined on its own and the tap going straight to one;
 * else the whole line one target, a small menu of the names under it.
 */
@Composable
private fun ArtistLinksSample(separate: Boolean) {
    SampleScreen(Modifier.fillMaxWidth().height(72.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(tr(R.string.northern_lights), color = Color.White, fontSize = 12.sp, maxLines = 1, fontWeight = androidx.compose.ui.text.font.FontWeight.Light)
            val names = listOf(tr(R.string.sample_artist_one), tr(R.string.sample_artist_two), tr(R.string.sample_artist_three))
            Row(verticalAlignment = Alignment.CenterVertically) {
                names.forEachIndexed { i, n ->
                    val mark = if (separate) Modifier.drawBehind {
                        drawLine(Color.White.copy(alpha = if (i == 1) 0.9f else 0.35f), androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx())
                    } else Modifier
                    Text(n, mark, color = Color.White.copy(alpha = if (separate && i == 1) 0.95f else 0.6f), fontSize = 10.sp, maxLines = 1)
                    if (i < names.lastIndex) Text(", ", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (separate) Text(tr(R.string.opens_artist_page, names[1]), color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp)
            else Row(
                Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) { names.forEach { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 8.sp) } }
        }
    }
}

@Composable
private fun TimesSample(times: PlayerTimes) {
    SampleScreen(Modifier.fillMaxWidth().height(44.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp)) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Faint)) {
                Box(Modifier.fillMaxWidth(0.35f).height(3.dp).background(Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (times == PlayerTimes.BOTH || times == PlayerTimes.ELAPSED) "1:12" else "", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Text(if (times == PlayerTimes.BOTH || times == PlayerTimes.LEFT) "−2:31" else "", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            }
        }
    }
}

/** The progress bar's styles as cards, each drawn as it looks on the player (1.7.7, with Ribbons, #151). */
@Composable
fun ScrubberStyleCards() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    ChoiceCards(
        com.prism.music.data.prefs.ScrubberStyle.entries, s.scrubberStyle, title = { it.label },
        detail = {
            when (it) {
                com.prism.music.data.prefs.ScrubberStyle.HAIRLINE -> tr(R.string.a_thin_line)
                com.prism.music.data.prefs.ScrubberStyle.BOLD -> tr(R.string.a_thicker_bar)
                com.prism.music.data.prefs.ScrubberStyle.THUMB -> tr(R.string.a_line_and_a_knob)
                com.prism.music.data.prefs.ScrubberStyle.WAVE -> tr(R.string.each_song_s_own_shape)
                com.prism.music.data.prefs.ScrubberStyle.RIBBONS -> tr(R.string.strands_flow_along_while_it_plays)
            }
        },
        onSelect = { prefs.setScrubberStyle(it) },
    ) { ScrubberStyleSample(it, s.scrubberColor) }
}

@Composable
private fun ScrubberStyleSample(style: com.prism.music.data.prefs.ScrubberStyle, color: ScrubberColor) {
    val fill = com.prism.music.ui.player.scrubberFill(color, LocalContainer.current.player.currentSong.value?.thumbnail)
        ?: Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    val paths = remember { List(3) { androidx.compose.ui.graphics.Path() } }
    SampleScreen(Modifier.fillMaxWidth().height(44.dp)) {
        androidx.compose.foundation.Canvas(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth().height(8.dp)) {
            val played = size.width * 0.58f
            val h = when (style) {
                com.prism.music.data.prefs.ScrubberStyle.HAIRLINE -> 2.5.dp; com.prism.music.data.prefs.ScrubberStyle.BOLD -> 5.dp
                com.prism.music.data.prefs.ScrubberStyle.THUMB -> 2.dp; com.prism.music.data.prefs.ScrubberStyle.WAVE -> 0.dp
                com.prism.music.data.prefs.ScrubberStyle.RIBBONS -> 3.5.dp
            }.toPx()
            val y = size.height / 2
            if (style == com.prism.music.data.prefs.ScrubberStyle.WAVE) {
                val n = 34
                val step = size.width / n
                for (i in 0 until n) {
                    val v = 0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(i * 0.9f) * kotlin.math.cos(i * 0.37f))
                    val bh = size.height * 1.6f * v
                    val x = i * step + step * 0.2f
                    val at = androidx.compose.ui.geometry.Offset(x, y - bh / 2)
                    val sz = androidx.compose.ui.geometry.Size(step * 0.6f, bh)
                    if (x < played) drawRoundRect(fill, at, sz, androidx.compose.ui.geometry.CornerRadius(step * 0.3f))
                    else drawRoundRect(Faint, at, sz, androidx.compose.ui.geometry.CornerRadius(step * 0.3f))
                }
                return@Canvas
            }
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
            drawRoundRect(Faint, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), r)
            drawRoundRect(fill, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(played, h), r)
            if (style == com.prism.music.data.prefs.ScrubberStyle.RIBBONS) {
                // The same strands as the player's, held still here.
                translate(top = y - h / 2) { drawRibbonStrands(paths, played, 0.35f, fill, barHeight = h) }
            }
            if (style == com.prism.music.data.prefs.ScrubberStyle.THUMB || style == com.prism.music.data.prefs.ScrubberStyle.RIBBONS) {
                drawCircle(Color.White, 5.dp.toPx(), androidx.compose.ui.geometry.Offset(played, y))
            }
        }
    }
}

@Composable
private fun BarSample(color: ScrubberColor) {
    val fill = com.prism.music.ui.player.scrubberFill(color, LocalContainer.current.player.currentSong.value?.thumbnail)
        ?: Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    SampleScreen(Modifier.fillMaxWidth().height(36.dp)) {
        Box(Modifier.align(Alignment.Center).padding(horizontal = 12.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(Faint)) {
            Box(Modifier.fillMaxWidth(0.55f).height(5.dp).background(fill))
        }
    }
}

// ---------------------------------------------------------------------------- Background

/** Background movement [v] (0 still … 1 lively) as the mesh's speed and its colors' strength (1 = as the cover … 1.5). */
fun meshMovement(v: Float): Pair<Float, Float> = if (v < 0.05f) 0f to 1f else v to 1f + 0.5f * v

/**
 * Why the color mesh holds still though its movement is up (#284), or null: Reduce motion, Performance mode, or the
 * phone's animations turned off (Settings → Accessibility, or developer options' animation scale).
 */
internal fun meshHeldBy(s: com.prism.music.data.prefs.AppSettings, context: android.content.Context): String? = when {
    s.reduceMotion -> tr(R.string.reduce_motion_is_on_so_it)
    s.performanceMode -> tr(R.string.performance_mode_is_on_so_it_stays_still)
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ->
        tr(R.string.your_phone_s_animations_are_off_so_it_stays_still)
    else -> null
}

/** The player's backgrounds as cards (#232, #338): each a small player on it, made from what's playing. */
@Composable
fun PlayerBackgroundCards() {
    val s = LocalAppSettings.current
    val c = LocalContainer.current
    val song by c.player.currentSong.collectAsState()
    val art = song?.let { com.prism.music.data.PlaylistCovers.artOf(it) }
    ChoiceCards(
        com.prism.music.data.prefs.PlayerBackground.entries, s.playerBackground, { it.label }, { null }, columns = 3,
        onSelect = { c.settings.setPlayerBackground(it) },
    ) { PlayerBackgroundSample(it, art) }
}

/** A cover's colors for the examples while nothing's playing. */
private val SAMPLE_GRADIENT = com.prism.music.ui.player.CoverGradient(0xFF858585.toInt(), 0xFF9E9E9E.toInt())

@Composable
private fun PlayerBackgroundSample(bg: com.prism.music.data.prefs.PlayerBackground, art: String?) {
    val s = LocalAppSettings.current
    val scheme = MaterialTheme.colorScheme
    val standIn = Brush.linearGradient(listOf(Color(SAMPLE_GRADIENT.lead), Color(SAMPLE_GRADIENT.second)))
    Box(Modifier.fillMaxWidth().aspectRatio(0.66f).clip(RoundedCornerShape(10.dp)).background(com.prism.music.ui.player.PlayerFallbackBackdrop)) {
        when (bg) {
            com.prism.music.data.prefs.PlayerBackground.MESH ->
                if (art == null) Box(Modifier.fillMaxSize().background(standIn).background(Color.Black.copy(alpha = 0.35f)))
                // Still, and with no live blur: a page of small examples isn't the place to pay for it.
                else com.prism.music.ui.player.ArtworkMeshBackdrop(com.prism.music.ui.player.rememberArtworkMesh(art), intensity = s.meshIntensity, liveBlur = false)
            com.prism.music.data.prefs.PlayerBackground.BLUR ->
                if (art == null) Box(Modifier.fillMaxSize().background(standIn).background(Color.Black.copy(alpha = 0.5f)))
                else com.prism.music.ui.player.FullArtworkBlurBackdrop(com.prism.music.ui.player.rememberFullArtworkBlur(art, s.playerBlur))
            com.prism.music.data.prefs.PlayerBackground.APPEARANCE -> {
                val (style, photo) = com.prism.music.ui.theme.Backdrop.parse(
                    s.backdrops[com.prism.music.ui.theme.PLAYER_BACKDROP] ?: s.backdrops[com.prism.music.ui.theme.BackdropScreen.HOME.name],
                )
                com.prism.music.ui.theme.BackdropLayer(style, photo, dim = 0.5f, motion = false, modifier = Modifier.fillMaxSize())
            }
            com.prism.music.data.prefs.PlayerBackground.THEME -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.55f), Color.Black))))
            com.prism.music.data.prefs.PlayerBackground.BLACK -> Box(Modifier.fillMaxSize().background(Color.Black))
            com.prism.music.data.prefs.PlayerBackground.WHITE -> Box(Modifier.fillMaxSize().background(com.prism.music.ui.player.PlayerWhiteBackdrop))
            com.prism.music.data.prefs.PlayerBackground.GRADIENT -> com.prism.music.ui.player.CoverGradientBackdrop(
                com.prism.music.ui.player.rememberCoverGradient(art) ?: SAMPLE_GRADIENT, s.view.gradientDepth,
            )
        }
        val white = bg == com.prism.music.data.prefs.PlayerBackground.WHITE
        val ink = if (white) Color(0xFF1C1C1C) else Color.White
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Bottom) {
            Box(Modifier.fillMaxWidth(0.72f).height(6.dp).clip(CircleShape).background(ink.copy(alpha = 0.9f)))
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(0.46f).height(4.dp).clip(CircleShape).background(ink.copy(alpha = 0.55f)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(ink.copy(alpha = 0.35f)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(16.dp).align(Alignment.CenterHorizontally).clip(CircleShape).background(ink))
        }
    }
}

/** The color mesh's movement, and how soft the blurred cover is (under Look → Background). */
@Composable
fun PlayerBackgroundTuning() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    // #338: how dark it gets toward the bottom.
    if (s.playerBackground == com.prism.music.data.prefs.PlayerBackground.GRADIENT) {
        SliderSetting(
            tr(R.string.gradient_depth), s.view.gradientDepth, 0f..1f, { "${(it * 100).roundToInt()}%" },
            info = tr(R.string.gradient_depth_info),
        ) { prefs.setGradientDepth(it) }
    }
    if (s.playerBackground == com.prism.music.data.prefs.PlayerBackground.MESH) {
        // 1.8.0: Motion and Colors as one feeling, still → lively: the mesh drifts faster and its colors grow richer
        // together. The slider stands where the motion is; colors set apart before keep until it's moved.
        SliderSetting(
            tr(R.string.background_movement), s.meshSpeed, 0f..1f,
            { if (it < 0.05f) tr(R.string.still) else worded(if (it < 0.5f) tr(R.string.gentle) else tr(R.string.lively), it) },
        ) { v -> meshMovement(v).let { (speed, colors) -> prefs.setMeshSpeed(speed); prefs.setMeshIntensity(colors) } }
        // #284: what else holds it still says so too (the page shows the settings as set, which Performance mode and the
        // phone's own animations switch leave alone).
        if (s.meshSpeed > 0f) meshHeldBy(s, androidx.compose.ui.platform.LocalContext.current)?.let { Note(it) }
    }
    // How soft the blurred cover is (1.8.0: only for Blurred cover, where it shows).
    if (s.playerBackground == com.prism.music.data.prefs.PlayerBackground.BLUR) {
        SliderSetting(tr(R.string.softness), s.playerBlur, 0.5f..1.5f, { "${(it * 100).roundToInt()}%" }) { prefs.setPlayerBlur(it) }
    }
}

// ---------------------------------------------------------------------------- Gestures

/** What swipes, a double tap and a long press do on the player's cover, and swipes on the mini player. */
@Composable
fun PlayerGesturesGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.gestures), info = tr(R.string.on_the_player_s_cover_swiping)) {
        GestureRow(tr(R.string.swipe_left), s.coverSwipeLeft, PlayerGesture.choices()) { prefs.setGesture("cover_swipe_left", it) }
        GestureRow(tr(R.string.swipe_right), s.coverSwipeRight, PlayerGesture.choices()) { prefs.setGesture("cover_swipe_right", it) }
        GestureRow(tr(R.string.swipe_up), s.coverSwipeUp, PlayerGesture.choices()) { prefs.setGesture("cover_swipe_up", it) }
        GestureRow(tr(R.string.double_tap), s.coverDoubleTap, PlayerGesture.choices(double = true)) {
            prefs.setGesture("cover_double_tap", it)
            // Seek by side needs a step; it used to be switched off by setting none.
            if (it == PlayerGesture.SEEK_SIDES && s.doubleTapSeekSeconds <= 0) prefs.setDoubleTapSeekSeconds(10)
        }
        if (s.coverDoubleTap == PlayerGesture.SEEK_SIDES) Chips(listOf(3, 5, 10, 15), s.doubleTapSeekSeconds.coerceAtLeast(3), { tr(R.string.s_a_tap, it) }) { prefs.setDoubleTapSeekSeconds(it) }
        GestureRow(tr(R.string.long_press), s.coverLongPress, PlayerGesture.choices()) { prefs.setGesture("cover_long_press", it) }
        // #259 (1.7.9.2): the same gestures around the controls too.
        Toggle(
            tr(R.string.gestures_anywhere_on_the_player), tr(R.string.not_only_on_the_cover_around_the_controls_too), s.behavior.gesturesEverywhere,
            info = tr(R.string.your_swipes_double_tap_and_long_press_work_below_the_title_too),
        ) { prefs.setGesturesEverywhere(it) }
        // #282: the Lyrics layout has no cover to swipe, so the controls below the lyrics take the gestures.
        Toggle(
            tr(R.string.gestures_under_the_lyrics), null, s.behavior.gesturesUnderLyrics,
            info = tr(R.string.gestures_under_the_lyrics_info),
        ) { prefs.setGesturesUnderLyrics(it) }
        Label(tr(R.string.mini_player))
        GestureRow(tr(R.string.swipe_left), s.miniSwipeLeft, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_left", it) }
        GestureRow(tr(R.string.swipe_right), s.miniSwipeRight, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_right", it) }
        GestureRow(tr(R.string.swipe_up), s.miniSwipeUp, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_up", it) }
        GestureRow(tr(R.string.pull_down), s.miniSwipeDown, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_down", it) }
    }
}

/** A gesture and what it does; tapping it lists the choices. */
@Composable
private fun GestureRow(title: String, value: PlayerGesture, choices: List<PlayerGesture>, onPick: (PlayerGesture) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = tr(R.string.change_what_does, title)) { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(PrismIcons.ChevronDown, null, Modifier.padding(start = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.TopEnd)) {
            choices.forEach { g ->
                DropdownMenuItem(
                    { Text(g.label) }, { open = false; onPick(g) },
                    trailingIcon = if (g == value) ({ Icon(PrismIcons.Check, null) }) else null,
                )
            }
        }
    }
}
