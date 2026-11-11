package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismFilledTonalButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.prism.music.data.model.Song
import com.prism.music.playback.together.Msg
import com.prism.music.playback.together.NearbySession
import com.prism.music.playback.together.Together
import com.prism.music.playback.together.TogetherState
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.Eyebrow
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.components.LocalFrosted
import com.prism.music.ui.components.featureInk
import com.prism.music.ui.components.featurePane
import com.prism.music.ui.components.pane
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import kotlinx.coroutines.launch

/**
 * Listen Together: host a session on this phone or join one nearby. Everything happens between
 * the phones on the same Wi-Fi (or one's hotspot); nothing goes through a server.
 */
@Composable
fun TogetherScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val state by c.together.state.collectAsState()
    SubPage(tr(R.string.listen_together), bottomPadding, horizontalPadding = 16.dp, spacing = 18.dp, subtitle = tr(R.string.with_phones_on_the_same_wi)) {
        when (val s = state) {
            TogetherState.Idle -> item("idle") { Idle() }
            is TogetherState.Joining -> item("joining") { Joining(s.hostName) }
            is TogetherState.Hosting -> item("hosting") { Hosting(s) }
            is TogetherState.Guest -> item("guest") { GuestView(s) }
        }
        item("how") { HowItWorks() }
    }
}

@Composable
private fun Idle() {
    val c = LocalContainer.current
    val nearby by c.together.nearby.collectAsState()
    val scope = rememberCoroutineScope()
    var joinTarget by remember { mutableStateOf<NearbySession?>(null) }
    var manual by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Looks for sessions while this page is open.
    DisposableEffect(Unit) {
        c.together.startDiscovery()
        onDispose { c.together.stopDiscovery() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BigCard(
            PrismIcons.Radio, tr(R.string.start_a_session),
            tr(R.string.friends_on_this_wi_fi_join),
            tr(R.string.start), onClick = { c.together.host() },
        )
        Group(tr(R.string.sessions_nearby)) {
            if (nearby.isEmpty()) Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                com.prism.music.ui.components.PrismSpinner(Modifier.size(18.dp), stroke = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(tr(R.string.looking_on_this_network), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            nearby.forEach { n ->
                Item(n.name, tr(R.string.tap_to_join_with_their_code), onClick = { joinTarget = n }, icon = PrismIcons.Groups)
            }
            Item(tr(R.string.join_with_an_address), tr(R.string.if_a_session_doesn_t_show), onClick = { manual = true })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp)) }
    }
    joinTarget?.let { n ->
        JoinDialog(n.name, askAddress = false, onDismiss = { joinTarget = null }) { _, code ->
            joinTarget = null
            scope.launch { error = c.together.join(n.host, n.port, code, n.name) }
        }
    }
    if (manual) JoinDialog(tr(R.string.a_session), askAddress = true, onDismiss = { manual = false }) { address, code ->
        manual = false
        val host = address.substringBefore(':').trim()
        val port = address.substringAfter(':', "").trim().toIntOrNull() ?: Together.PORT
        scope.launch { error = c.together.join(host, port, code, host) }
    }
}

@Composable
private fun JoinDialog(name: String, askAddress: Boolean, onDismiss: () -> Unit, onJoin: (address: String, code: String) -> Unit) {
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.join, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (askAddress) com.prism.music.ui.components.PrismTextField(
                    address, { address = it.trim() }, label = { Text(tr(R.string.address_on_the_host_s_screen)) }, placeholder = { Text("192.168.1.20") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                com.prism.music.ui.components.PrismTextField(
                    code, { code = it.filter(Char::isDigit).take(4) }, label = { Text(tr(R.string.session_code)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { PrismTextButton(onClick = { onJoin(address, code) }, enabled = code.length == 4 && (!askAddress || address.isNotBlank())) { Text(tr(R.string.join_2)) } },
        dismissButton = { PrismTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

@Composable
private fun Joining(name: String) {
    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        com.prism.music.ui.components.PrismSpinner(Modifier.size(22.dp), stroke = 2.dp)
        Spacer(Modifier.width(14.dp))
        Text(tr(R.string.joining, name), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Hosting(s: TogetherState.Hosting) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val song by c.player.currentSong.collectAsState()
    val ui = LocalUi.current
    val ink = featureInk()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(
            Modifier.fillMaxWidth().featurePane(ui.card).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Eyebrow(tr(R.string.session_code), color = ink.copy(alpha = 0.8f))
            Text(
                s.code.toCharArray().joinToString(" "), color = ink,
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Light, letterSpacing = 0.08.em, brush = com.prism.music.ui.theme.PrismTokens.spectrum(com.prism.music.ui.theme.LocalIsDark.current)),
            )
            Text(
                tr(R.string.friends_open_listen_together_in_prism),
                style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.85f), textAlign = TextAlign.Center,
            )
            s.address?.let {
                Text(
                    tr(R.string.not_showing_up_for_them_they) + if (s.port == Together.PORT) it else "$it:${s.port}",
                    style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        NowPlayingCard(song, if (song == null) tr(R.string.play_something_and_everyone_hears_it) else tr(R.string.everyone_in_the_session_hears_this))
        Group(tr(R.string.listening_2, s.guests.size)) {
            if (s.guests.isEmpty()) Note(tr(R.string.no_one_yet))
            s.guests.forEach { g -> Item(g, null, onClick = {}, icon = PrismIcons.Headphones) }
            Toggle(tr(R.string.guests_can_control_playback), tr(R.string.play_pause_and_skip_from_their), s.guestsControl) { c.together.setGuestsControl(it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrismFilledTonalButton(onClick = { nav.go(Routes.SEARCH) }, Modifier.weight(1f)) {
                Icon(PrismIcons.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.find_songs))
            }
            PrismOutlinedButton(onClick = { c.together.leave() }, Modifier.weight(1f)) { Text(tr(R.string.end_session)) }
        }
    }
}

@Composable
private fun GuestView(s: TogetherState.Guest) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val now = s.now
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        NowPlayingCard(now?.songs?.firstOrNull(), tr(R.string.s_session, s.hostName) + (now?.guests?.size?.takeIf { it > 1 }?.let { tr(R.string.listening_3, it) } ?: ""))
        if (now?.canControl == true) HostControls(now)
        Group(tr(R.string.on_this_phone)) {
            Toggle(
                tr(R.string.play_here_too), tr(R.string.the_same_music_plays_on_this, s.hostName),
                s.listenHere,
            ) { c.together.setListenHere(it) }
            if (s.listenHere && !s.inSync) Item(
                tr(R.string.out_of_step), tr(R.string.you_paused_or_played_something_else, s.hostName),
                onClick = { c.together.resync() }, icon = PrismIcons.Sync,
            )
        }
        val upcoming = now?.songs?.drop(1).orEmpty()
        if (upcoming.isNotEmpty()) Group(tr(R.string.up_next)) {
            upcoming.take(8).forEach { song -> QueueLine(song) }
            if (upcoming.size > 8) Note(tr(R.string.and_more_3, upcoming.size - 8))
        }
        Note(tr(R.string.add_songs_from_any_song_s_2, s.hostName))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrismButton(onClick = { nav.go(Routes.SEARCH) }, Modifier.weight(1f)) {
                Icon(PrismIcons.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.add_songs))
            }
            PrismOutlinedButton(onClick = { c.together.leave() }, Modifier.weight(1f)) { Text(tr(R.string.leave)) }
        }
    }
}

/** When the host allows it: their play, pause and skip. */
@Composable
private fun HostControls(now: Msg.State) {
    val c = LocalContainer.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { c.together.control("previous") }) { Icon(PrismIcons.Previous, tr(R.string.widget_previous), Modifier.size(30.dp)) }
        Spacer(Modifier.width(18.dp))
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface).border(1.5.dp, com.prism.music.ui.theme.PrismTokens.spectrumRing(), CircleShape)
                .clickable { c.together.control(if (now.playing) "pause" else "play") },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (now.playing) PrismIcons.Pause else PrismIcons.Play, if (now.playing) tr(R.string.widget_pause) else tr(R.string.widget_play), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.surface)
        }
        Spacer(Modifier.width(18.dp))
        IconButton(onClick = { c.together.control("next") }) { Icon(PrismIcons.Next, tr(R.string.widget_next), Modifier.size(30.dp)) }
    }
}

@Composable
private fun NowPlayingCard(song: Song?, caption: String) {
    val ui = LocalUi.current
    Row(
        Modifier.fillMaxWidth().pane(ui.card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song?.thumbnail, Modifier.size(72.dp), ui.art, size = 300)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Eyebrow(caption)
            Text(song?.title ?: tr(R.string.nothing_playing_yet), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            song?.let { Text(it.artistText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun QueueLine(song: Song) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.thumbnail, Modifier.size(40.dp), LocalUi.current.smallArt, size = 120)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artistText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BigCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: String, onClick: () -> Unit) {
    val ui = LocalUi.current
    val ink = featureInk()
    Column(
        Modifier.fillMaxWidth().featurePane(ui.card)
            .clickable(onClick = onClick).padding(20.dp),
    ) {
        Box(Modifier.size(48.dp).border(1.5.dp, com.prism.music.ui.theme.PrismTokens.spectrumRing(), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp), tint = ink) }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = ink)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.85f))
        Spacer(Modifier.height(14.dp))
        PrismButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun HowItWorks() {
    Group(tr(R.string.how_it_works)) {
        Note(tr(R.string.everyone_needs_prism_on_the_same))
        Note(tr(R.string.each_phone_streams_the_music_itself))
        Note(tr(R.string.phones_talk_to_each_other_directly))
        Note(tr(R.string.some_public_and_work_wi_fi))
        Spacer(Modifier.height(6.dp))
    }
}
