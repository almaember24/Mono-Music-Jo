package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.prism.music.container
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismTheme
import com.prism.music.widget.WidgetBackground
import com.prism.music.widget.WidgetOptions
import com.prism.music.widget.WidgetOptionsStore
import com.prism.music.widget.WidgetStyle
import com.prism.music.widget.WidgetText
import com.prism.music.widget.Widgets

/**
 * One home-screen widget's own settings (1.7.7, W1): opened when it's added, by holding it → Settings (Android 12+), or
 * from Prism. The preview at the top is the widget itself, drawn from the same views the home screen gets.
 */
class WidgetConfigActivity : ComponentActivity() {
    // Prism's language, when picked in Prism (below Android 13; AppLanguage).
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.prism.music.AppLanguage.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // Placed either way: leaving this screen keeps the widget, as designed.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        // A widget with a settings screen gets no first update from Android: drawn now, it shows what's playing even if
        // this screen is left without a change.
        Widgets.refresh(this)
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className
        val style = WidgetStyle.entries.firstOrNull { it.provider.name == provider } ?: run { finish(); return }
        enableEdgeToEdge()
        val c = container
        setContent {
            val settings by c.settings.flow.collectAsState()
            CompositionLocalProvider(LocalContainer provides c) {
                PrismTheme(settings, null) { WidgetSettingsScreen(id, style) { finish() } }
            }
        }
    }
}

@Composable
private fun WidgetSettingsScreen(id: Int, style: WidgetStyle, onDone: () -> Unit) {
    val context = LocalContext.current
    var o by remember { mutableStateOf(WidgetOptionsStore.get(context, id)) }
    fun change(n: WidgetOptions) {
        o = n
        WidgetOptionsStore.set(context, id, n)
        Widgets.refresh(context)
    }
    var picking by remember { mutableStateOf(false) }
    if (picking) com.prism.music.ui.components.ColorPickerSheet(tr(R.string.widget_color), o.color, onDismiss = { picking = false }) {
        change(o.copy(background = WidgetBackground.COLOR, color = it))
    }
    var pickingButton by remember { mutableStateOf(false) }
    if (pickingButton) com.prism.music.ui.components.ColorPickerSheet(tr(R.string.play_button), o.buttonColor, onDismiss = { pickingButton = false }) {
        change(o.copy(buttons = com.prism.music.widget.WidgetButtons.COLOR, buttonColor = it))
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.widget), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            PrismButton(onDone) { Text(tr(R.string.done)) }
        }
        WidgetPreview(style, o)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Group(tr(R.string.background)) {
                ChoiceCards(
                    WidgetBackground.entries, o.background, title = { it.label }, detail = { null },
                    onSelect = { b -> if (b == WidgetBackground.COLOR && o.background != b) picking = true else change(o.copy(background = b)) },
                ) { b -> BackgroundSwatch(b, o) }
                if (o.background == WidgetBackground.COLOR) Item(tr(R.string.pick_the_color), null, onClick = { picking = true }) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Color(o.color)))
                }
                if (o.background == WidgetBackground.COLOR || o.background == WidgetBackground.COVER) SliderSetting(
                    tr(R.string.opacity), o.opacity, 0.2f..1f, { "${(it * 100).toInt()}%" },
                    info = tr(R.string.below_android_12_a_see_through),
                ) { change(o.copy(opacity = it)) }
            }
            Group(tr(R.string.text)) {
                Chips(WidgetText.entries, o.text, { it.label }) { change(o.copy(text = it)) }
            }
            // The play button and progress line (Reddit, 1.7.9); "Your light" is how they were.
            if (com.prism.music.widget.Widgets.hasButtonColor(style)) Group(tr(R.string.play_button)) {
                Chips(com.prism.music.widget.WidgetButtons.entries, o.buttons, { it.label }) { b ->
                    if (b == com.prism.music.widget.WidgetButtons.COLOR && o.buttons != b) pickingButton = true else change(o.copy(buttons = b))
                }
                if (o.buttons == com.prism.music.widget.WidgetButtons.COLOR) Item(tr(R.string.pick_the_color), null, onClick = { pickingButton = true }) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Color(o.buttonColor)))
                }
            }
            Group(tr(R.string.show)) {
                if (style != WidgetStyle.COVER && style != WidgetStyle.DISC) Toggle(tr(R.string.cover), null, o.cover) { change(o.copy(cover = it)) }
                Toggle(tr(R.string.widget_like), null, o.like) { change(o.copy(like = it)) }
                Toggle(tr(R.string.previous_and_next), null, o.skips) { change(o.copy(skips = it)) }
                if (style != WidgetStyle.APPLE) Toggle(tr(R.string.progress), null, o.progress) { change(o.copy(progress = it)) }
            }
            if (!o.isDefault) com.prism.music.ui.components.PrismTextButton({ change(WidgetOptions()) }, Modifier.padding(start = 12.dp)) {
                Text(tr(R.string.back_to_how_it_s_designed))
            }
        }
    }
}

/**
 * Customize Prism → Icons & shapes → Home-screen widgets: each Prism widget on the home screen, opening its own settings
 * (the same screen as holding it → Settings). Nothing shows when none is placed.
 */
@Composable
fun HomeWidgetsGroup() {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    // Looked at again whenever the page comes back (a widget added or removed meanwhile).
    var tick by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.currentStateFlow.collect { if (it == androidx.lifecycle.Lifecycle.State.RESUMED) tick++ } }
    val placed = remember(tick) {
        WidgetStyle.entries.flatMap { s ->
            runCatching { manager.getAppWidgetIds(android.content.ComponentName(context, s.provider)).toList() }.getOrDefault(emptyList()).map { s to it }
        }
    }
    if (placed.isEmpty()) return
    Group(tr(R.string.home_screen_widgets), info = tr(R.string.each_widget_can_have_its_own)) {
        placed.forEach { (s, id) ->
            val label = remember(id) { runCatching { manager.getAppWidgetInfo(id).loadLabel(context.packageManager) }.getOrNull() ?: s.name }
            val own = !WidgetOptionsStore.get(context, id).isDefault
            Item(label, if (own) tr(R.string.your_own_settings) else tr(R.string.as_designed), onClick = {
                context.startActivity(
                    Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Chevron() }
        }
    }
}

/** The widget as the home screen will show it, from its own views. */
@Composable
private fun WidgetPreview(style: WidgetStyle, o: WidgetOptions) {
    val context = LocalContext.current
    var views by remember { mutableStateOf<android.widget.RemoteViews?>(null) }
    LaunchedEffect(o) { views = runCatching { Widgets.preview(context, style, o) }.getOrNull() }
    val height = when (style) { WidgetStyle.STRIP, WidgetStyle.SPOTIFY -> 96.dp; WidgetStyle.COVER, WidgetStyle.DISC -> 170.dp; else -> 160.dp }
    val width = when (style) { WidgetStyle.COVER, WidgetStyle.DISC -> 170.dp; else -> 340.dp }
    // A wallpaper-ish ground, so a see-through widget reads as one.
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(MaterialTheme.shapes.large)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF494949), Color(0xFF6D6D6D), Color(0xFFACACAC))))
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { android.widget.FrameLayout(it) },
            update = { frame ->
                frame.removeAllViews()
                views?.let { v -> runCatching { frame.addView(v.apply(frame.context, frame)) } }
            },
            modifier = Modifier.width(width).height(height),
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun BackgroundSwatch(b: WidgetBackground, o: WidgetOptions) {
    val fill = when (b) {
        WidgetBackground.DESIGN -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF1C1C1C), Color(0xFF3B3B3B)))
        WidgetBackground.COLOR -> androidx.compose.ui.graphics.SolidColor(Color(o.color))
        WidgetBackground.COVER -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
        WidgetBackground.CLEAR -> androidx.compose.ui.graphics.SolidColor(Color.Transparent)
    }
    Box(Modifier.fillMaxWidth().padding(10.dp).height(36.dp).clip(MaterialTheme.shapes.medium).background(fill)
        .then(if (b == WidgetBackground.CLEAR) Modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x11FFFFFF)))) else Modifier))
}
