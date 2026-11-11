package com.prism.music.ui.components

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.connect.HandoffRequest
import com.prism.music.connect.HandoffResult
import com.prism.music.connect.PrismDevice
import com.prism.music.connect.RemoteKey
import com.prism.music.connect.RemoteNow
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the remote asks the TV what it's playing, while the sheet is open. */
private const val POLL_MS = 1_500L

/**
 * A TV running Prism, from the phone (1.8.0, plan §4): the phone as its remote (what it plays, play/pause, skip,
 * volume), moving this phone's music there, signing it in with this phone's account (asked first), and sending this
 * phone's look. Everything goes over the encrypted link; the first time, the TV's code is asked for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvRemoteSheet(tv: PrismDevice, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val handoff = c.handoff
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.flow.collectAsState()
    var now by remember { mutableStateOf<RemoteNow?>(null) }
    var askSignIn by remember { mutableStateOf(false) }
    var askBring by remember { mutableStateOf(false) }
    // On the main thread whatever the caller's (the link answers on its own).
    val say: (String) -> Unit = { msg -> androidx.core.content.ContextCompat.getMainExecutor(context).execute { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }
    // Why the TV didn't answer, while it doesn't (1.8.1): said in the sheet, with Try again, in place of "One moment…".
    var failed by remember { mutableStateOf<String?>(null) }
    var tries by remember { mutableStateOf(0) }
    LaunchedEffect(tv.key, tries) {
        // A missed answer (the Wi-Fi blinked, the TV was busy) is said once, and the sheet keeps asking.
        var failing = false
        while (true) {
            when (val r = handoff.ask(tv, HandoffRequest.Now)) {
                is HandoffResult.Playing -> { now = r.now; failing = false; failed = null }
                is HandoffResult.Failed -> { if (!failing && now != null) say(r.reason); failing = true; failed = r.reason }
                HandoffResult.Cancelled -> { onDismiss(); return@LaunchedEffect }
                else -> Unit
            }
            delay(if (failing) POLL_MS * 2 else POLL_MS)
        }
    }
    val press: (RemoteKey) -> Unit = { key ->
        scope.launch { (handoff.ask(tv, HandoffRequest.Remote(key)) as? HandoffResult.Failed)?.let { say(it.reason) } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().testTag("tvRemote"), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PrismIcons.Tv, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(tv.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            val n = now
            if (n != null) {
                Artwork(n.thumbnail, Modifier.size(120.dp), shape = RoundedCornerShape(12.dp), size = 360)
                Spacer(Modifier.height(10.dp))
                Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(n.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else failed?.let { why ->
                Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.testTag("tvRemote/why"))
                PrismTextButton(onClick = { failed = null; tries++ }, Modifier.testTag("tvRemote/retry")) { Text(tr(R.string.try_again)) }
            } ?: Text(tr(R.string.one_moment), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { press(RemoteKey.VOLUME_DOWN) }) { Icon(PrismIcons.VolumeDown, tr(R.string.tv_volume_down)) }
                IconButton(onClick = { press(RemoteKey.PREVIOUS) }) { Icon(PrismIcons.Previous, tr(R.string.previous_song)) }
                FilledIconButton(onClick = { press(RemoteKey.TOGGLE) }, Modifier.size(64.dp).testTag("tvRemote/toggle")) {
                    Icon(if (n?.playing == true) PrismIcons.Pause else PrismIcons.Play, tr(R.string.play_pause), Modifier.size(32.dp))
                }
                IconButton(onClick = { press(RemoteKey.NEXT) }) { Icon(PrismIcons.Next, tr(R.string.next_song)) }
                IconButton(onClick = { press(RemoteKey.VOLUME_UP) }) { Icon(PrismIcons.VolumeUp, tr(R.string.tv_volume_up)) }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Plays along with this phone (1.8.0), as a speaker cast to: the phone stays its remote.
                PrismTextButton(onClick = { c.cast.connectTv(tv); onDismiss() }, Modifier.fillMaxWidth()) { Text(tr(R.string.move_the_music_there)) }
                if (settings.isLoggedIn) PrismTextButton(onClick = { askSignIn = true }, Modifier.fillMaxWidth().testTag("tvRemote/signIn")) { Text(tr(R.string.tv_sign_in_here)) }
                PrismTextButton(onClick = { scope.launch { say(handoff.sendLook(tv)) } }, Modifier.fillMaxWidth()) { Text(tr(R.string.tv_send_look)) }
                PrismTextButton(onClick = { askBring = true }, Modifier.fillMaxWidth().testTag("tvRemote/bring")) { Text(tr(R.string.bring_my_prism)) }
            }
            // A bring to this TV under way: how far it is, here too.
            val bringing by handoff.bringing.collectAsState()
            bringing?.takeIf { it.device.id == tv.id && it.running }?.let { b ->
                Spacer(Modifier.height(6.dp))
                Text(tr(R.string.bring_sending, tv.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                PrismProgressBar(progress = { b.fraction }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (askSignIn) TvSignInAsk(tv, onDone = { askSignIn = false })
    if (askBring) TvBringAsk(tv, onDone = { askBring = false })
}

/**
 * "Bring your Prism to Living room TV?" (1.8.0): this phone's listening history, likes, Prism playlists and the
 * choices that go with them (starred artists, song versions, lyric timings, best parts, exclusions) go to the TV only
 * once the listener says yes here; their look only if they tick it. The sign-in, downloads and settings never go.
 */
@Composable
fun TvBringAsk(tv: PrismDevice, onDone: () -> Unit) {
    val c = LocalContainer.current
    var withLook by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(tr(R.string.bring_ask_title, tv.name)) },
        text = {
            Column {
                Text(tr(R.string.bring_ask_text, tv.name), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                Text(tr(R.string.bring_ask_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().testTag("tvBring/look"), verticalAlignment = Alignment.CenterVertically) {
                    com.prism.music.ui.screens.PrismCheck(withLook, label = tr(R.string.bring_with_look)) { withLook = it }
                    Spacer(Modifier.width(6.dp))
                    Text(tr(R.string.bring_with_look), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {
            PrismTextButton(onClick = { c.handoff.bringTo(tv, withLook); onDone() }, Modifier.testTag("tvBring/yes")) { Text(tr(R.string.bring_yes)) }
        },
        dismissButton = { PrismTextButton(onClick = onDone) { Text(tr(R.string.not_now)) } },
    )
}

/**
 * A bring from this phone, while it goes and once it's over: how far, then done, or "Couldn't finish, try again" with
 * why and a Try again (nothing on the TV changed then).
 */
@Composable
fun TvBringProgress(state: com.prism.music.connect.Handoff.Bringing, onTryAgain: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!state.running) onClose() },
        title = {
            Text(
                when {
                    state.done -> tr(R.string.bring_done, state.device.name)
                    state.failed != null -> tr(R.string.bring_failed)
                    else -> tr(R.string.bring_sending, state.device.name)
                },
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().testTag("tvBring/progress")) {
                if (state.failed != null) {
                    if (state.failed != tr(R.string.bring_failed)) Text(state.failed, style = MaterialTheme.typography.bodyMedium)
                } else {
                    PrismProgressBar(progress = { if (state.done) 1f else state.fraction }, modifier = Modifier.fillMaxWidth())
                    if (state.total > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            android.text.format.Formatter.formatShortFileSize(context, state.sent) + " / " + android.text.format.Formatter.formatShortFileSize(context, state.total),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.failed != null -> PrismTextButton(onClick = onTryAgain, Modifier.testTag("tvBring/again")) { Text(tr(R.string.try_again)) }
                state.done -> PrismTextButton(onClick = onClose) { Text(tr(R.string.done)) }
                else -> Unit
            }
        },
        dismissButton = { if (state.failed != null) PrismTextButton(onClick = onClose) { Text(tr(R.string.close)) } },
    )
}

/**
 * "Sign in on Living room TV?": the phone's account goes to the TV only once the listener says yes here (plan §4).
 * Discord and Last.fm stay on the phone.
 */
@Composable
fun TvSignInAsk(tv: PrismDevice, onDone: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current.applicationContext
    val account = c.settings.current.let { it.accountEmail.ifBlank { it.accountName } }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(tr(R.string.tv_sign_in_ask, tv.name)) },
        text = { Text(tr(R.string.tv_sign_in_note, account, tv.name)) },
        confirmButton = {
            PrismTextButton(onClick = {
                // On the app's scope: the dialog closes now, the answer comes after (a toast on the main thread).
                c.scope.launch {
                    val said = c.handoff.sendSignIn(tv)
                    androidx.core.content.ContextCompat.getMainExecutor(context).execute { Toast.makeText(context, said, Toast.LENGTH_LONG).show() }
                }
                onDone()
            }, Modifier.testTag("tvSignIn/yes")) { Text(tr(R.string.sign_in)) }
        },
        dismissButton = { PrismTextButton(onClick = onDone) { Text(tr(R.string.cancel)) } },
    )
}
