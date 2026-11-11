package com.prism.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.PrismTokens

/*
 * Prism's own controls (1.7 redesign, Aura × Spectrum): Material's buttons are tonal pills in the accent and its switch
 * a large capsule. These keep Material's behaviour and the parameters Prism uses, in the new look: pills, glass for
 * secondary actions, switches and sliders lit with the split light. Use them instead of Material's.
 */

private val ButtonPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)

/** Classic controls (1.7.8, #155): Material's own drawing, in the accent, as Prism 1.6 had them. */
@Composable
private fun classic(): Boolean = com.prism.music.ui.theme.LocalUi.current.classicControls

/** The page's glass: a see-through fill and a light top edge (frosted on any background). */
@Composable
internal fun glassFill(strength: Float = 1f): Color = frostFill(strength)

@Composable
internal fun glassEdge(): Brush {
    val dark = LocalIsDark.current
    // #326, "Glass on more of the screen": a brighter lit edge, on chips, round buttons and cards.
    if (com.prism.music.ui.theme.LocalAppSettings.current.glassEverywhere) {
        return Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.42f else 1f), Color.White.copy(alpha = if (dark) 0.08f else 0.45f)))
    }
    return Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.22f else 0.85f), Color.White.copy(alpha = if (dark) 0.05f else 0.3f)))
}

/** The primary action: a solid pill in the text color (white in dark mode), its label in the background's. */
@Composable
fun PrismButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = com.prism.music.ui.theme.LocalUi.current.button,
    contentPadding: PaddingValues = ButtonPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (classic()) { Button(onClick, modifier, enabled, contentPadding = contentPadding, content = content); return }
    val s = MaterialTheme.colorScheme
    Button(
        onClick, modifier.heightIn(min = 46.dp), enabled, shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = s.onSurface, contentColor = s.surface,
            disabledContainerColor = s.onSurface.copy(alpha = 0.12f), disabledContentColor = s.onSurface.copy(alpha = 0.38f),
        ),
        elevation = null, contentPadding = contentPadding, content = content,
    )
}

/** A secondary action: a glass pill. */
@Composable
fun PrismOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = com.prism.music.ui.theme.LocalUi.current.button,
    contentPadding: PaddingValues = ButtonPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (classic()) { OutlinedButton(onClick, modifier, enabled, contentPadding = contentPadding, content = content); return }
    val s = MaterialTheme.colorScheme
    OutlinedButton(
        onClick, modifier.heightIn(min = 46.dp), enabled, shape,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = glassFill(1.3f), contentColor = s.onSurface, disabledContentColor = s.onSurface.copy(alpha = 0.38f)),
        border = BorderStroke(1.dp, glassEdge()),
        contentPadding = contentPadding, content = content,
    )
}

/** A quieter filled action: a stronger glass pill. */
@Composable
fun PrismFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = com.prism.music.ui.theme.LocalUi.current.button,
    contentPadding: PaddingValues = ButtonPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (classic()) { FilledTonalButton(onClick, modifier, enabled, contentPadding = contentPadding, content = content); return }
    val s = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick, modifier.heightIn(min = 46.dp), enabled, shape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = glassFill(2f), contentColor = s.onSurface,
            disabledContainerColor = glassFill(0.8f), disabledContentColor = s.onSurface.copy(alpha = 0.38f),
        ),
        elevation = null, contentPadding = contentPadding, content = content,
    )
}

/** A text-only action (dialogs, inline links), in the text color. */
@Composable
fun PrismTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = com.prism.music.ui.theme.LocalUi.current.button,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (classic()) { TextButton(onClick, modifier, enabled, contentPadding = contentPadding, content = content); return }
    val s = MaterialTheme.colorScheme
    TextButton(
        onClick, modifier, enabled, shape,
        colors = ButtonDefaults.textButtonColors(contentColor = s.onSurface, disabledContentColor = s.onSurface.copy(alpha = 0.38f)),
        contentPadding = contentPadding, content = content,
    )
}

/**
 * A text field on soft glass: a quiet hairline that firms up to full ink while typing, the label and cursor in ink
 * (no accent box). Same parameters as Material's OutlinedTextField for plain text.
 */
@Composable
fun PrismTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textStyle: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onSurface
    androidx.compose.material3.OutlinedTextField(
        value, onValueChange, modifier, enabled = enabled, textStyle = textStyle, label = label, placeholder = placeholder,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedContainerColor = glassFill(1.6f), unfocusedContainerColor = glassFill(1.1f), disabledContainerColor = glassFill(0.8f),
            focusedBorderColor = ink.copy(alpha = 0.55f), unfocusedBorderColor = ink.copy(alpha = 0.12f),
            focusedLabelColor = ink, cursorColor = ink,
            selectionColors = androidx.compose.foundation.text.selection.TextSelectionColors(ink, ink.copy(alpha = 0.25f)),
        ),
    )
}

/**
 * An on/off switch: on, its track fills with the split light and the knob is white; off, a glass track with a quiet
 * knob. [onCheckedChange] null: it only shows the state (its row does the toggling), as Material's does.
 */
@Composable
fun PrismSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (classic()) { androidx.compose.material3.Switch(checked, onCheckedChange, modifier, enabled = enabled); return }
    val s = MaterialTheme.colorScheme
    val dim = if (enabled) 1f else 0.4f
    val lit by animateFloatAsState(if (checked) 1f else 0f, tween(PrismTokens.Motion.STANDARD), label = "lit")
    // White light: the accent's track with its own ink for the knob (or, with a white accent, an ink track and the
    // page's color for the knob), so it still reads.
    val white = PrismTokens.white
    val accent = PrismTokens.whiteAccent != null
    val track = if (accent) s.primary else s.onSurface
    val knob by animateColorAsState(
        if (checked) (if (white) (if (accent) s.onPrimary else s.surface) else Color.White) else s.onSurface.copy(alpha = 0.6f),
        tween(PrismTokens.Motion.QUICK), label = "knob",
    )
    val x by animateDpAsState(if (checked) 19.dp else 3.dp, tween(PrismTokens.Motion.STANDARD), label = "x")
    val off = glassFill(2.2f)
    Box(
        modifier.then(
            if (onCheckedChange != null) Modifier.minimumInteractiveComponentSize()
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            else Modifier
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 44.dp, height = 26.dp)
                .background(off, CircleShape)
                .then(if (white) Modifier.background(track.copy(alpha = lit * dim), CircleShape) else Modifier.background(PrismTokens.spectrum, CircleShape, alpha = lit * dim))
                .border(1.dp, glassEdge(), CircleShape),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.offset(x = x).size(20.dp).shadow(if (checked) 2.dp else 0.dp, CircleShape).background(knob.copy(alpha = knob.alpha * dim), CircleShape))
        }
    }
}

/** A slider: a soft 4 dp track lit with the split light up to a white knob; steps show as dots. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrismSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    if (classic()) {
        Slider(value, onValueChange, modifier, enabled, valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished)
        return
    }
    val s = MaterialTheme.colorScheme
    val dark = LocalIsDark.current
    val track = s.onSurface.copy(alpha = 0.14f)
    val alpha = if (enabled) 1f else 0.4f
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
        valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished,
        thumb = {
            Box(
                Modifier.size(20.dp).shadow(3.dp, CircleShape).background(Color.White.copy(alpha = alpha), CircleShape)
                    .border(1.dp, s.onSurface.copy(alpha = if (dark) 0f else 0.08f), CircleShape),
            )
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val f = if (span <= 0f) 0f else ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(20.dp).padding(horizontal = 2.dp)) {
                val y = size.height / 2
                val w = 4.dp.toPx()
                drawLine(track, Offset(0f, y), Offset(size.width, y), w, StrokeCap.Round)
                if (f > 0f) drawLine(
                    PrismTokens.spectrum(dark, 0f, size.width), Offset(0f, y), Offset(size.width * f, y), w, StrokeCap.Round, alpha = alpha,
                )
                if (state.steps > 0) for (i in 0..state.steps + 1) {
                    val tx = size.width * i / (state.steps + 1)
                    if (tx > size.width * f) drawCircle(s.onSurface.copy(alpha = 0.3f), 1.5.dp.toPx(), Offset(tx, y))
                }
            }
        },
    )
}

/**
 * An icon in [tint] (1.7.7, Icons & shapes → The tab you're on): as drawn ([com.prism.music.data.prefs.TabTint.INK]), or
 * repainted in the accent or the split light across it (its own small layer, only on the one tab).
 */
@Composable
fun Modifier.tabTint(tint: com.prism.music.data.prefs.TabTint): Modifier {
    if (tint == com.prism.music.data.prefs.TabTint.INK) return this
    val dark = LocalIsDark.current
    val accent = MaterialTheme.colorScheme.primary
    return this
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val brush = if (tint == com.prism.music.data.prefs.TabTint.ACCENT) androidx.compose.ui.graphics.SolidColor(accent)
                else PrismTokens.spectrum(dark, 0f, size.width)
            drawRect(brush, blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn)
        }
}

/** A thin ring of split light round [shape]: the mark of what's picked (a chip, a background, a Sing button that's on). */
fun Modifier.spectrumRing(shape: Shape, width: androidx.compose.ui.unit.Dp = 1.5.dp): Modifier = border(width, PrismTokens.spectrum, shape)
