package com.prism.music.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.theme.PrismIcons

/*
 * Prism TV's pieces (1.8.0): every TV screen is made of these, so focus looks and behaves the same everywhere. Prism's
 * look (TvLook): frosted glass at rest; what has focus grows a little, fills white and gets the split light round it
 * (the one thing that looks picked, seen from across a room). No glow or blur: cheap on slow TVs.
 */

/**
 * A row of cards' padding: the screen's 48 dp margin inside the row (so the first card's focus growth isn't cut off
 * at the row's edge), and room above and below for it.
 */
val TvRowPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 12.dp)

/** How much a focused card grows. */
private const val FOCUS_SCALE = 1.08f

/** A row's title (Quick picks, Your mixes…), above its cards. */
@Composable
fun TvRowTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
}

/**
 * A song, album, playlist, mix or artist in a row: its picture (round for artists), with the title and a quiet second
 * line below. Focused, it grows with the split light round it. OK opens or plays it; holding OK is [onLongClick].
 */
@Composable
fun TvCard(
    art: String?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    width: Dp = 132.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = if (round) CircleShape else TvShapes.card
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.fillMaxWidth().aspectRatio(1f),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = FOCUS_SCALE),
            border = CardDefaults.border(focusedBorder = tvFocusBorder(shape)),
            // No fill of its own: the artwork's tile covers it (a whole card's area drawn twice per frame for nothing).
            colors = CardDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        ) {
            Artwork(art, Modifier.fillMaxSize(), shape = shape, size = 360)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A labeled button (Play, Shuffle, Sign in with your phone…) on frosted glass; [primary] is the screen's main action,
 * its label in the accent. Focused it fills white with the split light round it: one thing looks picked, never two.
 */
@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier,
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        colors = ButtonDefaults.colors(
            containerColor = TvGlassFill, contentColor = if (primary) scheme.primary else scheme.onSurface,
            focusedContainerColor = scheme.onSurface, focusedContentColor = scheme.surface,
        ),
        border = ButtonDefaults.border(focusedBorder = tvFocusBorder(CircleShape, TV_RING_THIN)),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * A round icon button (the player's controls); [big] is play/pause, on a frosted disc with the phone's play ring round
 * it (Settings → the ring on the play button). Focused, it fills white with the split light round it. [label] is what
 * TalkBack reads.
 */
@Composable
fun TvIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, big: Boolean = false, on: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val size = if (big) 72.dp else 52.dp
    val ring = big && com.prism.music.ui.theme.LocalAppSettings.current.view.playRing
    val light = tvRing()
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size).then(if (ring) Modifier.border(TV_RING_THIN, light, CircleShape) else Modifier),
        scale = IconButtonDefaults.scale(focusedScale = 1.1f),
        colors = IconButtonDefaults.colors(
            containerColor = if (big) TvGlassFill else Color.Transparent,
            contentColor = if (on) scheme.primary else scheme.onSurface,
            focusedContainerColor = scheme.onSurface, focusedContentColor = scheme.surface,
        ),
        border = IconButtonDefaults.border(focusedBorder = tvFocusBorder(CircleShape, TV_RING_THIN)),
    ) {
        Icon(icon, label, Modifier.size(if (big) 36.dp else 26.dp))
    }
}

/**
 * One song in a list (an album, a playlist, the queue, search results): number or picture, title, artist, length. The
 * song playing has its number turned into the playing mark and its title in the split light, as on the phone.
 */
@Composable
fun TvSongRow(
    title: String,
    artist: String,
    length: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    art: String? = null,
    playing: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val light = tvLight()
    ListItem(
        selected = playing,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        interactionSource = interaction,
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        shape = ListItemDefaults.shape(TvShapes.card),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent, selectedContainerColor = TvGlassFill, selectedContentColor = scheme.onSurface,
            focusedContainerColor = scheme.onSurface, focusedContentColor = scheme.surface,
            focusedSelectedContainerColor = scheme.onSurface, focusedSelectedContentColor = scheme.surface,
        ),
        border = ListItemDefaults.border(focusedBorder = tvFocusBorder(TvShapes.card, TV_RING_THIN)),
        leadingContent = when {
            art != null -> { { Artwork(art, Modifier.size(44.dp), shape = RoundedCornerShape(6.dp), size = 120) } }
            number != null && playing -> { { Box(Modifier.width(28.dp).testTag(TAG_PLAYING_MARK), contentAlignment = Alignment.Center) { Icon(PrismIcons.Equalizer, tr(R.string.now_playing), Modifier.size(20.dp)) } } }
            number != null -> { { Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { Text("$number", style = MaterialTheme.typography.titleSmall) } } }
            else -> null
        },
        headlineContent = {
            val style = MaterialTheme.typography.titleMedium
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = if (playing && !focused) style.copy(brush = light) else style)
        },
        supportingContent = { Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = length?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
    )
}

/** How far the song is: a thin line in the split light, with the time played and the song's length under it. */
@Composable
fun TvProgress(fraction: Float, played: String, total: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val light = tvLight()
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(light))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(played, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Text(total, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
    }
}
/** Test tag: the playing mark on the song playing in a list. */
const val TAG_PLAYING_MARK = "tv_playing_mark"
