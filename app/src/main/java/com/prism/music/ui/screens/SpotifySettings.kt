package com.prism.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.spotify.Spotify
import com.prism.music.data.spotify.SpotifyFeature
import com.prism.music.data.spotify.SpotifyGuard
import com.prism.music.data.spotify.SpotifyOAuth
import com.prism.music.tr
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.PrismTextField
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.launch

/**
 * Settings → Devices & connections → Spotify (1.8.1): connect Spotify through the listener's own developer app (the
 * steps to make one, the redirect address to copy, the Client ID to paste), then each feature's switch, and Disconnect.
 */
@Composable
internal fun SpotifyPage() {
    val c = LocalContainer.current
    val spotify = c.spotify
    val connection by spotify.connection.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        when (val conn = connection) {
            is Spotify.Connection.Connected -> {
                SpotifyFeatures(conn.name)
                SpotifyAccount(conn.name)
            }
            Spotify.Connection.Revoked -> {
                Group(tr(R.string.spotify), info = tr(R.string.spot_about)) {
                    Note(tr(R.string.spot_revoked))
                }
                SpotifySetUp(reconnect = true)
                SpotifyAccount(null)
            }
            else -> SpotifySetUp(reconnect = false)
        }
    }
}

/** The steps to make a developer app, the Client ID field and Connect. */
@Composable
private fun SpotifySetUp(reconnect: Boolean) {
    val c = LocalContainer.current
    val spotify = c.spotify
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val connection by spotify.connection.collectAsState()
    var clientId by rememberSaveable { mutableStateOf(spotify.store.clientId) }
    val valid = SpotifyOAuth.validClientId(clientId)
    if (!reconnect) Group(tr(R.string.spotify), info = tr(R.string.spot_about)) {
        Note(tr(R.string.spot_intro))
        Note(tr(R.string.spot_premium_note))
    }
    Group(tr(R.string.spot_set_up), info = tr(R.string.spot_set_up_info)) {
        Note(tr(R.string.spot_step_1))
        Item(tr(R.string.spot_open_dashboard), Spotify.DASHBOARD.removePrefix("https://"), onClick = { runCatching { uri.openUri(Spotify.DASHBOARD) } }) {
            Icon(PrismIcons.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Note(tr(R.string.spot_step_2))
        Note(tr(R.string.spot_step_3))
        Item(tr(R.string.spot_redirect_uri), SpotifyOAuth.REDIRECT_TO_REGISTER, onClick = {
            clipboard.setText(AnnotatedString(SpotifyOAuth.REDIRECT_TO_REGISTER))
            android.widget.Toast.makeText(context, tr(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
        }) { Icon(PrismIcons.Copy, tr(R.string.copy), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Note(tr(R.string.spot_step_4))
        Note(tr(R.string.spot_step_5))
        Note(tr(R.string.spot_step_6))
        PrismTextField(
            clientId, { clientId = it.trim() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            label = { Text(tr(R.string.spot_client_id)) },
            singleLine = true,
            isError = clientId.isNotEmpty() && !valid,
            supportingText = if (clientId.isNotEmpty() && !valid) ({ Text(tr(R.string.spot_client_id_invalid)) }) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (connection == Spotify.Connection.Waiting) {
                Text(tr(R.string.spot_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                PrismOutlinedButton(onClick = { spotify.cancelConnect() }) { Text(tr(R.string.cancel)) }
            } else {
                PrismButton(onClick = { spotify.connect(context, clientId) }, enabled = valid) {
                    Text(if (reconnect) tr(R.string.spot_reconnect) else tr(R.string.spot_connect))
                }
            }
        }
        (connection as? Spotify.Connection.Failed)?.let { Note(it.message) }
    }
}

/** Each feature's switch, with how it's going. */
@Composable
private fun SpotifyFeatures(name: String) {
    val c = LocalContainer.current
    val spotify = c.spotify
    val o by spotify.options.collectAsState()
    val scope = rememberCoroutineScope()
    var dryRun by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    @Composable
    fun status(f: SpotifyFeature, normal: String): String {
        val state by spotify.guards.getValue(f).state.collectAsState()
        return when (state) {
            SpotifyGuard.State.OK -> normal
            SpotifyGuard.State.NOT_ANSWERING -> tr(R.string.spot_not_answering)
            SpotifyGuard.State.PAUSED -> tr(R.string.spot_paused)
        }
    }
    Group(tr(R.string.spotify), info = tr(R.string.spot_about)) {
        Item(tr(R.string.spot_connected_as, name), tr(R.string.spot_official), onClick = {})
        Toggle(
            tr(R.string.spot_recent), status(SpotifyFeature.RECENT, tr(R.string.spot_recent_sub)), o.recent,
            info = tr(R.string.spot_recent_info),
        ) { spotify.setEnabled(SpotifyFeature.RECENT, it) }
        Toggle(
            tr(R.string.spot_top), status(SpotifyFeature.TOP, tr(R.string.spot_top_sub)), o.top,
            info = tr(R.string.spot_top_info),
        ) { spotify.setEnabled(SpotifyFeature.TOP, it) }
        val mirror = remember(o.playlists) { spotify.playlists.mirror() }
        Toggle(
            tr(R.string.spot_playlists),
            status(SpotifyFeature.PLAYLISTS, if (o.playlists && mirror.listedAt > 0) tr(R.string.spot_playlists_count, mirror.lists.size) else tr(R.string.spot_playlists_sub)),
            o.playlists, info = tr(R.string.spot_playlists_info),
        ) { spotify.setEnabled(SpotifyFeature.PLAYLISTS, it) }
        if (o.playlists) {
            if (mirror.notYours > 0) NoteWithInfo(
                tr(R.string.spot_playlists_not_yours, mirror.notYours),
                tr(R.string.spot_followed_why) + "\n" + tr(R.string.spot_followed_steps) + "\n" + tr(R.string.spot_followed_tunemymusic),
            )
            Toggle(tr(R.string.spot_keep_in_step), tr(R.string.spot_keep_in_step_sub), o.keepInStep, info = tr(R.string.spot_keep_in_step_info)) { spotify.setKeepInStep(it) }
        }
        Toggle(
            tr(R.string.spot_likes), status(SpotifyFeature.LIKES, if (checking) tr(R.string.spot_likes_counting) else tr(R.string.spot_likes_sub)), o.likes,
            info = tr(R.string.spot_likes_info),
        ) { on ->
            if (!on) spotify.setEnabled(SpotifyFeature.LIKES, false)
            else if (!checking) {
                // First the counts, then the listener's word.
                checking = true
                scope.launch {
                    val r = spotify.likesDryRun()
                    checking = false
                    dryRun = r.fold({ tr(R.string.spot_likes_dry_run, it.toLikeHere, it.toLikeOnSpotify) }, { "" })
                }
            }
        }
    }
    dryRun?.let { text ->
        AlertDialog(
            onDismissRequest = { dryRun = null },
            title = { Text(tr(R.string.spot_likes)) },
            text = { Text(text.ifEmpty { tr(R.string.spot_not_answering) }) },
            confirmButton = {
                if (text.isNotEmpty()) PrismTextButton(onClick = { dryRun = null; spotify.setEnabled(SpotifyFeature.LIKES, true) }) { Text(tr(R.string.spot_likes_turn_on)) }
            },
            dismissButton = { PrismTextButton(onClick = { dryRun = null }) { Text(tr(R.string.cancel)) } },
        )
    }
}

/** A [Note] (a figure) with an ⓘ that opens what to do about it. */
@Composable
private fun NoteWithInfo(text: String, info: String) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(text, "note") { mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            InfoMark(open, text) { open = !open }
        }
        InfoText(open, info, Modifier.padding(top = 4.dp))
    }
}

/** Disconnect, with what to forget. */
@Composable
private fun SpotifyAccount(name: String?) {
    val c = LocalContainer.current
    var asking by remember { mutableStateOf(false) }
    var clientIdToo by remember { mutableStateOf(false) }
    var keepPlaylists by remember { mutableStateOf(false) }
    Group(tr(R.string.spot_connection)) {
        Item(tr(R.string.disconnect), tr(R.string.spot_disconnect_sub), onClick = { asking = true })
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(tr(R.string.spot_disconnect_title)) },
        text = {
            Column {
                Text(tr(R.string.spot_disconnect_text))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrismCheck(clientIdToo, label = tr(R.string.spot_forget_client_id)) { clientIdToo = it }
                    Text(tr(R.string.spot_forget_client_id))
                }
                if (name != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    PrismCheck(keepPlaylists, label = tr(R.string.spot_keep_playlists)) { keepPlaylists = it }
                    Text(tr(R.string.spot_keep_playlists))
                }
            }
        },
        confirmButton = {
            PrismTextButton(onClick = { asking = false; c.scope.launch { c.spotify.disconnect(clientIdToo, keepPlaylists) } }) { Text(tr(R.string.disconnect)) }
        },
        dismissButton = { PrismTextButton(onClick = { asking = false }) { Text(tr(R.string.cancel)) } },
    )
}
