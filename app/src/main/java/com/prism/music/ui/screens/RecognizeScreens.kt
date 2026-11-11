package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.border
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismSwitch
import com.prism.music.ui.components.PrismTextButton
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.prism.music.data.db.RecognitionEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.recognize.Recognizer
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** A recognized song as one to play: the YouTube Music song it was matched to (null when there wasn't one). */
private fun RecognitionEntity.song(): Song? = videoId?.let {
    Song(id = it, title = title ?: "", artists = listOf(ArtistRef(artist ?: "")), thumbnail = cover)
}

/**
 * Song recognition: a big button to listen (up to 12 s), then what it is, with ways to play it. [autoStart]: from the
 * Quick Settings tile, listening starts at once.
 */
@Composable
fun RecognizeScreen(autoStart: Boolean, bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val state by c.recognizer.state.collectAsState()
    val playing by c.player.isPlaying.collectAsState()
    var pausePrism by remember { mutableStateOf(true) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var startWhenGranted by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok && startWhenGranted) c.recognizer.start(pausePrism)
    }
    fun listen() {
        if (granted) c.recognizer.start(pausePrism) else { startWhenGranted = true; ask.launch(Manifest.permission.RECORD_AUDIO) }
    }
    LaunchedEffect(Unit) {
        c.recognizer.reset()
        if (autoStart) listen()
    }
    // Leaving the page stops listening (it never listens unseen).
    DisposableEffect(Unit) { onDispose { if (c.recognizer.state.value is Recognizer.State.Listening) c.recognizer.cancel() } }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomPadding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundAction(PrismIcons.ArrowBack, tr(R.string.back), glass = true) { nav.back() }
                Spacer(Modifier.weight(1f))
                PrismTextButton({ nav.go(Routes.RECOGNIZED) }) { Text(tr(R.string.widget_recognize_open)) }
            }
            Spacer(Modifier.weight(0.6f))
            when (val s = state) {
                is Recognizer.State.Found -> FoundCard(s.recognition, s.song)
                else -> {
                    val listening = s as? Recognizer.State.Listening
                    ListenButton(listening != null, listening?.level ?: 0f) { if (listening != null) c.recognizer.cancel() else listen() }
                    Spacer(Modifier.height(28.dp))
                    Text(
                        when (s) {
                            is Recognizer.State.Listening -> tr(R.string.listening_s, s.heardMs / 1000)
                            Recognizer.State.NotFound -> tr(R.string.couldn_t_recognize_that_one)
                            Recognizer.State.Saved -> tr(R.string.you_re_offline_so_prism_kept)
                            Recognizer.State.RateLimited -> tr(R.string.shazam_asks_to_wait_a_little)
                            is Recognizer.State.Failed -> s.message
                            else -> tr(R.string.tap_to_find_out_what_s)
                        },
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (s) {
                            is Recognizer.State.Listening -> tr(R.string.hold_your_phone_near_the_music)
                            Recognizer.State.NotFound -> tr(R.string.try_again_closer_to_the_music)
                            Recognizer.State.Saved -> tr(R.string.it_ll_be_looked_up_when)
                            Recognizer.State.RateLimited -> tr(R.string.try_again_in_a_minute)
                            else -> tr(R.string.prism_listens_for_up_to_12_2)
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    if (playing && s !is Recognizer.State.Listening) {
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr(R.string.pause_prism_while_listening), style = MaterialTheme.typography.bodyLarge)
                                Text(tr(R.string.off_it_can_recognize_what_prism), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            PrismSwitch(pausePrism, { pausePrism = it })
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The round button: still when idle, rippling with the sound while listening. [size] is the ripples' reach. */
@Composable
internal fun ListenButton(listening: Boolean, level: Float, size: Dp = 260.dp, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val ripple = rememberInfiniteTransition(label = "ripple")
    val phase by ripple.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "phase")
    val loud by animateFloatAsState(level, tween(120), label = "level")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (listening) Canvas(Modifier.fillMaxSize()) {
            for (k in 0 until 3) {
                val p = (phase + k / 3f) % 1f
                drawCircle(primary.copy(alpha = (1 - p) * 0.35f), radius = this.size.minDimension / 2 * (0.55f + 0.45f * p + 0.1f * loud))
            }
        }
        Box(
            Modifier.size(size * 0.58f).clip(CircleShape).background(primary).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (listening) PrismIcons.Close else PrismIcons.Recognize, if (listening) tr(R.string.stop_listening) else tr(R.string.listen), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.25f))
        }
    }
}

@Composable
private fun FoundCard(r: RecognitionEntity, song: Song?) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val liked by c.library.likedIds.collectAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(r.cover ?: song?.thumbnail, Modifier.size(240.dp), LocalUi.current.art, placeholderIcon = PrismIcons.Song)
        Spacer(Modifier.height(20.dp))
        Text(r.title ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(r.artist ?: "", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        r.album?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(20.dp))
        if (song != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrismButton({ c.player.playSingle(song); nav.openPlayer() }) { Icon(PrismIcons.Play, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.widget_play)) }
                val isLiked = song.id in liked
                PrismOutlinedButton({ c.library.toggleLike(song) }) {
                    Icon(if (isLiked) PrismIcons.HeartFilled else PrismIcons.Heart, null); Spacer(Modifier.width(6.dp)); Text(if (isLiked) tr(R.string.liked) else tr(R.string.widget_like))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                song.album?.id?.let { album -> PrismTextButton({ nav.go(Routes.album(album)) }) { Text(tr(R.string.album)) } }
                PrismTextButton({ c.player.playSingle(song); nav.openPlayer() }) { Text(tr(R.string.lyrics)) }
                PrismTextButton({ c.recognizer.reset() }) { Text(tr(R.string.again)) }
            }
        } else {
            Text(tr(R.string.not_found_in_prism_music), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrismTextButton({ nav.go(Routes.SEARCH) }) { Text(tr(R.string.search_for_it)) }
            PrismTextButton({ c.recognizer.reset() }) { Text(tr(R.string.again)) }
        }
    }
}

/** Library → Recognized: every song found by listening, newest first (and any still waiting for a network). */
@Composable
fun RecognizedScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val history by c.recognizer.history.collectAsState(initial = emptyList())
    // The Recognize widget, offered here (the launcher pins it after asking) until one is on the home screen.
    val context = LocalContext.current
    val widgets = remember { android.appwidget.AppWidgetManager.getInstance(context) }
    val provider = remember { android.content.ComponentName(context, com.prism.music.widget.RecognizeWidget::class.java) }
    var placed by remember { mutableStateOf(widgets?.getAppWidgetIds(provider)?.isNotEmpty() == true) }
    SubPage(
        tr(R.string.recognized), bottomPadding, subtitle = tr(R.string.songs_prism_found_by_listening),
        actions = { RoundAction(PrismIcons.Recognize, tr(R.string.widget_recognize_empty), glass = true) { nav.go(Routes.RECOGNIZE) } },
    ) {
        if (widgets?.isRequestPinAppWidgetSupported == true && !placed) item("widget") {
            Row(
                Modifier.fillMaxWidth().clickable {
                    placed = widgets.requestPinAppWidget(provider, null, null)
                }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(com.prism.music.ui.theme.PrismTokens.spectrumRing()), contentAlignment = Alignment.Center) {
                    Icon(PrismIcons.Recognize, null, tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.add_the_widget), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(R.string.recognize_songs_from_your_home_screen), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (history.isEmpty()) item("empty") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                com.prism.music.ui.components.EmptyState(
                    PrismIcons.Recognize, tr(R.string.nothing_recognized_yet),
                    tr(R.string.hear_a_song_you_like_let),
                )
                PrismButton({ nav.go(Routes.RECOGNIZE + "?start=true") }) {
                    Icon(PrismIcons.Recognize, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.widget_recognize_empty))
                }
            }
        }
        history.forEach { r ->
            item(r.id) {
                val song = r.song()
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = song != null) { song?.let { c.player.playSingle(it) } }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(r.cover, Modifier.size(52.dp), LocalUi.current.art, placeholderIcon = if (r.status == "pending") PrismIcons.Recognize else PrismIcons.Song)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (r.status == "pending") tr(R.string.waiting_for_a_network) else r.title ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(r.artist?.takeIf { r.status != "pending" }, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.at))).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    androidx.compose.material3.IconButton({
                        scope.launch {
                            c.db.recognitions().delete(r.id)
                            com.prism.music.widget.RecognizeWidgets.refresh(c.app)
                        }
                    }) {
                        Icon(PrismIcons.Close, tr(R.string.remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
