package com.prism.music.ui.components

import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.hairline

/**
 * One choice in an [ActionSheet], as a tile ([QuickActions]) or a row ([ActionGroup]).
 * [value] is shown on the right of a row (a timer, a percentage); [active] lights it up (a mode
 * that's on); [keepOpen] runs it without closing the sheet; [danger] is for deleting things.
 */
@Immutable
class ActionItem(
    val icon: ImageVector,
    val label: String,
    val value: String? = null,
    val active: Boolean = false,
    val keepOpen: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

fun SheetItem.toAction() = ActionItem(icon, label, danger = danger, onClick = onClick)

/**
 * The menus behind ⋯ and a long press, in the app's frosted look: the sheet wears the
 * artwork ([art]) as frosted glass, as the artist and album pages do, with its accent taken from
 * the picture. [content] gets a `close { … }` that slides the sheet away and then runs the action.
 */
@Composable
fun ActionSheet(
    art: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit,
) {
    val settings = LocalAppSettings.current
    val dark = LocalIsDark.current
    val frosted = settings.frostedPages
    val palette = rememberArtPalette(art)
    val frost = rememberFrost(art)
    val scheme = MaterialTheme.colorScheme
    ArtworkAccent(palette) {
        PrismSheet(
            onDismiss,
            containerColor = if (frosted) palette?.settle(dark) ?: scheme.surfaceContainerLow else scheme.surfaceContainerLow,
            contentColor = scheme.onSurface,
            dragHandle = null,
        ) { close ->
            Box {
                if (frosted) FrostedBackdrop(frost, palette, 200.dp, { 0f }, Modifier.matchParentSize(), parallax = 0f)
                CompositionLocalProvider(LocalFrosted provides frosted) {
                    // Never the whole screen tall: a menu that reached the top (the player's, with an animated cover's two
                    // extra rows) didn't follow the finger when pulled down, and stuck. Longer menus scroll inside.
                    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.86f
                    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).nestedScroll(KeepUpwardScroll).verticalScroll(rememberScrollState())) {
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)))
                        }
                        content(close)
                        Spacer(Modifier.height(22.dp))
                    }
                }
            }
        }
    }
}

/**
 * What's left of an upward scroll or fling once the menu has reached its end stays with the menu (#147): handed to the
 * sheet, a fast fling pushed the whole sheet up past its top, under the status bar, and it bounced back down. Downward
 * leftovers still go to the sheet, so pulling it down closes it as before. Add to playlist's list has it too (#401).
 */
internal val KeepUpwardScroll = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) =
        if (available.y < 0f) androidx.compose.ui.geometry.Offset(0f, available.y) else androidx.compose.ui.geometry.Offset.Zero

    override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity) =
        if (available.y < 0f) androidx.compose.ui.unit.Velocity(0f, available.y) else androidx.compose.ui.unit.Velocity.Zero
}

/** Runs [a]: straight away when it keeps the sheet open, else once the sheet has slid away. */
fun runAction(a: ActionItem, close: (() -> Unit) -> Unit) = if (a.keepOpen) a.onClick() else close(a.onClick)

/** What the sheet is about: its artwork, title and a quiet line, with round [actions] (like, share) on the right. */
@Composable
fun ActionHeader(
    art: String?,
    title: String,
    subtitle: String?,
    shape: Shape = LocalUi.current.art,
    placeholderIcon: ImageVector = PrismIcons.Song,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(art, Modifier.size(56.dp), shape, size = 544, placeholderIcon = placeholderIcon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(
                subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A round glass button for the header; [active] (a liked heart, its filled twin passed in) shows in full ink. */
@Composable
fun SheetIconButton(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.padding(start = 6.dp).size(42.dp).clip(CircleShape).background(glassFill(1.4f)).border(1.dp, glassEdge(), CircleShape)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(21.dp), tint = if (active) scheme.onSurface else scheme.onSurfaceVariant) }
}

/**
 * The sheet's main actions in one row, each on a round tile tinted with one of the spectrum's hues (red, yellow, blue,
 * violet for four), its label under it.
 */
@Composable
fun QuickActions(items: List<ActionItem>, onPick: (ActionItem) -> Unit) {
    if (items.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val step = if (items.size <= 4) 2 else 1
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        items.forEachIndexed { i, a ->
            val hue = com.prism.music.ui.theme.PrismTokens.hue(i * step)
            Column(
                Modifier.weight(1f).clip(LocalUi.current.tile).clickable(onClickLabel = a.label) { onPick(a) }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(hue.copy(alpha = if (a.active) 0.42f else 0.22f))
                        .then(if (a.active) Modifier.spectrumRing(CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) { Icon(a.icon, null, Modifier.size(23.dp), tint = if (com.prism.music.ui.theme.LocalIsDark.current) hue else hue.copy(red = hue.red * 0.75f, green = hue.green * 0.75f, blue = hue.blue * 0.75f)) }
                Spacer(Modifier.height(7.dp))
                Text(
                    a.value ?: a.label, style = MaterialTheme.typography.labelMedium, color = if (a.active) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A titled pane of glass with rows in it, like a settings group. */
@Composable
fun ActionGroup(title: String?, items: List<ActionItem>, onPick: (ActionItem) -> Unit) {
    if (items.isEmpty()) return
    val ui = LocalUi.current
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
        if (title != null) Eyebrow(title, Modifier.padding(start = 8.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().pane(ui.card)) {
            items.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                ActionRow(a) { onPick(a) }
            }
        }
    }
}

@Composable
private fun ActionRow(a: ActionItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ink = when {
        a.danger -> scheme.error
        else -> scheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            a.icon, null, Modifier.size(22.dp),
            tint = when { a.danger -> scheme.error; a.active -> scheme.onSurface; else -> scheme.onSurfaceVariant },
        )
        Spacer(Modifier.width(16.dp))
        Text(
            a.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (a.value != null) Text(
            a.value, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            color = if (a.active) scheme.onSurface else scheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
        ) else if (a.active) Icon(PrismIcons.Check, null, Modifier.size(20.dp), tint = scheme.onSurface)
    }
}
