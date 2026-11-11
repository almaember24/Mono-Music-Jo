package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismTextButton
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.prism.music.PrismActivity
import com.prism.music.container
import com.prism.music.data.recognize.Recognizer
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTheme

/**
 * Song recognition as a card over whatever is on screen, for the Recognize widget and the Quick Settings tile: it
 * listens straight away (asking for the microphone the first time), then shows the song with Play and Open. The
 * microphone is only used while this card is on screen; leaving it stops listening.
 */
class RecognizeActivity : ComponentActivity() {
    // Prism's language, when picked in Prism (below Android 13; AppLanguage).
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.prism.music.AppLanguage.wrap(base))
    private val c by lazy { container }
    private val denied = mutableStateOf(false)
    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied.value = !ok
        if (ok) c.recognizer.start(pausePrism = true)
    }

    private fun granted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun listen() {
        if (granted()) { denied.value = false; c.recognizer.start(pausePrism = true) } else ask.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            c.recognizer.reset()
            listen()
        }
        setContent {
            val settings by c.settings.flow.collectAsState()
            CompositionLocalProvider(LocalContainer provides c) {
                PrismTheme(settings, null) {
                    RecognizeCard(
                        denied = denied.value,
                        onListen = ::listen,
                        onOpen = { uri ->
                            startActivity(Intent(this, PrismActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        onDone = ::finish,
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Never listening unseen: leaving the card (Home, another app) stops it.
        if (c.recognizer.state.value is Recognizer.State.Listening) c.recognizer.cancel()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && c.recognizer.state.value !is Recognizer.State.Listening) c.recognizer.reset()
    }
}

@Composable
private fun RecognizeCard(denied: Boolean, onListen: () -> Unit, onOpen: (String) -> Unit, onDone: () -> Unit) {
    val c = LocalContainer.current
    val state by c.recognizer.state.collectAsState()
    val found = state as? Recognizer.State.Found
    ActionSheet(found?.recognition?.cover ?: found?.song?.thumbnail, onDismiss = {
        if (c.recognizer.state.value is Recognizer.State.Listening) c.recognizer.cancel()
        onDone()
    }) { close ->
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is Recognizer.State.Found -> {
                    val r = s.recognition
                    val song = s.song
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(r.cover ?: song?.thumbnail, Modifier.size(88.dp), LocalUi.current.art, placeholderIcon = PrismIcons.Song)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr(R.string.found_it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(r.title ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(r.artist ?: "", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (song != null) PrismButton({ close { c.player.playSingle(song) } }, Modifier.weight(1f)) {
                            Icon(PrismIcons.Play, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.widget_play))
                        }
                        // With a song: it plays and Prism opens on the player; without one, on the Recognized list.
                        PrismOutlinedButton({
                            close {
                                if (song != null) c.player.playSingle(song)
                                onOpen(if (song != null) "prism://player" else "prism://recognized")
                            }
                        }, Modifier.weight(1f)) {
                            Text(if (song != null) tr(R.string.open_in_prism_2) else tr(R.string.see_in_prism))
                        }
                    }
                    if (song == null) Text(
                        tr(R.string.not_found_in_prism_music), Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrismTextButton({ c.recognizer.reset(); onListen() }) { Text(tr(R.string.listen_again)) }
                }
                else -> {
                    val listening = s as? Recognizer.State.Listening
                    Text(tr(R.string.recognize), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    ListenButton(listening != null, listening?.level ?: 0f, size = 190.dp) {
                        if (listening != null) c.recognizer.cancel() else onListen()
                    }
                    Text(
                        when {
                            listening != null -> tr(R.string.listening_s, listening.heardMs / 1000)
                            denied -> tr(R.string.prism_needs_the_microphone_to_listen)
                            s == Recognizer.State.NotFound -> tr(R.string.couldn_t_recognize_that_one)
                            s == Recognizer.State.Saved -> tr(R.string.widget_recognize_waiting)
                            s == Recognizer.State.RateLimited -> tr(R.string.shazam_asks_to_wait_a_little)
                            s is Recognizer.State.Failed -> s.message
                            else -> tr(R.string.tap_to_listen)
                        },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    )
                    Text(
                        when {
                            listening != null -> tr(R.string.hold_your_phone_near_the_music)
                            denied -> tr(R.string.tap_the_button_to_allow_it)
                            s == Recognizer.State.NotFound -> tr(R.string.try_again_closer_to_the_music)
                            s == Recognizer.State.Saved -> tr(R.string.you_ll_get_a_notification_when)
                            s == Recognizer.State.RateLimited -> tr(R.string.try_again_in_a_minute)
                            else -> tr(R.string.prism_listens_for_up_to_12)
                        },
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
